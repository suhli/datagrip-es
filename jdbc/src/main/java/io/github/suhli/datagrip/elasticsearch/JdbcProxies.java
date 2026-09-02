package io.github.suhli.datagrip.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.Executor;

final class JdbcProxies {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JdbcProxies() {}

    static Connection open(EsJdbcUrl config) throws SQLException {
        try {
            HttpTransport transport = new HttpTransport(config);
            try {
                return open(config, transport, EsVersion.detect(transport, config));
            } catch (Exception e) {
                transport.close();
                throw e;
            }
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Cannot create Elasticsearch HTTP client", "08001", e);
        }
    }

    static Connection open(EsJdbcUrl config, Transport transport, EsVersion version) {
        State state = new State(config, transport);
        state.version = version;
        Connection connection = proxy(Connection.class, new ConnectionHandler(state));
        state.connection = connection;
        return connection;
    }

    private static final class State {
        final EsJdbcUrl config;
        final Transport transport;
        final MetadataCache metadataCache = new MetadataCache();
        final Set<Statement> statements = Collections.newSetFromMap(new IdentityHashMap<>());
        Connection connection;
        EsVersion version;
        boolean closed;
        boolean autoCommit = true;
        boolean readOnly = true;
        String catalog;
        String schema;
        int networkTimeout;

        State(EsJdbcUrl config, Transport transport) {
            this.config = config;
            this.transport = transport;
            this.networkTimeout = (int) Math.min(config.responseTimeout().toMillis(), Integer.MAX_VALUE);
        }

        synchronized void close() throws SQLException {
            if (closed) return;
            closed = true;
            SQLException failure = null;
            for (Statement statement : List.copyOf(statements)) {
                try { statement.close(); } catch (SQLException e) { failure = e; }
            }
            try { transport.close(); } catch (IOException e) {
                if (failure == null) failure = new SQLException("Failed to close HTTP transport", e);
            }
            if (failure != null) throw failure;
        }
    }

    private static final class ConnectionHandler implements InvocationHandler {
        private final State state;

        ConnectionHandler(State state) { this.state = state; }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (objectMethod(proxy, method, args) != NO_RESULT) return objectMethod(proxy, method, args);
            if (name.equals("close")) { state.close(); return null; }
            if (name.equals("isClosed")) return state.closed;
            if (name.equals("isValid")) {
                int timeoutSeconds = args == null ? 0 : (int) args[0];
                if (timeoutSeconds < 0) {
                    throw new SQLException("Connection.isValid timeout must be non-negative", "HY092");
                }
                return isValid(state, timeoutSeconds);
            }
            requireOpen(state);
            return switch (name) {
                case "createStatement" -> statement(state, null);
                case "prepareStatement" -> statement(state, (String) args[0]);
                case "getMetaData" -> metadata(state);
                case "nativeSQL" -> args[0];
                case "getAutoCommit" -> state.autoCommit;
                case "setAutoCommit" -> { state.autoCommit = (boolean) args[0]; yield null; }
                case "isReadOnly" -> state.readOnly;
                case "setReadOnly" -> { state.readOnly = (boolean) args[0]; yield null; }
                case "getCatalog" -> state.catalog;
                case "setCatalog" -> { state.catalog = (String) args[0]; yield null; }
                case "getSchema" -> state.schema;
                case "setSchema" -> { state.schema = (String) args[0]; yield null; }
                case "getTransactionIsolation" -> Connection.TRANSACTION_NONE;
                case "setTransactionIsolation" -> {
                    if ((int) args[0] != Connection.TRANSACTION_NONE) throw unsupported(name);
                    yield null;
                }
                case "getWarnings" -> null;
                case "clearWarnings" -> null;
                case "getTypeMap" -> Map.of();
                case "setTypeMap" -> { if (!((Map<?, ?>) args[0]).isEmpty()) throw unsupported(name); yield null; }
                case "getHoldability" -> ResultSet.CLOSE_CURSORS_AT_COMMIT;
                case "setHoldability" -> null;
                case "setNetworkTimeout" -> {
                    Executor executor = (Executor) args[0];
                    int timeout = (int) args[1];
                    if (executor == null) {
                        throw new SQLException("Connection.setNetworkTimeout executor must not be null", "HY009");
                    }
                    if (timeout < 0) {
                        throw new SQLException("Connection.setNetworkTimeout timeout must be non-negative", "HY092");
                    }
                    state.networkTimeout = timeout;
                    state.transport.setNetworkTimeoutMillis(state.networkTimeout);
                    yield null;
                }
                case "getNetworkTimeout" -> state.networkTimeout;
                case "abort" -> { state.close(); yield null; }
                case "unwrap" -> unwrap(proxy, (Class<?>) args[0]);
                case "isWrapperFor" -> ((Class<?>) args[0]).isInstance(proxy);
                case "commit", "rollback", "setSavepoint", "releaseSavepoint", "createClob",
                        "createBlob", "createNClob", "createSQLXML", "createArrayOf", "createStruct",
                        "prepareCall" -> throw unsupported(name);
                default -> throw unsupported(name);
            };
        }
    }

    /**
     * @param timeoutSeconds JDBC {@link Connection#isValid(int)} timeout in seconds
     */
    private static boolean isValid(State state, int timeoutSeconds) {
        if (state.closed) return false;
        try {
            // timeoutSeconds == 0 → no isValid-specific override (network/default timeout)
            // timeoutSeconds > 0  → convert seconds to milliseconds for this health check only
            int timeoutMillis = timeoutSeconds == 0
                    ? 0
                    : Math.multiplyExact(timeoutSeconds, 1000);
            Transport.ExecuteOptions options = Transport.ExecuteOptions.of(timeoutMillis);
            Transport.Response response = state.transport.execute(new Transport.Request(
                    "GET", state.config.endpoint().resolve("/"), Map.of(), null), options);
            return response.successful();
        } catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ArithmeticException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static Statement statement(State state, String template) {
        StatementHandler handler = new StatementHandler(state, template);
        Class<?> type = template == null ? Statement.class : PreparedStatement.class;
        Statement proxy = (Statement) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
        handler.self = proxy;
        state.statements.add(proxy);
        return proxy;
    }

    private static final class StatementHandler implements InvocationHandler {
        final State state;
        final String template;
        final Map<Integer, String> parameters = new HashMap<>();
        Statement self;
        ResultSet current;
        String lastRawBody;
        final List<ResultSet> results = new ArrayList<>();
        int currentIndex = -1;
        boolean closed;
        boolean closeOnCompletion;
        int maxRows;
        int queryTimeout;
        int fetchSize;
        volatile Transport.Cancellation runningCancellation;

        StatementHandler(State state, String template) {
            this.state = state;
            this.template = template;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            Object objectResult = objectMethod(proxy, method, args);
            if (objectResult != NO_RESULT) return objectResult;
            if (name.equals("close")) { close(); return null; }
            if (name.equals("isClosed")) return closed;
            requireOpen(state);
            if (closed) throw new SQLException("Statement is closed", "07000");
            if (name.startsWith("set") && template != null && args != null && args.length >= 2
                    && args[0] instanceof Integer index) {
                parameters.put(index, encodeParameter(name, args[1]));
                return null;
            }
            return switch (name) {
                case "executeQuery" -> execute(sql(args));
                case "execute" -> { execute(sql(args)); yield true; }
                case "getResultSet" -> current;
                case "getUpdateCount" -> -1;
                case "getMoreResults" -> advanceResult(
                        args == null || args.length == 0
                                ? Statement.CLOSE_CURRENT_RESULT
                                : (int) args[0]);
                case "clearParameters" -> { parameters.clear(); yield null; }
                case "getConnection" -> state.connection;
                case "clearWarnings", "clearBatch" -> null;
                case "cancel" -> { cancelRunning(); yield null; }
                case "getWarnings" -> null;
                case "setMaxRows" -> { maxRows = (int) args[0]; yield null; }
                case "getMaxRows" -> maxRows;
                case "setLargeMaxRows" -> { maxRows = Math.toIntExact((long) args[0]); yield null; }
                case "getLargeMaxRows" -> (long) maxRows;
                case "setQueryTimeout" -> {
                    int seconds = (int) args[0];
                    if (seconds < 0) {
                        throw new SQLException("Statement.setQueryTimeout timeout must be non-negative", "HY092");
                    }
                    queryTimeout = seconds;
                    yield null;
                }
                case "getQueryTimeout" -> queryTimeout;
                case "setFetchSize" -> { fetchSize = (int) args[0]; yield null; }
                case "getFetchSize" -> fetchSize;
                case "getFetchDirection" -> ResultSet.FETCH_FORWARD;
                case "setFetchDirection" -> {
                    if ((int) args[0] != ResultSet.FETCH_FORWARD) throw unsupported(name);
                    yield null;
                }
                case "getResultSetType" -> ResultSet.TYPE_FORWARD_ONLY;
                case "getResultSetConcurrency" -> ResultSet.CONCUR_READ_ONLY;
                case "getResultSetHoldability" -> ResultSet.CLOSE_CURSORS_AT_COMMIT;
                case "setEscapeProcessing", "setCursorName", "setPoolable" -> null;
                case "isPoolable" -> false;
                case "closeOnCompletion" -> { closeOnCompletion = true; yield null; }
                case "isCloseOnCompletion" -> closeOnCompletion;
                case "unwrap" -> unwrap(proxy, (Class<?>) args[0]);
                case "isWrapperFor" -> ((Class<?>) args[0]).isInstance(proxy);
                case "addBatch", "executeBatch", "executeLargeBatch", "executeUpdate", "executeLargeUpdate",
                        "getGeneratedKeys" -> throw unsupported(name);
                default -> throw unsupported(name);
            };
        }

        private void cancelRunning() {
            Transport.Cancellation cancellation = runningCancellation;
            if (cancellation != null) cancellation.cancel();
        }

        private String sql(Object[] args) throws SQLException {
            if (template == null) return (String) args[0];
            if (args != null && args.length > 0 && args[0] instanceof String) throw unsupported("SQL argument");
            return bind(template, parameters);
        }

        private ResultSet execute(String text) throws SQLException {
            closeResults();
            RestRequestParser.ParsedRequest translated =
                    SqlSelectTranslator.translate(text, maxRows, fetchSize, state.version);
            List<RestRequestParser.ParsedRequest> requests = translated == null
                    ? RestRequestParser.parseAll(text)
                    : List.of(translated);
            try {
                for (RestRequestParser.ParsedRequest request : requests) {
                    results.add(executeRequest(request));
                }
                currentIndex = 0;
                current = results.get(0);
                return current;
            } catch (SQLException e) {
                closeResults();
                throw e;
            }
        }

        private ResultSet executeRequest(RestRequestParser.ParsedRequest request) throws SQLException {
            URI uri = requestUri(state.config.endpoint(), request.path());
            Transport.RequestCancellation cancellation = new Transport.RequestCancellation();
            runningCancellation = cancellation;
            int timeoutMillis = resolveTimeoutMillis();
            try {
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Accept", "application/json");
                if (RestRequestParser.isNdjsonPath(request.path())) {
                    headers.put("Content-Type", "application/x-ndjson");
                }
                Transport.Response response = state.transport.execute(
                        new Transport.Request(request.method(), uri, headers, request.body()),
                        new Transport.ExecuteOptions(timeoutMillis, cancellation));
                if (cancellation.isCancelled()) {
                    throw cancelledException(request);
                }
                if (!response.successful()) {
                    throw EsSqlException.from(response, request.method(), request.path());
                }
                TabularResult result;
                if (response.body() == null || response.body().isBlank()) {
                    result = new TabularResult(
                            List.of(new TabularResult.Column("_status", Types.INTEGER, "INTEGER")),
                            List.of(List.of(response.status())));
                } else {
                    JsonResultMapper.MappedResponse mapped = JsonResultMapper.mapResponse(response.body());
                    result = mapped.tabular();
                    lastRawBody = mapped.structured() ? null : mapped.rawBody();
                }
                if (maxRows > 0 && result.rows().size() > maxRows) {
                    result = new TabularResult(result.columns(), result.rows().subList(0, maxRows),
                            result.rawBody(), result.structured());
                }
                return resultSet(result, this);
            } catch (SQLTimeoutException e) {
                throw e;
            } catch (SQLException e) {
                throw e;
            } catch (IOException e) {
                if (cancellation.isCancelled()) {
                    throw cancelledException(request);
                }
                SQLTimeoutException timeout = asTimeout(e, request, timeoutMillis);
                if (timeout != null) throw timeout;
                throw new SQLException("Elasticsearch request failed", "08S01", e);
            } finally {
                if (runningCancellation == cancellation) runningCancellation = null;
            }
        }

        private static SQLException cancelledException(RestRequestParser.ParsedRequest request) {
            return new SQLException(
                    "Elasticsearch request was cancelled: " + request.method() + " " + request.path(),
                    "HY008");
        }

        private int resolveTimeoutMillis() throws SQLException {
            if (queryTimeout > 0) {
                try {
                    return Math.multiplyExact(queryTimeout, 1000);
                } catch (ArithmeticException e) {
                    throw new SQLException("Statement query timeout is too large", "HY092", e);
                }
            }
            if (state.networkTimeout > 0) return state.networkTimeout;
            return 0;
        }

        private static SQLTimeoutException asTimeout(
                IOException error, RestRequestParser.ParsedRequest request, int timeoutMillis) {
            if (error instanceof SocketTimeoutException
                    || error instanceof ConnectTimeoutException
                    || error instanceof ConnectionRequestTimeoutException) {
                return new SQLTimeoutException(request.method() + " " + request.path()
                        + " timed out after " + timeoutMillis + " ms");
            }
            // InterruptedIOException after an explicit cancel is handled by the caller.
            return null;
        }

        private boolean advanceResult(int behavior) throws SQLException {
            if (behavior != Statement.CLOSE_CURRENT_RESULT
                    && behavior != Statement.KEEP_CURRENT_RESULT
                    && behavior != Statement.CLOSE_ALL_RESULTS) {
                throw new SQLException("Invalid getMoreResults behavior", "HY092");
            }
            if (behavior == Statement.CLOSE_ALL_RESULTS) {
                for (int i = 0; i <= currentIndex && i < results.size(); i++) {
                    if (!results.get(i).isClosed()) results.get(i).close();
                }
            } else if (behavior == Statement.CLOSE_CURRENT_RESULT
                    && current != null && !current.isClosed()) {
                current.close();
            }
            currentIndex++;
            current = currentIndex < results.size() ? results.get(currentIndex) : null;
            return current != null;
        }

        private void closeResults() throws SQLException {
            for (ResultSet result : List.copyOf(results)) {
                if (!result.isClosed()) result.close();
            }
            results.clear();
            currentIndex = -1;
            current = null;
        }

        void resultClosed(ResultSet result) throws SQLException {
            if (current == result) current = null;
            if (closeOnCompletion && results.stream().allMatch(StatementHandler::isClosed)) close();
        }

        private static boolean isClosed(ResultSet result) {
            try {
                return result.isClosed();
            } catch (SQLException ignored) {
                return true;
            }
        }

        void close() throws SQLException {
            if (closed) return;
            cancelRunning();
            closed = true;
            closeResults();
            state.statements.remove(self);
        }
    }

    private static String encodeParameter(String setter, Object value) throws SQLException {
        if (setter.equals("setNull") || value == null) return "null";
        if (setter.equals("setObject") || setter.equals("setString") || setter.equals("setNString")
                || setter.equals("setDate") || setter.equals("setTime") || setter.equals("setTimestamp")) {
            try { return JSON.writeValueAsString(value.toString()); }
            catch (Exception e) { throw new SQLException("Cannot encode parameter", e); }
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        throw unsupported(setter);
    }

    private static String bind(String template, Map<Integer, String> parameters) throws SQLException {
        int body = Math.max(template.indexOf('\n'), template.indexOf('\r'));
        if (body < 0) {
            if (!parameters.isEmpty()) throw new SQLException("Prepared parameters are supported only in JSON body", "07001");
            return template;
        }
        StringBuilder out = new StringBuilder(template.length() + 32);
        boolean string = false, escaped = false;
        int index = 1;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (i > body) {
                if (string) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') string = false;
                } else if (c == '"') string = true;
                else if (c == '?') {
                    String value = parameters.get(index++);
                    if (value == null) throw new SQLException("Parameter " + (index - 1) + " is not set", "07001");
                    out.append(value);
                    continue;
                }
            }
            out.append(c);
        }
        for (Integer parameterIndex : parameters.keySet()) {
            if (parameterIndex >= index) {
                throw new SQLException("Too many prepared parameters", "07001");
            }
        }
        return out.toString();
    }

    private static URI requestUri(URI endpoint, String path) throws SQLException {
        try {
            String base = endpoint.toASCIIString();
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            return URI.create(base + path);
        } catch (IllegalArgumentException e) {
            throw new SQLException("Invalid request path", "42000", e);
        }
    }

    private static DatabaseMetaData metadata(State state) {
        return proxy(DatabaseMetaData.class, new MetadataHandler(state));
    }

    private static final class MetadataHandler implements InvocationHandler {
        private final State state;

        MetadataHandler(State state) { this.state = state; }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            Object objectResult = objectMethod(proxy, method, args);
            if (objectResult != NO_RESULT) return objectResult;
            requireOpen(state);
            if (name.equals("supportsResultSetType")) {
                return (int) args[0] == ResultSet.TYPE_FORWARD_ONLY;
            }
            if (name.equals("supportsResultSetConcurrency")) {
                return (int) args[0] == ResultSet.TYPE_FORWARD_ONLY
                        && (int) args[1] == ResultSet.CONCUR_READ_ONLY;
            }
            return switch (name) {
                case "getConnection" -> state.connection;
                case "getURL" -> state.config.jdbcUrl();
                case "getUserName" -> Optional.ofNullable(state.config.property("user")).orElse("");
                case "getDatabaseProductName" -> state.version.product();
                case "getDatabaseProductVersion" -> state.version.number();
                case "getDatabaseMajorVersion" -> state.version.major();
                case "getDatabaseMinorVersion" -> versionPart(state.version.number(), 1);
                case "getDriverName" -> "Elasticsearch REST JDBC";
                case "getDriverVersion" -> "1.0";
                case "getDriverMajorVersion" -> 1;
                case "getDriverMinorVersion" -> 0;
                case "getJDBCMajorVersion" -> 4;
                case "getJDBCMinorVersion" -> 3;
                case "getIdentifierQuoteString" -> "\"";
                case "getSearchStringEscape" -> "\\";
                case "getCatalogSeparator" -> ".";
                case "getCatalogTerm" -> "cluster";
                case "getSchemaTerm" -> "schema";
                case "getProcedureTerm" -> "procedure";
                case "getSQLKeywords", "getNumericFunctions", "getStringFunctions",
                        "getSystemFunctions", "getTimeDateFunctions", "getExtraNameCharacters" -> "";
                case "storesLowerCaseIdentifiers", "storesMixedCaseIdentifiers",
                        "storesLowerCaseQuotedIdentifiers", "storesUpperCaseIdentifiers",
                        "storesUpperCaseQuotedIdentifiers", "supportsTransactions",
                        "supportsStoredProcedures", "supportsBatchUpdates", "supportsSavepoints",
                        "supportsMultipleTransactions" -> false;
                case "storesMixedCaseQuotedIdentifiers", "supportsMixedCaseIdentifiers",
                        "supportsMixedCaseQuotedIdentifiers", "allTablesAreSelectable",
                        "isReadOnly", "nullsAreSortedLow", "supportsMultipleResultSets",
                        "supportsMultipleOpenResults" -> true;
                case "nullsAreSortedHigh", "nullsAreSortedAtStart", "nullsAreSortedAtEnd",
                        "usesLocalFiles", "usesLocalFilePerTable", "supportsAlterTableWithAddColumn",
                        "supportsAlterTableWithDropColumn" -> false;
                case "getDefaultTransactionIsolation" -> Connection.TRANSACTION_NONE;
                case "getSQLStateType" -> DatabaseMetaData.sqlStateSQL;
                case "getResultSetHoldability" -> ResultSet.CLOSE_CURSORS_AT_COMMIT;
                case "getMaxConnections", "getMaxStatements", "getMaxColumnsInTable",
                        "getMaxColumnNameLength", "getMaxTableNameLength", "getMaxSchemaNameLength",
                        "getMaxCatalogNameLength", "getMaxRowSize", "getMaxStatementLength" -> 0;
                case "getSchemas" -> metadataRows(MetadataSchemas.SCHEMAS, List.of());
                case "getCatalogs" -> metadataRows(MetadataSchemas.CATALOGS,
                        List.of(List.of(state.version.clusterName())));
                case "getTableTypes" -> metadataRows(MetadataSchemas.TABLE_TYPES, List.of(List.of("TABLE")));
                case "getTables" -> tableMetadata((String) args[0], (String) args[2],
                        args[3] == null ? null : (String[]) args[3]);
                case "getColumns" -> columnMetadata((String) args[0], (String) args[2], (String) args[3]);
                case "getPrimaryKeys", "getImportedKeys", "getExportedKeys", "getCrossReference",
                        "getIndexInfo", "getProcedures", "getProcedureColumns", "getFunctions",
                        "getFunctionColumns", "getUDTs", "getSuperTypes", "getSuperTables",
                        "getAttributes", "getPseudoColumns", "getClientInfoProperties" -> emptyStandard(name);
                case "getTypeInfo" -> typeInfo();
                case "unwrap" -> unwrap(proxy, (Class<?>) args[0]);
                case "isWrapperFor" -> ((Class<?>) args[0]).isInstance(proxy);
                default -> throw unsupported(name);
            };
        }

        private ResultSet tableMetadata(String catalog, String pattern, String[] types) throws SQLException {
            if (catalog != null && !catalog.equals(state.version.clusterName())) {
                return metadataRows(MetadataSchemas.TABLES, List.of());
            }
            if (types != null && Arrays.stream(types).noneMatch("TABLE"::equalsIgnoreCase)) {
                return metadataRows(MetadataSchemas.TABLES, List.of());
            }
            List<List<Object>> result = new ArrayList<>();
            for (MetadataCache.IndexInfo index : indices()) {
                if (JdbcLikePattern.matches(index.name(), pattern)
                        || (pattern != null && index.name().equals(pattern))) {
                    String remarks = "health=" + index.health() + ", status=" + index.status()
                            + ", docs=" + index.docsCount() + ", store=" + index.storeSize();
                    result.add(Arrays.asList(state.version.clusterName(), null, index.name(), "TABLE", remarks,
                            null, null, null, null, null));
                }
            }
            return metadataRows(MetadataSchemas.TABLES, result);
        }

        private ResultSet columnMetadata(String catalog, String tablePattern, String columnPattern)
                throws SQLException {
            if (catalog != null && !catalog.equals(state.version.clusterName())) {
                return metadataRows(MetadataSchemas.COLUMNS, List.of());
            }
            List<List<Object>> result = new ArrayList<>();
            for (var index : mappingsForTable(tablePattern).entrySet()) {
                if (!(JdbcLikePattern.matches(index.getKey(), tablePattern)
                        || (tablePattern != null && index.getKey().equals(tablePattern)))) {
                    continue;
                }
                int ordinal = 1;
                for (MappingFlattener.Field field : MappingFlattener.flatten(index.getValue())) {
                    if (JdbcLikePattern.matches(field.name(), columnPattern)) {
                        result.add(Arrays.asList(state.version.clusterName(), null, index.getKey(), field.name(),
                                field.jdbcType(), field.esType(), 0, null, null, 10,
                                DatabaseMetaData.columnNullableUnknown,
                                "Elasticsearch type: " + field.esType(), null, 0, 0, 0,
                                ordinal, "YES", null, null, null, null, "NO", "NO"));
                    }
                    ordinal++;
                }
            }
            return metadataRows(MetadataSchemas.COLUMNS, result);
        }

        private Map<String, JsonNode> mappingsForTable(String tablePattern) throws SQLException {
            List<String> matched = resolveTableNames(tablePattern);
            return loadMappingsForIndices(matched);
        }

        /**
         * Resolves a JDBC tableNamePattern to concrete index names.
         * Exact matches against known indices win over LIKE {@code _} wildcards.
         */
        private List<String> resolveTableNames(String tablePattern) throws SQLException {
            List<MetadataCache.IndexInfo> known = indices();
            if (tablePattern == null || tablePattern.equals("%")) {
                return known.stream().map(MetadataCache.IndexInfo::name).toList();
            }

            // Prefer exact index-name match so names like game_logs are concrete.
            for (MetadataCache.IndexInfo index : known) {
                if (index.name().equals(tablePattern)) {
                    return List.of(index.name());
                }
            }

            String literal = JdbcLikePattern.literalOrNull(tablePattern);
            if (literal != null) {
                return List.of(literal);
            }

            return known.stream()
                    .map(MetadataCache.IndexInfo::name)
                    .filter(name -> JdbcLikePattern.matches(name, tablePattern))
                    .toList();
        }

        private Map<String, JsonNode> loadMappingsForIndices(List<String> indexNames) throws SQLException {
            Map<String, JsonNode> result = new LinkedHashMap<>();
            List<String> missing = new ArrayList<>();
            for (String index : indexNames) {
                JsonNode cached = state.metadataCache.mappingIfFresh(index);
                if (cached != null) result.put(index, cached);
                else missing.add(index);
            }
            if (missing.isEmpty()) return result;

            final int batchSize = EsClusterMetadata.MAPPING_BATCH_SIZE;
            for (int offset = 0; offset < missing.size(); offset += batchSize) {
                List<String> batch = missing.subList(offset, Math.min(offset + batchSize, missing.size()));
                String path = "/" + String.join(",", batch) + "/_mapping";
                try {
                    result.putAll(fetchMappingsByPath(path));
                } catch (SQLException e) {
                    throw new SQLException(
                            "Mapping request failed for batch [" + String.join(",", batch) + "]: "
                                    + e.getMessage(),
                            e.getSQLState(),
                            e);
                }
            }
            return result;
        }

        private Map<String, JsonNode> fetchMappingsByPath(String path) throws SQLException {
            try {
                Transport.Response response = state.transport.execute(new Transport.Request(
                        "GET", requestUri(state.config.endpoint(), path), Map.of(), null));
                if (!response.successful()) {
                    throw new SQLException("Mapping request returned HTTP " + response.status());
                }
                JsonNode root = JSON.readTree(response.body());
                Map<String, JsonNode> result = new LinkedHashMap<>();
                root.properties().forEach(entry -> {
                    result.put(entry.getKey(), entry.getValue());
                    state.metadataCache.putMapping(entry.getKey(), entry.getValue());
                });
                return result;
            } catch (IOException e) {
                throw new SQLException("Cannot load Elasticsearch mappings", "08S01", e);
            }
        }

        private List<MetadataCache.IndexInfo> indices() throws SQLException {
            List<MetadataCache.IndexInfo> cached = state.metadataCache.indicesIfFresh();
            if (cached != null) return cached;
            try {
                Transport.Response response = state.transport.execute(new Transport.Request(
                        "GET", requestUri(state.config.endpoint(),
                        "/_cat/indices?format=json&h=index,health,status,docs.count,store.size&expand_wildcards=all"),
                        Map.of(), null));
                if (!response.successful()) {
                    throw EsSqlException.from(response, "GET", "/_cat/indices");
                }
                List<MetadataCache.IndexInfo> result = new ArrayList<>();
                for (var item : JSON.readTree(response.body())) {
                    result.add(new MetadataCache.IndexInfo(item.path("index").asText(),
                            item.path("health").asText(""),
                            item.path("status").asText(""),
                            item.path("docs.count").asText(""),
                            item.path("store.size").asText("")));
                }
                state.metadataCache.putIndices(result);
                return result;
            } catch (IOException e) {
                throw new SQLException("Cannot list Elasticsearch indices", "08S01", e);
            }
        }
    }

    private static ResultSet resultSet(TabularResult result, StatementHandler owner) {
        ResultSetHandler handler = new ResultSetHandler(result, owner);
        ResultSet proxy = proxy(ResultSet.class, handler);
        handler.self = proxy;
        return proxy;
    }

    private static ResultSet metadataRows(List<MetadataSchemas.Column> schema, List<List<Object>> values) {
        List<TabularResult.Column> columns = schema.stream()
                .map(column -> new TabularResult.Column(column.name(), column.jdbcType(), column.typeName()))
                .toList();
        return resultSet(new TabularResult(columns, values), null);
    }

    private static final class ResultSetHandler implements InvocationHandler {
        final TabularResult result;
        final StatementHandler owner;
        ResultSet self;
        int cursor = -1;
        boolean closed;
        boolean wasNull;

        ResultSetHandler(TabularResult result, StatementHandler owner) {
            this.result = result;
            this.owner = owner;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            Object objectResult = objectMethod(proxy, method, args);
            if (objectResult != NO_RESULT) return objectResult;
            if (name.equals("close")) {
                if (!closed) {
                    closed = true;
                    if (owner != null) owner.resultClosed(self);
                }
                return null;
            }
            if (name.equals("isClosed")) return closed;
            if (closed) throw new SQLException("ResultSet is closed", "24000");
            return switch (name) {
                case "next" -> ++cursor < result.rows().size();
                case "wasNull" -> wasNull;
                case "findColumn" -> find((String) args[0]);
                case "getObject" -> {
                    Object value = value(args[0]);
                    if (args.length == 2 && args[1] instanceof Class<?> type) yield convert(value, type);
                    yield value;
                }
                case "getString", "getNString" -> asString(value(args[0]));
                case "getBoolean" -> asBoolean(value(args[0]));
                case "getByte" -> number(value(args[0])).byteValue();
                case "getShort" -> number(value(args[0])).shortValue();
                case "getInt" -> number(value(args[0])).intValue();
                case "getLong" -> number(value(args[0])).longValue();
                case "getFloat" -> number(value(args[0])).floatValue();
                case "getDouble" -> number(value(args[0])).doubleValue();
                case "getBigDecimal" -> asBigDecimal(value(args[0]));
                case "getBytes" -> {
                    String text = asString(value(args[0]));
                    yield text == null ? null : text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                case "getDate" -> asDate(value(args[0]));
                case "getTime" -> asTime(value(args[0]));
                case "getTimestamp" -> timestamp(value(args[0]));
                case "getMetaData" -> resultSetMetadata(result);
                case "getStatement" -> owner == null ? null : owner.self;
                case "getRow" -> {
                    if (cursor < 0 || cursor >= result.rows().size()) yield 0;
                    yield cursor + 1;
                }
                case "isBeforeFirst" -> cursor < 0 && !result.rows().isEmpty();
                case "isAfterLast" -> cursor >= result.rows().size();
                case "isFirst" -> cursor == 0 && !result.rows().isEmpty();
                case "isLast" -> cursor == result.rows().size() - 1 && !result.rows().isEmpty();
                case "getType" -> ResultSet.TYPE_FORWARD_ONLY;
                case "getConcurrency" -> ResultSet.CONCUR_READ_ONLY;
                case "getFetchDirection" -> ResultSet.FETCH_FORWARD;
                case "setFetchDirection", "setFetchSize", "clearWarnings" -> null;
                case "getFetchSize" -> 0;
                case "getHoldability" -> ResultSet.CLOSE_CURSORS_AT_COMMIT;
                case "getWarnings" -> null;
                case "unwrap" -> unwrap(proxy, (Class<?>) args[0]);
                case "isWrapperFor" -> ((Class<?>) args[0]).isInstance(proxy);
                default -> throw unsupported(name);
            };
        }

        private int find(String label) throws SQLException {
            for (int i = 0; i < result.columns().size(); i++) {
                if (result.columns().get(i).label().equalsIgnoreCase(label)) return i + 1;
            }
            throw new SQLException("Unknown column: " + label, "S0022");
        }

        private Object value(Object key) throws SQLException {
            if (cursor < 0 || cursor >= result.rows().size()) throw new SQLException("Cursor is not on a row", "24000");
            int index = key instanceof Integer i ? i : find((String) key);
            if (index < 1 || index > result.columns().size()) throw new SQLException("Invalid column index: " + index, "S1002");
            Object value = result.rows().get(cursor).get(index - 1);
            wasNull = value == null;
            return value;
        }
    }

    private static ResultSetMetaData resultSetMetadata(TabularResult result) {
        return proxy(ResultSetMetaData.class, (proxy, method, args) -> {
            String name = method.getName();
            Object objectResult = objectMethod(proxy, method, args);
            if (objectResult != NO_RESULT) return objectResult;
            if (name.equals("getColumnCount")) return result.columns().size();
            if (name.equals("unwrap")) return unwrap(proxy, (Class<?>) args[0]);
            if (name.equals("isWrapperFor")) return ((Class<?>) args[0]).isInstance(proxy);
            int index = args != null && args.length > 0 && args[0] instanceof Integer i ? i : 0;
            if (index < 1 || index > result.columns().size()) throw new SQLException("Invalid column index", "S1002");
            TabularResult.Column column = result.columns().get(index - 1);
            return switch (name) {
                case "getColumnLabel", "getColumnName" -> column.label();
                case "getColumnType" -> column.jdbcType();
                case "getColumnTypeName" -> column.typeName();
                case "getColumnClassName" -> className(column.jdbcType());
                case "getCatalogName", "getSchemaName", "getTableName" -> "";
                case "getPrecision", "getScale", "getColumnDisplaySize" -> 0;
                case "isNullable" -> ResultSetMetaData.columnNullableUnknown;
                case "isAutoIncrement", "isCurrency", "isWritable", "isDefinitelyWritable" -> false;
                case "isCaseSensitive", "isSearchable", "isReadOnly", "isSigned" -> true;
                default -> throw unsupported(name);
            };
        });
    }

    private static String className(int type) {
        return switch (type) {
            case Types.BOOLEAN -> Boolean.class.getName();
            case Types.TINYINT -> Byte.class.getName();
            case Types.SMALLINT -> Short.class.getName();
            case Types.INTEGER -> Integer.class.getName();
            case Types.BIGINT -> Long.class.getName();
            case Types.FLOAT -> Float.class.getName();
            case Types.DOUBLE -> Double.class.getName();
            case Types.DECIMAL -> BigDecimal.class.getName();
            case Types.TIMESTAMP -> Timestamp.class.getName();
            default -> String.class.getName();
        };
    }

    private static Number number(Object value) throws SQLException {
        if (value == null) return 0;
        if (value instanceof Number number) return number;
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException e) { throw new SQLException("Value is not numeric: " + value, "22018", e); }
    }

    private static BigDecimal asBigDecimal(Object value) throws SQLException {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof BigInteger integer) return new BigDecimal(integer);
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException e) { throw new SQLException("Value is not numeric: " + value, "22018", e); }
    }

    private static java.sql.Date asDate(Object value) throws SQLException {
        if (value == null) return null;
        if (value instanceof java.sql.Date date) return date;
        if (value instanceof Timestamp timestamp) return new java.sql.Date(timestamp.getTime());
        String text = value.toString();
        try { return java.sql.Date.valueOf(LocalDate.parse(text)); }
        catch (RuntimeException e) { throw new SQLException("Value is not a date: " + text, "22007", e); }
    }

    private static Time asTime(Object value) throws SQLException {
        if (value == null) return null;
        if (value instanceof Time time) return time;
        if (value instanceof Timestamp timestamp) return new Time(timestamp.getTime());
        try { return Time.valueOf(value.toString()); }
        catch (RuntimeException e) { throw new SQLException("Value is not a time: " + value, "22007", e); }
    }

    private static String asString(Object value) { return value == null ? null : value.toString(); }

    private static boolean asBoolean(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean bool) return bool;
        return Boolean.parseBoolean(value.toString()) || "1".equals(value.toString());
    }

    private static Timestamp timestamp(Object value) throws SQLException {
        if (value == null) return null;
        if (value instanceof Timestamp ts) return ts;
        if (value instanceof Number number) return new Timestamp(number.longValue());
        String text = value.toString();
        try { return Timestamp.from(Instant.parse(text)); } catch (RuntimeException ignored) {}
        try { return Timestamp.valueOf(LocalDateTime.parse(text)); } catch (RuntimeException ignored) {}
        try { return Timestamp.from(OffsetDateTime.parse(text).toInstant()); } catch (RuntimeException ignored) {}
        try { return Timestamp.valueOf(LocalDate.parse(text).atStartOfDay()); }
        catch (RuntimeException e) { throw new SQLException("Value is not a timestamp: " + text, "22007", e); }
    }

    private static Object convert(Object value, Class<?> type) throws SQLException {
        if (value == null || type.isInstance(value)) return value;
        if (type == String.class) return value.toString();
        if (type == Integer.class || type == int.class) return number(value).intValue();
        if (type == Long.class || type == long.class) return number(value).longValue();
        if (type == Double.class || type == double.class) return number(value).doubleValue();
        if (type == BigDecimal.class) return asBigDecimal(value);
        if (type == Boolean.class || type == boolean.class) return asBoolean(value);
        if (type == Timestamp.class) return timestamp(value);
        throw new SQLException("Cannot convert value to " + type.getName(), "22005");
    }

    private static ResultSet typeInfo() {
        List<List<Object>> rows = new ArrayList<>();
        for (int type : List.of(Types.VARCHAR, Types.BOOLEAN, Types.INTEGER, Types.BIGINT, Types.FLOAT,
                Types.DOUBLE, Types.TIMESTAMP, Types.BINARY, Types.JAVA_OBJECT, Types.OTHER)) {
            rows.add(Arrays.asList(EsTypes.jdbcTypeName(type), type, 0, "'", "'", null,
                    DatabaseMetaData.typeNullable, true, DatabaseMetaData.typeSearchable,
                    false, false, false, null, 0, 0, null, null, 10));
        }
        return metadataRows(MetadataSchemas.TYPE_INFO, rows);
    }

    private static ResultSet emptyStandard(String name) {
        return metadataRows(switch (name) {
            case "getPrimaryKeys" -> List.of(
                    col("TABLE_CAT", Types.VARCHAR), col("TABLE_SCHEM", Types.VARCHAR),
                    col("TABLE_NAME", Types.VARCHAR), col("COLUMN_NAME", Types.VARCHAR),
                    col("KEY_SEQ", Types.INTEGER), col("PK_NAME", Types.VARCHAR));
            case "getImportedKeys", "getExportedKeys", "getCrossReference" -> List.of(
                    col("PKTABLE_CAT", Types.VARCHAR), col("PKTABLE_SCHEM", Types.VARCHAR),
                    col("PKTABLE_NAME", Types.VARCHAR), col("PKCOLUMN_NAME", Types.VARCHAR),
                    col("FKTABLE_CAT", Types.VARCHAR), col("FKTABLE_SCHEM", Types.VARCHAR),
                    col("FKTABLE_NAME", Types.VARCHAR), col("FKCOLUMN_NAME", Types.VARCHAR),
                    col("KEY_SEQ", Types.INTEGER), col("UPDATE_RULE", Types.INTEGER),
                    col("DELETE_RULE", Types.INTEGER), col("FK_NAME", Types.VARCHAR),
                    col("PK_NAME", Types.VARCHAR), col("DEFERRABILITY", Types.INTEGER));
            case "getIndexInfo" -> List.of(
                    col("TABLE_CAT", Types.VARCHAR), col("TABLE_SCHEM", Types.VARCHAR),
                    col("TABLE_NAME", Types.VARCHAR), col("NON_UNIQUE", Types.BOOLEAN),
                    col("INDEX_QUALIFIER", Types.VARCHAR), col("INDEX_NAME", Types.VARCHAR),
                    col("TYPE", Types.INTEGER), col("ORDINAL_POSITION", Types.INTEGER),
                    col("COLUMN_NAME", Types.VARCHAR), col("ASC_OR_DESC", Types.VARCHAR),
                    col("CARDINALITY", Types.INTEGER), col("PAGES", Types.INTEGER),
                    col("FILTER_CONDITION", Types.VARCHAR));
            default -> List.of(col("RESULT", Types.VARCHAR));
        }, List.of());
    }

    private static MetadataSchemas.Column col(String name, int type) {
        return new MetadataSchemas.Column(name, type, EsTypes.jdbcTypeName(type));
    }

    private static int versionPart(String version, int index) {
        try { return Integer.parseInt(version.split("\\.")[index]); }
        catch (RuntimeException e) { return 0; }
    }

    private static void requireOpen(State state) throws SQLException {
        if (state.closed) throw new SQLException("Connection is closed", "08003");
    }

    private static SQLFeatureNotSupportedException unsupported(String operation) {
        return new SQLFeatureNotSupportedException(operation + " is not supported");
    }

    private static Object unwrap(Object proxy, Class<?> type) throws SQLException {
        if (type.isInstance(proxy)) return proxy;
        throw new SQLException("Not a wrapper for " + type.getName());
    }

    private static final Object NO_RESULT = new Object();

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> method.getDeclaringClass() == Object.class
                    ? proxy.getClass().getInterfaces()[0].getSimpleName() + "Proxy" : NO_RESULT;
            case "hashCode" -> method.getDeclaringClass() == Object.class ? System.identityHashCode(proxy) : NO_RESULT;
            case "equals" -> method.getDeclaringClass() == Object.class ? proxy == args[0] : NO_RESULT;
            default -> NO_RESULT;
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}

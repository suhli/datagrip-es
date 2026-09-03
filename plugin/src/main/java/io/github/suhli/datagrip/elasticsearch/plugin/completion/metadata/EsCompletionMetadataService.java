package io.github.suhli.datagrip.elasticsearch.plugin.completion.metadata;

import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DataSourceManager;
import com.intellij.database.model.RawDataSource;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Datasource-scoped metadata cache. Completion only reads immutable snapshots;
 * network refresh happens on background threads.
 */
@Service(Service.Level.PROJECT)
public final class EsCompletionMetadataService implements Disposable {
    private static final Logger LOG = Logger.getInstance(EsCompletionMetadataService.class);
    public static final long TTL_MILLIS = 20_000L;
    static final long RETRY_BACKOFF_MILLIS = 2_000L;
    static final int MAX_WILDCARD_MAPPING_TARGETS = 100;

    private final Project project;
    private final Consumer<Runnable> backgroundExecutor;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, AtomicReference<EsCompletionMetadataSnapshot>> snapshots =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RefreshState> refreshStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProviderState> providers =
            new ConcurrentHashMap<>();

    public EsCompletionMetadataService(@NotNull Project project) {
        this.project = project;
        this.clock = System::currentTimeMillis;
        this.backgroundExecutor = runnable ->
                ApplicationManager.getApplication().executeOnPooledThread(runnable);
        project.getMessageBus().connect(this).subscribe(DataSourceManager.TOPIC, new DataSourceManager.Listener() {
            @Override
            public <T extends RawDataSource> void dataSourceRemoved(DataSourceManager<T> manager, T dataSource) {
                unregisterProvider(rawDatasourceId(dataSource));
            }

            @Override
            public <T extends RawDataSource> void dataSourceChanged(DataSourceManager<T> manager, T dataSource) {
                // The next completion recreates the provider from the new effective config.
                unregisterProvider(rawDatasourceId(dataSource));
            }
        });
    }

    EsCompletionMetadataService(Consumer<Runnable> backgroundExecutor) {
        this(backgroundExecutor, System::currentTimeMillis);
    }

    EsCompletionMetadataService(Consumer<Runnable> backgroundExecutor, LongSupplier clock) {
        this.project = null;
        this.backgroundExecutor = backgroundExecutor;
        this.clock = clock;
    }

    public static EsCompletionMetadataService getInstance(Project project) {
        return project.getService(EsCompletionMetadataService.class);
    }

    public void registerProvider(EsCompletionMetadataProvider provider) {
        registerProvider(provider == null ? "" : provider.datasourceId(), "", provider);
    }

    public void registerProvider(String datasourceId, String configFingerprint, EsCompletionMetadataProvider provider) {
        if (provider == null || provider.datasourceId() == null) return;
        ProviderState replacement = new ProviderState(
                configFingerprint == null ? "" : configFingerprint, provider);
        ProviderState previous = providers.put(datasourceId, replacement);
        if (previous != null && previous.provider() != provider) {
            closeProvider(previous.provider());
            snapshots.remove(datasourceId);
            refreshStates.remove(datasourceId);
        }
    }

    public boolean hasProvider(@Nullable String datasourceId) {
        return datasourceId != null && !datasourceId.isBlank() && providers.containsKey(datasourceId);
    }

    public boolean hasProvider(@Nullable String datasourceId, String configFingerprint) {
        ProviderState state = datasourceId == null ? null : providers.get(datasourceId);
        return state != null && state.fingerprint().equals(configFingerprint == null ? "" : configFingerprint);
    }

    public void unregisterProvider(String datasourceId) {
        if (datasourceId == null) return;
        ProviderState removed = providers.remove(datasourceId);
        if (removed != null) closeProvider(removed.provider());
        snapshots.remove(datasourceId);
        refreshStates.remove(datasourceId);
    }

    public EsCompletionMetadataSnapshot snapshot(@Nullable String datasourceId) {
        if (datasourceId == null || datasourceId.isBlank()) {
            return EsCompletionMetadataSnapshot.EMPTY;
        }
        AtomicReference<EsCompletionMetadataSnapshot> ref =
                snapshots.computeIfAbsent(datasourceId, id -> new AtomicReference<>(EsCompletionMetadataSnapshot.EMPTY));
        EsCompletionMetadataSnapshot current = ref.get();
        RefreshState state = refreshStates.computeIfAbsent(datasourceId, ignored -> new RefreshState());
        if (state.targetsExpired(clock.getAsLong())) {
            scheduleRefresh(datasourceId, true, List.of());
        }
        return current;
    }

    public EsCompletionMetadataSnapshot snapshotForIndices(
            @Nullable String datasourceId, List<String> indexPatterns) {
        return snapshotForIndices(datasourceId, null, indexPatterns);
    }

    public EsCompletionMetadataSnapshot snapshotForIndices(
            @Nullable String datasourceId,
            @Nullable DbDataSource dataSource,
            List<String> indexPatterns) {
        if (dataSource != null) {
            EsCompletionMetadataRegistrar.ensureRegistered(project, dataSource);
        }
        EsCompletionMetadataSnapshot current = snapshot(datasourceId);
        if (current.indices().isEmpty() && dataSource != null) {
            List<EsCompletionMetadataSnapshot.IndexObject> modelIndices = EsDbModelIndices.list(dataSource);
            if (!modelIndices.isEmpty()) {
                putSnapshot(new EsCompletionMetadataSnapshot(
                        datasourceId == null ? "" : datasourceId,
                        current.esVersion(),
                        modelIndices,
                        current.fields(),
                        clock.getAsLong()));
                current = snapshot(datasourceId);
            }
        }
        final EsCompletionMetadataSnapshot snapshot = current;
        List<String> concrete = resolveIndices(snapshot, indexPatterns);
        int matchedTargetCount = concrete.size();
        if (concrete.size() > MAX_WILDCARD_MAPPING_TARGETS) {
            concrete = concrete.subList(0, MAX_WILDCARD_MAPPING_TARGETS);
        }
        int loadedTargetCount = concrete.size();
        boolean fieldsPartial = matchedTargetCount > loadedTargetCount;
        RefreshState refreshState = datasourceId == null
                ? null
                : refreshStates.computeIfAbsent(datasourceId, ignored -> new RefreshState());
        long now = clock.getAsLong();
        List<String> missing = refreshState == null ? List.of() : concrete.stream()
                .filter(index -> refreshState.mappingExpired(index, now))
                .toList();
        if (!missing.isEmpty()) {
            scheduleRefresh(datasourceId, false, missing);
        }
        if (concrete.isEmpty()) {
            return copyWithFields(snapshot, Map.of(), fieldsPartial, matchedTargetCount, loadedTargetCount);
        }

        Map<String, EsCompletionMetadataSnapshot.FieldInfo> filtered = filterFields(snapshot, concrete);
        if (!filtered.isEmpty()) {
            return copyWithFields(snapshot, filtered, fieldsPartial, matchedTargetCount, loadedTargetCount);
        }

        Map<String, EsCompletionMetadataSnapshot.FieldInfo> modelFields =
                EsDbModelFields.load(dataSource, concrete);
        if (!modelFields.isEmpty()) {
            return copyWithFields(snapshot, modelFields, fieldsPartial, matchedTargetCount, loadedTargetCount);
        }
        EsCompletionMetadataSnapshot enriched = enrichFromModel(snapshot, dataSource, concrete);
        return copyWithFields(
                snapshot, enriched.fields(), fieldsPartial, matchedTargetCount, loadedTargetCount);
    }

    private static EsCompletionMetadataSnapshot copyWithFields(
            EsCompletionMetadataSnapshot snapshot,
            Map<String, EsCompletionMetadataSnapshot.FieldInfo> fields) {
        return copyWithFields(snapshot, fields, snapshot.fieldsPartial(),
                snapshot.matchedTargetCount(), snapshot.loadedTargetCount());
    }

    private static EsCompletionMetadataSnapshot copyWithFields(
            EsCompletionMetadataSnapshot snapshot,
            Map<String, EsCompletionMetadataSnapshot.FieldInfo> fields,
            boolean fieldsPartial,
            int matchedTargetCount,
            int loadedTargetCount) {
        return new EsCompletionMetadataSnapshot(
                snapshot.datasourceId(),
                snapshot.esVersion(),
                snapshot.indices(),
                fields,
                snapshot.loadedAtMillis(),
                fieldsPartial,
                matchedTargetCount,
                loadedTargetCount);
    }

    private static Map<String, EsCompletionMetadataSnapshot.FieldInfo> filterFields(
            EsCompletionMetadataSnapshot snapshot, List<String> concrete) {
        Map<String, EsCompletionMetadataSnapshot.FieldInfo> filtered = new LinkedHashMap<>();
        for (EsCompletionMetadataSnapshot.FieldInfo field : snapshot.fields().values()) {
            for (String index : concrete) {
                if (field.indexCoverage().contains(index)) {
                    filtered.put(field.path(), field);
                    break;
                }
            }
        }
        return filtered;
    }

    private static EsCompletionMetadataSnapshot enrichFromModel(
            EsCompletionMetadataSnapshot snapshot,
            DbDataSource dataSource,
            List<String> indexNames) {
        if (dataSource == null || !snapshot.fields().isEmpty()) {
            return snapshot;
        }
        if (indexNames.isEmpty()) return snapshot;
        Map<String, EsCompletionMetadataSnapshot.FieldInfo> modelFields =
                EsDbModelFields.load(dataSource, indexNames);
        if (modelFields.isEmpty()) {
            return snapshot;
        }
        return copyWithFields(snapshot, modelFields);
    }

    public void putSnapshot(EsCompletionMetadataSnapshot stored) {
        if (stored == null || stored.datasourceId().isBlank()) return;
        snapshots.computeIfAbsent(stored.datasourceId(), id -> new AtomicReference<>())
                .set(stored);
    }

    private void scheduleRefresh(
            @Nullable String datasourceId, boolean refreshTargets, List<String> indexNames) {
        if (datasourceId == null || datasourceId.isBlank()) return;
        ProviderState provider = providers.get(datasourceId);
        if (provider == null) return;
        RefreshState state = refreshStates.computeIfAbsent(datasourceId, id -> new RefreshState());
        if (refreshTargets) state.targetsPending.set(true);
        state.pendingIndices.addAll(indexNames);
        if (!state.workerRunning.compareAndSet(false, true)) return;
        backgroundExecutor.accept(() -> runRefreshWorker(datasourceId, provider, state));
    }

    private void runRefreshWorker(String datasourceId, ProviderState providerState, RefreshState state) {
        for (;;) {
            if (providers.get(datasourceId) != providerState) {
                state.workerRunning.set(false);
                return;
            }
            boolean loadTargets = state.targetsPending.getAndSet(false);
            List<String> indices = drain(state.pendingIndices);
            if (!loadTargets && indices.isEmpty()) {
                state.workerRunning.set(false);
                if ((!state.pendingIndices.isEmpty() || state.targetsPending.get())
                        && state.workerRunning.compareAndSet(false, true)) {
                    continue;
                }
                return;
            }

            long attemptedAt = clock.getAsLong();
            List<EsCompletionMetadataSnapshot.IndexObject> targets = null;
            List<EsCompletionMetadataSnapshot.FieldInfo> fields = null;
            if (loadTargets) {
                state.targetsLastAttemptAt = attemptedAt;
                try {
                    try {
                        providerState.provider().refreshClusterMetadata();
                    } catch (Exception versionFailure) {
                        LOG.debug("Elasticsearch cluster version refresh failed", versionFailure);
                    }
                    targets = providerState.provider().listTargets();
                    state.targetsLastSuccessAt = clock.getAsLong();
                } catch (Exception e) {
                    LOG.debug("Elasticsearch completion target refresh failed", e);
                }
            }
            if (!indices.isEmpty()) {
                for (String index : indices) state.mappingLastAttemptAt.put(index, attemptedAt);
                try {
                    fields = providerState.provider().loadFields(indices);
                    long succeededAt = clock.getAsLong();
                    for (String index : indices) state.mappingLastSuccessAt.put(index, succeededAt);
                } catch (Exception e) {
                    LOG.debug("Elasticsearch completion mapping refresh failed", e);
                }
            }
            if (providers.get(datasourceId) != providerState) continue;
            if (targets != null || fields != null) {
                mergeRefresh(datasourceId, providerState.provider(), targets, fields, indices, attemptedAt);
            }
        }
    }

    private void mergeRefresh(
            String datasourceId,
            EsCompletionMetadataProvider provider,
            List<EsCompletionMetadataSnapshot.IndexObject> targets,
            List<EsCompletionMetadataSnapshot.FieldInfo> fields,
            List<String> refreshedIndices,
            long loadedAt) {
        AtomicReference<EsCompletionMetadataSnapshot> ref =
                snapshots.computeIfAbsent(datasourceId, ignored -> new AtomicReference<>(EsCompletionMetadataSnapshot.EMPTY));
        ref.updateAndGet(previous -> {
            Map<String, EsCompletionMetadataSnapshot.FieldInfo> merged =
                    removeCoverage(previous.fields(), fields == null ? List.of() : refreshedIndices);
            if (fields != null) {
                for (EsCompletionMetadataSnapshot.FieldInfo field : fields) {
                    merged.merge(field.path(), field, EsCompletionMetadataService::mergeField);
                }
            }
            return new EsCompletionMetadataSnapshot(
                    datasourceId,
                    provider.esVersion().isBlank() ? previous.esVersion() : provider.esVersion(),
                    targets == null ? previous.indices() : targets,
                    merged,
                    loadedAt);
        });
    }

    private static Map<String, EsCompletionMetadataSnapshot.FieldInfo> removeCoverage(
            Map<String, EsCompletionMetadataSnapshot.FieldInfo> fields, List<String> refreshedIndices) {
        if (refreshedIndices.isEmpty()) return new LinkedHashMap<>(fields);
        Set<String> refreshed = Set.copyOf(refreshedIndices);
        Map<String, EsCompletionMetadataSnapshot.FieldInfo> result = new LinkedHashMap<>();
        for (EsCompletionMetadataSnapshot.FieldInfo field : fields.values()) {
            Set<String> coverage = new LinkedHashSet<>(field.indexCoverage());
            coverage.removeAll(refreshed);
            if (!coverage.isEmpty()) {
                result.put(field.path(), new EsCompletionMetadataSnapshot.FieldInfo(
                        field.path(), field.types(), coverage, field.multiField()));
            }
        }
        return result;
    }

    private static List<String> drain(Set<String> pending) {
        List<String> result = new ArrayList<>();
        for (String value : pending) {
            if (pending.remove(value)) result.add(value);
        }
        return result;
    }

    private static void closeProvider(EsCompletionMetadataProvider provider) {
        try {
            provider.close();
        } catch (Exception e) {
            LOG.debug("Cannot close Elasticsearch completion metadata provider", e);
        }
    }

    private static String rawDatasourceId(RawDataSource dataSource) {
        if (dataSource == null) return "";
        String uniqueId = dataSource.getUniqueId();
        return uniqueId == null || uniqueId.isBlank() ? dataSource.getName() : uniqueId;
    }

    @Override
    public void dispose() {
        providers.values().forEach(state -> closeProvider(state.provider()));
        providers.clear();
        snapshots.clear();
        refreshStates.clear();
    }

    public static List<String> resolveIndices(EsCompletionMetadataSnapshot snapshot, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank() || pattern.equals("*")) {
                snapshot.indices().forEach(i -> result.add(i.name()));
                continue;
            }
            if (!pattern.contains("*") && !pattern.contains("?")) {
                result.add(pattern);
                continue;
            }
            Pattern regex = wildcard(pattern);
            for (EsCompletionMetadataSnapshot.IndexObject index : snapshot.indices()) {
                if (regex.matcher(index.name()).matches()) result.add(index.name());
            }
        }
        return List.copyOf(result);
    }

    private static Pattern wildcard(String pattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> {
                    if ("\\.[]{}()+-^$|".indexOf(c) >= 0) regex.append('\\');
                    regex.append(c);
                }
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString());
    }

    private static EsCompletionMetadataSnapshot.FieldInfo mergeField(
            EsCompletionMetadataSnapshot.FieldInfo left,
            EsCompletionMetadataSnapshot.FieldInfo right) {
        Set<String> types = new LinkedHashSet<>(left.types());
        types.addAll(right.types());
        Set<String> coverage = new LinkedHashSet<>(left.indexCoverage());
        coverage.addAll(right.indexCoverage());
        return new EsCompletionMetadataSnapshot.FieldInfo(
                left.path(), types, coverage, left.multiField() || right.multiField());
    }

    private record ProviderState(String fingerprint, EsCompletionMetadataProvider provider) {}

    private static final class RefreshState {
        private final Set<String> pendingIndices = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean targetsPending = new AtomicBoolean();
        private final AtomicBoolean workerRunning = new AtomicBoolean();
        private final ConcurrentHashMap<String, Long> mappingLastAttemptAt = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Long> mappingLastSuccessAt = new ConcurrentHashMap<>();
        private volatile long targetsLastAttemptAt;
        private volatile long targetsLastSuccessAt;

        private boolean targetsExpired(long now) {
            boolean stale = targetsLastSuccessAt <= 0 || now - targetsLastSuccessAt > TTL_MILLIS;
            return stale && (targetsLastAttemptAt <= 0
                    || now - targetsLastAttemptAt > RETRY_BACKOFF_MILLIS);
        }

        private boolean mappingExpired(String index, long now) {
            Long success = mappingLastSuccessAt.get(index);
            boolean stale = success == null || now - success > TTL_MILLIS;
            Long attempt = mappingLastAttemptAt.get(index);
            return stale && (attempt == null || now - attempt > RETRY_BACKOFF_MILLIS);
        }
    }
}

package io.github.suhli.datagrip.elasticsearch.plugin.completion;

import io.github.suhli.datagrip.elasticsearch.plugin.completion.model.EsCaretLocation;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.model.EsCompletionContext;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.model.EsExpectedKind;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsCompletionSchema;
import io.github.suhli.datagrip.elasticsearch.plugin.completion.schema.EsSchemaModels;
import io.github.suhli.datagrip.elasticsearch.plugin.language.EsRestTypes;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves completion context from .esrest PSI plus a tolerant body scanner.
 */
public final class EsCompletionContextResolver {
    private static final Pattern REQUEST_LINE = Pattern.compile(
            "^(GET|POST|PUT|DELETE|PATCH|HEAD)\\s+(\\S*)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> HTTP_METHODS = Set.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD");
    private static final Set<String> QUERY_CONTEXTS = Set.of(
            "query", "bool.must", "bool.filter", "bool.should", "bool.must_not",
            "constant_score.filter", "nested.query", "function_score.query");
    private static final Set<String> FIELD_QUERY_KEYS = Set.of(
            "term", "terms", "match", "match_phrase", "multi_match", "range", "prefix",
            "wildcard", "regexp", "fuzzy", "exists");

    private final EsCompletionSchema schema;

    public EsCompletionContextResolver(EsCompletionSchema schema) {
        this.schema = schema;
    }

    public EsCompletionContext resolve(PsiFile file, int offset, String datasourceId, String esVersion) {
        CharSequence text = file.getViewProvider().getContents();
        PsiElement element = file.findElementAt(Math.max(0, Math.min(offset, Math.max(text.length() - 1, 0))));
        PsiElement request = element == null ? null : findRequest(element);
        int requestStart;
        int requestEnd;
        if (request != null) {
            requestStart = request.getTextRange().getStartOffset();
            requestEnd = request.getTextRange().getEndOffset();
        } else {
            requestStart = findRequestStart(text, offset);
            requestEnd = findRequestEnd(text, offset);
        }
        return resolve(text, offset, requestStart, requestEnd, datasourceId, esVersion);
    }

    /** Text-only resolution used by unit tests and fallbacks. */
    public EsCompletionContext resolve(
            CharSequence fullText, int offset, String datasourceId, String esVersion) {
        int requestStart = findRequestStart(fullText, offset);
        int requestEnd = findRequestEnd(fullText, offset);
        return resolve(fullText, offset, requestStart, requestEnd, datasourceId, esVersion);
    }

    private EsCompletionContext resolve(
            CharSequence text,
            int offset,
            int requestStart,
            int requestEnd,
            String datasourceId,
            String esVersion) {
        String requestText = text.subSequence(requestStart, Math.min(requestEnd, text.length())).toString();
        Matcher matcher = REQUEST_LINE.matcher(requestText);
        String method = "";
        String rawPath = "";
        int lineEnd = lineEnd(requestText, 0);
        if (matcher.find() && matcher.start() < lineEnd) {
            method = matcher.group(1).toUpperCase(Locale.ROOT);
            rawPath = matcher.group(2);
        }

        int caretInRequest = Math.max(0, offset - requestStart);
        boolean inBody = caretInRequest > lineEnd;
        PathInfo pathInfo = parsePath(rawPath, method, inBody ? rawPath.length() : caretInRequest - methodPrefixLength(requestText, method));

        EsCompletionContext.Builder builder = EsCompletionContext.builder()
                .method(method)
                .path(pathInfo.pathOnly())
                .endpoint(pathInfo.endpoint())
                .indices(pathInfo.indices())
                .datasourceId(datasourceId)
                .esVersion(esVersion)
                .requestStart(requestStart)
                .requestEnd(requestEnd)
                .caretOffset(offset);

        if (!inBody) {
            return resolveUrlContext(builder, pathInfo, requestText, caretInRequest, method);
        }

        int bodyStart = findBodyStart(requestText, lineEnd);
        if (bodyStart < 0) {
            return builder.location(EsCaretLocation.BODY).expectedKind(EsExpectedKind.UNKNOWN).build();
        }
        EsJsonPathScanner.ScanResult scan = EsJsonPathScanner.scan(requestText, bodyStart, caretInRequest);
        return resolveBodyContext(builder, pathInfo, scan);
    }

    private EsCompletionContext resolveUrlContext(
            EsCompletionContext.Builder builder,
            PathInfo pathInfo,
            String requestText,
            int caretInRequest,
            String method) {
        if (method.isEmpty()) {
            String methodPrefix = methodPrefixAtCaret(requestText, caretInRequest);
            if (!methodPrefix.isEmpty() || caretInRequest <= firstNonWhitespace(requestText)) {
                return builder.location(EsCaretLocation.METHOD)
                        .expectedKind(EsExpectedKind.HTTP_METHOD)
                        .prefix(methodPrefix)
                        .build();
            }
        }

        if (pathInfo.inQueryString()) {
            builder.location(EsCaretLocation.QUERY_STRING);
            if (pathInfo.queryValue()) {
                return builder.expectedKind(EsExpectedKind.QUERY_PARAMETER_VALUE)
                        .queryParameterName(pathInfo.queryParameterName())
                        .prefix(pathInfo.prefix())
                        .build();
            }
            return builder.expectedKind(EsExpectedKind.QUERY_PARAMETER)
                    .prefix(pathInfo.prefix())
                    .build();
        }

        builder.location(EsCaretLocation.URL).prefix(pathInfo.prefix());
        if (pathInfo.expectIndex()) {
            return builder.expectedKind(EsExpectedKind.INDEX).build();
        }
        if (pathInfo.expectEndpoint()) {
            return builder.expectedKind(EsExpectedKind.ENDPOINT).build();
        }
        return builder.expectedKind(EsExpectedKind.PATH_SEGMENT).build();
    }

    private EsCompletionContext resolveBodyContext(
            EsCompletionContext.Builder builder,
            PathInfo pathInfo,
            EsJsonPathScanner.ScanResult scan) {
        builder.location(EsCaretLocation.BODY)
                .prefix(scan.prefix())
                .jsonPath(scan.jsonPath())
                .currentProperty(scan.currentProperty())
                .parentProperty(scan.parentProperty())
                .insideString(scan.insideString())
                .replacingQuotedValue(scan.insideString());

        String path = scan.jsonPath();
        String parent = scan.parentProperty();
        List<EsJsonPathScanner.Frame> stack = scan.stack();

        if (isAggregationNameContext(stack, path, scan.expectingKey())) {
            return builder.expectedKind(EsExpectedKind.AGGREGATION_NAME).build();
        }
        if (isAggregationTypeContext(stack, path, scan.expectingKey())) {
            return builder.expectedKind(EsExpectedKind.AGGREGATION_TYPE).build();
        }
        if (scan.expectingKey() && isFieldKeyContext(path, parent, stack)) {
            return builder.expectedKind(EsExpectedKind.FIELD_KEY).build();
        }
        if (!scan.expectingKey() && isFieldValueContext(path, parent)) {
            return builder.expectedKind(EsExpectedKind.FIELD_VALUE).build();
        }
        if (!scan.expectingKey()) {
            EsSchemaModels.DslNode node = schema.findProperty(parent, lastSegment(path));
            if (node != null) {
                if ("boolean".equals(node.valueType())) {
                    return builder.expectedKind(EsExpectedKind.BOOLEAN_VALUE).build();
                }
                if ("enum".equals(node.valueType()) || !node.enumValues().isEmpty()) {
                    return builder.expectedKind(EsExpectedKind.ENUM_VALUE).build();
                }
                if (node.fieldReference() || "field".equals(node.valueType())) {
                    return builder.expectedKind(EsExpectedKind.FIELD_VALUE).build();
                }
            }
            if ("operator".equals(lastSegment(path)) || "score_mode".equals(lastSegment(path))) {
                return builder.expectedKind(EsExpectedKind.ENUM_VALUE).build();
            }
        }

        if (scan.expectingKey()) {
            if (isQueryDslContext(path, parent, stack)) {
                return builder.expectedKind(EsExpectedKind.QUERY_DSL).build();
            }
            if (path.isEmpty() || isSearchRoot(pathInfo.endpoint())) {
                if (path.isEmpty() || !path.contains(".")) {
                    return builder.expectedKind(EsExpectedKind.BODY_KEY).build();
                }
            }
            EsSchemaModels.DslNode parentNode = schema.findKey(parent);
            if (parentNode != null && !parentNode.children().isEmpty()) {
                return builder.expectedKind(EsExpectedKind.BODY_KEY).build();
            }
            if (isQueryDslContext(path, parent, stack)) {
                return builder.expectedKind(EsExpectedKind.QUERY_DSL).build();
            }
            return builder.expectedKind(EsExpectedKind.BODY_KEY).build();
        }
        return builder.expectedKind(EsExpectedKind.UNKNOWN).build();
    }

    private boolean isSearchRoot(String endpoint) {
        return endpoint != null && (endpoint.equals("_search") || endpoint.endsWith("/_search")
                || endpoint.contains("_search"));
    }

    private boolean isQueryDslContext(String path, String parent, List<EsJsonPathScanner.Frame> stack) {
        if ("query".equals(parent) || path.equals("query") || path.endsWith(".query")) return true;
        if (QUERY_CONTEXTS.contains(path) || QUERY_CONTEXTS.contains(parent)) return true;
        if (path.contains("bool.filter") || path.contains("bool.must")
                || path.contains("bool.should") || path.contains("bool.must_not")) {
            return path.endsWith("[]") || path.matches(".*bool\\.(filter|must|should|must_not)$")
                    || (scanTopIsObjectAfterArray(stack) && path.contains("bool."));
        }
        return false;
    }

    private boolean scanTopIsObjectAfterArray(List<EsJsonPathScanner.Frame> stack) {
        return !stack.isEmpty() && stack.get(stack.size() - 1).kind() == EsJsonPathScanner.FrameKind.OBJECT;
    }

    private boolean isFieldKeyContext(String path, String parent, List<EsJsonPathScanner.Frame> stack) {
        if (FIELD_QUERY_KEYS.contains(parent)) {
            // term/match/range object keys are field names
            return !"exists".equals(parent);
        }
        return false;
    }

    private boolean isFieldValueContext(String path, String parent) {
        String leaf = lastSegment(path);
        if ("field".equals(leaf) || "fields".equals(leaf)) return true;
        if (("sort".equals(parent) || "_source".equals(parent) || "fields".equals(parent))
                && path.endsWith("[]")) {
            return true;
        }
        return "exists".equals(parent) && "field".equals(leaf);
    }

    private boolean isAggregationNameContext(List<EsJsonPathScanner.Frame> stack, String path, boolean expectingKey) {
        if (!expectingKey || stack.isEmpty()) return false;
        EsJsonPathScanner.Frame top = stack.get(stack.size() - 1);
        if (top.kind() != EsJsonPathScanner.FrameKind.OBJECT) return false;
        String prop = top.property();
        return "aggs".equals(prop) || "aggregations".equals(prop);
    }

    private boolean isAggregationTypeContext(List<EsJsonPathScanner.Frame> stack, String path, boolean expectingKey) {
        if (!expectingKey || stack.size() < 2) return false;
        EsJsonPathScanner.Frame top = stack.get(stack.size() - 1);
        EsJsonPathScanner.Frame parent = stack.get(stack.size() - 2);
        if (top.kind() != EsJsonPathScanner.FrameKind.OBJECT) return false;
        String aggContainer = parent.property();
        return ("aggs".equals(aggContainer) || "aggregations".equals(aggContainer))
                && top.property() != null && !top.property().isBlank();
    }

    private static PsiElement findRequest(PsiElement element) {
        PsiElement current = element;
        while (current != null) {
            if (current.getNode() != null && current.getNode().getElementType() == EsRestTypes.REQUEST) {
                return current;
            }
            current = current.getParent();
        }
        return PsiTreeUtil.getParentOfType(element, PsiElement.class);
    }

    private static int findRequestStart(CharSequence text, int offset) {
        int i = Math.min(offset, text.length());
        while (i > 0) {
            int lineStart = lineStart(text, i - 1);
            String line = text.subSequence(lineStart, lineEnd(text, lineStart)).toString().trim();
            if (looksLikeMethod(line)) return lineStart;
            if (lineStart == 0) break;
            i = lineStart;
        }
        return 0;
    }

    private static int findRequestEnd(CharSequence text, int offset) {
        int i = Math.min(offset, text.length());
        while (i < text.length()) {
            int start = lineStart(text, i);
            if (start > offset) {
                String line = text.subSequence(start, lineEnd(text, start)).toString().trim();
                if (looksLikeMethod(line)) return start;
            }
            int end = lineEnd(text, i);
            if (end >= text.length()) return text.length();
            i = end + 1;
        }
        return text.length();
    }

    private static boolean looksLikeMethod(String line) {
        int sp = indexOfWhitespace(line);
        if (sp <= 0) return false;
        String method = line.substring(0, sp).toUpperCase(Locale.ROOT);
        return Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD").contains(method);
    }

    private static int findBodyStart(String requestText, int afterLine) {
        for (int i = afterLine; i < requestText.length(); i++) {
            char c = requestText.charAt(i);
            if (c == '{' || c == '[') return i;
            if (!Character.isWhitespace(c) && c != '#' && c != '/') return i;
        }
        return -1;
    }

    private static String methodPrefixAtCaret(String requestText, int caretInRequest) {
        int lineEnd = lineEnd(requestText, 0);
        int limit = Math.min(caretInRequest, lineEnd);
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            char c = requestText.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!prefix.isEmpty()) break;
                continue;
            }
            if (Character.isLetter(c)) {
                prefix.append(c);
                continue;
            }
            break;
        }
        String value = prefix.toString();
        if (value.isEmpty()) return "";
        String upper = value.toUpperCase(Locale.ROOT);
        for (String candidate : HTTP_METHODS) {
            if (candidate.startsWith(upper)) return value;
        }
        return "";
    }

    private static int methodPrefixLength(String requestText, String method) {
        if (method.isEmpty()) return 0;
        int idx = requestText.toUpperCase(Locale.ROOT).indexOf(method);
        if (idx < 0) return 0;
        int i = idx + method.length();
        while (i < requestText.length() && Character.isWhitespace(requestText.charAt(i))) i++;
        return i;
    }

    private static PathInfo parsePath(String rawPath, String method, int caretInPathArea) {
        String path = rawPath == null ? "" : rawPath;
        // caret relative approximation for URL area
        int caret = Math.max(0, Math.min(caretInPathArea, path.length()));
        String before = path.substring(0, caret);
        String afterQuery = before;
        boolean inQuery = before.contains("?");
        String queryParameterName = "";
        boolean queryValue = false;
        String prefix;
        if (inQuery) {
            int q = before.indexOf('?');
            afterQuery = before.substring(q + 1);
            int amp = afterQuery.lastIndexOf('&');
            String current = amp >= 0 ? afterQuery.substring(amp + 1) : afterQuery;
            int eq = current.indexOf('=');
            if (eq >= 0) {
                queryParameterName = current.substring(0, eq);
                prefix = current.substring(eq + 1);
                queryValue = true;
            } else {
                prefix = current;
            }
            String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            EndpointParts parts = endpointParts(pathOnly);
            return new PathInfo(pathOnly, parts.endpoint(), parts.indices(), prefix, false, true,
                    true, queryParameterName, queryValue);
        }

        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        String beforePath = before.contains("?") ? before.substring(0, before.indexOf('?')) : before;
        EndpointParts parts = endpointParts(pathOnly);
        boolean trailingSlash = beforePath.endsWith("/");
        String lastSegment = lastPathSegment(beforePath);
        prefix = trailingSlash ? "" : lastSegment;
        boolean expectIndex = beforePath.equals("/") || beforePath.isEmpty()
                || (!lastSegment.startsWith("_") && parts.indices().isEmpty() && !beforePath.contains("/_"));
        // GET /game<caret> or GET /<caret>
        if (beforePath.matches("^/[^/]*$") && !beforePath.startsWith("/_")) {
            expectIndex = true;
        }
        boolean expectEndpoint = beforePath.contains("/_") || beforePath.equals("/_")
                || lastSegment.startsWith("_") || trailingSlash && !parts.indices().isEmpty();
        if (beforePath.matches("^/[^/]+/_?[^/]*$") && beforePath.contains("/_")) {
            expectEndpoint = true;
            expectIndex = false;
        }
        if (beforePath.startsWith("/_") || beforePath.equals("/")) {
            // cluster level endpoints also start with _
            if (beforePath.startsWith("/_")) {
                expectEndpoint = true;
                expectIndex = false;
            }
        }
        return new PathInfo(pathOnly, parts.endpoint(), parts.indices(), prefix, expectIndex, expectEndpoint,
                false, "", false);
    }

    private static EndpointParts endpointParts(String pathOnly) {
        String normalized = pathOnly.startsWith("/") ? pathOnly.substring(1) : pathOnly;
        if (normalized.isEmpty()) return new EndpointParts("", List.of());
        String[] segments = normalized.split("/");
        List<String> indices = new ArrayList<>();
        String endpoint = "";
        if (segments.length > 0 && !segments[0].startsWith("_")) {
            indices.addAll(Arrays.asList(segments[0].split(",")));
            if (segments.length > 1) {
                endpoint = String.join("/", Arrays.copyOfRange(segments, 1, segments.length));
            }
        } else {
            endpoint = String.join("/", segments);
        }
        return new EndpointParts(endpoint, List.copyOf(indices));
    }

    private static String lastPathSegment(String path) {
        if (path.isEmpty()) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String lastSegment(String path) {
        if (path == null || path.isEmpty()) return "";
        String clean = path.endsWith("[]") ? path.substring(0, path.length() - 2) : path;
        int idx = clean.lastIndexOf('.');
        return idx >= 0 ? clean.substring(idx + 1) : clean;
    }

    private static int lineStart(CharSequence text, int offset) {
        int i = Math.min(offset, text.length());
        while (i > 0 && text.charAt(i - 1) != '\n' && text.charAt(i - 1) != '\r') i--;
        return i;
    }

    private static int lineEnd(CharSequence text, int offset) {
        int i = Math.min(offset, text.length());
        while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') i++;
        return i;
    }

    private static int lineEnd(String text, int offset) {
        return lineEnd((CharSequence) text, offset);
    }

    private static int firstNonWhitespace(String text) {
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return i;
    }

    private static int indexOfWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) return i;
        }
        return -1;
    }

    private record PathInfo(
            String pathOnly,
            String endpoint,
            List<String> indices,
            String prefix,
            boolean expectIndex,
            boolean expectEndpoint,
            boolean inQueryString,
            String queryParameterName,
            boolean queryValue) {}

    private record EndpointParts(String endpoint, List<String> indices) {}
}

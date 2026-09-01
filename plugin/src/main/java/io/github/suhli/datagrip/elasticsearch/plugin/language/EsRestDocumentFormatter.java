package io.github.suhli.datagrip.elasticsearch.plugin.language;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text-based Dev Tools formatter used by the database console reformat action. */
public final class EsRestDocumentFormatter {
    private static final Pattern REQUEST_LINE = Pattern.compile(
            "^(GET|POST|PUT|DELETE|PATCH|HEAD)\\s+(\\S*)(\\s+#.*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUEST_START = Pattern.compile(
            "^(GET|POST|PUT|DELETE|PATCH|HEAD)\\b", Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectWriter PRETTY = JSON.writerWithDefaultPrettyPrinter();

    private EsRestDocumentFormatter() {}

    public static String format(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        boolean trailingNewline = text.endsWith("\n");
        List<RequestChunk> chunks = splitRequests(text);
        if (chunks.isEmpty()) return text;

        StringBuilder out = new StringBuilder(text.length() + 64);
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) out.append('\n');
            out.append(formatChunk(chunks.get(i)));
        }
        if (trailingNewline && !out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
        return out.toString();
    }

    private static String formatChunk(RequestChunk chunk) {
        String requestLine = normalizeRequestLine(chunk.requestLine());
        String body = chunk.body().trim();
        if (body.isEmpty()) return requestLine;
        return requestLine + '\n' + formatJsonBody(body);
    }

    private static String normalizeRequestLine(String line) {
        String trimmed = line.trim();
        Matcher matcher = REQUEST_LINE.matcher(trimmed);
        if (!matcher.matches()) return trimmed;
        String method = matcher.group(1).toUpperCase(Locale.ROOT);
        String path = matcher.group(2) == null ? "" : matcher.group(2);
        String comment = matcher.group(3) == null ? "" : matcher.group(3);
        return method + " " + path + comment;
    }

    private static String formatJsonBody(String body) {
        int start = firstJsonStart(body);
        if (start < 0) return body;
        String prefix = body.substring(0, start);
        String jsonPart = body.substring(start).trim();
        String suffix = "";
        int jsonEnd = lastJsonEnd(jsonPart);
        if (jsonEnd >= 0 && jsonEnd + 1 < jsonPart.length()) {
            suffix = jsonPart.substring(jsonEnd + 1);
            jsonPart = jsonPart.substring(0, jsonEnd + 1);
        }
        try {
            JsonNode node = JSON.readTree(jsonPart);
            String pretty = PRETTY.writeValueAsString(node);
            return prefix + pretty + suffix;
        } catch (JsonProcessingException ignored) {
            return body;
        }
    }

    private static int firstJsonStart(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') return i;
        }
        return -1;
    }

    private static int lastJsonEnd(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '}' || c == ']') return i;
        }
        return -1;
    }

    private static List<RequestChunk> splitRequests(String text) {
        List<Integer> starts = new ArrayList<>();
        int lineStart = 0;
        while (lineStart < text.length()) {
            int lineEnd = lineEnd(text, lineStart);
            String line = text.substring(lineStart, lineEnd).trim();
            if (!line.isEmpty() && REQUEST_START.matcher(line).find()) {
                starts.add(lineStart);
            }
            lineStart = skipNewline(text, lineEnd);
        }
        if (starts.isEmpty()) {
            return List.of(new RequestChunk("", text));
        }

        List<RequestChunk> chunks = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int chunkStart = starts.get(i);
            int chunkEnd = i + 1 < starts.size() ? starts.get(i + 1) : text.length();
            String chunkText = text.substring(chunkStart, chunkEnd);
            int firstLineEnd = lineEnd(chunkText, 0);
            String requestLine = chunkText.substring(0, firstLineEnd);
            String body = chunkText.substring(skipNewline(chunkText, firstLineEnd));
            chunks.add(new RequestChunk(requestLine, body));
        }
        return chunks;
    }

    private static int lineEnd(CharSequence text, int offset) {
        int i = offset;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') return i;
            i++;
        }
        return text.length();
    }

    private static int skipNewline(CharSequence text, int offset) {
        int i = offset;
        if (i < text.length() && text.charAt(i) == '\r') i++;
        if (i < text.length() && text.charAt(i) == '\n') i++;
        return i;
    }

    private record RequestChunk(String requestLine, String body) {}
}

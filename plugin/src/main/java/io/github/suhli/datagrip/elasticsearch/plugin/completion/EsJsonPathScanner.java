package io.github.suhli.datagrip.elasticsearch.plugin.completion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Tolerant scanner for incomplete JSON bodies. Does not require a complete
 * parse tree or ObjectMapper success.
 */
public final class EsJsonPathScanner {
    public enum FrameKind { OBJECT, ARRAY }

    public record Frame(FrameKind kind, String property) {}

    public record ScanResult(
            List<Frame> stack,
            String jsonPath,
            String currentProperty,
            String parentProperty,
            boolean expectingKey,
            boolean expectingValue,
            boolean insideString,
            boolean unfinishedString,
            String prefix,
            int stringStartOffset) {}

    private EsJsonPathScanner() {}

    public static ScanResult scan(CharSequence text, int start, int caret) {
        Deque<Frame> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        boolean unfinishedString = false;
        int stringStart = -1;
        String currentKey = null;
        boolean expectingKey = true;
        boolean expectingValue = false;
        StringBuilder token = new StringBuilder();
        String prefix = "";

        int end = Math.min(caret, text.length());
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    token.append(c);
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    token.append(c);
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                    unfinishedString = false;
                    String value = token.toString();
                    token.setLength(0);
                    if (expectingKey) {
                        currentKey = value;
                        expectingKey = false;
                    } else if (expectingValue) {
                        expectingValue = false;
                        expectingKey = topIsObject(stack);
                        currentKey = null;
                    }
                    prefix = "";
                    stringStart = -1;
                    continue;
                }
                token.append(c);
                continue;
            }

            if (Character.isWhitespace(c)) continue;

            switch (c) {
                case '"' -> {
                    inString = true;
                    unfinishedString = true;
                    stringStart = i;
                    token.setLength(0);
                    prefix = "";
                }
                case '{' -> {
                    stack.push(new Frame(FrameKind.OBJECT, currentKey));
                    currentKey = null;
                    expectingKey = true;
                    expectingValue = false;
                    prefix = "";
                }
                case '[' -> {
                    stack.push(new Frame(FrameKind.ARRAY, currentKey));
                    currentKey = null;
                    expectingKey = false;
                    expectingValue = true;
                    prefix = "";
                }
                case '}' -> {
                    if (!stack.isEmpty() && stack.peek().kind() == FrameKind.OBJECT) stack.pop();
                    expectingKey = topIsObject(stack);
                    expectingValue = false;
                    currentKey = null;
                    prefix = "";
                }
                case ']' -> {
                    if (!stack.isEmpty() && stack.peek().kind() == FrameKind.ARRAY) stack.pop();
                    expectingKey = topIsObject(stack);
                    expectingValue = false;
                    currentKey = null;
                    prefix = "";
                }
                case ':' -> {
                    expectingValue = true;
                    expectingKey = false;
                    prefix = "";
                }
                case ',' -> {
                    expectingKey = topIsObject(stack);
                    expectingValue = !expectingKey;
                    currentKey = null;
                    prefix = "";
                }
                default -> {
                    // unquoted prefix (incomplete key/value)
                    int j = i;
                    while (j < end && !Character.isWhitespace(text.charAt(j))
                            && "{}[]:,\"".indexOf(text.charAt(j)) < 0) {
                        j++;
                    }
                    prefix = text.subSequence(i, j).toString();
                    i = j - 1;
                }
            }
        }

        if (inString) {
            prefix = token.toString();
            unfinishedString = true;
        }

        List<Frame> frames = new ArrayList<>(stack);
        java.util.Collections.reverse(frames);
        String jsonPath = buildPath(frames, currentKey, expectingKey);
        String parentProperty = frames.isEmpty() ? "" : nullToEmpty(frames.get(frames.size() - 1).property());
        String currentProperty = currentKey != null ? currentKey : (expectingKey ? prefix : "");
        return new ScanResult(
                List.copyOf(frames),
                jsonPath,
                currentProperty,
                parentProperty,
                expectingKey,
                expectingValue || (!expectingKey && topIsObject(stack)),
                inString || unfinishedString,
                unfinishedString,
                prefix,
                stringStart);
    }

    private static boolean topIsObject(Deque<Frame> stack) {
        return !stack.isEmpty() && stack.peek().kind() == FrameKind.OBJECT;
    }

    private static String buildPath(List<Frame> frames, String currentKey, boolean expectingKey) {
        StringBuilder path = new StringBuilder();
        for (Frame frame : frames) {
            if (frame.property() != null && !frame.property().isBlank()) {
                if (!path.isEmpty()) path.append('.');
                path.append(frame.property());
            }
            if (frame.kind() == FrameKind.ARRAY) {
                path.append("[]");
            }
        }
        if (!expectingKey && currentKey != null && !currentKey.isBlank()) {
            if (!path.isEmpty()) path.append('.');
            path.append(currentKey);
        }
        return path.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

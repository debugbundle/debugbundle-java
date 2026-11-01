package com.debugbundle.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Reads native exception state only on the existing bounded delivery worker.
 * Scalars used for new evidence are retained from the same legacy stack reads. */
final class ExceptionSnapshot {
    record Result(String stack, Map<String, Object> evidence) { }
    private record Retained(String name, String message, StackTraceElement[] frames, Throwable cause) { }
    private final Function<Throwable, String> message;
    private final Map<Throwable, Retained> retained = new IdentityHashMap<>();
    private final Map<Throwable, Integer> ids = new IdentityHashMap<>();
    private final Set<Throwable> active = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Map<String, Object>> nodes = new ArrayList<>();
    private boolean truncated;
    private final boolean fatal;
    private final int maxNodes;
    private final int maxFrames;

    private ExceptionSnapshot(Function<Throwable, String> message, boolean fatal) {
        this.message = message; this.fatal = fatal; maxNodes = fatal ? 2 : 8; maxFrames = fatal ? 8 : 32;
    }

    static Result capture(Throwable error, Function<Throwable, String> message, boolean evidence, boolean fatal) {
        ExceptionSnapshot snapshot = new ExceptionSnapshot(message, fatal);
        String stack = snapshot.legacy(error);
        return new Result(stack, evidence ? snapshot.graph(error) : null);
    }

    private Retained read(Throwable error) {
        Retained previous = retained.get(error);
        if (previous != null) return previous;
        if (retained.size() >= 8) { truncated = true; return null; }
        String safeMessage = message.apply(error);
        StackTraceElement[] frames;
        try { frames = error.getStackTrace(); } catch (Throwable ignored) { frames = null; }
        // JVM accessors may copy a native array before these bounds; this worker
        // is the only owner and never retains raw exceptions after snapshotting.
        if (frames == null) frames = new StackTraceElement[0];
        Throwable cause;
        try { cause = error.getCause(); } catch (Throwable ignored) { cause = null; truncated = true; }
        Retained value = new Retained(error.getClass().getName(), safeMessage, frames, cause);
        retained.put(error, value);
        return value;
    }

    private String legacy(Throwable error) {
        StringBuilder builder = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        int causes = 0;
        while (current != null && causes < 8 && seen.add(current) && builder.length() < 32768) {
            Retained value = read(current);
            if (value == null) break;
            if (causes > 0) builder.append("\nCaused by: ");
            builder.append(value.name()).append(": ").append(value.message());
            StackTraceElement[] frames = value.frames();
            for (int index = 0; index < Math.min(frames.length, 64) && builder.length() < 32768; index++) {
                StackTraceElement element = frames[index];
                if (element == null) continue;
                builder.append("\n at ").append(element.getClassName()).append(".").append(element.getMethodName())
                        .append("(").append(element.getFileName()).append(":").append(element.getLineNumber()).append(")");
            }
            if (frames.length > 64) builder.append("\n ... ").append(frames.length - 64).append(" more");
            current = value.cause(); causes++;
        }
        return builder.substring(0, Math.min(builder.length(), 32768));
    }

    private Map<String, Object> graph(Throwable root) {
        add(root, 0);
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("version", 1); graph.put("runtime", "jvm"); graph.put("root_id", 0);
        graph.put("nodes", nodes); graph.put("truncated", truncated); graph.put("unavailable_reason", null);
        ExceptionEvidenceBudget.fit(graph, fatal ? 3500 : 31000);
        return graph;
    }

    private boolean fitsDepth(int id, int depth, Set<Integer> active) {
        if (depth > 4 || !active.add(id)) return false;
        for (var edge : edges(nodes.get(id))) if (!fitsDepth(((Number) edge.get("target_id")).intValue(), depth + 1, active)) return false;
        active.remove(id); return true;
    }

    private Integer add(Throwable error, int depth) {
        if (error == null) return null;
        if (depth > 4 || active.contains(error)) { truncated = true; return null; }
        if (ids.containsKey(error)) {
            int id = ids.get(error);
            if (fitsDepth(id, depth, new java.util.HashSet<>())) return id;
            truncated = true; return null;
        }
        if (nodes.size() >= maxNodes) { truncated = true; return null; }
        Retained value = read(error);
        if (value == null) return null;
        int id = nodes.size(); ids.put(error, id); active.add(error);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id); node.put("name", cap(value.name(), 256)); node.put("message", cap(value.message(), 2048));
        List<Map<String, Object>> frames = frames(value.frames(), maxFrames);
        node.put("stack", null); node.put("trace_origin", fatal ? "native_fatal" : frames.isEmpty() ? "unavailable" : "throw");
        node.put("parsing_status", value.frames().length > maxFrames ? "truncated" : frames.isEmpty() ? "unavailable" : "parsed");
        node.put("frames", frames); node.put("relationships", new ArrayList<Map<String, Object>>());
        if (value.frames().length > maxFrames) truncated = true;
        nodes.add(node);
        edge(node, value.cause(), depth, "cause");
        Throwable[] suppressed;
        try { suppressed = error.getSuppressed(); } catch (Throwable ignored) { suppressed = null; truncated = true; }
        if (suppressed != null) {
            for (int i = 0; i < Math.min(suppressed.length, 5); i++) {
                if (edges(node).size() >= 5) { truncated = true; break; }
                edge(node, suppressed[i], depth, "suppressed");
            }
            if (suppressed.length > 5) truncated = true;
        }
        active.remove(error);
        return id;
    }

    private void edge(Map<String, Object> node, Throwable child, int depth, String kind) {
        Integer target = add(child, depth + 1);
        if (target == null) return;
        Map<String, Object> edge = Map.of("kind", kind, "target_id", target);
        if (!edges(node).contains(edge)) edges(node).add(edge);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> edges(Map<String, Object> node) {
        return (List<Map<String, Object>>) node.get("relationships");
    }

    private static List<Map<String, Object>> frames(StackTraceElement[] source, int maximum) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < Math.min(source.length, maximum); index++) {
            StackTraceElement item = source[index];
            if (item == null) continue;
            Map<String, Object> frame = new LinkedHashMap<>();
            String function = item.getClassName() + "." + item.getMethodName();
            frame.put("function", clip(function, 256)); frame.put("file", clip(item.getFileName(), 2048));
            frame.put("line", item.getLineNumber() > 0 ? item.getLineNumber() : null); frame.put("column", null);
            frame.put("classification", function.startsWith("com.debugbundle.") ? "sdk"
                    : function.startsWith("java.") || function.startsWith("jdk.") || function.startsWith("sun.") ? "framework" : "unknown");
            result.add(frame);
        }
        return result;
    }

    private String cap(String value, int maximum) {
        String result = clip(value, maximum);
        if (!java.util.Objects.equals(result, value)) truncated = true;
        return result;
    }

    static String clip(String value, int maximum) {
        if (value == null) return null;
        int bytes = 0, index = 0;
        while (index < value.length()) {
            int point = value.codePointAt(index);
            int width = point < 128 ? 1 : point < 2048 ? 2 : point < 65536 ? 3 : 4;
            if (bytes + width > maximum) break;
            bytes += width; index += Character.charCount(point);
        }
        return value.substring(0, index);
    }
}

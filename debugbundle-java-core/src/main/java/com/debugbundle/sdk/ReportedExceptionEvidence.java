package com.debugbundle.sdk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Pure bounded parsing of retained JVM stderr text. No Throwable, lookup or symbolication. */
final class ReportedExceptionEvidence {
    private static final Pattern RELATION = Pattern.compile("^(Caused by|Suppressed):\\s+([\\w.$/]{1,256})(?::\\s*(.*))?$");
    private static final Pattern FRAME = Pattern.compile("^at\\s+([^()\\s]{1,512})\\(([^\\r\\n]{1,4096})\\)$");
    private ReportedExceptionEvidence() { }

    static Map<String, Object> capture(String name, String message, String stack) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node(0, name, message));
        Map<Integer, Integer> levels = new HashMap<>(); levels.put(0, 0);
        Map<Integer, Integer> depths = new HashMap<>(); depths.put(0, 0);
        int current = 0;
        boolean truncated = stack != null && stack.length() > 32768;
        String bounded = stack == null ? "" : stack.substring(0, Math.min(32768, stack.length()));
        String[] lines = bounded.split("\\r?\\n", 130);
        for (int index = 1; index < Math.min(lines.length, 128); index++) {
            String raw = lines[index], line = raw.strip();
            var relation = RELATION.matcher(line);
            if (relation.matches()) {
                int indentation = 0;
                for (int i = 0; i < raw.length() && Character.isWhitespace(raw.charAt(i)); i++)
                    indentation += raw.charAt(i) == '\t' ? 4 : 1;
                int level = indentation / 4;
                Integer parent = relation.group(1).equals("Suppressed") ? levels.get(level - 1) : levels.get(level);
                if (parent == null || depths.get(parent) >= 4 || nodes.size() >= 8 || edges(nodes.get(parent)).size() >= 5) {
                    truncated = true; continue;
                }
                String kind = relation.group(1).equals("Suppressed") ? "suppressed" : "cause";
                if (kind.equals("cause") && edges(nodes.get(parent)).stream().anyMatch(edge -> edge.get("kind").equals("cause"))) {
                    truncated = true; continue;
                }
                current = nodes.size();
                nodes.add(node(current, relation.group(2), relation.group(3) == null ? "" : relation.group(3)));
                edges(nodes.get(parent)).add(Map.of("kind", kind, "target_id", current));
                depths.put(current, depths.get(parent) + 1);
                levels.keySet().removeIf(key -> key > level); levels.put(level, current);
                continue;
            }
            var parsed = FRAME.matcher(line);
            Map<String, Object> currentNode = nodes.get(current);
            if (!parsed.matches()) { currentNode.put("parsing_status", "partial"); continue; }
            List<Map<String, Object>> frames = frames(currentNode);
            if (frames.size() >= 32) { currentNode.put("parsing_status", "truncated"); truncated = true; continue; }
            String file = parsed.group(2);
            Integer number = null;
            int colon = file.lastIndexOf(':');
            if (colon >= 0) {
                try { int value = Integer.parseInt(file.substring(colon + 1)); if (value > 0) number = value; }
                catch (NumberFormatException ignored) { }
                file = file.substring(0, colon);
            }
            if (file.equals("Native Method") || file.equals("Unknown Source")) file = null;
            Map<String, Object> frame = new LinkedHashMap<>();
            String function = parsed.group(1);
            frame.put("function", ExceptionSnapshot.clip(function, 256)); frame.put("file", ExceptionSnapshot.clip(file, 2048));
            frame.put("line", number); frame.put("column", null);
            String owner = function.substring(function.lastIndexOf('/') + 1);
            frame.put("classification", owner.startsWith("com.debugbundle.") ? "sdk"
                    : owner.startsWith("java.") || owner.startsWith("jdk.") || owner.startsWith("sun.") ? "framework" : "unknown");
            frames.add(frame);
            if (currentNode.get("parsing_status").equals("unparsed")) currentNode.put("parsing_status", "parsed");
        }
        if (lines.length > 128) truncated = true;
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("version", 1); graph.put("runtime", "jvm"); graph.put("root_id", 0); graph.put("nodes", nodes);
        graph.put("truncated", truncated); graph.put("unavailable_reason", null);
        ExceptionEvidenceBudget.fit(graph, 31000);
        return graph;
    }
    private static Map<String, Object> node(int id, String name, String message) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id); value.put("name", ExceptionSnapshot.clip(name, 256)); value.put("message", ExceptionSnapshot.clip(message, 2048));
        value.put("stack", null); value.put("trace_origin", "runtime_reported"); value.put("parsing_status", "unparsed");
        value.put("frames", new ArrayList<>()); value.put("relationships", new ArrayList<>()); return value;
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> edges(Map<String, Object> node) { return (List<Map<String, Object>>) node.get("relationships"); }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> frames(Map<String, Object> node) { return (List<Map<String, Object>>) node.get("frames"); }
}

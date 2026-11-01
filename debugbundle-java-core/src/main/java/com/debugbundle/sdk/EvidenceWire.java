package com.debugbundle.sdk;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Semantic refinements that the generated JSON Schema cannot express.
 * Call only after complete schema validation and native JSON ownership. */
final class EvidenceWire {
    static final List<String> FEATURES = List.of("exception_evidence_v1", "resource_observations_v1",
            "recovery_v1", "evidence_provenance_v1");
    private static final Pattern V4 = Pattern.compile("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-4[a-fA-F0-9]{3}-[89aAbB][a-fA-F0-9]{3}-[a-fA-F0-9]{12}");

    private EvidenceWire() { }

    static boolean validOwnedEvent(Map<String, Object> event) {
        try {
            Map<String, Object> payload = object(event.get("payload"));
            if ("diagnostic_context".equals(event.get("event_type"))) {
                if (!fresh(payload.get("failure_ref")) || !scope(payload.get("scope"))) return false;
                if ("recovery".equals(payload.get("kind"))) {
                    if (!fresh(payload.get("attempt_id")) || payload.containsKey("operation") && !text(payload.get("operation"), 64)) return false;
                } else if (!resources(payload.get("observations"))) return false;
                if ("page".equals(object(payload.get("scope")).get("kind"))) {
                    Object session = object(event.get("correlation")).get("session_id");
                    if (!(session instanceof String value) || !nonBlank(value)) return false;
                }
                return true;
            }
            Map<String, Object> additions = new LinkedHashMap<>();
            for (String field : List.of("exception_evidence", "execution", "resource_observations")) {
                if (!payload.containsKey(field)) continue;
                boolean backend = "backend_exception".equals(event.get("event_type"));
                boolean frontend = "frontend_exception".equals(event.get("event_type"));
                if (!(backend && !field.equals("resource_observations") || frontend && !field.equals("execution"))) return false;
                Object value = payload.get(field);
                if (field.equals("exception_evidence") && !graph(value)) return false;
                if (field.equals("execution") && !text(object(value).get("name"), 128)) return false;
                if (field.equals("resource_observations") && !resources(value)) return false;
                additions.put(field, value);
            }
            if (additions.isEmpty()) return true;
            Object selected = object(event.get("context")).get("evidence_reference");
            if (reference(selected)) additions.put("evidence_reference", selected);
            return CanonicalJson.bytes(additions) <= 32768;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean reference(Object value) {
        Map<String, Object> map = object(value);
        return map.size() == 3 && map.keySet().equals(Set.of("version", "failure_ref", "scope"))
                && map.get("version") instanceof Number version && version.doubleValue() == 1
                && fresh(map.get("failure_ref")) && scope(map.get("scope"));
    }

    private static boolean scope(Object value) {
        Map<String, Object> scope = object(value);
        String key = "page".equals(scope.get("kind")) ? "page_id" : "operation".equals(scope.get("kind")) ? "operation_id" : null;
        return key != null && scope.keySet().equals(Set.of("kind", key)) && fresh(scope.get(key));
    }

    private static boolean fresh(Object value) {
        return value instanceof String text && text.length() == 36 && V4.matcher(text).matches();
    }

    private static boolean text(Object value, int maximum) {
        return value == null || value instanceof String string && CanonicalJson.utf8Within(string, maximum);
    }

    private static boolean nonBlank(String value) {
        return value.codePoints().anyMatch(point -> !Character.isWhitespace(point) && !Character.isSpaceChar(point) && point != 0xFEFF);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static boolean graph(Object value) {
        Map<String, Object> graph = object(value);
        List<Map<String, Object>> nodes = items(graph.get("nodes"));
        if (nodes.isEmpty() || nodes.size() > 8) return false;
        Map<Integer, List<Integer>> adjacency = new HashMap<>();
        for (Map<String, Object> node : nodes) {
            int id = ((Number) node.get("id")).intValue();
            if (adjacency.containsKey(id) || !text(node.get("name"), 256) || !text(node.get("message"), 2048)
                    || !text(node.get("stack"), 16384)) return false;
            for (Map<String, Object> frame : items(node.get("frames")))
                if (!text(frame.get("function"), 256) || !text(frame.get("file"), 2048)) return false;
            adjacency.put(id, new java.util.ArrayList<>());
        }
        for (Map<String, Object> node : nodes) {
            List<Integer> children = adjacency.get(((Number) node.get("id")).intValue());
            Set<String> edges = new HashSet<>();
            int causal = 0;
            for (Map<String, Object> edge : items(node.get("relationships"))) {
                int target = ((Number) edge.get("target_id")).intValue();
                if (!adjacency.containsKey(target) || !edges.add(edge.get("kind") + ":" + target)) return false;
                if ("cause".equals(edge.get("kind")) || "context".equals(edge.get("kind"))) causal++;
                children.add(target);
            }
            if (causal > 1) return false;
        }
        Set<Integer> reached = new HashSet<>();
        return visit(((Number) graph.get("root_id")).intValue(), 0, adjacency, reached, new HashSet<>())
                && reached.size() == adjacency.size() && CanonicalJson.bytes(value) <= 32768;
    }

    private static boolean visit(int id, int depth, Map<Integer, List<Integer>> nodes, Set<Integer> reached, Set<Integer> active) {
        if (depth > 4 || !nodes.containsKey(id) || !active.add(id)) return false;
        reached.add(id);
        // Revisit shared children along each path: a short path must not hide
        // an over-deep path to the same descendant.
        for (int child : nodes.get(id)) if (!visit(child, depth + 1, nodes, reached, active)) return false;
        active.remove(id);
        return true;
    }

    private static boolean resources(Object value) {
        for (Map<String, Object> item : items(object(value).get("items"))) {
            // Java dollar anchors accept a terminal newline; the receiving contract does not.
            if ("csp".equals(item.get("kind")) && !(item.get("effective_directive") instanceof String directive
                    && directive.matches("[a-z-]{1,64}"))) return false;
            Object url = item.get("source_url");
            if (url != null && (!(url instanceof String source) || !url(source))) return false;
        }
        return CanonicalJson.bytes(value) <= 32768;
    }

    private static boolean url(String value) {
        if (!CanonicalJson.utf8Within(value, 2048) || value.isEmpty() || value.startsWith("//")) return false;
        for (int i = 0; i < value.length(); i++) {
            char item = value.charAt(i);
            if (item <= 0x20 || item == 0x7F || item == '\\' || item == '?' || item == '#') return false;
        }
        if (value.startsWith("/")) return true;
        int start = value.regionMatches(true, 0, "https://", 0, 8) ? 8 : value.regionMatches(true, 0, "http://", 0, 7) ? 7 : -1;
        if (start < 0) return false;
        int slash = value.indexOf('/', start);
        String authority = value.substring(start, slash < 0 ? value.length() : slash);
        try {
            // Validate authority separately; opaque/Unicode path text must not
            // acquire Java URI's stricter percent-escape interpretation.
            URI parsed = new URI(value.substring(0, start) + authority + "/").parseServerAuthority();
            String user = parsed.getRawUserInfo();
            return parsed.getHost() != null && !parsed.getHost().isEmpty() && parsed.getPort() <= 65535
                    && (user == null || user.isEmpty() || user.equals(":"));
        } catch (java.net.URISyntaxException ignored) {
            return false;
        }
    }
}

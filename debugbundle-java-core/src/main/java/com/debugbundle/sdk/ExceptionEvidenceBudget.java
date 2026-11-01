package com.debugbundle.sdk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reduce SDK-owned optional evidence while retaining the primary legacy failure. */
final class ExceptionEvidenceBudget {
    private ExceptionEvidenceBudget() { }
    @SuppressWarnings("unchecked")
    static void fit(Map<String, Object> graph, int maximum) {
        var nodes = (List<Map<String, Object>>) graph.get("nodes");
        while (CanonicalJson.bytes(graph) > maximum) {
            graph.put("truncated", true);
            if (nodes.size() > 1) {
                nodes.remove(nodes.size() - 1);
                for (var node : nodes) ((List<Map<String, Object>>) node.get("relationships"))
                        .removeIf(edge -> ((Number) edge.get("target_id")).intValue() >= nodes.size());
                continue;
            }
            var root = nodes.get(0);
            var frames = (List<?>) root.get("frames");
            if (!frames.isEmpty()) {
                frames.remove(frames.size() - 1); root.put("parsing_status", "truncated"); continue;
            }
            String message = (String) root.get("message");
            if (!message.isEmpty()) { root.put("message", message.substring(0, message.length() / 2)); continue; }
            return;
        }
    }
    @SuppressWarnings("unchecked")
    static void fitFatal(Map<String, Object> event) {
        var payload = (Map<String, Object>) event.get("payload");
        if (!(payload.get("exception_evidence") instanceof Map<?, ?> raw)) return;
        Map<String, Object> other = new LinkedHashMap<>();
        if (payload.containsKey("execution")) other.put("execution", payload.get("execution"));
        if (event.get("context") instanceof Map<?, ?> context && EvidenceWire.reference(context.get("evidence_reference")))
            other.put("evidence_reference", context.get("evidence_reference"));
        // Include the field name and commas, not just the graph's value bytes.
        fit((Map<String, Object>) raw, Math.max(512, 4096 - CanonicalJson.bytes(other) - 32));
    }
}

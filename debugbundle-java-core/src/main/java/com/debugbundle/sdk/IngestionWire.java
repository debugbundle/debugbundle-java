package com.debugbundle.sdk;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actual native serializer limits, independent of semantic metadata budgets. */
final class IngestionWire {
    static final int MAX_BODY_BYTES = 262144;
    private IngestionWire() { }

    static int bytes(Object value) { return JsonWriter.write(value).getBytes(StandardCharsets.UTF_8).length; }

    static int prefix(List<Map<String, Object>> events) {
        int size = 13, count = 0; // {"events":[]} plus its first event.
        for (Map<String, Object> event : events) {
            int next = bytes(event) + (count == 0 ? 0 : 1);
            if (next > MAX_BODY_BYTES - size) break;
            size += next; count++;
        }
        return count;
    }

    static Map<String, Object> fit(Map<String, Object> event) {
        if (event == null) return null;
        if (bytes(event) <= MAX_BODY_BYTES - 13) return event;
        Map<String, Object> marked = withoutOptional(event, true);
        if (marked != null && bytes(marked) <= MAX_BODY_BYTES - 13) return marked;
        Map<String, Object> reduced = withoutOptional(event, false);
        return reduced != null && bytes(reduced) <= MAX_BODY_BYTES - 13 ? reduced : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> withoutOptional(Map<String, Object> event, boolean mark) {
        if (!List.of("backend_exception", "frontend_exception").contains(event.get("event_type"))) return null;
        Map<String, Object> payload = new LinkedHashMap<>((Map<String, Object>) event.get("payload"));
        Map<String, Object> limits = new LinkedHashMap<>(); limits.put("version", 1);
        for (String field : List.of("exception_evidence", "resource_observations", "execution")) {
            if (payload.containsKey(field)) { payload.remove(field); limits.put(field, "budget_exceeded"); }
        }
        if (limits.size() == 1) return null;
        Map<String, Object> result = new LinkedHashMap<>(event); result.put("payload", payload);
        Map<String, Object> context = new LinkedHashMap<>();
        if (event.get("context") instanceof Map<?, ?> original) context.putAll((Map<String, Object>) original);
        if (EvidenceWire.reference(context.get("evidence_reference"))) context.remove("evidence_reference");
        if (mark) context.put("evidence_limits", limits);
        result.put("context", context);
        return result;
    }
}

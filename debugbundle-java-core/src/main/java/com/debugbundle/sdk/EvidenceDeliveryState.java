package com.debugbundle.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-client proof for one complete destination path. Discovery can revoke a pin. */
final class EvidenceDeliveryState {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private volatile Map<String, Object> proof;

    EvidenceDeliveryState(Object pin) { proof = descriptor(pin); }

    static Map<String, Object> descriptor(Object value) {
        if (value instanceof Map<?, ?> map) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> owned = OwnedJson.copyEvent((Map<String, Object>) map);
                if (owned.size() == 2 && owned.get("version") instanceof Number version && version.doubleValue() == 1
                        && owned.get("features") instanceof List<?> features && features.size() <= 4
                        && features.stream().allMatch(EvidenceWire.FEATURES::contains)
                        && features.stream().distinct().count() == features.size()) {
                    return Map.of("version", 1, "features", EvidenceWire.FEATURES.stream().filter(features::contains).toList());
                }
            } catch (RuntimeException ignored) { }
        }
        return Map.of("version", 1, "features", List.of());
    }

    static Map<String, Object> parsePin(String value) {
        try { return value == null || value.length() > 4096 ? descriptor(null) : descriptor(JSON.readValue(value, Object.class)); }
        catch (Exception ignored) { return descriptor(null); }
    }

    boolean supports(String feature) { return ((List<?>) proof.get("features")).contains(feature); }

    synchronized boolean observe(Object descriptor) {
        var next = descriptor(descriptor);
        boolean lost = ((List<?>) proof.get("features")).stream().anyMatch(feature -> !((List<?>) next.get("features")).contains(feature));
        proof = next;
        return lost;
    }

    boolean observeBody(String body, boolean required, boolean valid) {
        try {
            Map<String, Object> parsed = body == null || body.length() > 262144 ? null : JSON.readValue(body, new TypeReference<>() { });
            if (!required && (parsed == null || !parsed.containsKey("evidence_capabilities"))) return false;
            return observe(valid && parsed != null ? parsed.get("evidence_capabilities") : null);
        } catch (Exception ignored) {
            return required && observe(null);
        }
    }

    static String url(String endpoint) {
        // Query negotiation uses the existing request, leaving relay paths and fragments intact.
        int fragmentAt = endpoint.indexOf('#');
        String fragment = fragmentAt < 0 ? "" : endpoint.substring(fragmentAt);
        String base = fragmentAt < 0 ? endpoint : endpoint.substring(0, fragmentAt);
        int queryAt = base.indexOf('?');
        String path = queryAt < 0 ? base : base.substring(0, queryAt);
        List<String> query = new ArrayList<>();
        if (queryAt >= 0) for (String entry : base.substring(queryAt + 1).split("&")) {
            if (!entry.isEmpty() && !entry.split("=", 2)[0].equals("debugbundle_evidence")) query.add(entry);
        }
        query.add("debugbundle_evidence=1");
        return path + "?" + String.join("&", query) + fragment;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> filter(Map<String, Object> event, boolean captureException) {
        if (event == null) return null;
        Map<String, Object> payload = new LinkedHashMap<>((Map<String, Object>) event.get("payload"));
        if ("diagnostic_context".equals(event.get("event_type"))) {
            return supports("recovery".equals(payload.get("kind")) ? "recovery_v1" : "resource_observations_v1") ? event : null;
        }
        if (!captureException || !supports("exception_evidence_v1")) {
            payload.remove("exception_evidence"); payload.remove("execution");
        }
        if (!supports("resource_observations_v1")) payload.remove("resource_observations");
        Map<String, Object> result = new LinkedHashMap<>(event);
        result.put("payload", payload);
        if (!supports("recovery_v1") && !supports("resource_observations_v1") && event.get("context") instanceof Map<?, ?> context
                && EvidenceWire.reference(context.get("evidence_reference"))) {
            Map<String, Object> reduced = new LinkedHashMap<>((Map<String, Object>) context);
            reduced.remove("evidence_reference"); result.put("context", reduced);
        }
        return result;
    }
}

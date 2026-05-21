package com.debugbundle.sdk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SuppressionKeyBuilder {
    private SuppressionKeyBuilder() {
    }

    static String build(Map<String, Object> event) {
        Object eventType = event.get("event_type");
        if (!(eventType instanceof String type)) {
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = event.get("payload") instanceof Map<?, ?> rawPayload
                ? (Map<String, Object>) rawPayload
                : Map.of();

        if ("backend_exception".equals(type)) {
            return JsonWriter.write(suppressionKeyMap(
                    "event_type", type,
                    "name", payload.get("name"),
                    "message", payload.get("message"),
                    "stack", normalizedStackSignature(payload.get("stack")),
                    "path", nestedValue(payload, "request", "path"),
                    "status", nestedValue(payload, "response", "status_code")
            ));
        }

        if ("log_event".equals(type)) {
            return JsonWriter.write(suppressionKeyMap(
                    "event_type", type,
                    "level", payload.get("level"),
                    "message", payload.get("message"),
                    "attributes", payload.get("attributes")
            ));
        }

        if ("request_event".equals(type)) {
            return JsonWriter.write(suppressionKeyMap(
                    "event_type", type,
                    "method", payload.get("method"),
                    "path", payload.get("path"),
                    "status", payload.get("response_status"),
                    "route_template", payload.get("route_template")
            ));
        }

        return null;
    }

    private static Map<String, Object> suppressionKeyMap(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < entries.length; index += 2) {
            map.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> normalizedStackSignature(Object rawStack) {
        if (!(rawStack instanceof List<?> stackFrames)) {
            return List.of();
        }

        List<Map<String, Object>> normalizedFrames = new ArrayList<>();
        for (Object frame : stackFrames) {
            if (!(frame instanceof Map<?, ?> rawFrame)) {
                continue;
            }

            Map<String, Object> frameMap = (Map<String, Object>) rawFrame;
            normalizedFrames.add(Map.of(
                    "class", frameMap.get("class"),
                    "method", frameMap.get("method"),
                    "file", frameMap.get("file")
            ));
        }
        return normalizedFrames;
    }

    @SuppressWarnings("unchecked")
    private static Object nestedValue(Map<String, Object> source, String key, String nestedKey) {
        Object value = source.get(key);
        if (!(value instanceof Map<?, ?> mapValue)) {
            return null;
        }
        return ((Map<String, Object>) mapValue).get(nestedKey);
    }
}

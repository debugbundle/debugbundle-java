package com.debugbundle.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class BeforeSendProcessor {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> EVENT_TYPE = new TypeReference<>() {
    };
    private static final Map<String, List<String>> REQUIRED_PAYLOAD_FIELDS = Map.of(
            "backend_exception", List.of("name", "message", "stack", "handled", "request", "response", "runtime"),
            "request_event", List.of("method", "path", "query", "headers", "response_status", "duration_ms"),
            "log_event", List.of("level", "message", "attributes"),
            "frontend_breadcrumb", List.of("breadcrumb_type", "data"),
            "frontend_exception", List.of("name", "message", "stack"),
            "deploy_metadata", List.of("commit_sha", "version", "branch", "environment", "deployed_at"),
            "error_suppressed", List.of("fingerprint", "suppressed_count", "window_seconds", "first_seen", "last_seen"),
            "probe_event", List.of("label", "data", "activation_id", "probe_label_pattern")
    );
    private static final Map<String, Set<String>> ALLOWED_PAYLOAD_FIELDS = Map.of(
            "backend_exception", Set.of("name", "message", "stack", "handled", "request", "response", "runtime", "probe_data"),
            "request_event", Set.of("method", "path", "query", "headers", "body", "response_status", "duration_ms",
                    "route_template", "response_headers", "response_body", "device"),
            "log_event", Set.of("level", "message", "attributes", "device"),
            "frontend_breadcrumb", Set.of("breadcrumb_type", "route", "data", "device"),
            "frontend_exception", Set.of("name", "message", "stack", "route", "browser", "breadcrumbs", "device",
                    "browser_event", "rejection_reason", "dom_context", "probe_data"),
            "deploy_metadata", Set.of("commit_sha", "version", "branch", "environment", "deployed_at"),
            "error_suppressed", Set.of("fingerprint", "suppressed_count", "window_seconds", "first_seen", "last_seen",
                    "device"),
            "probe_event", Set.of("label", "data", "activation_id", "probe_label_pattern", "device")
    );
    private static final Set<String> REQUIRED_STRING_FIELDS =
            Set.of("schema_version", "event_id", "event_type", "occurred_at", "sdk_name", "sdk_version");
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schema_version", "event_id", "event_type", "project_token", "project_id", "sdk_name", "sdk_version",
            "service", "occurred_at", "correlation", "context", "payload");
    private static final Set<String> RUNTIME_FIELDS = Set.of(
            "version", "platform", "arch", "pid", "cwd", "uptime_sec", "hostname", "thread_id",
            "framework_version", "memory", "framework_extras");
    private static final Set<String> RUNTIME_MEMORY_FIELDS =
            Set.of("rss", "heap_total", "heap_used", "external", "peak");

    private BeforeSendProcessor() {
    }

    static Map<String, Object> apply(Map<String, Object> event, DebugBundleBeforeSend hook) {
        if (hook == null) {
            return event;
        }

        try {
            Map<String, Object> result = hook.apply(OBJECT_MAPPER.convertValue(event, EVENT_TYPE));
            if (result == null) {
                return null;
            }
            return isValid(result) ? result : event;
        } catch (Throwable ignored) {
            return event;
        }
    }

    static boolean isValid(Map<String, Object> event) {
        if (!ROOT_FIELDS.containsAll(event.keySet())) {
            return false;
        }
        if (!REQUIRED_STRING_FIELDS.stream().allMatch(field -> nonBlankString(event.get(field)))) {
            return false;
        }
        try {
            UUID.fromString((String) event.get("event_id"));
            Instant.parse((String) event.get("occurred_at"));
        } catch (RuntimeException error) {
            return false;
        }

        if (!(event.get("service") instanceof Map<?, ?> service)
                || !nonBlankString(service.get("name"))
                || !nonBlankString(service.get("environment"))
                || !(event.get("payload") instanceof Map<?, ?> payload)) {
            return false;
        }

        List<String> requiredPayloadFields = REQUIRED_PAYLOAD_FIELDS.get(event.get("event_type"));
        Set<String> allowedPayloadFields = ALLOWED_PAYLOAD_FIELDS.get(event.get("event_type"));
        return requiredPayloadFields != null
                && allowedPayloadFields != null
                && payload.keySet().stream().allMatch(allowedPayloadFields::contains)
                && requiredPayloadFields.stream().allMatch(payload::containsKey)
                && hasValidPayloadShape((String) event.get("event_type"), payload);
    }

    private static boolean hasValidPayloadShape(String eventType, Map<?, ?> payload) {
        return switch (eventType) {
            case "backend_exception" -> hasNonBlankStrings(payload, "name", "message", "stack")
                    && payload.get("handled") instanceof Boolean
                    && payload.get("request") instanceof Map<?, ?>
                    && payload.get("response") instanceof Map<?, ?>
                    && validRuntime(payload.get("runtime"))
                    && optionalMap(payload, "probe_data");
            case "request_event" -> hasNonBlankStrings(payload, "method", "path")
                    && payload.get("query") instanceof Map<?, ?>
                    && payload.get("headers") instanceof Map<?, ?>
                    && nonNegativeNumber(payload.get("response_status"))
                    && nonNegativeNumber(payload.get("duration_ms"))
                    && optionalMap(payload, "response_headers");
            case "log_event" -> hasNonBlankStrings(payload, "level", "message")
                    && payload.get("attributes") instanceof Map<?, ?>;
            case "frontend_breadcrumb" -> nonBlankString(payload.get("breadcrumb_type"))
                    && payload.get("data") instanceof Map<?, ?>;
            case "frontend_exception" -> hasNonBlankStrings(payload, "name", "message", "stack")
                    && (!payload.containsKey("breadcrumbs") || payload.get("breadcrumbs") instanceof List<?>)
                    && optionalMap(payload, "probe_data");
            case "deploy_metadata" -> hasNonBlankStrings(payload, "commit_sha", "version", "branch", "environment")
                    && timestamp(payload.get("deployed_at"));
            case "error_suppressed" -> nonBlankString(payload.get("fingerprint"))
                    && nonNegativeInteger(payload.get("suppressed_count"))
                    && positiveInteger(payload.get("window_seconds"))
                    && timestamp(payload.get("first_seen"))
                    && timestamp(payload.get("last_seen"));
            case "probe_event" -> hasNonBlankStrings(payload, "label", "probe_label_pattern")
                    && payload.get("data") instanceof Map<?, ?>
                    && nullableUuid(payload.get("activation_id"));
            default -> false;
        };
    }

    private static boolean hasNonBlankStrings(Map<?, ?> payload, String... fields) {
        return List.of(fields).stream().allMatch(field -> nonBlankString(payload.get(field)));
    }

    private static boolean optionalMap(Map<?, ?> payload, String field) {
        return !payload.containsKey(field) || payload.get(field) instanceof Map<?, ?>;
    }

    private static boolean validRuntime(Object value) {
        if (!(value instanceof Map<?, ?> runtime)
                || !RUNTIME_FIELDS.containsAll(runtime.keySet())
                || !nonBlankString(runtime.get("version"))) {
            return false;
        }
        return optionalNullableNonBlankString(runtime, "platform")
                && optionalNullableNonBlankString(runtime, "arch")
                && optionalNullableNonNegativeInteger(runtime, "pid")
                && optionalNullableNonBlankString(runtime, "cwd")
                && optionalNullableNonNegativeNumber(runtime, "uptime_sec")
                && optionalNullableNonBlankString(runtime, "hostname")
                && optionalNullableThreadId(runtime, "thread_id")
                && optionalNullableNonBlankString(runtime, "framework_version")
                && optionalRuntimeMemory(runtime)
                && optionalNullableMap(runtime, "framework_extras");
    }

    private static boolean optionalRuntimeMemory(Map<?, ?> runtime) {
        if (!runtime.containsKey("memory") || runtime.get("memory") == null) {
            return true;
        }
        if (!(runtime.get("memory") instanceof Map<?, ?> memory)
                || !RUNTIME_MEMORY_FIELDS.containsAll(memory.keySet())
                || !memory.keySet().containsAll(RUNTIME_MEMORY_FIELDS)) {
            return false;
        }
        return RUNTIME_MEMORY_FIELDS.stream().allMatch(field ->
                memory.get(field) == null || nonNegativeNumber(memory.get(field)));
    }

    private static boolean optionalNullableNonBlankString(Map<?, ?> value, String field) {
        return !value.containsKey(field) || value.get(field) == null || nonBlankString(value.get(field));
    }

    private static boolean optionalNullableNonNegativeNumber(Map<?, ?> value, String field) {
        return !value.containsKey(field) || value.get(field) == null || nonNegativeNumber(value.get(field));
    }

    private static boolean optionalNullableNonNegativeInteger(Map<?, ?> value, String field) {
        return !value.containsKey(field) || value.get(field) == null || nonNegativeInteger(value.get(field));
    }

    private static boolean optionalNullableThreadId(Map<?, ?> value, String field) {
        Object candidate = value.get(field);
        return !value.containsKey(field)
                || candidate == null
                || candidate instanceof String
                || candidate instanceof Number number && Double.isFinite(number.doubleValue());
    }

    private static boolean optionalNullableMap(Map<?, ?> value, String field) {
        return !value.containsKey(field) || value.get(field) == null || value.get(field) instanceof Map<?, ?>;
    }

    private static boolean nonNegativeNumber(Object value) {
        return value instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() >= 0;
    }

    private static boolean nonNegativeInteger(Object value) {
        return value instanceof Number number
                && Double.isFinite(number.doubleValue())
                && number.doubleValue() >= 0
                && Math.rint(number.doubleValue()) == number.doubleValue();
    }

    private static boolean positiveInteger(Object value) {
        return value instanceof Number number
                && Double.isFinite(number.doubleValue())
                && number.doubleValue() > 0
                && Math.rint(number.doubleValue()) == number.doubleValue();
    }

    private static boolean timestamp(Object value) {
        if (!(value instanceof String timestamp)) {
            return false;
        }
        try {
            Instant.parse(timestamp);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean nullableUuid(Object value) {
        if (value == null) {
            return true;
        }
        if (!(value instanceof String uuid)) {
            return false;
        }
        try {
            UUID.fromString(uuid);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean nonBlankString(Object value) {
        return value instanceof String string && !string.isBlank();
    }
}

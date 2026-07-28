package com.debugbundle.sdk;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

final class EventFactory {
    private static final String SCHEMA_VERSION = "2026-03-01";
    private static final String SDK_NAME = "@debugbundle/sdk-java";
    private static final String SDK_VERSION = "1.3.0";

    private final DebugBundleConfig config;
    private final Set<String> sensitiveFields;
    private final Map<String, List<Map<String, Object>>> probeBuffers;
    private final Supplier<Long> clockMillis;

    EventFactory(
            DebugBundleConfig config,
            Set<String> sensitiveFields,
            Map<String, List<Map<String, Object>>> probeBuffers,
            Supplier<Long> clockMillis
    ) {
        this.config = config;
        this.sensitiveFields = sensitiveFields;
        this.probeBuffers = probeBuffers;
        this.clockMillis = clockMillis;
    }

    Map<String, Object> buildExceptionEvent(Throwable error, Map<String, Object> inputContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", error.getClass().getSimpleName());
        payload.put("message", error.getMessage());
        payload.put("handled", true);
        payload.put("runtime", RuntimeFacts.capture());
        payload.put("stack", buildStackTrace(error));

        Map<String, Object> request = extractMap(inputContext, "request");
        payload.put("request", redact(requestPayload(request)));

        Map<String, Object> response = extractMap(inputContext, "response");
        payload.put("response", redact(responsePayload(response)));

        if (config.probeFlushOnError()) {
            Map<String, Object> probeData = flushProbeData();
            if (!probeData.isEmpty()) {
                payload.put("probe_data", probeData);
            }
        }

        return baseEvent("backend_exception", payload, extractCorrelation(inputContext), residualContext(inputContext));
    }

    Map<String, Object> buildLogEvent(String message, LogLevel level, Map<String, Object> inputContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("level", level.name().toLowerCase(Locale.ROOT));
        payload.put("message", message);
        payload.put("attributes", redact(residualContext(inputContext)));
        return baseEvent("log_event", payload, extractCorrelation(inputContext), new LinkedHashMap<>());
    }

    Map<String, Object> buildRequestEvent(Object request, Object response, Map<String, Object> inputContext) {
        Map<String, Object> requestMap = new LinkedHashMap<>();
        requestMap.putAll(asMap(request));

        Map<String, Object> responseMap = asMap(response);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("method", firstString(requestMap.get("method"), "UNKNOWN"));
        payload.put("path", firstString(requestMap.get("path"), "/"));
        payload.put("query", redact(asMap(requestMap.get("query"))));
        payload.put("headers", redact(asMap(requestMap.get("headers"))));
        payload.put("response_status", firstNumber(responseMap.get("status_code"), 0));
        payload.put("duration_ms", firstNumber(responseMap.get("duration_ms"), 0));
        Map<String, Object> eventContext = residualContext(inputContext);
        if (eventContext.get("route_template") instanceof String routeTemplate && !routeTemplate.isBlank()) {
            payload.put("route_template", routeTemplate);
        }
        return baseEvent("request_event", payload, extractCorrelation(inputContext), eventContext);
    }

    Map<String, Object> buildMessageEvent(String message, LogLevel level, Map<String, Object> inputContext) {
        return buildLogEvent(message, level, inputContext);
    }

    Map<String, Object> buildProbeEvent(
            String label,
            Object data,
            String activationId,
            String probeLabelPattern,
            Map<String, Object> inputContext
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("label", label);
        payload.put("data", normalizeProbeData(data));
        payload.put("activation_id", activationId);
        payload.put("probe_label_pattern", probeLabelPattern);
        return baseEvent("probe_event", payload, extractCorrelation(inputContext), residualContext(inputContext));
    }

    Map<String, Object> buildErrorSuppressedEvent(EventSuppressionTracker.SuppressionAggregate aggregate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fingerprint", aggregate.fingerprint());
        payload.put("suppressed_count", aggregate.suppressedCount());
        payload.put("window_seconds", aggregate.windowSeconds());
        payload.put("first_seen", isoTimestamp(aggregate.firstSeen()));
        payload.put("last_seen", isoTimestamp(aggregate.lastSeen()));
        return baseEvent("error_suppressed", payload, Map.of(), Map.of(), aggregate.lastSeen());
    }

    void bufferProbe(String label, Object data) {
        List<Map<String, Object>> entries = probeBuffers.computeIfAbsent(label, ignored -> new ArrayList<>());
        if (entries.isEmpty() && probeBuffers.size() > config.maxProbeLabels()) {
            probeBuffers.remove(label);
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("label", label);
        entry.put("data", normalizeProbeData(data));
        entry.put("timestamp", isoTimestamp(now()));
        entry.put("activation_id", null);
        entries.add(entry);
        while (entries.size() > config.maxProbeEntriesPerLabel()) {
            entries.remove(0);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeProbeData(Object data) {
        Object redacted = redact(data);
        if (redacted instanceof Map<?, ?>) {
            return new LinkedHashMap<>((Map<String, Object>) redacted);
        }

        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("value", redacted);
        return wrapped;
    }

    private Map<String, Object> baseEvent(
            String eventType,
            Map<String, Object> payload,
            Map<String, Object> correlation,
            Map<String, Object> eventContext
    ) {
        return baseEvent(eventType, payload, correlation, eventContext, now());
    }

    private Map<String, Object> baseEvent(
            String eventType,
            Map<String, Object> payload,
            Map<String, Object> correlation,
            Map<String, Object> eventContext,
            Instant occurredAt
    ) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schema_version", SCHEMA_VERSION);
        event.put("event_id", UUID.randomUUID().toString());
        event.put("event_type", eventType);
        event.put("sdk_name", SDK_NAME);
        event.put("sdk_version", SDK_VERSION);
        event.put("occurred_at", isoTimestamp(occurredAt));
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("name", config.service());
        service.put("runtime", "java");
        service.put("framework", null);
        service.put("environment", config.environment());
        event.put("service", service);
        if (!correlation.isEmpty()) {
            event.put("correlation", correlation);
        }
        if (!eventContext.isEmpty()) {
            event.put("context", redact(eventContext));
        }
        event.put("payload", redact(payload));
        return event;
    }

    private Map<String, Object> extractCorrelation(Map<String, Object> inputContext) {
        Object correlationValue = inputContext.get("correlation");
        Map<String, Object> correlation = asMap(correlationValue);
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("request_id", firstNonNull(correlation.get("request_id"), inputContext.get("request_id")));
        normalized.put("trace_id", firstNonNull(correlation.get("trace_id"), inputContext.get("trace_id")));
        normalized.put("session_id", firstNonNull(correlation.get("session_id"), inputContext.get("session_id")));
        normalized.put("user_id_hash", firstNonNull(correlation.get("user_id_hash"), inputContext.get("user_id_hash")));
        return normalized;
    }

    private Map<String, Object> residualContext(Map<String, Object> inputContext) {
        Map<String, Object> context = new LinkedHashMap<>(inputContext);
        context.remove("request");
        context.remove("response");
        context.remove("correlation");
        context.remove("request_id");
        context.remove("trace_id");
        context.remove("session_id");
        context.remove("user_id_hash");
        return context;
    }

    private Map<String, Object> flushProbeData() {
        if (probeBuffers.isEmpty()) {
            return Map.of();
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (List<Map<String, Object>> entries : probeBuffers.values()) {
            items.addAll(entries);
        }
        probeBuffers.clear();
        return Map.of(
                "version", 1,
                "items", items
        );
    }

    private String buildStackTrace(Throwable error) {
        StringBuilder builder = new StringBuilder();
        builder.append(error.getClass().getName()).append(": ");
        if (error.getMessage() != null) {
            builder.append(error.getMessage());
        }
        for (StackTraceElement element : error.getStackTrace()) {
            builder.append("\n at ")
                    .append(element.getClassName())
                    .append(".")
                    .append(element.getMethodName())
                    .append("(")
                    .append(element.getFileName())
                    .append(":")
                    .append(element.getLineNumber())
                    .append(")");
        }
        return builder.toString();
    }

    private Map<String, Object> requestPayload(Map<String, Object> request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("method", firstString(request.get("method"), "UNKNOWN"));
        payload.put("path", firstString(request.get("path"), "/"));
        payload.put("query", asMap(request.get("query")));
        payload.put("headers", asMap(request.get("headers")));
        if (request.containsKey("body")) {
            payload.put("body", request.get("body"));
        }
        return payload;
    }

    private Map<String, Object> responsePayload(Map<String, Object> response) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status_code", firstNumber(response.get("status_code"), 0));
        if (response.containsKey("headers")) {
            payload.put("headers", asMap(response.get("headers")));
        }
        if (response.containsKey("body")) {
            payload.put("body", response.get("body"));
        }
        return payload;
    }

    private String firstString(Object value, String fallback) {
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        return fallback;
    }

    private Number firstNumber(Object value, Number fallback) {
        if (value instanceof Number numberValue) {
            return numberValue;
        }
        return fallback;
    }

    private List<Map<String, Object>> buildStackFrames(Throwable error) {
        List<Map<String, Object>> frames = new ArrayList<>();
        for (StackTraceElement element : error.getStackTrace()) {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("class", element.getClassName());
            frame.put("method", element.getMethodName());
            frame.put("file", element.getFileName());
            frame.put("line", element.getLineNumber());
            frames.add(frame);
        }
        return frames;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Map<?, ?> mapValue) {
            return (Map<String, Object>) mapValue;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            return (Map<String, Object>) mapValue;
        }
        return Map.of();
    }

    private Object redact(Object value) {
        return Redaction.redact(value, sensitiveFields);
    }

    private String isoTimestamp(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    private Instant now() {
        return Instant.ofEpochMilli(clockMillis.get());
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }
}

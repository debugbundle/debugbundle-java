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
    private static final String SDK_VERSION = "1.0.0";

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
        payload.put("stack", buildStackFrames(error));

        Map<String, Object> request = extractMap(inputContext, "request");
        if (!request.isEmpty()) {
            payload.put("request", redact(request));
        }

        Map<String, Object> response = extractMap(inputContext, "response");
        if (!response.isEmpty()) {
            payload.put("response", redact(response));
        }

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
        payload.put("method", requestMap.get("method"));
        payload.put("path", requestMap.get("path"));
        payload.put("query", redact(requestMap.getOrDefault("query", Map.of())));
        payload.put("headers", redact(requestMap.getOrDefault("headers", Map.of())));
        payload.put("response_status", responseMap.get("status_code"));
        payload.put("duration_ms", responseMap.get("duration_ms"));
        payload.put("attributes", redact(residualContext(inputContext)));
        return baseEvent("request_event", payload, extractCorrelation(inputContext), new LinkedHashMap<>());
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
        payload.put("data", redact(data));
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
        entry.put("data", redact(data));
        entry.put("timestamp", isoTimestamp(now()));
        entry.put("activation_id", null);
        entries.add(entry);
        while (entries.size() > config.maxProbeEntriesPerLabel()) {
            entries.remove(0);
        }
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
        event.put("correlation", correlation);
        event.put("context", redact(eventContext));
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

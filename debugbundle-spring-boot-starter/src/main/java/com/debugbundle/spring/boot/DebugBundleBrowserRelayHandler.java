package com.debugbundle.spring.boot;

import com.debugbundle.sdk.DebugBundleFileWriter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class DebugBundleBrowserRelayHandler {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    static final int DEFAULT_MAX_BODY_BYTES = 256 * 1024;
    private static final String BROWSER_SDK_NAME = "@debugbundle/sdk-browser";
    private static final String DELIVERED_MARKER_SUFFIX = ".delivered";
    private static final Set<String> ACCEPTED_EVENT_TYPES = Set.of(
            "frontend_exception",
            "error_suppressed",
            "frontend_breadcrumb",
            "request_event",
            "probe_event"
    );
    private static final Set<String> CORRELATION_KEYS = Set.of(
            "request_id",
            "trace_id",
            "session_id",
            "user_id_hash"
    );
    private static final Set<String> PROTECTED_BROWSER_KEYS = Set.of(
            "project_token",
            "organization_id",
            "authorization",
            "cookie",
            "set_cookie",
            "proxy_authorization"
    );

    private final DebugBundleProperties properties;
    private final RelayForwarder relayForwarder;
    private final Map<String, ArrayDeque<Long>> rateLimitState = new ConcurrentHashMap<>();

    DebugBundleBrowserRelayHandler(DebugBundleProperties properties) {
        this(properties, new HttpRelayForwarder(properties));
    }

    DebugBundleBrowserRelayHandler(DebugBundleProperties properties, RelayForwarder relayForwarder) {
        this.properties = properties;
        this.relayForwarder = relayForwarder;
    }

    RelayResponse handle(HttpServletRequest request, byte[] requestBody) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return RelayResponse.empty(405);
        }

        if (!isOriginAllowed(request)) {
            return RelayResponse.empty(403);
        }

        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
            return RelayResponse.withBody(400, 0, 0, List.of("Relay requests must use Content-Type: application/json."));
        }

        byte[] body = requestBody == null ? new byte[0] : requestBody;
        if (body.length > DEFAULT_MAX_BODY_BYTES) {
            return RelayResponse.empty(413);
        }

        if (isRateLimited(clientIp(request))) {
            return RelayResponse.empty(429);
        }

        Map<String, Object> decoded;
        try {
            decoded = OBJECT_MAPPER.readValue(body, new TypeReference<>() {});
        } catch (IOException error) {
            return RelayResponse.withBody(400, 0, 0, List.of("Relay request body must be valid JSON."));
        }

        Object batchValue = decoded.get("batch");
        if (!(batchValue instanceof List<?> rawBatch)) {
            return RelayResponse.withBody(400, 0, 0, List.of("Relay request body must include a batch array."));
        }

        List<Map<String, Object>> acceptedEvents = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int index = 0; index < rawBatch.size(); index++) {
            Object candidate = rawBatch.get(index);
            if (!(candidate instanceof Map<?, ?> rawEvent)) {
                errors.add("batch[" + index + "]: Relay events must be objects.");
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> event = (Map<String, Object>) rawEvent;
            String eventType = asNonBlankString(event.get("event_type"));
            if (eventType == null || !ACCEPTED_EVENT_TYPES.contains(eventType)) {
                errors.add("batch[" + index + "]: Unsupported browser relay event type " + (eventType == null ? "unknown" : eventType) + ".");
                continue;
            }

            Map<String, Object> sanitized = sanitizeEvent(event);
            if (sanitized == null) {
                errors.add("batch[" + index + "]: Invalid browser relay event payload.");
                continue;
            }

            acceptedEvents.add(sanitized);
        }

        if (!acceptedEvents.isEmpty() && !deliverAcceptedEvents(acceptedEvents)) {
            return RelayResponse.empty(500);
        }

        if (!errors.isEmpty()) {
            return RelayResponse.withBody(400, acceptedEvents.size(), errors.size(), errors);
        }

        return RelayResponse.withBody(202, acceptedEvents.size(), 0, List.of());
    }

    private boolean deliverAcceptedEvents(List<Map<String, Object>> acceptedEvents) {
        String normalizedMode = normalize(properties.getProjectMode());
        if (normalizedMode.isEmpty()) {
            return true;
        }

        String serviceName = String.valueOf(((Map<?, ?>) acceptedEvents.get(0).get("service")).get("name"));
        if ("local-only".equals(normalizedMode)) {
            return writeEvents(Path.of(properties.getLocalEventsDir()), serviceName, acceptedEvents) != null;
        }

        if (!"connected".equals(normalizedMode)) {
            return true;
        }

        if (properties.getRelay().isDurableWrite()) {
            Path writtenFile = writeEvents(Path.of(properties.getRelay().getSpoolDir()), serviceName, acceptedEvents);
            if (writtenFile == null) {
                return false;
            }

            if (relayForwarder.forward(acceptedEvents) && writtenFile != null) {
                markDelivered(writtenFile);
            }
            return true;
        }

        return relayForwarder.forward(acceptedEvents);
    }

    private Path writeEvents(Path directory, String serviceName, List<Map<String, Object>> events) {
        try {
            return DebugBundleFileWriter.writeEventFile(
                    directory,
                    serviceName,
                    OBJECT_MAPPER.writeValueAsString(events)
            );
        } catch (IOException error) {
            return null;
        }
    }

    private void markDelivered(Path writtenFile) {
        try {
            DebugBundleFileWriter.writeMarker(Path.of(writtenFile + DELIVERED_MARKER_SUFFIX));
        } catch (IOException ignored) {
        }
    }

    private Map<String, Object> sanitizeEvent(Map<String, Object> event) {
        String schemaVersion = asNonBlankString(event.get("schema_version"));
        String eventId = asNonBlankString(event.get("event_id"));
        String eventType = asNonBlankString(event.get("event_type"));
        String occurredAt = asNonBlankString(event.get("occurred_at"));
        String sdkVersion = asNonBlankString(event.get("sdk_version"));
        if (schemaVersion == null || eventId == null || eventType == null || occurredAt == null || sdkVersion == null) {
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> service = event.get("service") instanceof Map<?, ?> rawService
                ? (Map<String, Object>) rawService
                : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = event.get("payload") instanceof Map<?, ?> rawPayload
                ? (Map<String, Object>) rawPayload
                : null;
        if (service == null || payload == null) {
            return null;
        }

        String serviceName = asNonBlankString(service.get("name"));
        String environment = asNonBlankString(service.get("environment"));
        if (serviceName == null || environment == null) {
            return null;
        }

        Map<String, Object> sanitized = new LinkedHashMap<>();
        sanitized.put("schema_version", schemaVersion);
        sanitized.put("event_id", eventId);
        sanitized.put("event_type", eventType);
        sanitized.put("sdk_name", BROWSER_SDK_NAME);
        sanitized.put("sdk_version", sdkVersion);
        sanitized.put("occurred_at", occurredAt);

        Map<String, Object> sanitizedService = new LinkedHashMap<>();
        sanitizedService.put("name", serviceName);
        sanitizedService.put("environment", environment);
        copyIfStringOrNull(sanitizedService, "runtime", service.get("runtime"));
        copyIfStringOrNull(sanitizedService, "framework", service.get("framework"));
        sanitized.put("service", sanitizedService);

        @SuppressWarnings("unchecked")
        Map<String, Object> correlation = event.get("correlation") instanceof Map<?, ?> rawCorrelation
                ? (Map<String, Object>) rawCorrelation
                : null;
        if (correlation != null) {
            Map<String, Object> sanitizedCorrelation = new LinkedHashMap<>();
            for (String key : CORRELATION_KEYS) {
                Object value = correlation.get(key);
                if (value instanceof String || value == null) {
                    sanitizedCorrelation.put(key, value);
                }
            }
            if (!sanitizedCorrelation.isEmpty()) {
                sanitized.put("correlation", sanitizedCorrelation);
            }
        }

        sanitized.put("payload", stripProtectedFields(payload, 0));
        return sanitized;
    }

    private Object stripProtectedFields(Object value, int depth) {
        if (value == null || depth > 8) {
            return value;
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (count >= 100) {
                    sanitized.put("_debugbundle_truncated", true);
                    break;
                }
                String key = String.valueOf(entry.getKey());
                if (!isProtectedBrowserKey(key)) {
                    sanitized.put(key, stripProtectedFields(entry.getValue(), depth + 1));
                }
                count++;
            }
            return sanitized;
        }
        if (value instanceof List<?> rawList) {
            List<Object> sanitized = new ArrayList<>();
            int count = 0;
            for (Object item : rawList) {
                if (count >= 100) {
                    sanitized.add("[Truncated]");
                    break;
                }
                sanitized.add(stripProtectedFields(item, depth + 1));
                count++;
            }
            return sanitized;
        }
        return value;
    }

    private boolean isProtectedBrowserKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "_");
        return PROTECTED_BROWSER_KEYS.contains(normalized);
    }

    private void copyIfStringOrNull(Map<String, Object> target, String key, Object value) {
        if (value instanceof String || value == null) {
            target.put(key, value);
        }
    }

    private boolean isOriginAllowed(HttpServletRequest request) {
        String sourceOrigin = sourceOrigin(request);
        if (sourceOrigin == null) {
            return false;
        }

        List<String> allowedOrigins = properties.getRelay().getAllowedOrigins();
        if (allowedOrigins != null && !allowedOrigins.isEmpty()) {
            String normalizedSourceOrigin = normalizeOrigin(sourceOrigin);
            for (String allowedOrigin : allowedOrigins) {
                if (normalizeOrigin(allowedOrigin).equals(normalizedSourceOrigin)) {
                    return true;
                }
            }
            return false;
        }

        String host = request.getHeader("Host");
        if (host == null || host.isBlank()) {
            return false;
        }

        try {
            return new URL(sourceOrigin).getHost().equalsIgnoreCase(host.split(":")[0]);
        } catch (Exception error) {
            return false;
        }
    }

    private String sourceOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            return origin.trim();
        }

        String referer = request.getHeader("Referer");
        if (referer == null || referer.isBlank()) {
            return null;
        }

        try {
            URL parsed = URI.create(referer.trim()).toURL();
            return parsed.getProtocol() + "://" + parsed.getAuthority();
        } catch (Exception error) {
            return null;
        }
    }

    private boolean isRateLimited(String clientIp) {
        String key = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        long nowSeconds = Instant.now().getEpochSecond();
        long cutoff = nowSeconds - 60;
        int limit = Math.max(1, properties.getRelay().getRateLimitPerMinute());

        ArrayDeque<Long> timestamps = rateLimitState.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
                timestamps.removeFirst();
            }

            if (timestamps.size() >= limit) {
                return true;
            }

            timestamps.addLast(nowSeconds);
            return false;
        }
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private String asNonBlankString(Object value) {
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        return null;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeOrigin(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("/$", "");
    }

    interface RelayForwarder {
        boolean forward(List<Map<String, Object>> acceptedEvents);
    }

    record RelayResponse(int status, Map<String, Object> body) {
        static RelayResponse empty(int status) {
            return new RelayResponse(status, null);
        }

        static RelayResponse withBody(int status, int accepted, int rejected, List<String> errors) {
            return new RelayResponse(status, Map.of(
                    "accepted", accepted,
                    "rejected", rejected,
                    "errors", errors
            ));
        }
    }
}

package com.debugbundle.sdk.web;

import com.debugbundle.sdk.DebugBundleFileWriter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class DebugBundleBrowserRelay {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final int DEFAULT_MAX_BODY_BYTES = 256 * 1024;
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

    private final Config config;
    private final RelayForwarder relayForwarder;
    private final Supplier<Long> currentEpochSecond;
    private final Map<String, ArrayDeque<Long>> rateLimitState = new ConcurrentHashMap<>();

    public DebugBundleBrowserRelay(Config config) {
        this(config, new HttpRelayForwarder(config));
    }

    public DebugBundleBrowserRelay(Config config, RelayForwarder relayForwarder) {
        this(config, relayForwarder, () -> Instant.now().getEpochSecond());
    }

    DebugBundleBrowserRelay(Config config, RelayForwarder relayForwarder, Supplier<Long> currentEpochSecond) {
        this.config = config == null ? new Config(null, null, null, null, 60, true, null, List.of()) : config;
        this.relayForwarder = relayForwarder == null ? acceptedEvents -> true : relayForwarder;
        this.currentEpochSecond = currentEpochSecond == null ? () -> Instant.now().getEpochSecond() : currentEpochSecond;
    }

    public Response handle(Request request, byte[] requestBody) {
        if (!"POST".equalsIgnoreCase(request.method())) {
            return Response.empty(405);
        }

        if (!isOriginAllowed(request)) {
            return Response.empty(403);
        }

        String contentType = request.contentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
            return Response.withBody(400, 0, 0, List.of("Relay requests must use Content-Type: application/json."));
        }

        byte[] body = requestBody == null ? new byte[0] : requestBody;
        if (body.length > DEFAULT_MAX_BODY_BYTES) {
            return Response.empty(413);
        }

        if (isRateLimited(request.remoteAddr())) {
            return Response.empty(429);
        }

        Map<String, Object> decoded;
        try {
            decoded = OBJECT_MAPPER.readValue(body, new TypeReference<>() {});
        } catch (IOException error) {
            return Response.withBody(400, 0, 0, List.of("Relay request body must be valid JSON."));
        }

        Object batchValue = decoded.get("batch");
        if (!(batchValue instanceof List<?> rawBatch)) {
            return Response.withBody(400, 0, 0, List.of("Relay request body must include a batch array."));
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
                errors.add("batch[" + index + "]: Unsupported browser relay event type "
                        + (eventType == null ? "unknown" : eventType) + ".");
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
            return Response.empty(500);
        }

        if (!errors.isEmpty()) {
            return Response.withBody(400, acceptedEvents.size(), errors.size(), errors);
        }

        return Response.withBody(202, acceptedEvents.size(), 0, List.of());
    }

    private boolean deliverAcceptedEvents(List<Map<String, Object>> acceptedEvents) {
        String normalizedMode = normalize(config.projectMode());
        if (normalizedMode.isEmpty()) {
            return true;
        }

        String serviceName = String.valueOf(((Map<?, ?>) acceptedEvents.get(0).get("service")).get("name"));
        if ("local-only".equals(normalizedMode)) {
            return writeEvents(Path.of(config.localEventsDir()), serviceName, acceptedEvents) != null;
        }

        if (!"connected".equals(normalizedMode)) {
            return true;
        }

        if (config.durableWrite()) {
            Path writtenFile = writeEvents(Path.of(config.spoolDir()), serviceName, acceptedEvents);
            if (writtenFile == null) {
                return false;
            }

            if (relayForwarder.forward(eventsWithProjectToken(acceptedEvents)) && writtenFile != null) {
                markDelivered(writtenFile);
            }
            return true;
        }

        return relayForwarder.forward(eventsWithProjectToken(acceptedEvents));
    }

    private List<Map<String, Object>> eventsWithProjectToken(List<Map<String, Object>> events) {
        if (config.projectToken() == null || config.projectToken().isBlank()) {
            return events;
        }

        List<Map<String, Object>> forwardedEvents = new ArrayList<>();
        for (Map<String, Object> event : events) {
            Map<String, Object> forwardedEvent = new LinkedHashMap<>(event);
            forwardedEvent.put("project_token", config.projectToken());
            forwardedEvents.add(forwardedEvent);
        }
        return forwardedEvents;
    }

    private Path writeEvents(Path directory, String serviceName, List<Map<String, Object>> events) {
        try {
            return DebugBundleFileWriter.writeEventFile(directory, serviceName, OBJECT_MAPPER.writeValueAsString(events));
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

        String serviceName = firstNonBlank(config.service(), asNonBlankString(service.get("name")));
        String environment = firstNonBlank(config.environment(), asNonBlankString(service.get("environment")));
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

    private boolean isOriginAllowed(Request request) {
        String sourceOrigin = sourceOrigin(request);
        if (sourceOrigin == null) {
            return false;
        }

        if (!config.allowedOrigins().isEmpty()) {
            String normalizedSourceOrigin = normalizeOrigin(sourceOrigin);
            for (String allowedOrigin : config.allowedOrigins()) {
                if (normalizeOrigin(allowedOrigin).equals(normalizedSourceOrigin)) {
                    return true;
                }
            }
            return false;
        }

        String host = request.header("Host");
        if (host == null || host.isBlank()) {
            return false;
        }

        try {
            return new URL(sourceOrigin).getHost().equalsIgnoreCase(host.split(":")[0]);
        } catch (Exception error) {
            return false;
        }
    }

    private String sourceOrigin(Request request) {
        String origin = request.header("Origin");
        if (origin != null && !origin.isBlank()) {
            return origin.trim();
        }

        String referer = request.header("Referer");
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
        long nowSeconds = currentEpochSecond.get();
        long cutoff = nowSeconds - 60;

        ArrayDeque<Long> timestamps = rateLimitState.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
                timestamps.removeFirst();
            }

            if (timestamps.size() >= config.rateLimitPerMinute()) {
                return true;
            }

            timestamps.addLast(nowSeconds);
            return false;
        }
    }

    private String asNonBlankString(Object value) {
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
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

    @FunctionalInterface
    public interface RelayForwarder {
        boolean forward(List<Map<String, Object>> acceptedEvents);
    }

    @FunctionalInterface
    public interface HeaderLookup {
        String get(String name);
    }

    public record Config(
            String projectToken,
            String endpoint,
            String projectMode,
            String localEventsDir,
            int rateLimitPerMinute,
            boolean durableWrite,
            String spoolDir,
            List<String> allowedOrigins,
            String service,
            String environment
    ) {
        public Config(
                String projectToken,
                String endpoint,
                String projectMode,
                String localEventsDir,
                int rateLimitPerMinute,
                boolean durableWrite,
                String spoolDir,
                List<String> allowedOrigins
        ) {
            this(projectToken, endpoint, projectMode, localEventsDir, rateLimitPerMinute, durableWrite, spoolDir, allowedOrigins, null, null);
        }

        public Config {
            endpoint = endpoint == null || endpoint.isBlank() ? "https://api.debugbundle.com/v1/events" : endpoint;
            projectMode = projectMode == null || projectMode.isBlank() ? "connected" : projectMode;
            localEventsDir = localEventsDir == null || localEventsDir.isBlank()
                    ? ".debugbundle/local/events"
                    : localEventsDir;
            rateLimitPerMinute = rateLimitPerMinute <= 0 ? 60 : rateLimitPerMinute;
            spoolDir = spoolDir == null || spoolDir.isBlank()
                    ? ".debugbundle/local/browser-relay-spool"
                    : spoolDir;
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
            service = service == null || service.isBlank() ? null : service;
            environment = environment == null || environment.isBlank() ? null : environment;
        }
    }

    public record Request(String method, String contentType, String remoteAddr, HeaderLookup headerLookup) {
        public Request {
            method = method == null ? "GET" : method;
            headerLookup = headerLookup == null ? ignored -> null : headerLookup;
        }

        public String header(String name) {
            return headerLookup.get(name);
        }
    }

    public record Response(int status, Map<String, Object> body) {
        public static Response empty(int status) {
            return new Response(status, null);
        }

        public static Response withBody(int status, int accepted, int rejected, List<String> errors) {
            return new Response(status, Map.of(
                    "accepted", accepted,
                    "rejected", rejected,
                    "errors", errors
            ));
        }
    }

    public static final class HttpRelayForwarder implements RelayForwarder {
        private static final ObjectMapper FORWARD_OBJECT_MAPPER = new ObjectMapper();

        private final Config config;
        private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        public HttpRelayForwarder(Config config) {
            this.config = config == null ? new Config(null, null, null, null, 60, true, null, List.of()) : config;
        }

        @Override
        public boolean forward(List<Map<String, Object>> acceptedEvents) {
            if (acceptedEvents.isEmpty()) {
                return true;
            }
            if (config.projectToken() == null || config.projectToken().isBlank()) {
                return false;
            }

            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("events", acceptedEvents);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(config.endpoint()))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + config.projectToken())
                        .POST(HttpRequest.BodyPublishers.ofString(FORWARD_OBJECT_MAPPER.writeValueAsString(payload)))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() >= 200 && response.statusCode() < 300;
            } catch (IOException | InterruptedException | IllegalArgumentException error) {
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
        }
    }
}
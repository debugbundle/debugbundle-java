package com.debugbundle.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

class DebugBundleBrowserRelayHandlerTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void localOnlyRelayAcceptsValidBrowserBatchAndWritesSanitizedEvents(@TempDir Path tempDir) throws Exception {
        DebugBundleProperties properties = properties(tempDir);
        properties.setProjectMode("local-only");
        DebugBundleBrowserRelayHandler handler = new DebugBundleBrowserRelayHandler(properties, events -> true);

        MockHttpServletRequest request = validRelayRequest("127.0.0.1");
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(Map.of("batch", List.of(validFrontendExceptionEvent())));

        DebugBundleBrowserRelayHandler.RelayResponse response = handler.handle(request, body);

        assertThat(response.status()).isEqualTo(202);
        assertThat(response.body()).containsEntry("accepted", 1);
        assertThat(response.body()).containsEntry("rejected", 0);

        try (var files = Files.list(tempDir)) {
            List<Path> writtenFiles = files.toList();
            assertThat(writtenFiles).hasSize(1);

            List<Map<String, Object>> writtenEvents = OBJECT_MAPPER.readValue(
                    Files.readString(writtenFiles.get(0)),
                    new TypeReference<>() {}
            );
            assertThat(writtenEvents).hasSize(1);
            assertThat(writtenEvents.get(0)).containsEntry("sdk_name", "@debugbundle/sdk-browser");
            assertThat(writtenEvents.get(0)).doesNotContainKeys("project_token", "organization_id");

            @SuppressWarnings("unchecked")
            Map<String, Object> correlation = (Map<String, Object>) writtenEvents.get(0).get("correlation");
            assertThat(correlation).containsEntry("trace_id", "trace-123");
            assertThat(correlation).doesNotContainKey("forged");
        }
    }

    @Test
    void relayStripsNestedCredentialSmugglingFields(@TempDir Path tempDir) throws Exception {
        DebugBundleProperties properties = properties(tempDir);
        properties.setProjectMode("local-only");
        DebugBundleBrowserRelayHandler handler = new DebugBundleBrowserRelayHandler(properties, events -> true);
        Map<String, Object> event = new LinkedHashMap<>(validFrontendExceptionEvent());
        event.put("payload", Map.of(
                "name", "Error",
                "message", "boom",
                "project_token", "nested-token",
                "organization_id", "nested-org",
                "headers", Map.of(
                        "authorization", "Bearer browser-token",
                        "cookie", "sid=browser-session",
                        "x-debugbundle-trace-id", "trace-123"
                )
        ));

        DebugBundleBrowserRelayHandler.RelayResponse response = handler.handle(
                validRelayRequest("127.0.0.9"),
                OBJECT_MAPPER.writeValueAsBytes(Map.of("batch", List.of(event)))
        );

        assertThat(response.status()).isEqualTo(202);
        try (var files = Files.list(tempDir)) {
            List<Map<String, Object>> writtenEvents = OBJECT_MAPPER.readValue(
                    Files.readString(files.toList().get(0)),
                    new TypeReference<>() {}
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) writtenEvents.get(0).get("payload");
            @SuppressWarnings("unchecked")
            Map<String, Object> headers = (Map<String, Object>) payload.get("headers");

            assertThat(payload).doesNotContainKeys("project_token", "organization_id");
            assertThat(headers).doesNotContainKeys("authorization", "cookie");
            assertThat(headers).containsEntry("x-debugbundle-trace-id", "trace-123");
        }
    }

        @Test
        void relayPreservesBrowserServiceIdentityByDefaultEvenWhenBackendServiceIsConfigured(@TempDir Path tempDir)
                        throws Exception {
                DebugBundleProperties properties = properties(tempDir);
                properties.setProjectMode("local-only");
                properties.setService("api-backend");
                properties.setEnvironment("production");
                DebugBundleBrowserRelayHandler handler = new DebugBundleBrowserRelayHandler(properties, events -> true);

                DebugBundleBrowserRelayHandler.RelayResponse response = handler.handle(
                                validRelayRequest("127.0.0.10"),
                                OBJECT_MAPPER.writeValueAsBytes(Map.of("batch", List.of(validFrontendExceptionEvent())))
                );

                assertThat(response.status()).isEqualTo(202);
                try (var files = Files.list(tempDir)) {
                        List<Map<String, Object>> writtenEvents = OBJECT_MAPPER.readValue(
                                        Files.readString(files.toList().get(0)),
                                        new TypeReference<>() {}
                        );

                        @SuppressWarnings("unchecked")
                        Map<String, Object> service = (Map<String, Object>) writtenEvents.get(0).get("service");
                        assertThat(service).containsEntry("name", "checkout-web");
                        assertThat(service).containsEntry("environment", "production");
                }
        }

        @Test
        void relayUsesExplicitRelayServiceOverrideWhenConfigured(@TempDir Path tempDir) throws Exception {
                DebugBundleProperties properties = properties(tempDir);
                properties.setProjectMode("local-only");
                properties.getRelay().setService("browser-relay");
                properties.getRelay().setEnvironment("preview");
                DebugBundleBrowserRelayHandler handler = new DebugBundleBrowserRelayHandler(properties, events -> true);

                DebugBundleBrowserRelayHandler.RelayResponse response = handler.handle(
                                validRelayRequest("127.0.0.11"),
                                OBJECT_MAPPER.writeValueAsBytes(Map.of("batch", List.of(validFrontendExceptionEvent())))
                );

                assertThat(response.status()).isEqualTo(202);
                try (var files = Files.list(tempDir)) {
                        List<Map<String, Object>> writtenEvents = OBJECT_MAPPER.readValue(
                                        Files.readString(files.toList().get(0)),
                                        new TypeReference<>() {}
                        );

                        @SuppressWarnings("unchecked")
                        Map<String, Object> service = (Map<String, Object>) writtenEvents.get(0).get("service");
                        assertThat(service).containsEntry("name", "browser-relay");
                        assertThat(service).containsEntry("environment", "preview");
                }
        }

    @Test
    void mixedBatchReturns400AndWritesOnlyValidEvents(@TempDir Path tempDir) throws Exception {
        DebugBundleProperties properties = properties(tempDir);
        properties.setProjectMode("local-only");
        DebugBundleBrowserRelayHandler handler = new DebugBundleBrowserRelayHandler(properties, events -> true);

        MockHttpServletRequest request = validRelayRequest("127.0.0.2");
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(Map.of(
                "batch", List.of(
                        validRequestEvent(),
                        Map.of("event_type", "backend_exception")
                )
        ));

        DebugBundleBrowserRelayHandler.RelayResponse response = handler.handle(request, body);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).containsEntry("accepted", 1);
        assertThat(response.body()).containsEntry("rejected", 1);
        assertThat((List<String>) response.body().get("errors"))
                .anySatisfy(value -> assertThat(value).contains("Unsupported browser relay event type"));

        try (var files = Files.list(tempDir)) {
            assertThat(files.toList()).hasSize(1);
        }
    }

    @Test
    void relayRejectsWrongOriginAndDoesNotWriteFiles(@TempDir Path tempDir) throws Exception {
        DebugBundleProperties properties = properties(tempDir);
        properties.setProjectMode("local-only");
        DebugBundleBrowserRelayHandler handler = new DebugBundleBrowserRelayHandler(properties, events -> true);

        MockHttpServletRequest request = validRelayRequest("127.0.0.3");
        request.removeHeader("Origin");
        request.addHeader("Origin", "https://evil.example.com");

        DebugBundleBrowserRelayHandler.RelayResponse response = handler.handle(
                request,
                OBJECT_MAPPER.writeValueAsBytes(Map.of("batch", List.of(validRequestEvent())))
        );

        assertThat(response.status()).isEqualTo(403);
        try (var files = Files.list(tempDir)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void connectedDurableRelayWritesSpoolAndMarksDeliveredOnForwardSuccess(@TempDir Path tempDir) throws Exception {
        DebugBundleProperties properties = properties(tempDir.resolve("local"));
        properties.setProjectMode("connected");
        properties.getRelay().setSpoolDir(tempDir.resolve("spool").toString());
        properties.getRelay().setDurableWrite(true);
        DebugBundleBrowserRelayHandler handler = new DebugBundleBrowserRelayHandler(properties, events -> true);

        DebugBundleBrowserRelayHandler.RelayResponse response = handler.handle(
                validRelayRequest("127.0.0.4"),
                OBJECT_MAPPER.writeValueAsBytes(Map.of("batch", List.of(validRequestEvent())))
        );

        assertThat(response.status()).isEqualTo(202);
        try (var files = Files.list(tempDir.resolve("spool"))) {
            List<Path> writtenFiles = files.toList();
            assertThat(writtenFiles).anyMatch(path -> path.getFileName().toString().endsWith(".events.json"));
            assertThat(writtenFiles).anyMatch(path -> path.getFileName().toString().endsWith(".delivered"));
        }
    }

    @Test
    void connectedForwardOnlyRelayDoesNotWriteSpoolWhenForwardSucceeds(@TempDir Path tempDir) throws Exception {
        DebugBundleProperties properties = properties(tempDir.resolve("local"));
        properties.setProjectMode("connected");
        properties.getRelay().setSpoolDir(tempDir.resolve("spool").toString());
        properties.getRelay().setDurableWrite(false);
        DebugBundleBrowserRelayHandler handler = new DebugBundleBrowserRelayHandler(properties, events -> true);

        DebugBundleBrowserRelayHandler.RelayResponse response = handler.handle(
                validRelayRequest("127.0.0.6"),
                OBJECT_MAPPER.writeValueAsBytes(Map.of("batch", List.of(validRequestEvent())))
        );

        assertThat(response.status()).isEqualTo(202);
        assertThat(Files.exists(tempDir.resolve("spool"))).isFalse();
    }

    @Test
    void relayRateLimitsPerIp(@TempDir Path tempDir) throws Exception {
        DebugBundleProperties properties = properties(tempDir);
        properties.setProjectMode("local-only");
        properties.getRelay().setRateLimitPerMinute(1);
        DebugBundleBrowserRelayHandler handler = new DebugBundleBrowserRelayHandler(properties, events -> true);
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(Map.of("batch", List.of(validRequestEvent())));

        DebugBundleBrowserRelayHandler.RelayResponse first = handler.handle(validRelayRequest("127.0.0.5"), body);
        DebugBundleBrowserRelayHandler.RelayResponse second = handler.handle(validRelayRequest("127.0.0.5"), body);

        assertThat(first.status()).isEqualTo(202);
        assertThat(second.status()).isEqualTo(429);
    }

    @Test
    void relayRejectsUnsupportedContentType(@TempDir Path tempDir) throws Exception {
        DebugBundleProperties properties = properties(tempDir);
        properties.setProjectMode("local-only");
        DebugBundleBrowserRelayHandler handler = new DebugBundleBrowserRelayHandler(properties, events -> true);
        MockHttpServletRequest request = validRelayRequest("127.0.0.7");
        request.setContentType("text/plain");

        DebugBundleBrowserRelayHandler.RelayResponse response = handler.handle(
                request,
                OBJECT_MAPPER.writeValueAsBytes(Map.of("batch", List.of(validRequestEvent())))
        );

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).containsEntry("accepted", 0);
        assertThat(response.body()).containsEntry("rejected", 0);
    }

    @Test
    void relayRejectsOversizedBody(@TempDir Path tempDir) {
        DebugBundleProperties properties = properties(tempDir);
        properties.setProjectMode("local-only");
        DebugBundleBrowserRelayHandler handler = new DebugBundleBrowserRelayHandler(properties, events -> true);

        DebugBundleBrowserRelayHandler.RelayResponse response = handler.handle(
                validRelayRequest("127.0.0.8"),
                new byte[(256 * 1024) + 1]
        );

        assertThat(response.status()).isEqualTo(413);
    }

    private DebugBundleProperties properties(Path localEventsDir) {
        DebugBundleProperties properties = new DebugBundleProperties();
        properties.setProjectToken("dbundle_proj_test");
        properties.setEndpoint("https://api.debugbundle.com/v1/events");
        properties.setLocalEventsDir(localEventsDir.toString());
        return properties;
    }

    private MockHttpServletRequest validRelayRequest(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/debugbundle/browser");
        request.setRemoteAddr(remoteAddr);
        request.addHeader("Host", "app.example.com");
        request.addHeader("Origin", "https://app.example.com");
        request.setContentType("application/json");
        return request;
    }

    private Map<String, Object> validRequestEvent() {
        return Map.of(
                "schema_version", "2026-03-01",
                "event_id", "22222222-2222-4222-8222-222222222222",
                "event_type", "request_event",
                "sdk_version", "1.0.0",
                "occurred_at", "2026-05-21T06:00:00Z",
                "service", Map.of(
                        "name", "checkout-web",
                        "environment", "production"
                ),
                "payload", Map.of(
                        "method", "GET",
                        "path", "/checkout",
                        "query", Map.of(),
                        "headers", Map.of(),
                        "response_status", 500,
                        "duration_ms", 120
                )
        );
    }

    private Map<String, Object> validFrontendExceptionEvent() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schema_version", "2026-03-01");
        event.put("event_id", "11111111-1111-4111-8111-111111111111");
        event.put("event_type", "frontend_exception");
        event.put("sdk_name", "evil-sdk");
        event.put("sdk_version", "1.0.0");
        event.put("occurred_at", "2026-05-21T06:00:00Z");
        event.put("project_token", "stolen-token");
        event.put("organization_id", "org_123");
        event.put(
                "service",
                Map.of(
                        "name", "checkout-web",
                        "environment", "production",
                        "runtime", "browser",
                        "framework", "react"
                )
        );
        event.put(
                "correlation",
                Map.of(
                        "trace_id", "trace-123",
                        "request_id", "req-123",
                        "session_id", "sess-123",
                        "user_id_hash", "user-123",
                        "forged", "drop-me"
                )
        );
        event.put(
                "payload",
                Map.of(
                        "name", "Error",
                        "message", "boom",
                        "stack", "Error: boom"
                )
        );
        return event;
    }
}

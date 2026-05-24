package com.debugbundle.servlet.jakarta;

import static org.assertj.core.api.Assertions.assertThat;

import com.debugbundle.sdk.web.DebugBundleBrowserRelay;
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
import org.springframework.mock.web.MockHttpServletResponse;

class DebugBundleRelayServletTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void relayServletWritesSanitizedEventsForLocalOnlyMode(@TempDir Path tempDir) throws Exception {
        DebugBundleRelayServlet servlet = new DebugBundleRelayServlet(new DebugBundleBrowserRelay(
                new DebugBundleBrowserRelay.Config(
                        "dbundle_proj_test",
                        "https://api.debugbundle.com/v1/events",
                        "local-only",
                        tempDir.toString(),
                        60,
                        true,
                        tempDir.resolve("spool").toString(),
                        List.of()
                ),
                events -> true
        ));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/debugbundle/browser");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "app.example.com");
        request.addHeader("Origin", "https://app.example.com");
        request.setContentType("application/json");
        request.setContent(OBJECT_MAPPER.writeValueAsBytes(Map.of("batch", List.of(validFrontendExceptionEvent()))));

        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.doPost(request, response);

        assertThat(response.getStatus()).isEqualTo(202);
        Map<String, Object> payload = OBJECT_MAPPER.readValue(response.getContentAsByteArray(), new TypeReference<>() {});
        assertThat(payload).containsEntry("accepted", 1).containsEntry("rejected", 0);
        try (var files = Files.list(tempDir)) {
            List<Map<String, Object>> writtenEvents = OBJECT_MAPPER.readValue(
                    Files.readString(files.toList().get(0)),
                    new TypeReference<>() {}
            );
            assertThat(writtenEvents.get(0)).doesNotContainKeys("project_token", "organization_id");
        }
    }

    @Test
    void relayServletRejectsOversizedBody(@TempDir Path tempDir) throws Exception {
        DebugBundleRelayServlet servlet = new DebugBundleRelayServlet(new DebugBundleBrowserRelay(
                new DebugBundleBrowserRelay.Config(
                        "dbundle_proj_test",
                        "https://api.debugbundle.com/v1/events",
                        "local-only",
                        tempDir.toString(),
                        60,
                        true,
                        tempDir.resolve("spool").toString(),
                        List.of()
                ),
                events -> true
        ));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/debugbundle/browser");
        request.setRemoteAddr("127.0.0.2");
        request.addHeader("Host", "app.example.com");
        request.addHeader("Origin", "https://app.example.com");
        request.setContentType("application/json");
        request.setContent(new byte[DebugBundleBrowserRelay.DEFAULT_MAX_BODY_BYTES + 1]);

        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.doPost(request, response);

        assertThat(response.getStatus()).isEqualTo(413);
    }

    private Map<String, Object> validFrontendExceptionEvent() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schema_version", "2026-03-01");
        event.put("event_id", "11111111-1111-4111-8111-111111111111");
        event.put("event_type", "frontend_exception");
        event.put("sdk_name", "evil-sdk");
        event.put("sdk_version", "0.1.0");
        event.put("occurred_at", "2026-05-21T06:00:00Z");
        event.put("project_token", "stolen-token");
        event.put("organization_id", "org_123");
        event.put("service", Map.of(
                "name", "checkout-web",
                "environment", "production",
                "runtime", "browser",
                "framework", "react"
        ));
        event.put("correlation", Map.of(
                "trace_id", "trace-123",
                "request_id", "req-123",
                "session_id", "sess-123",
                "user_id_hash", "user-123",
                "forged", "drop-me"
        ));
        event.put("payload", Map.of(
                "name", "Error",
                "message", "boom",
                "headers", Map.of("authorization", "Bearer browser-token")
        ));
        return event;
    }
}
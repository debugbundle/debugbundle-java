package com.debugbundle.sdk.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DebugBundleWebCaptureTest {
    @Test
    void beginRequestSnapshotPreservesCorrelationAndTriggerInputs() {
        Map<String, Object> headers = DebugBundleWebCapture.selectedHeaders(
                name -> switch (name) {
                    case "X-DebugBundle-Probe-Trigger" -> "probe-token";
                    case "X-DebugBundle-Trace-Id" -> "trace-123";
                    default -> null;
                },
                List.of("X-DebugBundle-Probe-Trigger", "X-DebugBundle-Trace-Id", "X-Request-Id"),
                false
        );
        Map<String, Object> query = DebugBundleWebCapture.queryParameters(
                List.of("_debug_probe", "tab"),
                name -> "_debug_probe".equals(name) ? new String[] {"query-token"} : new String[] {"summary", "details"}
        );

        Map<String, Object> snapshot = DebugBundleWebCapture.beginRequestSnapshot(
                "GET",
                "/records/123",
                "/records/{id}",
                headers,
                query
        );

        assertThat(snapshot).containsEntry("method", "GET");
        assertThat(snapshot).containsEntry("path", "/records/123");
        assertThat(snapshot).containsEntry("route_template", "/records/{id}");
        assertThat(objectMap(snapshot.get("headers")))
                .containsEntry("X-DebugBundle-Probe-Trigger", "probe-token")
                .containsEntry("X-DebugBundle-Trace-Id", "trace-123")
                .doesNotContainKey("X-Request-Id");
        assertThat(objectMap(snapshot.get("query")))
                .containsEntry("_debug_probe", "query-token")
                .containsEntry("tab", List.of("summary", "details"));
    }

    @Test
    void requestSnapshotKeepsAllowlistedHeaderKeysWhenConfigured() {
        Map<String, Object> headers = DebugBundleWebCapture.selectedHeaders(
                name -> "x-debugbundle-trace-id".equals(name) ? "trace-123" : null,
                List.of("x-request-id", "x-correlation-id", "x-debugbundle-trace-id"),
                true
        );

        Map<String, Object> snapshot = DebugBundleWebCapture.requestSnapshot(
                "POST",
                "/api/search",
                headers,
                Map.of("q", "term")
        );

        assertThat(objectMap(snapshot.get("headers")))
                .containsEntry("x-request-id", null)
                .containsEntry("x-correlation-id", null)
                .containsEntry("x-debugbundle-trace-id", "trace-123");
        assertThat(objectMap(snapshot.get("query"))).containsEntry("q", "term");
    }

    @Test
    void contextPrefersCallerResolvedCorrelationValues() {
        Map<String, Object> context = DebugBundleWebCapture.context(
                "GET",
                "/api/orders/42",
                "/api/orders/{id}",
                503,
                125L,
                "trace-123",
                DebugBundleWebCapture.firstNonBlank("", "req-123", "corr-123")
        );

        assertThat(context)
                .containsEntry("method", "GET")
                .containsEntry("path", "/api/orders/42")
                .containsEntry("route_template", "/api/orders/{id}")
                .containsEntry("response_status", 503)
                .containsEntry("duration_ms", 125L)
                .containsEntry("trace_id", "trace-123")
                .containsEntry("request_id", "req-123");
    }

    @Test
    void durationNeverReturnsNegativeValues() {
        long duration = DebugBundleWebCapture.durationMillis(
                Instant.parse("2026-05-24T10:00:01Z"),
                Instant.parse("2026-05-24T10:00:00Z")
        );

        assertThat(duration).isZero();
    }

        @SuppressWarnings("unchecked")
        private Map<String, Object> objectMap(Object value) {
                return (Map<String, Object>) value;
        }
}

package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RemoteConfigPolicyTest {
    @Test
    void initFetchAppliesCapturePolicyToLogsAndRequestEvents() {
        FakeRemoteConfigFetcher fetcher = new FakeRemoteConfigFetcher(new RemoteConfigResponse(
                200,
                """
                {
                  "probes_enabled": true,
                  "remote_probes_enabled": true,
                  "active_probes": [],
                  "poll_interval_ms": 60000,
                  "capture_policy": {
                    "preset": "balanced",
                    "capture_logs": "off",
                    "capture_request_events": "all",
                    "capture_breadcrumbs": "exception_only",
                    "capture_probe_events": "buffer_only",
                    "immediate_client_error_statuses": []
                  }
                }
                """,
                "\"cfg-v1\""
        ));
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .remoteConfigFetcher(fetcher)
                        .logLevel(LogLevel.INFO)
                        .build(),
                transport,
                System::currentTimeMillis
        );

        client.captureLog("suppressed warning", LogLevel.WARNING, Map.of());
        client.captureRequest(
                Map.of("method", "GET", "path", "/orders", "headers", Map.of(), "query", Map.of()),
                Map.of("status_code", 200, "duration_ms", 12),
                Map.of()
        );
        client.flush();

        assertThat(fetcher.requests()).hasSize(1);
        List<Map<String, Object>> events = transport.calls().get(0).events();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("event_type", "request_event");
    }

    @Test
    void failedInitFetchFallsBackToMinimalPolicy() {
        FakeRemoteConfigFetcher fetcher = new FakeRemoteConfigFetcher(new RemoteConfigResponse(500, null, null));
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .remoteConfigFetcher(fetcher)
                        .logLevel(LogLevel.INFO)
                        .build(),
                transport,
                System::currentTimeMillis
        );

        client.captureLog("warning suppressed by minimal fallback", LogLevel.WARNING, Map.of());
        client.captureLog("error survives minimal fallback", LogLevel.ERROR, Map.of());
        client.captureRequest(
                Map.of("method", "GET", "path", "/health", "headers", Map.of(), "query", Map.of()),
                Map.of("status_code", 200, "duration_ms", 5),
                Map.of()
        );
        client.captureRequest(
                Map.of("method", "POST", "path", "/checkout", "headers", Map.of(), "query", Map.of()),
                Map.of("status_code", 503, "duration_ms", 9),
                Map.of()
        );
        client.flush();

        List<Map<String, Object>> events = transport.calls().get(0).events();
        assertThat(events).hasSize(2);
        assertThat(events).extracting(event -> event.get("event_type"))
                .containsExactly("log_event", "request_event");

        @SuppressWarnings("unchecked")
        Map<String, Object> logPayload = (Map<String, Object>) events.get(0).get("payload");
        assertThat(logPayload).containsEntry("message", "error survives minimal fallback");
    }

    @Test
    void refreshUsesEtagAndDefaultsMissingPolicyToBalanced() {
        FakeRemoteConfigFetcher fetcher = new FakeRemoteConfigFetcher(
                new RemoteConfigResponse(
                        200,
                        """
                        {
                          "probes_enabled": true,
                          "remote_probes_enabled": true,
                          "active_probes": [],
                          "poll_interval_ms": 60000
                        }
                        """,
                        "\"cfg-v1\""
                ),
                new RemoteConfigResponse(304, null, "\"cfg-v1\"")
        );
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .remoteConfigFetcher(fetcher)
                        .logLevel(LogLevel.INFO)
                        .build(),
                transport,
                System::currentTimeMillis
        );

        client.captureLog("balanced warning log", LogLevel.WARNING, Map.of());
        client.refreshRemoteConfigNow();
        client.flush();

        assertThat(fetcher.requests()).hasSize(2);
        assertThat(fetcher.requests().get(1).ifNoneMatch()).isEqualTo("\"cfg-v1\"");
        List<Map<String, Object>> events = transport.calls().get(0).events();
        assertThat(events).hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) events.get(0).get("payload");
        assertThat(payload).containsEntry("message", "balanced warning log");
    }
}

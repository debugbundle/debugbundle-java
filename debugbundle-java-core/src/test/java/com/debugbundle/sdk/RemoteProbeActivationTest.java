package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RemoteProbeActivationTest {
    @Test
    void remoteActivationEmitsStandaloneProbeEventsAndKeepsRingBufferBehavior() {
        FakeRemoteConfigFetcher fetcher = new FakeRemoteConfigFetcher(new RemoteConfigResponse(
                200,
                """
                {
                  "probes_enabled": true,
                  "remote_probes_enabled": true,
                  "active_probes": [
                    {
                      "id": "11111111-1111-4111-8111-111111111111",
                      "label_pattern": "checkout.*",
                      "service": "checkout-api",
                      "environment": "production",
                      "expires_at": "2036-03-20T00:00:00Z"
                    }
                  ],
                  "poll_interval_ms": 60000,
                  "capture_policy": {
                    "preset": "balanced",
                    "capture_logs": "warning",
                    "capture_request_events": "failures_only",
                    "capture_breadcrumbs": "exception_only",
                    "capture_probe_events": "standalone_when_activated",
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
                        .build(),
                transport,
                System::currentTimeMillis
        );

        client.probe("checkout.tax", Map.of("secret", "top-secret", "total", 42));
        client.captureException(new RuntimeException("checkout failed"));
        client.flush();

        List<Map<String, Object>> events = transport.calls().get(0).events();
        assertThat(events).extracting(event -> event.get("event_type"))
                .containsExactly("probe_event", "backend_exception");

        @SuppressWarnings("unchecked")
        Map<String, Object> probePayload = (Map<String, Object>) events.get(0).get("payload");
        assertThat(probePayload).containsEntry("label", "checkout.tax");
        assertThat(probePayload).containsEntry("activation_id", "11111111-1111-4111-8111-111111111111");
        assertThat(probePayload).containsEntry("probe_label_pattern", "checkout.*");

        @SuppressWarnings("unchecked")
        Map<String, Object> probeData = (Map<String, Object>) probePayload.get("data");
        assertThat(probeData).containsEntry("secret", "[REDACTED]");

        @SuppressWarnings("unchecked")
        Map<String, Object> exceptionPayload = (Map<String, Object>) events.get(1).get("payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> flushedProbeData = (Map<String, Object>) exceptionPayload.get("probe_data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) flushedProbeData.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("activation_id", null);
    }

    @Test
    void heavyProbeStaysDormantUntilMatchingRemoteActivationExists() {
        FakeTransport dormantTransport = new FakeTransport();
        DefaultDebugBundleClient dormantClient = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .remoteConfigFetcher(new FakeRemoteConfigFetcher(new RemoteConfigResponse(
                                200,
                                """
                                {
                                  "probes_enabled": true,
                                  "remote_probes_enabled": true,
                                  "active_probes": [],
                                  "poll_interval_ms": 60000,
                                  "capture_policy": {
                                    "preset": "balanced",
                                    "capture_logs": "warning",
                                    "capture_request_events": "failures_only",
                                    "capture_breadcrumbs": "exception_only",
                                    "capture_probe_events": "standalone_when_activated",
                                    "immediate_client_error_statuses": []
                                  }
                                }
                                """,
                                "\"cfg-empty\""
                        )))
                        .build(),
                dormantTransport,
                System::currentTimeMillis
        );

        int[] dormantCalls = {0};
        dormantClient.probe("db.query-plan", () -> {
            dormantCalls[0]++;
            return Map.of("plan", "full scan");
        }, ProbeOptions.heavyOption());
        dormantClient.flush();

        assertThat(dormantCalls[0]).isZero();
        assertThat(dormantTransport.calls()).isEmpty();

        FakeRemoteConfigFetcher activeFetcher = new FakeRemoteConfigFetcher(new RemoteConfigResponse(
                200,
                """
                {
                  "probes_enabled": true,
                  "remote_probes_enabled": true,
                  "active_probes": [
                    {
                      "id": "22222222-2222-4222-8222-222222222222",
                      "label_pattern": "db.*",
                      "service": "checkout-api",
                      "environment": "production",
                      "expires_at": "2036-03-20T00:00:00Z"
                    }
                  ],
                  "poll_interval_ms": 60000,
                  "capture_policy": {
                    "preset": "balanced",
                    "capture_logs": "warning",
                    "capture_request_events": "failures_only",
                    "capture_breadcrumbs": "exception_only",
                    "capture_probe_events": "standalone_when_activated",
                    "immediate_client_error_statuses": []
                  }
                }
                """,
                "\"cfg-active\""
        ));
        FakeTransport activeTransport = new FakeTransport();
        DefaultDebugBundleClient activeClient = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .remoteConfigFetcher(activeFetcher)
                        .build(),
                activeTransport,
                System::currentTimeMillis
        );

        int[] activeCalls = {0};
        activeClient.probe("db.query-plan", () -> {
            activeCalls[0]++;
            return Map.of("plan", "full scan");
        }, ProbeOptions.heavyOption());
        activeClient.flush();

        assertThat(activeCalls[0]).isEqualTo(1);
        List<Map<String, Object>> events = activeTransport.calls().get(0).events();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("event_type", "probe_event");
    }

    @Test
    void expiredRemoteActivationStopsStandaloneProbeShippingLocally() {
        ManualClock clock = new ManualClock();
        clock.setNowMillis(1_773_446_400_000L);
        FakeRemoteConfigFetcher fetcher = new FakeRemoteConfigFetcher(new RemoteConfigResponse(
                200,
                """
                {
                  "probes_enabled": true,
                  "remote_probes_enabled": true,
                  "active_probes": [
                    {
                      "id": "33333333-3333-4333-8333-333333333333",
                      "label_pattern": "checkout.*",
                      "service": "checkout-api",
                      "environment": "production",
                      "expires_at": "2026-03-14T00:01:00Z"
                    }
                  ],
                  "poll_interval_ms": 60000,
                  "capture_policy": {
                    "preset": "balanced",
                    "capture_logs": "warning",
                    "capture_request_events": "failures_only",
                    "capture_breadcrumbs": "exception_only",
                    "capture_probe_events": "standalone_when_activated",
                    "immediate_client_error_statuses": []
                  }
                }
                """,
                "\"cfg-ttl\""
        ));
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .remoteConfigFetcher(fetcher)
                        .build(),
                transport,
                clock::nowMillis
        );

        client.probe("checkout.tax", Map.of("total", 42));
        client.flush();

        assertThat(transport.calls()).hasSize(1);
        assertThat(transport.calls().get(0).events()).hasSize(1);
        assertThat(transport.calls().get(0).events().get(0)).containsEntry("event_type", "probe_event");

        clock.advanceMillis(61_000L);
        client.probe("checkout.tax", Map.of("total", 43));
        client.flush();

        assertThat(transport.calls()).hasSize(1);
    }
}

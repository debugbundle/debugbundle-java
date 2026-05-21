package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class TriggerTokenRequestScopeTest {
    @Test
    void requestScopedTriggerTokenPrefersHeaderCorrelatesProbeEventsAndResetsAfterRequest() throws Exception {
        ManualClock clock = new ManualClock();
        clock.setNowMillis(1_773_446_400_000L);
        FakeTransport transport = new FakeTransport();
        String triggerTokenKey = "trigger-key-123";

        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
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
                                  "trigger_token_key": "trigger-key-123",
                                  "capture_policy": {
                                    "preset": "balanced",
                                    "capture_logs": "warning",
                                    "capture_request_events": "all",
                                    "capture_breadcrumbs": "local_only",
                                    "capture_probe_events": "standalone_when_activated",
                                    "immediate_client_error_statuses": []
                                  }
                                }
                                """,
                                "\"cfg-trigger\""
                        )))
                        .build(),
                transport,
                clock::nowMillis
        );

        String expiredQueryToken = createTriggerToken(triggerTokenKey, """
                {"activation_id":"11111111-1111-4111-8111-111111111111","label_pattern":"checkout.*","service":"checkout-api","environment":"production","trigger_expires_at":"2026-03-14T00:00:00Z"}
                """);
        String validHeaderToken = createTriggerToken(triggerTokenKey, """
                {"activation_id":"22222222-2222-4222-8222-222222222222","label_pattern":"checkout.*","service":"checkout-api","environment":"production","trigger_expires_at":"2036-03-20T00:00:00Z"}
                """);

        DebugBundleRequestScope scope = client.beginRequest(Map.of(
                "method", "GET",
                "path", "/checkout",
                "headers", Map.of(
                        "X-DebugBundle-Probe-Trigger", validHeaderToken,
                        "X-DebugBundle-Trace-Id", "trace-123",
                        "X-Request-Id", "req-123"
                ),
                "query", Map.of("_debug_probe", expiredQueryToken)
        ));

        int[] invocations = {0};
        client.probe("checkout.tax", () -> {
            invocations[0]++;
            return Map.of("total", 42);
        }, ProbeOptions.heavyOption());
        client.endRequest(scope);
        client.flush();

        assertThat(invocations[0]).isEqualTo(1);
        assertThat(transport.calls()).hasSize(1);
        assertThat(transport.calls().get(0).events()).extracting(event -> event.get("event_type"))
                .containsExactly("probe_event");

        @SuppressWarnings("unchecked")
        Map<String, Object> event = transport.calls().get(0).events().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> correlation = (Map<String, Object>) event.get("correlation");

        assertThat(payload).containsEntry("activation_id", "22222222-2222-4222-8222-222222222222");
        assertThat(correlation).containsEntry("trace_id", "trace-123");
        assertThat(correlation).containsEntry("request_id", "req-123");

        DebugBundleRequestScope secondScope = client.beginRequest(Map.of(
                "method", "GET",
                "path", "/checkout",
                "headers", Map.of(
                        "X-DebugBundle-Trace-Id", "trace-456",
                        "X-Request-Id", "req-456"
                ),
                "query", Map.of()
        ));
        client.probe("checkout.tax", () -> {
            invocations[0]++;
            return Map.of("total", 43);
        }, ProbeOptions.heavyOption());
        client.endRequest(secondScope);
        client.flush();

        assertThat(invocations[0]).isEqualTo(1);
        assertThat(transport.calls()).hasSize(1);
    }

    private String createTriggerToken(String triggerTokenKey, String payloadJson) throws Exception {
        String normalizedJson = payloadJson.replace("\n", "").replace(" ", "");
        String payloadSegment = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(normalizedJson.getBytes(StandardCharsets.UTF_8));

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(triggerTokenKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signatureSegment = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(mac.doFinal(payloadSegment.getBytes(StandardCharsets.UTF_8)));

        return "dbundle_probe_" + payloadSegment + "." + signatureSegment;
    }
}

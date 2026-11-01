package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoveryEvidenceTest {
    @Test
    @SuppressWarnings("unchecked")
    void scopesFailureAndExplicitRecoveryWithoutChangingApplicationResults() {
        FakeTransport transport = new FakeTransport();
        try (var client = client(transport)) {
            String failure = UUID.randomUUID().toString();
            String attempt = UUID.randomUUID().toString();
            String result = client.runWithOperationContext(Map.of("kind", "job", "name", "queue.process", "limitMs", 1000), () -> {
                client.captureException(new RuntimeException("fictional failure"), Map.of("failureRef", failure));
                client.captureRecovery(Map.of("failureRef", failure, "attemptId", attempt, "state", "attempt_completed", "statusCode", 200));
                client.captureRecovery(Map.of("failureRef", failure, "attemptId", attempt, "state", "recovery_observed"));
                return "unchanged result";
            });
            client.flush().join();
            assertThat(result).isEqualTo("unchanged result");
            List<Map<String, Object>> events = transport.calls().stream().flatMap(call -> call.events().stream()).toList();
            assertThat(events).hasSize(3);
            events.forEach(event -> assertThat(BeforeSendProcessor.isValid(event)).isTrue());
            var failureEvent = events.stream().filter(event -> event.get("event_type").equals("backend_exception")).findFirst().orElseThrow();
            var reference = (Map<String, Object>) ((Map<String, Object>) failureEvent.get("context")).get("evidence_reference");
            assertThat(((Map<String, Object>) failureEvent.get("payload")).get("execution").toString()).contains("queue.process");
            for (var event : events) if (event.get("event_type").equals("diagnostic_context")) {
                assertThat(((Map<String, Object>) event.get("payload")).get("scope")).isEqualTo(reference.get("scope"));
            }
        }
    }

    @Test
    void completedRequestCannotBeRevivedByADecoratedTask() {
        FakeTransport transport = new FakeTransport();
        try (var client = client(transport)) {
            var request = client.beginRequest(Map.of("path", "/fixture"));
            Runnable captured = client.decorate(() -> client.captureException(new RuntimeException("late fixture"),
                    Map.of("failureRef", UUID.randomUUID().toString())));
            client.endRequest(request);
            captured.run();
            client.flush().join();
            String sent = transport.calls().toString();
            assertThat(sent).doesNotContain("execution=", "evidence_reference=");
        }
    }

    @Test
    void observationBudgetCannotRenewWhileAnOfflineBatchIsPending() {
        FakeTransport transport = new FakeTransport(List.of(new TransportResponse(503, 300000L)));
        try (var client = client(transport)) {
            client.runWithOperationContext(Map.of("kind", "command"), () -> {
                String failure = UUID.randomUUID().toString();
                String attempt = UUID.randomUUID().toString();
                for (int i = 0; i < 100; i++) client.captureRecovery(Map.of("failureRef", failure, "attemptId", attempt, "state", "attempt_started"));
                return null;
            });
            client.flush().join();
            assertThat(transport.calls().get(0).events()).hasSize(20);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void minimalPolicyStillWithholdsPassiveMetadataFromAValidHookReplacement() throws Exception {
        var replacement = EvidenceProtocolTest.fixture("resource_unknown");
        var body = JsonWriter.write(Map.of("capture_policy", Map.of("preset", "minimal", "capture_logs", "error",
                "capture_request_events", "failures_only", "capture_breadcrumbs", "local_only", "capture_probe_events", "buffer_only",
                "capture_diagnostic_context", true), "evidence_capabilities", Map.of("version", 1, "features", EvidenceWire.FEATURES)));
        var config = DebugBundleConfig.builder().projectToken("synthetic").environment("test")
                .remoteConfigFetcher(request -> new RemoteConfigResponse(200, body, null)).beforeSend(event -> replacement).build();
        FakeTransport transport = new FakeTransport();
        try (var client = new DefaultDebugBundleClient(config, transport, System::currentTimeMillis)) {
            client.initialConfigReady().join();
            client.captureException(new RuntimeException("original fixture")); client.flush().join();
            var payload = (Map<String, Object>) transport.calls().get(0).events().get(0).get("payload");
            assertThat(payload.get("message")).isEqualTo(((Map<?, ?>) replacement.get("payload")).get("message"));
            assertThat(payload.containsKey("resource_observations")).isFalse();
        }
    }

    private static DefaultDebugBundleClient client(FakeTransport transport) {
        var capabilities = Map.of("version", 1, "features", EvidenceWire.FEATURES);
        // Recovery tests require stable destination proof, independent of a real
        // remote-config request failing while the observation burst is captured.
        var body = JsonWriter.write(Map.of("evidence_capabilities", capabilities));
        var client = new DefaultDebugBundleClient(DebugBundleConfig.builder().projectToken("synthetic").environment("test")
                .remoteConfigFetcher(request -> new RemoteConfigResponse(200, body, null))
                .captureRecoveryObservations(true).evidenceCapabilities(Map.of("version", 1, "features", EvidenceWire.FEATURES))
                .batchSize(1000).flushInterval(Duration.ofHours(1)).build(), transport, System::currentTimeMillis);
        client.initialConfigReady().join();
        return client;
    }
}

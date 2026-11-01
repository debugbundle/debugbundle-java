package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ExceptionEvidenceTest {
    @Test
    @SuppressWarnings("unchecked")
    void capturesNativeCausesSuppressedAndFramesOnlyWithOptInAndProof() throws Exception {
        FakeTransport transport = new FakeTransport();
        var config = DebugBundleConfig.builder().projectToken("synthetic-token").environment("test")
                .captureExceptionEvidence(true)
                .evidenceCapabilities(Map.of("version", 1, "features", EvidenceWire.FEATURES))
                .flushInterval(Duration.ofHours(1)).build();
        try (var client = new DefaultDebugBundleClient(config, transport, System::currentTimeMillis)) {
            var root = new IllegalStateException("original failure", new IllegalArgumentException("cause failure"));
            root.addSuppressed(new RuntimeException("suppressed failure"));
            client.captureException(root);
            client.flush().get(3, TimeUnit.SECONDS);
            Map<String, Object> event = transport.calls().get(0).events().get(0);
            var payload = (Map<String, Object>) event.get("payload");
            var graph = (Map<String, Object>) payload.get("exception_evidence");
            assertThat(graph).isNotNull();
            assertThat(BeforeSendProcessor.isValid(event)).isTrue();
            assertThat((List<?>) graph.get("nodes")).hasSize(3);
            assertThat(graph.toString()).contains("cause", "suppressed", "original failure", "ExceptionEvidenceTest.java");
            assertThat(payload.get("stack").toString()).contains("Caused by: java.lang.IllegalArgumentException: cause failure");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void nativeFatalBypassesApplicationHooksAndUsesTheSmallerMetadataBudget() {
        FakeTransport transport = new FakeTransport();
        var hookCalls = new java.util.concurrent.atomic.AtomicInteger();
        var config = DebugBundleConfig.builder().projectToken("synthetic").environment("test")
                .evidenceCapabilities(Map.of("version", 1, "features", EvidenceWire.FEATURES))
                .beforeSend(event -> { hookCalls.incrementAndGet(); return null; }).build();
        try (var client = new DefaultDebugBundleClient(config, transport, System::currentTimeMillis)) {
            var root = new IllegalStateException("fatal fixture", new IllegalArgumentException("cause fixture", new RuntimeException("third")));
            client.captureFatalException(root, Map.of());
            client.flush().join();
            assertThat(hookCalls).hasValue(0);
            var event = transport.calls().get(0).events().get(0);
            var payload = (Map<String, Object>) event.get("payload");
            var graph = (Map<String, Object>) payload.get("exception_evidence");
            assertThat(payload.get("handled")).isEqualTo(false);
            assertThat((List<?>) graph.get("nodes")).hasSizeLessThanOrEqualTo(2);
            assertThat(CanonicalJson.bytes(Map.of("exception_evidence", graph))).isLessThanOrEqualTo(4096);
            assertThat(graph.toString()).contains("native_fatal");
            assertThat(BeforeSendProcessor.isValid(event)).isTrue();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportedStacksUseRetainedRuntimeTextWithoutCaptureFrames() {
        var graph = ReportedExceptionEvidence.capture("java.lang.IllegalStateException", "reported fixture",
                "java.lang.IllegalStateException: reported fixture\n\tat app.Main.run(Main.java:42)\n"
                + "\tSuppressed: java.io.IOException: close fixture\n\t\tat app.Resource.close(Resource.java:9)\n"
                + "Caused by: java.lang.IllegalArgumentException: cause fixture\n\tat app.Parse.read(Parse.java:12)");
        assertThat(graph.toString()).contains("runtime_reported", "suppressed", "cause", "Main.java", "42")
                .doesNotContain("ExceptionEvidenceTest.java");
        assertThat((List<?>) graph.get("nodes")).hasSize(3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void crashMetadataCountsEscapingExecutionAndReferencesTogether() {
        FakeTransport transport = new FakeTransport();
        var config = DebugBundleConfig.builder().projectToken("synthetic").environment("test")
                .evidenceCapabilities(Map.of("version", 1, "features", EvidenceWire.FEATURES)).build();
        try (var client = new DefaultDebugBundleClient(config, transport, System::currentTimeMillis)) {
            var error = new IllegalStateException("\n".repeat(2048), new RuntimeException("\t".repeat(2048)));
            StackTraceElement[] frames = new StackTraceElement[40];
            java.util.Arrays.fill(frames, new StackTraceElement("fixture.Main", "run", "f".repeat(2048), 42));
            error.setStackTrace(frames);
            client.runWithOperationContext(Map.of("kind", "command", "name", "\n".repeat(128)), () -> {
                client.captureFatalException(error, Map.of("failureRef", java.util.UUID.randomUUID().toString()));
                return null;
            });
            client.flush().join();
            var event = transport.calls().get(0).events().get(0);
            var payload = (Map<String, Object>) event.get("payload");
            Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("exception_evidence", payload.get("exception_evidence"));
            metadata.put("execution", payload.get("execution"));
            metadata.put("evidence_reference", ((Map<String, Object>) event.get("context")).get("evidence_reference"));
            assertThat(metadata.get("exception_evidence")).isNotNull();
            assertThat(CanonicalJson.bytes(metadata)).isLessThanOrEqualTo(4096);
            assertThat(BeforeSendProcessor.isValid(event)).isTrue();
            assertThat(((Map<String, Object>) metadata.get("exception_evidence")).get("truncated")).isEqualTo(true);
        }
    }

    @Test
    void rejectsMalformedProofAndRevokesPreviouslyPinnedSupport() {
        var state = new EvidenceDeliveryState(Map.of("version", 1, "features", EvidenceWire.FEATURES));
        assertThat(state.supports("exception_evidence_v1")).isTrue();
        state.observe(Map.of("version", 2, "features", EvidenceWire.FEATURES));
        assertThat(state.supports("exception_evidence_v1")).isFalse();
        state.observe(Map.of("version", 1, "features", List.of("recovery_v1", "recovery_v1")));
        assertThat(state.supports("recovery_v1")).isFalse();
    }
}

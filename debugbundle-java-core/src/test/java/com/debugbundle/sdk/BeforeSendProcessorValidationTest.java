package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BeforeSendProcessorValidationTest {
    @Test
    void acceptsEveryContractEventShape() {
        assertAccepted("backend_exception", map(
                "name", "IllegalStateException",
                "message", "failed",
                "stack", "stack",
                "handled", true,
                "request", Map.of(),
                "response", Map.of(),
                "runtime", validRuntime(),
                "probe_data", Map.of()
        ));
        assertAccepted("request_event", map(
                "method", "GET",
                "path", "/orders",
                "query", Map.of(),
                "headers", Map.of(),
                "response_status", 200,
                "duration_ms", 2.5,
                "response_headers", Map.of()
        ));
        assertAccepted("log_event", map(
                "level", "warning",
                "message", "slow",
                "attributes", Map.of()
        ));
        assertAccepted("frontend_breadcrumb", map(
                "breadcrumb_type", "navigation",
                "data", Map.of()
        ));
        assertAccepted("frontend_exception", map(
                "name", "TypeError",
                "message", "failed",
                "stack", "stack",
                "breadcrumbs", List.of(),
                "probe_data", Map.of()
        ));
        assertAccepted("deploy_metadata", map(
                "commit_sha", "abc123",
                "version", "1.3.0",
                "branch", "main",
                "environment", "production",
                "deployed_at", "2026-03-14T00:00:00Z"
        ));
        assertAccepted("error_suppressed", map(
                "fingerprint", "abc",
                "suppressed_count", 2,
                "window_seconds", 30,
                "first_seen", "2026-03-14T00:00:00Z",
                "last_seen", "2026-03-14T00:00:01Z"
        ));
        assertAccepted("probe_event", map(
                "label", "checkout.tax",
                "data", Map.of("total", 42),
                "activation_id", "22222222-2222-4222-8222-222222222222",
                "probe_label_pattern", "checkout.*"
        ));
        assertAccepted("probe_event", map(
                "label", "checkout.tax",
                "data", Map.of(),
                "activation_id", null,
                "probe_label_pattern", "checkout.*"
        ));
    }

    @Test
    void rejectsInvalidEnvelopeAndPayloadShapesWithoutDroppingOriginal() {
        assertRejected(event("unknown", Map.of()));

        Map<String, Object> unknownRoot = event("log_event", validLogPayload());
        unknownRoot.put("unexpected", true);
        assertRejected(unknownRoot);

        Map<String, Object> missingSdk = event("log_event", validLogPayload());
        missingSdk.remove("sdk_name");
        assertRejected(missingSdk);

        Map<String, Object> badId = event("log_event", validLogPayload());
        badId.put("event_id", "not-a-uuid");
        assertRejected(badId);

        Map<String, Object> badTime = event("log_event", validLogPayload());
        badTime.put("occurred_at", "tomorrow");
        assertRejected(badTime);

        Map<String, Object> missingService = event("log_event", validLogPayload());
        missingService.put("service", "invalid");
        assertRejected(missingService);

        Map<String, Object> extraPayload = event("log_event", validLogPayload());
        payload(extraPayload).put("unknown", true);
        assertRejected(extraPayload);

        Map<String, Object> missingPayloadField = event("log_event", validLogPayload());
        payload(missingPayloadField).remove("message");
        assertRejected(missingPayloadField);
    }

    @Test
    void rejectsInvalidTypeSpecificValues() {
        assertRejected(event("backend_exception", map(
                "name", "",
                "message", "failed",
                "stack", "stack",
                "handled", "yes",
                "request", List.of(),
                "response", Map.of(),
                "runtime", Map.of(),
                "probe_data", List.of()
        )));
        assertRejected(event("request_event", map(
                "method", "GET",
                "path", "/",
                "query", Map.of(),
                "headers", Map.of(),
                "response_status", -1,
                "duration_ms", Double.NaN,
                "response_headers", List.of()
        )));
        assertRejected(event("frontend_breadcrumb", map(
                "breadcrumb_type", " ",
                "data", List.of()
        )));
        assertRejected(event("frontend_exception", map(
                "name", "TypeError",
                "message", "failed",
                "stack", "stack",
                "breadcrumbs", Map.of(),
                "probe_data", List.of()
        )));
        assertRejected(event("deploy_metadata", map(
                "commit_sha", "abc",
                "version", "1",
                "branch", "main",
                "environment", "prod",
                "deployed_at", 123
        )));
        assertRejected(event("deploy_metadata", map(
                "commit_sha", "abc",
                "version", "1",
                "branch", "main",
                "environment", "prod",
                "deployed_at", "invalid"
        )));
        assertRejected(event("error_suppressed", map(
                "fingerprint", "abc",
                "suppressed_count", 1.5,
                "window_seconds", 0,
                "first_seen", "invalid",
                "last_seen", "2026-03-14T00:00:00Z"
        )));
        assertRejected(event("probe_event", map(
                "label", "probe",
                "data", List.of(),
                "activation_id", 123,
                "probe_label_pattern", "*"
        )));
        assertRejected(event("probe_event", map(
                "label", "probe",
                "data", Map.of(),
                "activation_id", "invalid",
                "probe_label_pattern", "*"
        )));
    }

    @Test
    void rejectsNonCanonicalRuntimeMemoryWithoutDroppingTheOriginalEvent() {
        Map<String, Object> legacyRuntime = map(
                "version", "17.0.15",
                "memory", map(
                        "max_bytes", 1_073_741_824L,
                        "total_bytes", 536_870_912L,
                        "free_bytes", 134_217_728L
                ),
                "framework_extras", null,
                "jvm_name", "OpenJDK 64-Bit Server VM"
        );
        assertRejected(event("backend_exception", validBackendExceptionPayload(legacyRuntime)));

        Map<String, Object> incompleteMemory = validRuntime();
        incompleteMemory.put("memory", map(
                "rss", null,
                "heap_total", 536_870_912L,
                "heap_used", 402_653_184L,
                "external", null
        ));
        assertRejected(event("backend_exception", validBackendExceptionPayload(incompleteMemory)));
    }

    @Test
    void nullHookDropAndHookFailureRemainSafe() {
        Map<String, Object> original = event("log_event", validLogPayload());

        assertThat(BeforeSendProcessor.apply(original, null)).isSameAs(original);
        assertThat(BeforeSendProcessor.apply(original, ignored -> null)).isNull();
        assertThat(BeforeSendProcessor.apply(original, ignored -> {
            throw new AssertionError("hook failed");
        })).isSameAs(original);
    }

    private static void assertAccepted(String type, Map<String, Object> payload) {
        Map<String, Object> original = event(type, payload);
        assertThat(BeforeSendProcessor.apply(original, value -> value))
                .isNotNull()
                .isNotSameAs(original);
    }

    private static void assertRejected(Map<String, Object> original) {
        assertThat(BeforeSendProcessor.apply(original, value -> value)).isSameAs(original);
    }

    private static Map<String, Object> event(String type, Map<String, Object> payload) {
        return map(
                "schema_version", "2026-03-01",
                "event_id", "11111111-1111-4111-8111-111111111111",
                "event_type", type,
                "sdk_name", "@debugbundle/sdk-java",
                "sdk_version", "1.4.0",
                "service", Map.of("name", "checkout", "environment", "test"),
                "occurred_at", "2026-03-14T00:00:00Z",
                "payload", payload
        );
    }

    private static Map<String, Object> validLogPayload() {
        return map("level", "warning", "message", "slow", "attributes", Map.of());
    }

    private static Map<String, Object> validBackendExceptionPayload(Map<String, Object> runtime) {
        return map(
                "name", "IllegalStateException",
                "message", "failed",
                "stack", "stack",
                "handled", true,
                "request", Map.of(),
                "response", Map.of(),
                "runtime", runtime
        );
    }

    private static Map<String, Object> validRuntime() {
        return map(
                "version", "17.0.15",
                "memory", map(
                        "rss", null,
                        "heap_total", 536_870_912L,
                        "heap_used", 402_653_184L,
                        "external", null,
                        "peak", null
                ),
                "framework_extras", map(
                        "jvm_name", "OpenJDK 64-Bit Server VM",
                        "jvm_max_bytes", 1_073_741_824L
                )
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Map<String, Object> event) {
        return (Map<String, Object>) event.get("payload");
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return result;
    }
}

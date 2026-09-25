package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TelemetryPrivacyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void protocolIdentitiesArePreservedOnlyIfTheirValuesAreSafe() {
        assertThat(TelemetryPrivacy.hasSafeEventIdentity(Map.of("correlation", Map.of("session_id", "trace-123")), Set.of())).isTrue();
        assertThat(TelemetryPrivacy.hasSafeEventIdentity(Map.of("correlation", Map.of("trace_id", "dbundle_proj_SYNTHETIC_SECRET")), Set.of())).isFalse();
        assertThat(TelemetryPrivacy.hasSafeEventIdentity(Map.of("sdk_name", "password=SYNTHETIC_SECRET"), Set.of())).isFalse();
    }

    @Test
    void sharedPortableConformanceCases() throws Exception {
        Map<String, Object> fixture = JSON.readValue(
                Files.readString(Path.of("..", "tests", "fixtures", "privacy-conformance.json")),
                new TypeReference<>() { }
        );
        assertThat(fixture.get("policy")).isEqualTo("telemetry-privacy-v1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) fixture.get("cases");
        for (Map<String, Object> entry : cases) {
            assertThat(TelemetryPrivacy.protect(entry.get("input"), Set.of()))
                    .as((String) entry.get("id")).isEqualTo(entry.get("expected"));
        }
    }

    @Test
    void customFieldsAddToMandatoryBaselineWithoutMutatingInput() {
        Map<String, Object> input = Map.of("password", "SYNTHETIC_SECRET", "tenant_pin_code", 123, "status", 503);
        Object safe = TelemetryPrivacy.protect(input, Set.of("tenant_pin_code"));
        assertThat(safe).isEqualTo(Map.of("password", "[REDACTED]", "tenant_pin_code", "[REDACTED]", "status", 503));
        assertThat(TelemetryPrivacy.protect(safe, Set.of("tenant_pin_code"))).isEqualTo(safe);
        assertThat(input.get("password")).isEqualTo("SYNTHETIC_SECRET");
    }

    @Test
    void redactsMultipleMandatoryAndCustomAssignmentsInOneText() {
        String input = "password=alpha token:bravo api_key=charlie tenant_pin_code='delta'";
        assertThat(TelemetryPrivacy.protect(input, Set.of("tenant_pin_code")))
                .isEqualTo("password=[REDACTED] token:[REDACTED] api_key=[REDACTED] tenant_pin_code=[REDACTED]");
    }

    @Test
    void acceptedTextScanningHasABoundedBurstCost() {
        Map<String, Object> event = Map.of("message", "chart render failed while loading ordinary telemetry context");
        for (int index = 0; index < 100; index++) TelemetryPrivacy.protect(event, Set.of());
        long started = System.nanoTime();
        for (int index = 0; index < 10_000; index++) TelemetryPrivacy.protect(event, Set.of());
        long elapsed = System.nanoTime() - started;
        assertThat(elapsed).as("10,000 accepted text scans took %d ms", TimeUnit.NANOSECONDS.toMillis(elapsed))
                .isLessThan(TimeUnit.SECONDS.toNanos(3));
    }
}

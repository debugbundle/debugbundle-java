package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    @Test
    @SuppressWarnings("unchecked")
    void sharedExactKeyCasesAndCombinedBounds() throws Exception {
        Map<String, Object> fixture = JSON.readValue(
                Files.readString(Path.of("..", "tests", "fixtures", "privacy-conformance.json")),
                new TypeReference<>() { }
        );
        List<Map<String, Object>> exactCases = (List<Map<String, Object>>) fixture.get("exact_key_cases");
        assertThat(exactCases).isNotEmpty();
        for (Map<String, Object> entry : exactCases) {
            List<String> rawAdditional = (List<String>) entry.get("additional_keys");
            List<String> rawExact = (List<String>) entry.get("exact_keys");
            Set<String> additional = rawAdditional == null ? Set.of() : Set.copyOf(rawAdditional);
            Set<String> exact = rawExact == null ? Set.of() : Set.copyOf(rawExact);
            assertThat(TelemetryPrivacy.protect(entry.get("input"), additional, exact))
                    .as((String) entry.get("id")).isEqualTo(entry.get("expected"));
        }

        // Literal regex metacharacters and ASCII-only case folding (Kelvin sign must not fold to 'k').
        Map<String, Object> literal = (Map<String, Object>) TelemetryPrivacy.protect(
                Map.of("a+b", "secret", "aaab", "keep", "k", "secret", "K", "secret", "\u212A", "keep"),
                Set.of(),
                Set.of("a+b", "k")
        );
        assertThat(literal).containsEntry("a+b", "[REDACTED]")
                .containsEntry("aaab", "keep")
                .containsEntry("k", "[REDACTED]")
                .containsEntry("K", "[REDACTED]")
                .containsEntry("\u212A", "keep");

        // Combined 128-key, blank, and >64-char bounds fail closed.
        Set<String> broad65 = java.util.stream.IntStream.range(0, 65).mapToObj(i -> "broad_" + i)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> exact64 = java.util.stream.IntStream.range(0, 64).mapToObj(i -> "exact_" + i)
                .collect(java.util.stream.Collectors.toSet());
        assertThatThrownBy(() -> TelemetryPrivacy.protect(Map.of("ok", 1), broad65, exact64))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TelemetryPrivacy.protect(Map.of("ok", 1), Set.of(), Set.of("   ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TelemetryPrivacy.protect(Map.of("ok", 1), Set.of(), Set.of("x".repeat(65))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void protocolPrivacyBoundaryPreservesValidatedStructuresUnderBroadRedactFields() {
        String failureRef = UUID.randomUUID().toString();
        String pageId = UUID.randomUUID().toString();
        Map<String, Object> frontend = new LinkedHashMap<>();
        frontend.put("schema_version", "2026-03-01");
        frontend.put("event_id", UUID.randomUUID().toString());
        frontend.put("event_type", "frontend_exception");
        frontend.put("sdk_name", "@debugbundle/sdk-browser");
        frontend.put("sdk_version", "3.0.0");
        frontend.put("occurred_at", "2026-10-26T10:00:00Z");
        frontend.put("service", Map.of("name", "web", "environment", "production"));
        frontend.put("context", Map.of(
                "evidence_reference", Map.of(
                        "version", 1,
                        "failure_ref", failureRef,
                        "scope", Map.of("kind", "page", "page_id", pageId)
                ),
                "evidence_limits", Map.of("version", 1, "execution", "unsupported"),
                "payout", Map.of("tag", "SYNTHETIC_TAG", "reference", "SYNTHETIC_REF", "reason", "SYNTHETIC_REASON", "file", "SYNTHETIC_FILE")
        ));
        frontend.put("payload", Map.of(
                "name", "Error",
                "message", "boom reason=SYNTHETIC_REASON",
                "browser", Map.of("name", "Chrome", "version", "120"),
                "browser_event", Map.of(
                        "kind", "resource_error",
                        "message", "failed reason=SYNTHETIC_REASON",
                        "file_name", "https://cdn.example.test/app.js?file=SYNTHETIC_FILE",
                        "line_number", 1,
                        "column_number", 2,
                        "target", Map.of(
                                "tag_name", "script",
                                "source_url", "https://cdn.example.test/app.js?tag=SYNTHETIC_TAG",
                                "attributes", Map.of("rel", "preload reason=SYNTHETIC_REASON", "async", true)
                        ),
                        "opaque", true
                ),
                "rejection_reason", Map.of("kind", "error", "message", "rejected reference=SYNTHETIC_REF")
        ));

        Map<String, Object> safe = EvidenceProtocol.protectRelayEvent(
                frontend,
                List.of("tag", "reference", "reason", "file"),
                List.of("payout_exact"),
                false
        );
        assertThat(safe).isNotNull();
        Map<String, Object> payload = (Map<String, Object>) safe.get("payload");
        Map<String, Object> browserEvent = (Map<String, Object>) payload.get("browser_event");
        Map<String, Object> target = (Map<String, Object>) browserEvent.get("target");
        assertThat(target.get("tag_name")).isEqualTo("script");
        Map<String, Object> context = (Map<String, Object>) safe.get("context");
        assertThat(((Map<String, Object>) context.get("evidence_reference")).get("failure_ref")).isEqualTo(failureRef);
        assertThat(((Map<String, Object>) context.get("evidence_limits")).get("execution")).isEqualTo("unsupported");
        Map<String, Object> payout = (Map<String, Object>) context.get("payout");
        assertThat(payout).containsEntry("tag", "[REDACTED]")
                .containsEntry("reference", "[REDACTED]")
                .containsEntry("reason", "[REDACTED]")
                .containsEntry("file", "[REDACTED]");

        // Non-allowlisted tag_name must not bypass broad redactFields=["tag"].
        Map<String, Object> customTag = new LinkedHashMap<>(frontend);
        Map<String, Object> customPayload = new LinkedHashMap<>((Map<String, Object>) frontend.get("payload"));
        Map<String, Object> customBrowser = new LinkedHashMap<>((Map<String, Object>) customPayload.get("browser_event"));
        customBrowser.put("target", Map.of("tag_name", "custom-secret", "source_url", "https://cdn.example.test/app.js"));
        customPayload.put("browser_event", customBrowser);
        customTag.put("payload", customPayload);
        Map<String, Object> safeCustom = EvidenceProtocol.protectRelayEvent(customTag, List.of("tag"), List.of(), false);
        assertThat(((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) safeCustom.get("payload")).get("browser_event")).get("target")).get("tag_name"))
                .isEqualTo("[REDACTED]");
    }

    @Test
    @SuppressWarnings("unchecked")
    void redactionExactFieldsAndProtocolPrivacyBlocksCoverClientAndDiagnosticPaths() {
        // Redaction exact-field matching + normalization + null/empty guards.
        Map<String, Object> redacted = (Map<String, Object>) Redaction.redact(
                Map.of("tag", "SYNTHETIC_SECRET", "TAG", "SYNTHETIC_UPPER", "tag_name", "script", "nested", List.of(Map.of("tag", "secret", "tagName", "img"))),
                Set.of("password"),
                Set.of(" tag ")
        );
        assertThat(redacted).containsEntry("tag", "[REDACTED]").containsEntry("TAG", "[REDACTED]").containsEntry("tag_name", "script");
        assertThat((List<Map<String, Object>>) redacted.get("nested")).first().isEqualTo(Map.of("tag", "[REDACTED]", "tagName", "img"));
        assertThat(Redaction.isExactSensitiveKey(null, Set.of("tag"))).isFalse();
        assertThat(Redaction.isExactSensitiveKey("tag", Set.of())).isFalse();
        assertThat(Redaction.isExactSensitiveKey("tag_name", Set.of("tag"))).isFalse();

        // ProtocolPrivacyBoundary: exception_evidence, execution, browser_event.page, resource_observations,
        // diagnostic_context (recovery + resource_observation), error_suppressed, protectContext, stack URLs.
        TelemetryPrivacy.CompiledPolicy policy = TelemetryPrivacy.compile(
                List.of("tag", "reference", "reason", "file"),
                List.of("payout_exact")
        );
        TelemetryPrivacy.CompiledPolicy diagPolicy = TelemetryPrivacy.compile(
                List.of("tag", "reference", "reason", "file", "status", "scope", "state"),
                List.of("payout_exact")
        );
        String failureRef = UUID.randomUUID().toString();
        String opId = UUID.randomUUID().toString();
        String pageId = UUID.randomUUID().toString();
        String attemptId = UUID.randomUUID().toString();
        String sourceEventId = UUID.randomUUID().toString();

        Map<String, Object> backendEvent = new LinkedHashMap<>();
        backendEvent.put("schema_version", "2026-03-01");
        backendEvent.put("event_id", UUID.randomUUID().toString());
        backendEvent.put("event_type", "backend_exception");
        backendEvent.put("sdk_name", "@debugbundle/sdk-java");
        backendEvent.put("sdk_version", "3.1.4");
        backendEvent.put("occurred_at", "2026-10-26T10:00:00Z");
        backendEvent.put("service", Map.of("name", "api", "runtime", "java", "environment", "production"));
        backendEvent.put("correlation", Map.of("trace_id", "trace-123"));
        backendEvent.put("context", Map.of(
                "evidence_reference", Map.of("version", 1, "failure_ref", failureRef, "scope", Map.of("kind", "operation", "operation_id", opId)),
                "evidence_limits", Map.of("version", 1, "resource_observations", "unsupported"),
                "payout_exact", "SECRET_EXACT"
        ));
        backendEvent.put("payload", Map.of(
                "name", "RuntimeException",
                "message", "failed reason=SECRET_REASON",
                "handled", true,
                "stack", "at run (https://user:pass@cdn.example.test/app.js?token=SECRET:12:4)",
                "runtime", Map.of("version", "21", "thread_id", 1),
                "request", Map.of("method", "GET", "path", "/", "query", Map.of(), "headers", Map.of()),
                "response", Map.of("status_code", 500),
                "execution", Map.of("version", 1, "kind", "job", "name", "sync reason=SECRET_EXEC", "elapsed_ms", 12.5, "limit_ms", 5000),
                "exception_evidence", Map.of(
                        "version", 1,
                        "runtime", "jvm",
                        "root_id", 0,
                        "truncated", false,
                        "unavailable_reason", "unsupported",
                        "nodes", List.of(
                                Map.of(
                                        "id", 0,
                                        "name", "RuntimeException",
                                        "message", "outer reason=SECRET_REASON",
                                        "stack", "at run (https://user:pass@cdn.example.test/app.js?token=SECRET:12:4)",
                                        "trace_origin", "throw",
                                        "parsing_status", "parsed",
                                        "frames", List.of(Map.of(
                                                "function", "run tag=SECRET",
                                                "file", "https://user:pass@cdn.example.test/app.js?file=SECRET",
                                                "line", 12,
                                                "column", 4,
                                                "classification", "application"
                                        )),
                                        "relationships", List.of(Map.of("kind", "cause", "target_id", 1))
                                ),
                                Map.of(
                                        "id", 1,
                                        "name", "IllegalStateException",
                                        "message", "inner",
                                        "stack", "no url here",
                                        "trace_origin", "capture",
                                        "parsing_status", "unavailable",
                                        "frames", List.of(),
                                        "relationships", List.of()
                                )
                        )
                )
        ));
        Map<String, Object> safeBackend = ProtocolPrivacyBoundary.protectClientEvent(backendEvent, policy);
        assertThat(safeBackend).isNotNull();
        Map<String, Object> safePayload = (Map<String, Object>) safeBackend.get("payload");
        assertThat(((Map<String, Object>) safePayload.get("execution")).get("name")).isEqualTo("sync reason=[REDACTED]");
        assertThat(((Map<String, Object>) safePayload.get("exception_evidence")).get("unavailable_reason")).isEqualTo("unsupported");

        // Recovery diagnostic_context
        Map<String, Object> recoveryEvent = new LinkedHashMap<>();
        recoveryEvent.put("schema_version", "2026-03-01");
        recoveryEvent.put("event_id", UUID.randomUUID().toString());
        recoveryEvent.put("event_type", "diagnostic_context");
        recoveryEvent.put("sdk_name", "@debugbundle/sdk-java");
        recoveryEvent.put("sdk_version", "3.1.4");
        recoveryEvent.put("occurred_at", "2026-10-26T10:00:00Z");
        recoveryEvent.put("service", Map.of("name", "api", "runtime", "java", "environment", "production"));
        recoveryEvent.put("payload", Map.of(
                "version", 1,
                "kind", "recovery",
                "failure_ref", failureRef,
                "scope", Map.of("kind", "operation", "operation_id", opId),
                "attempt_id", attemptId,
                "state", "recovery_observed",
                "operation", "retry reason=SECRET",
                "status_code", 200,
                "duration_ms", 15
        ));
        Map<String, Object> safeRecovery = ProtocolPrivacyBoundary.protectClientEvent(recoveryEvent, diagPolicy);
        assertThat(safeRecovery).isNotNull();
        assertThat(((Map<String, Object>) safeRecovery.get("payload")).get("operation")).isEqualTo("retry reason=[REDACTED]");

        // Resource observation diagnostic_context + browser_event.page via relay
        Map<String, Object> timingItem = new LinkedHashMap<>();
        timingItem.put("kind", "timing");
        timingItem.put("observed_at", "2026-10-26T10:00:00Z");
        timingItem.put("source_url", "https://cdn.example.test/app.js");
        timingItem.put("availability", "observed");
        timingItem.put("response_status", 502);
        timingItem.put("duration_ms", 18.5);
        timingItem.put("transfer_size_bytes", 0);

        Map<String, Object> cspItem = new LinkedHashMap<>();
        cspItem.put("kind", "csp");
        cspItem.put("observed_at", "2026-10-26T10:00:00Z");
        cspItem.put("source_url", null);
        cspItem.put("effective_directive", "script-src");
        cspItem.put("disposition", "enforce");
        cspItem.put("document_status_code", 200);

        Map<String, Object> resourceDiag = new LinkedHashMap<>();
        resourceDiag.put("schema_version", "2026-03-01");
        resourceDiag.put("event_id", UUID.randomUUID().toString());
        resourceDiag.put("event_type", "diagnostic_context");
        resourceDiag.put("sdk_name", "@debugbundle/sdk-browser");
        resourceDiag.put("sdk_version", "3.0.0");
        resourceDiag.put("occurred_at", "2026-10-26T10:00:00Z");
        resourceDiag.put("service", Map.of("name", "web", "environment", "production"));
        resourceDiag.put("correlation", Map.of("session_id", "sess-123"));
        resourceDiag.put("payload", Map.of(
                "version", 1,
                "kind", "resource_observation",
                "failure_ref", failureRef,
                "scope", Map.of("kind", "page", "page_id", pageId),
                "source_event_id", sourceEventId,
                "observations", Map.of("version", 1, "items", List.of(timingItem, cspItem))
        ));
        Map<String, Object> safeResourceDiag = ProtocolPrivacyBoundary.protectRelayEvent(resourceDiag, diagPolicy, true);
        assertThat(safeResourceDiag).isNotNull();

        // error_suppressed queue_pressure + protectContext + browser page + invalid diagnostic fail-closed
        Map<String, Object> suppressed = new LinkedHashMap<>();
        suppressed.put("schema_version", "2026-03-01");
        suppressed.put("event_id", UUID.randomUUID().toString());
        suppressed.put("event_type", "error_suppressed");
        suppressed.put("sdk_name", "@debugbundle/sdk-java");
        suppressed.put("sdk_version", "3.1.4");
        suppressed.put("occurred_at", "2026-10-26T10:00:00Z");
        suppressed.put("service", Map.of("name", "api", "runtime", "java", "environment", "production"));
        suppressed.put("payload", Map.of(
                "fingerprint", "fp", "suppressed_count", 3, "window_seconds", 60,
                "first_seen", "2026-10-26T10:00:00Z", "last_seen", "2026-10-26T10:01:00Z",
                "reason", "queue_pressure", "level", "error"
        ));
        Map<String, Object> safeSuppressed = ProtocolPrivacyBoundary.protectClientEvent(suppressed, policy);
        assertThat(safeSuppressed).isNotNull();
        assertThat(((Map<String, Object>) safeSuppressed.get("payload")).get("reason")).isEqualTo("queue_pressure");

        Map<String, Object> protectedCtx = ProtocolPrivacyBoundary.protectContext(
                Map.of(
                        "evidence_reference", Map.of("version", 1, "failure_ref", failureRef, "scope", Map.of("kind", "operation", "operation_id", opId)),
                        "evidence_limits", Map.of("version", 1, "execution", "unsupported"),
                        "payout_exact", "SECRET"
                ),
                policy
        );
        assertThat(protectedCtx).containsEntry("payout_exact", "[REDACTED]");
        assertThat(((Map<String, Object>) protectedCtx.get("evidence_reference")).get("failure_ref")).isEqualTo(failureRef);

        Map<String, Object> badDiag = new LinkedHashMap<>(recoveryEvent);
        badDiag.put("payload", Map.of("version", 1, "kind", "unknown", "failure_ref", failureRef, "scope", Map.of("kind", "operation", "operation_id", opId)));
        assertThat(ProtocolPrivacyBoundary.protectClientEvent(badDiag, policy)).isNull();
        assertThat(ProtocolPrivacyBoundary.sanitizeBrowserStackUrls("at fn (https://host:8443/path?a=1)")).isEqualTo("at fn (https://host:8443/path)");
        assertThat(ProtocolPrivacyBoundary.sanitizeBrowserStackUrls("at fn (https://[::bad/path)")).isEqualTo("at fn ([unavailable-url])");
    }
}

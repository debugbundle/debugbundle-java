package com.debugbundle.sdk;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Local permission, destination proof and diagnostic ownership meet here. */
final class EvidenceController {
    private final DebugBundleConfig config;
    private final EvidenceDeliveryState proof;
    final OperationContext operations;
    private final DiagnosticCaptureBudget budget = new DiagnosticCaptureBudget();
    private final Supplier<CapturePolicy> policy;
    private final Supplier<Long> clock;

    EvidenceController(DebugBundleConfig config, EvidenceDeliveryState proof, OperationContext operations,
            Supplier<CapturePolicy> policy, Supplier<Long> clock) {
        this.config = config; this.proof = proof; this.operations = operations; this.policy = policy; this.clock = clock;
    }
    boolean allowsRecovery() {
        return config != null && config.captureRecoveryObservations() && proof.supports("recovery_v1")
                && !"minimal".equals(policy.get().preset()) && policy.get().captureDiagnosticContext();
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> context(Map<String, Object> merged) {
        if (!proof.supports("recovery_v1") && !proof.supports("resource_observations_v1")) return merged;
        Map<String, Object> reference = reference(merged);
        if (reference == null) return merged;
        Map<String, Object> result = new LinkedHashMap<>(merged);
        result.remove("failureRef"); result.remove("evidenceScope"); result.put("evidence_reference", reference);
        return result;
    }
    private Map<String, Object> reference(Map<String, Object> input) {
        var lease = operations.current();
        Object scope = input.containsKey("evidenceScope") ? input.get("evidenceScope") : lease == null ? null : lease.scope;
        Map<String, Object> selected = new LinkedHashMap<>();
        selected.put("version", 1); selected.put("failure_ref", input.get("failureRef")); selected.put("scope", scope);
        return EvidenceWire.reference(selected) ? selected : null;
    }
    Map<String, Object> recoveryPayload(Map<String, Object> observation) {
        if (!allowsRecovery() || observation == null) return null;
        Map<String, Object> selected = reference(observation);
        if (selected == null) return null;
        Map<String, Object> payload = new LinkedHashMap<>(selected);
        payload.put("kind", "recovery"); payload.put("attempt_id", observation.get("attemptId")); payload.put("state", observation.get("state"));
        for (String[] field : new String[][]{{"operation", "operation"}, {"statusCode", "status_code"}, {"durationMs", "duration_ms"}})
            if (observation.containsKey(field[0])) payload.put(field[1], observation.get(field[0]));
        return payload;
    }
    @SuppressWarnings("unchecked")
    boolean reserve(Map<String, Object> event) {
        return allowsRecovery() && budget.reserve((String) event.get("event_id"), (Map<String, Object>) event.get("payload"), IngestionWire.bytes(event), clock.get());
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> reconcile(Map<String, Object> original, Map<String, Object> replacement) {
        String originalId = "diagnostic_context".equals(original.get("event_type")) ? (String) original.get("event_id") : null;
        Map<String, Object> output = proof.filter(replacement, config.captureExceptionEvidence());
        if (output == null) { if (originalId != null) budget.release(originalId); return null; }
        if ("diagnostic_context".equals(output.get("event_type"))) {
            var payload = (Map<String, Object>) output.get("payload");
            if (!allowsRecovery() || !"recovery".equals(payload.get("kind")) || !budget.replace(originalId,
                    (String) output.get("event_id"), payload, IngestionWire.bytes(output), clock.get())) {
                if (originalId != null) budget.release(originalId);
                return null;
            }
        } else {
            if (originalId != null) budget.release(originalId);
            if (("backend_exception".equals(output.get("event_type")) || "frontend_exception".equals(output.get("event_type")))
                    && output.get("context") instanceof Map<?, ?> context && EvidenceWire.reference(context.get("evidence_reference"))) {
                budget.register((Map<String, Object>) context.get("evidence_reference"), (String) output.get("event_id"),
                        Instant.parse((String) output.get("occurred_at")).toEpochMilli());
            }
        }
        return output;
    }
    Map<String, Object> filterPolicy(Map<String, Object> event) {
        if (event == null || !"minimal".equals(policy.get().preset())) return event;
        @SuppressWarnings("unchecked")
        var payload = new LinkedHashMap<>((Map<String, Object>) event.get("payload"));
        if (!payload.containsKey("resource_observations")) return event;
        payload.remove("resource_observations");
        var output = new LinkedHashMap<>(event); output.put("payload", payload); return output;
    }
    @SuppressWarnings("unchecked")
    boolean eligible(Map<String, Object> event) {
        return !"diagnostic_context".equals(event.get("event_type")) || allowsRecovery()
                && budget.eligible((String) event.get("event_id"), (Map<String, Object>) event.get("payload"));
    }
    void release(Map<String, Object> event) {
        if (event != null && "diagnostic_context".equals(event.get("event_type"))) budget.release((String) event.get("event_id"));
    }
    void close() { operations.close(); budget.clear(); }
}

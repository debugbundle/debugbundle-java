package com.debugbundle.sdk.web;

import com.debugbundle.sdk.BoundedHttpResponse;
import com.debugbundle.sdk.EvidenceProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native relay forwarding with complete ACKs and one budget across all chunks/retries. */
final class RelayHttpForwarder {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DebugBundleBrowserRelay.Config config;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final EvidenceProtocol.State evidence;

    RelayHttpForwarder(DebugBundleBrowserRelay.Config config) {
        this.config = config == null ? new DebugBundleBrowserRelay.Config(null, null, null, null, 60, true, null, List.of()) : config;
        evidence = new EvidenceProtocol.State(this.config.evidenceCapabilities());
    }
    DebugBundleBrowserRelay.ForwardResult forward(List<Map<String, Object>> events) {
        var pathProof = new EvidenceProtocol.State(evidence.descriptor());
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        if (config.projectToken() == null || config.projectToken().isBlank()) return failed();
        Map<String, Object> intersection = EvidenceProtocol.descriptor(Map.of("version", 1, "features", List.of(
                "exception_evidence_v1", "resource_observations_v1", "recovery_v1", "evidence_provenance_v1")));
        try {
            List<Map<String, Object>> owned = new ArrayList<>();
            for (var event : events) {
                Map<String, Object> value = new LinkedHashMap<>(event); value.put("project_token", config.projectToken());
                value = EvidenceProtocol.fit(value);
                if (value == null) return failed();
                owned.add(value);
            }
            for (var original : EvidenceProtocol.chunks(owned)) {
                List<Map<String, Object>> chunk = new ArrayList<>();
                for (var event : original) {
                    var supported = pathProof.filter(event);
                    if (supported == null) return failed();
                    chunk.add(supported);
                }
                var ack = send(chunk, deadline, pathProof);
                intersection = EvidenceProtocol.intersection(List.of(intersection, ack.capabilities()));
                pathProof.observe(intersection);
                if (!ack.complete()) {
                    if (!ack.lost()) return failed();
                    List<Map<String, Object>> retry = new ArrayList<>();
                    for (int index : ack.invalidIndices()) {
                        var source = chunk.get(index);
                        if (!"frontend_exception".equals(source.get("event_type"))) continue;
                        var reduced = pathProof.filter(source);
                        if (reduced != null && !reduced.equals(source)) retry.add(reduced);
                    }
                    if (retry.isEmpty()) return failed();
                    var retryAck = send(retry, deadline, pathProof);
                    intersection = EvidenceProtocol.intersection(List.of(intersection, retryAck.capabilities()));
                    pathProof.observe(intersection);
                    if (!retryAck.complete() || ack.accepted() + retryAck.accepted() != chunk.size()) return failed();
                }
            }
            evidence.observe(pathProof.descriptor());
            return new DebugBundleBrowserRelay.ForwardResult(true, true, pathProof.descriptor());
        } catch (Exception ignored) {
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
            return failed();
        }
    }
    private EvidenceProtocol.Ack send(List<Map<String, Object>> events, long deadline, EvidenceProtocol.State pathProof) throws Exception {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new java.io.IOException("forwarding budget exhausted");
        byte[] body = JSON.writeValueAsBytes(Map.of("events", events));
        if (body.length > 262144) throw new java.io.IOException("forwarding body exceeds limit");
        var request = HttpRequest.newBuilder(URI.create(EvidenceProtocol.url(config.endpoint())))
                .timeout(Duration.ofNanos(remaining)).header("Content-Type", "application/json")
                .header("Accept-Encoding", "identity").header("Authorization", "Bearer " + config.projectToken())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var response = BoundedHttpResponse.send(client, request);
        if (response.statusCode() < 200 || response.statusCode() >= 300 || System.nanoTime() >= deadline)
            throw new java.io.IOException("forwarding not acknowledged");
        return EvidenceProtocol.acknowledge(response.body(), events.size(), true, pathProof);
    }
    private DebugBundleBrowserRelay.ForwardResult failed() {
        evidence.observe(null);
        return new DebugBundleBrowserRelay.ForwardResult(false, false, evidence.descriptor());
    }
}

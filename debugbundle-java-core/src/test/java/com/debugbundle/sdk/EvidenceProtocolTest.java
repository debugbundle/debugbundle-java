package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> PIN = Map.of("version", 1, "features", EvidenceWire.FEATURES);

    @Test
    @SuppressWarnings("unchecked")
    void sharedAdapterPrimitivesMatchTheCanonicalCorpus() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/evidence/evidence-wire-cases.json")) {
            Map<String, Object> corpus = JSON.readValue(stream, new TypeReference<>() { });
            for (var entry : (List<Map<String, Object>>) corpus.get("events"))
                assertThat(EvidenceProtocol.valid((Map<String, Object>) entry.get("event"))).as((String) entry.get("id")).isEqualTo(entry.get("valid"));
            for (var entry : (List<Map<String, Object>>) corpus.get("capabilities")) {
                var expected = entry.get("features");
                var descriptor = EvidenceProtocol.descriptor(entry.get("input"));
                assertThat(descriptor.get("features")).as((String) entry.get("id")).isEqualTo(expected);
                assertThat(EvidenceProtocol.parsePin(JSON.writeValueAsString(entry.get("input")))).isEqualTo(descriptor);
            }
        }
        assertThat(EvidenceProtocol.parsePin("{invalid").get("features")).isEqualTo(List.of());
        assertThat(EvidenceProtocol.parsePin("x".repeat(4097)).get("features")).isEqualTo(List.of());
        assertThat(EvidenceProtocol.intersection(List.of(PIN, Map.of("version", 1, "features", List.of("recovery_v1")))))
                .containsEntry("features", List.of("recovery_v1"));
        assertThat(EvidenceProtocol.intersection(List.of()).get("features")).isEqualTo(List.of());
        assertThat(EvidenceProtocol.intersection(java.util.Collections.nCopies(9, PIN)).get("features")).isEqualTo(List.of());
    }

    @Test
    void adapterAckProofRequiresCanonicalCompleteAcceptanceAndRevokesUnknownPaths() {
        var state = new EvidenceProtocol.State(PIN);
        String accepted = "{\"accepted\":1,\"rejected\":0,\"errors\":[],\"evidence_capabilities\":" + JsonWriter.write(PIN) + "}";
        var complete = EvidenceProtocol.acknowledge(accepted, 1, true, state);
        assertThat(complete.success()).isTrue(); assertThat(complete.complete()).isTrue(); assertThat(complete.lost()).isFalse();
        assertThat(state.descriptor()).isEqualTo(PIN);
        var invalid = EvidenceProtocol.acknowledge("{\"accepted\":0,\"rejected\":1,\"errors\":[{\"index\":0,\"reason\":\"invalid_event\"}]}", 1, true, state);
        assertThat(invalid.complete()).isFalse(); assertThat(invalid.lost()).isTrue(); assertThat(invalid.invalidIndices()).containsExactly(0);
        assertThat(EvidenceProtocol.acknowledge(null, 1, true, state).success()).isFalse();
        assertThat(EvidenceProtocol.acknowledge(null, 1, false, state).success()).isTrue();
        assertThat(state.observe(PIN)).isFalse();
        assertThat(EvidenceProtocol.url("https://example.invalid/events?x=1&debugbundle_evidence=0#keep"))
                .isEqualTo("https://example.invalid/events?x=1&debugbundle_evidence=1#keep");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fittingRetainsLatestPrimaryAndDropsOnlyOptionalMetadataAndValidTypedReferences() throws Exception {
        var event = fixture("native_graph");
        var payload = (Map<String, Object>) event.get("payload");
        var graph = (Map<String, Object>) payload.get("exception_evidence");
        var root = ((List<Map<String, Object>>) graph.get("nodes")).get(0);
        root.put("stack", "s".repeat(16000)); root.put("message", "m".repeat(2000));
        List<Map<String, Object>> frames = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("function", "f".repeat(100)); frame.put("file", "p".repeat(100)); frame.put("line", 1); frame.put("column", null); frame.put("classification", "unknown"); frames.add(frame);
        }
        root.put("frames", frames);
        var context = (Map<String, Object>) event.computeIfAbsent("context", ignored -> new LinkedHashMap<>());
        for (int i = 0; i < 16; i++) context.put("detail_" + i, "\n".repeat(7500));
        var recovery = fixture("operation_recovery");
        var recoveryPayload = (Map<String, Object>) recovery.get("payload");
        context.put("evidence_reference", Map.of("version", 1, "failure_ref", recoveryPayload.get("failure_ref"), "scope", recoveryPayload.get("scope")));
        assertThat(EvidenceProtocol.valid(event)).isTrue();
        assertThat(IngestionWire.bytes(event) > 262131).isTrue();
        var fitted = EvidenceProtocol.fit(event);
        assertThat(fitted).isNotNull();
        assertThat(((Map<String, Object>) fitted.get("payload")).get("message")).isEqualTo(payload.get("message"));
        assertThat(((Map<String, Object>) fitted.get("payload")).containsKey("exception_evidence")).isFalse();
        assertThat(((Map<String, Object>) fitted.get("context")).containsKey("evidence_reference")).isFalse();
        assertThat(IngestionWire.bytes(fitted) <= 262131).isTrue();
        context.put("evidence_reference", Map.of("owner", "legacy"));
        assertThat(((Map<String, Object>) EvidenceProtocol.fit(event).get("context")).get("evidence_reference"))
                .isEqualTo(Map.of("owner", "legacy"));
        var oversized = fixture("legacy_error");
        oversized.put("context", Map.of("latest_accepted_primary", "\n".repeat(140000)));
        assertThat(EvidenceProtocol.fit(oversized) == null).isTrue();
        assertThat(EvidenceProtocol.fit(null)).isNull();
        assertThatThrownBy(() -> EvidenceProtocol.chunks(List.of(oversized))).isInstanceOf(IllegalArgumentException.class);
        assertThat(EvidenceProtocol.chunks(List.of(fitted, fitted))).hasSize(2);
        assertThat(new EvidenceProtocol.State(null).filter(event).get("event_id")).isEqualTo(event.get("event_id"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> fixture(String id) throws Exception {
        try (InputStream stream = EvidenceProtocolTest.class.getResourceAsStream("/evidence/evidence-wire-cases.json")) {
            Map<String, Object> corpus = JSON.readValue(stream, new TypeReference<>() { });
            return (Map<String, Object>) ((List<Map<String, Object>>) corpus.get("events")).stream()
                    .filter(entry -> id.equals(entry.get("id"))).findFirst().orElseThrow().get("event");
        }
    }
}

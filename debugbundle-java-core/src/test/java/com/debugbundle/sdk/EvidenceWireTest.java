package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceWireTest {
    @Test
    @SuppressWarnings("unchecked")
    void countsMetadataUsingTheAcceptingJsonNumberAndUtf8Forms() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/evidence/evidence-wire-cases.json")) {
            assertThat(stream).isNotNull();
            Map<String, Object> corpus = new ObjectMapper().readValue(stream, new TypeReference<>() { });
            for (Map<String, Object> fixture : (List<Map<String, Object>>) corpus.get("json_sizes")) {
                assertThat(CanonicalJson.bytes(fixture.get("value"))).as(String.valueOf(fixture.get("value")))
                        .isEqualTo(((Number) fixture.get("bytes")).intValue());
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void completeHookBoundaryMatchesTheAcceptingSemanticCorpus() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/evidence/evidence-wire-cases.json")) {
            assertThat(stream).isNotNull();
            Map<String, Object> corpus = new ObjectMapper().readValue(stream, new TypeReference<>() { });
            for (Map<String, Object> fixture : (List<Map<String, Object>>) corpus.get("events")) {
                Map<String, Object> event = (Map<String, Object>) fixture.get("event");
                assertThat(BeforeSendProcessor.isValid(event)).as((String) fixture.get("id"))
                        .isEqualTo(fixture.get("valid"));
            }
        }
    }
}

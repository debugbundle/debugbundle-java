package com.debugbundle.sdk.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.debugbundle.sdk.EvidenceProtocol;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserRelayEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> PIN = Map.of("version", 1, "features", List.of(
            "exception_evidence_v1", "resource_observations_v1", "recovery_v1", "evidence_provenance_v1"));

    @Test
    @SuppressWarnings("unchecked")
    void preservesClosedEvidenceAndContextOnlyOnAQualifiedPath(@TempDir Path dir) throws Exception {
        for (boolean known : List.of(false, true)) {
            Path events = Files.createDirectories(dir.resolve(known ? "known" : "unknown"));
            var relay = new DebugBundleBrowserRelay(new DebugBundleBrowserRelay.Config("server-token", null, "local-only",
                    events.toString(), 60, true, dir.resolve("spool").toString(), List.of(), "server-app", "test", known ? PIN : null));
            var event = fixture("native_graph");
            var recovery = fixture("operation_recovery");
            var response = relay.handle(request(), JSON.writeValueAsBytes(Map.of("batch", List.of(event, recovery))));
            assertThat(response.status()).isEqualTo(202);
            assertThat(response.body().get("accepted")).isEqualTo(known ? 2 : 1);
            assertThat(response.body().get("evidence_capabilities")).isEqualTo(known ? PIN : EvidenceProtocol.descriptor(null));
            try (var paths = Files.list(events)) {
                var stored = JSON.readValue(Files.readString(paths.findFirst().orElseThrow()), new TypeReference<List<Map<String, Object>>>() { });
                assertThat(((Map<String, Object>) stored.get(0).get("payload")).containsKey("exception_evidence")).isEqualTo(known);
                assertThat(((Map<String, Object>) stored.get(0).get("service")).get("name")).isEqualTo("server-app");
                if (known) assertThat(stored.get(1).get("payload")).isEqualTo(recovery.get("payload"));
            }
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> fixture(String id) throws Exception {
        Map<String, Object> corpus = JSON.readValue(Files.readString(Path.of("../debugbundle-java-core/src/test/resources/evidence/evidence-wire-cases.json")), new TypeReference<>() { });
        return (Map<String, Object>) ((List<Map<String, Object>>) corpus.get("events")).stream()
                .filter(entry -> id.equals(entry.get("id"))).findFirst().orElseThrow().get("event");
    }
    static DebugBundleBrowserRelay.Request request() {
        return new DebugBundleBrowserRelay.Request("POST", "application/json", "127.0.0.1", key -> switch (key) {
            case "Host" -> "app.example.test";
            case "Origin" -> "https://app.example.test";
            case "X-DebugBundle-Evidence" -> "1";
            default -> null;
        });
    }
}

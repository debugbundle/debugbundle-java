package com.debugbundle.sdk.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserRelayNativeEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> PIN = Map.of("version", 1, "features", List.of(
            "exception_evidence_v1", "resource_observations_v1", "recovery_v1", "evidence_provenance_v1"));

    @Test
    void nativeUnknownPathLearnsFromActualAckBeforePreservingEvidence(@TempDir Path dir) throws Exception {
        var bodies = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/events", exchange -> {
            byte[] bytes = exchange.getRequestBody().readAllBytes(); bodies.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            assertThat(exchange.getRequestURI().getQuery()).contains("debugbundle_evidence=1");
            int count = JSON.readTree(bytes).path("events").size();
            byte[] reply = JSON.writeValueAsBytes(Map.of("accepted", count, "rejected", 0, "errors", List.of(), "evidence_capabilities", PIN));
            exchange.sendResponseHeaders(202, reply.length); exchange.getResponseBody().write(reply); exchange.close();
        });
        server.start();
        try {
            var relay = new DebugBundleBrowserRelay(config(dir, server, null));
            var event = BrowserRelayEvidenceTest.fixture("native_graph");
            var first = relay.handle(BrowserRelayEvidenceTest.request(), JSON.writeValueAsBytes(Map.of("batch", List.of(event))));
            assertThat(first.body().get("evidence_capabilities")).isEqualTo(PIN);
            assertThat(bodies.get(0)).doesNotContain("exception_evidence");
            var recovery = BrowserRelayEvidenceTest.fixture("operation_recovery");
            var second = relay.handle(BrowserRelayEvidenceTest.request(), JSON.writeValueAsBytes(Map.of("batch", List.of(event, recovery))));
            assertThat(second.body().get("accepted")).isEqualTo(2);
            assertThat(bodies).hasSize(2); assertThat(bodies.get(1)).contains("exception_evidence", "diagnostic_context", "server-app");
            try (var files = Files.list(dir.resolve("spool"))) {
                assertThat(files.filter(path -> path.toString().endsWith(".delivered")).count()).isEqualTo(2);
            }
        } finally { server.stop(0); }
    }

    @Test
    @SuppressWarnings("unchecked")
    void finalServerBytesAreChunkedAndOnlyCompleteAcceptanceMarksSpool(@TempDir Path dir) throws Exception {
        for (boolean complete : List.of(false, true)) {
            var sizes = new CopyOnWriteArrayList<Integer>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/events", exchange -> {
                byte[] bytes = exchange.getRequestBody().readAllBytes(); sizes.add(bytes.length);
                byte[] reply = JSON.writeValueAsBytes(Map.of("accepted", sizes.size() == 2 && !complete ? 99 : 1,
                        "rejected", 0, "errors", List.of(), "evidence_capabilities", PIN));
                exchange.sendResponseHeaders(202, reply.length); exchange.getResponseBody().write(reply); exchange.close();
            });
            server.start();
            Path current = Files.createDirectories(dir.resolve(complete ? "complete" : "partial"));
            try {
                var relay = new DebugBundleBrowserRelay(config(current, server, PIN));
                List<Map<String, Object>> events = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    var event = BrowserRelayEvidenceTest.fixture("native_graph");
                    Map<String, Object> context = new LinkedHashMap<>();
                    for (int j = 0; j < 63; j++) context.put("detail_" + j, "x".repeat(2000));
                    event.put("context", context); event.put("event_id", java.util.UUID.randomUUID().toString()); events.add(event);
                }
                byte[] input = JSON.writeValueAsBytes(Map.of("batch", events));
                assertThat(input.length).isLessThanOrEqualTo(262144);
                var response = relay.handle(BrowserRelayEvidenceTest.request(), input);
                assertThat(response.status()).isEqualTo(202);
                assertThat(sizes).hasSize(2); assertThat(sizes.stream().allMatch(size -> size <= 262144)).isTrue();
                try (var files = Files.list(current.resolve("spool"))) {
                    assertThat(files.filter(path -> path.toString().endsWith(".delivered")).count()).isEqualTo(complete ? 1 : 0);
                }
            } finally { server.stop(0); }
        }
    }
    @Test
    @SuppressWarnings("unchecked")
    void downgradeRetriesOnlyTheRejectedOriginalAndRequiresCombinedCompleteAcceptance(@TempDir Path dir) throws Exception {
        for (boolean complete : List.of(false, true)) {
            var sent = new CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/events", exchange -> {
                sent.add(JSON.readTree(exchange.getRequestBody()).path("events"));
                Map<String, Object> reply = sent.size() == 1
                        ? Map.of("accepted", 1, "rejected", 1, "errors", List.of(Map.of("index", 1, "reason", "invalid_event")),
                                "evidence_capabilities", Map.of("version", 1, "features", List.of()))
                        : Map.of("accepted", complete ? 1 : 99, "rejected", 0, "errors", List.of(),
                                "evidence_capabilities", Map.of("version", 1, "features", List.of()));
                byte[] bytes = JSON.writeValueAsBytes(reply);
                exchange.sendResponseHeaders(202, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start();
            Path current = Files.createDirectories(dir.resolve(complete ? "accepted" : "unknown"));
            try {
                var relay = new DebugBundleBrowserRelay(config(current, server, PIN));
                var original = BrowserRelayEvidenceTest.fixture("native_graph");
                var legacy = new com.debugbundle.sdk.EvidenceProtocol.State(null).filter(BrowserRelayEvidenceTest.fixture("native_graph"));
                legacy.put("event_id", java.util.UUID.randomUUID().toString());
                var response = relay.handle(BrowserRelayEvidenceTest.request(), JSON.writeValueAsBytes(Map.of("batch", List.of(legacy, original))));
                assertThat(response.status()).isEqualTo(202);
                assertThat(sent).hasSize(2); assertThat(sent.get(1).size()).isEqualTo(1);
                assertThat(sent.get(1).get(0).path("event_id").asText()).isEqualTo(original.get("event_id"));
                assertThat(sent.get(1).get(0).path("payload").has("exception_evidence")).isFalse();
                try (var files = Files.list(current.resolve("spool"))) {
                    assertThat(files.filter(path -> path.toString().endsWith(".delivered")).count()).isEqualTo(complete ? 1 : 0);
                }
            } finally { server.stop(0); }
        }
    }

    private static DebugBundleBrowserRelay.Config config(Path dir, HttpServer server, Map<String, Object> pin) {
        return new DebugBundleBrowserRelay.Config("t".repeat(8192), "http://127.0.0.1:" + server.getAddress().getPort() + "/events", "connected",
                dir.resolve("events").toString(), 60, true, dir.resolve("spool").toString(), List.of(), "server-app", "test", pin);
    }
}

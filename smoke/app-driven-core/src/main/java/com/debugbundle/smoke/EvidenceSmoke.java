package com.debugbundle.smoke;

import com.debugbundle.sdk.DebugBundle;
import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.DebugBundleConfig;
import com.debugbundle.sdk.EvidenceProtocol;
import com.debugbundle.sdk.LogLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Installed-artifact native HTTP, recovery, fatal and ownership checks. */
final class EvidenceSmoke {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> PIN = Map.of("version", 1, "features", List.of(
            "exception_evidence_v1", "resource_observations_v1", "recovery_v1", "evidence_provenance_v1"));

    @SuppressWarnings("unchecked")
    static void run() throws Exception {
        var events = new CopyOnWriteArrayList<Map<String, Object>>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/sdk/config", exchange -> {
            byte[] body = JSON.writeValueAsBytes(Map.of("evidence_capabilities", PIN));
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/v1/events", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            require(body.length <= 262144, "oversize installed request");
            Map<String, Object> envelope = JSON.readValue(body, Map.class);
            var batch = (List<Map<String, Object>>) envelope.get("events"); events.addAll(batch);
            byte[] reply = JSON.writeValueAsBytes(Map.of("accepted", batch.size(), "rejected", 0, "errors", List.of(), "evidence_capabilities", PIN));
            exchange.sendResponseHeaders(202, reply.length); exchange.getResponseBody().write(reply); exchange.close();
        });
        server.start();
        try {
            require(EvidenceSmoke.class.getResource("/com/debugbundle/sdk/event-envelope.schema.json") != null, "missing installed runtime schema");
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/events";
            var config = DebugBundleConfig.builder().projectToken("synthetic-installed").environment("smoke")
                    .service("installed-evidence").endpoint(endpoint).captureRecoveryObservations(true)
                    .batchSize(1000).flushInterval(Duration.ofHours(1)).build();
            try (DebugBundleClient client = DebugBundle.create(config)) {
                client.captureMessage("installed discovery", LogLevel.ERROR, Map.of()); client.flush().join();
                String failure = UUID.randomUUID().toString(), attempt = UUID.randomUUID().toString();
                String result = client.runWithOperationContext(Map.of("kind", "job", "name", "queue.process", "limitMs", 1000), () -> {
                    var root = new IllegalStateException("installed original", new IllegalArgumentException("installed cause"));
                    root.addSuppressed(new RuntimeException("installed suppressed"));
                    client.captureException(root, Map.of("failureRef", failure, "password", "INSTALLED_SYNTHETIC_PRIVATE"));
                    client.captureRecovery(Map.of("failureRef", failure, "attemptId", attempt, "state", "attempt_completed", "statusCode", 200));
                    client.captureRecovery(Map.of("failureRef", failure, "attemptId", attempt, "state", "recovery_observed"));
                    return "application result";
                });
                client.flush().join();
                require(result.equals("application result"), "changed callback result");
                var error = events.stream().filter(event -> event.get("event_type").equals("backend_exception")).findFirst().orElseThrow();
                var payload = (Map<String, Object>) error.get("payload");
                var graph = (Map<String, Object>) payload.get("exception_evidence");
                require(((List<?>) graph.get("nodes")).size() == 3, "native graph not installed");
                require(payload.containsKey("execution"), "missing installed operation execution");
                var reference = (Map<String, Object>) ((Map<String, Object>) error.get("context")).get("evidence_reference");
                var recovery = events.stream().filter(event -> event.get("event_type").equals("diagnostic_context")).toList();
                require(recovery.size() == 2, "recovery not independently delivered");
                for (var event : recovery) require(((Map<?, ?>) event.get("payload")).get("scope").equals(reference.get("scope")), "scope mismatch");
                require(!JSON.writeValueAsString(events).contains("INSTALLED_SYNTHETIC_PRIVATE"), "installed privacy leak");
                events.forEach(event -> require(EvidenceProtocol.valid(event), "installed event not canonical"));
                var marker = new RuntimeException("application error");
                try {
                    client.runWithOperationContext(Map.of("kind", "command"), () -> { throw marker; });
                    throw new IllegalStateException("callback error was swallowed");
                } catch (RuntimeException actual) { require(actual == marker, "changed callback error"); }
            }
            var hooks = new AtomicInteger();
            var fatalConfig = DebugBundleConfig.builder().projectToken("synthetic-installed").environment("smoke")
                    .endpoint(endpoint).evidenceCapabilities(PIN).beforeSend(event -> { hooks.incrementAndGet(); return null; }).build();
            events.clear();
            DebugBundle.init(fatalConfig); DebugBundle.captureUncaughtExceptions();
            try {
                var original = new IllegalStateException("installed native fatal", new RuntimeException("installed fatal cause"));
                Thread thread = new Thread(() -> { throw original; }); thread.start(); thread.join(); DebugBundle.flush().join();
                require(hooks.get() == 0, "native fatal invoked hook");
                var payload = (Map<String, Object>) events.get(0).get("payload");
                var graph = (Map<String, Object>) payload.get("exception_evidence");
                require(Boolean.FALSE.equals(payload.get("handled")), "fatal became handled");
                require(((List<?>) graph.get("nodes")).size() <= 2, "fatal node cap exceeded");
                require(graph.toString().contains("native_fatal"), "fatal provenance missing");
            } finally { DebugBundle.shutdown(); }
            System.out.println("Installed evidence, operation, recovery, privacy and native fatal smoke passed.");
        } finally { server.stop(0); }
    }
    private static void require(boolean accepted, String reason) { if (!accepted) throw new IllegalStateException(reason); }
}

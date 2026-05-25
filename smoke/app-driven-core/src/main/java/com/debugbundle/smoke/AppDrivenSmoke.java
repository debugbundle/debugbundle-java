package com.debugbundle.smoke;

import com.debugbundle.sdk.DebugBundle;
import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.DebugBundleConfig;
import com.debugbundle.sdk.DebugBundleRequestScope;
import com.debugbundle.sdk.DebugBundleStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class AppDrivenSmoke {
    private static final String SERVICE = "java-smoke-service";
    private static final String ENVIRONMENT = "smoke";
    private static final String TRACE_ID = "smoke-trace-id";
    private static final String REQUEST_ID = "smoke-request-id";
    private static final byte[] RESPONSE_BODY = "{\"accepted\":1,\"rejected\":0}".getBytes(StandardCharsets.UTF_8);

    private AppDrivenSmoke() {
    }

    public static void main(String[] args) throws Exception {
        try (RecordingServer server = new RecordingServer()) {
            DebugBundleClient client = DebugBundle.create(DebugBundleConfig.builder()
                    .projectToken("dbundle_proj_smoke")
                    .service(SERVICE)
                    .environment(ENVIRONMENT)
                    .endpoint(server.endpoint())
                    .requestTimeout(Duration.ofSeconds(2))
                    .batchSize(1)
                    .flushInterval(Duration.ofSeconds(1))
                    .build());
            try {
                DebugBundleRequestScope scope = client.beginRequest(Map.of(
                        "method", "GET",
                        "path", "/smoke",
                        "headers", Map.of(
                                "X-DebugBundle-Trace-Id", TRACE_ID,
                                "X-Request-Id", REQUEST_ID
                        ),
                        "query", Map.of()
                ));

                client.captureException(new IllegalStateException("java smoke failure"));
                client.endRequest(scope);
                client.flush().join();

                String payload = server.awaitPayload();
                assertCondition(server.requestCount() == 1, "expected one ingestion request");
                assertCondition(client.status() == DebugBundleStatus.HEALTHY, "expected healthy SDK status");
                assertContains(payload, "\"service\":{\"name\":\"" + SERVICE + "\"");
                assertContains(payload, "\"environment\":\"" + ENVIRONMENT + "\"");
                assertContains(payload, "\"sdk_name\":\"@debugbundle/sdk-java\"");
                assertContains(payload, "\"trace_id\":\"" + TRACE_ID + "\"");
                assertContains(payload, "\"request_id\":\"" + REQUEST_ID + "\"");
                assertContains(payload, "\"message\":\"java smoke failure\"");
                System.out.println("App-driven smoke passed.");
            } finally {
                client.close();
            }
        }
    }

    private static void assertContains(String payload, String expected) {
        assertCondition(payload.contains(expected), "missing expected payload fragment: " + expected + " in " + payload);
    }

    private static void assertCondition(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class RecordingServer implements AutoCloseable {
        private final AtomicReference<String> payload = new AtomicReference<>();
        private final CountDownLatch received = new CountDownLatch(1);
        private final AtomicInteger requestCount = new AtomicInteger(0);
        private final HttpServer server;

        private RecordingServer() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/v1/events", new RecordingHandler());
            this.server.start();
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/events";
        }

        private int requestCount() {
            return requestCount.get();
        }

        private String awaitPayload() throws InterruptedException {
            if (!received.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the smoke event batch.");
            }
            return payload.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private final class RecordingHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                requestCount.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(202, RESPONSE_BODY.length);
                exchange.getResponseBody().write(RESPONSE_BODY);
                exchange.close();
                received.countDown();
            }
        }
    }
}
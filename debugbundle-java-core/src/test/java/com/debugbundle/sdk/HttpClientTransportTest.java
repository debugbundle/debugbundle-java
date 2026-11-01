package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpClientTransportTest {
    private HttpServer server;

    @Test
    void discoversProofOnExistingAckAndDowngradesRejectedOriginalOnlyOnce() throws Exception {
        var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var query = new AtomicReference<String>();
        var now = new AtomicLong(1773446400000L);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server = startServer(exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int call = calls.incrementAndGet();
            String proof = "\"evidence_capabilities\":{\"version\":1,\"features\":"
                    + (call == 1 ? "[\"exception_evidence_v1\"]" : "[]") + "}";
            respond(exchange, 202, call == 2
                    ? "{\"accepted\":0,\"rejected\":1,\"errors\":[{\"index\":0,\"reason\":\"invalid_event\"}]," + proof + "}"
                    : "{\"accepted\":1,\"rejected\":0,\"errors\":[]," + proof + "}", Map.of());
        });
        var hooks = new java.util.concurrent.atomic.AtomicInteger();
        var config = DebugBundleConfig.builder().projectToken("synthetic").environment("test")
                .flushInterval(Duration.ofHours(1)).beforeSend(event -> { hooks.incrementAndGet(); return event; }).build();
        try (var client = new DefaultDebugBundleClient(config, new HttpTransport(endpoint("/events"), "synthetic"), now::get)) {
            client.captureMessage("discover", LogLevel.ERROR, Map.of()); client.flush().join();
            client.captureException(new RuntimeException("preserve original")); client.flush().join();
            assertPending(client, 1);
            now.addAndGet(1001); client.flush().join();
            assertThat(requests).hasSize(3);
            assertThat(query.get()).contains("debugbundle_evidence=1");
            assertThat(requests.get(1)).contains("exception_evidence", "preserve original");
            assertThat(requests.get(2)).contains("preserve original").doesNotContain("exception_evidence");
            assertThat(hooks).hasValue(2);
            assertThat(client.pendingEventCount()).isZero();
        }
    }

    @Test
    void nativeBatchesUseActualWireBytesAndRetainFinalizedTails() throws Exception {
        var sizes = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        var accepted = new java.util.concurrent.atomic.AtomicInteger();
        var hooks = new java.util.concurrent.atomic.AtomicInteger();
        server = startServer(exchange -> {
            byte[] data = exchange.getRequestBody().readAllBytes();
            sizes.add(data.length);
            int count = new com.fasterxml.jackson.databind.ObjectMapper().readTree(data).path("events").size();
            if (data.length > 262144) respond(exchange, 413, "{}", Map.of());
            else { accepted.addAndGet(count); respond(exchange, 202,
                    "{\"accepted\":" + count + ",\"rejected\":0,\"errors\":[]}", Map.of()); }
        });
        var config = DebugBundleConfig.builder().projectToken("synthetic").environment("test")
                .batchSize(1000).flushInterval(Duration.ofHours(1)).beforeSend(event -> { hooks.incrementAndGet(); return event; }).build();
        try (var client = new DefaultDebugBundleClient(config, new HttpTransport(endpoint("/events"), "synthetic"), System::currentTimeMillis)) {
            for (int i = 0; i < 25; i++) client.captureMessage("x".repeat(16000), LogLevel.ERROR, Map.of("sequence", i, "detail", "y".repeat(8000)));
            for (int i = 0; i < 5 && client.pendingEventCount() > 0; i++) client.flush().join();
            assertThat(sizes.stream().allMatch(size -> size <= 262144)).isTrue();
            assertThat(accepted).hasValue(25);
            assertThat(hooks).hasValue(25);
            assertThat(client.pendingEventCount()).isZero();
        }
    }

    @Test
    void nativeIngestionAndConfigRepliesAreBoundedBeforeParsing() throws Exception {
        server = startServer(exchange -> respond(exchange, 202, "x".repeat(262145), Map.of()));
        TransportResponse ingestion = new HttpTransport(endpoint("/events"), "synthetic-project")
                .send(new EventBatchRequest(List.of()));
        assertThat(ingestion.statusCode()).isEqualTo(202);
        assertThat(ingestion.body() == null).isTrue();
        RemoteConfigResponse config = new HttpRemoteConfigFetcher().fetch(new RemoteConfigRequest(
                endpoint("/config"), "synthetic-project", "sdk", "candidate", null, Duration.ofSeconds(2)));
        assertThat(config.responseBody() == null).isTrue();
    }

    @Test
    void aStreamingReplyCannotRenewTheNativeRequestDeadline() throws Exception {
        server = startServer(exchange -> {
            exchange.sendResponseHeaders(202, 0);
            try {
                for (int i = 0; i < 100; i++) {
                    exchange.getResponseBody().write(' ');
                    exchange.getResponseBody().flush();
                    Thread.sleep(20);
                }
            } catch (IOException ignored) {
                // The bounded client closes this synthetic stream at its deadline.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        long started = System.nanoTime();
        TransportResponse response = new HttpTransport(endpoint("/events"), "synthetic-project", Duration.ofMillis(80))
                .send(new EventBatchRequest(List.of()));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(750));
        assertThat(response.body()).isNull();
    }

    @Test
    void configuredRequestTimeoutStopsWaitingForAStalledIngestionResponse() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        server = startServer(exchange -> {
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            respond(exchange, 202, "{\"accepted\":0,\"rejected\":0,\"errors\":[]}", Map.of());
        });
        DebugBundleTransport transport = TransportFactory.create(DebugBundleConfig.builder()
                .environment("production").projectToken("token").endpoint(endpoint("/events"))
                .requestTimeout(Duration.ofMillis(50)).build());
        var response = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> transport.send(new EventBatchRequest(List.of())));
        try {
            assertThat(response.get(2, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(500);
        } finally {
            release.countDown();
            response.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void invalidAndExtremeTimeoutsRemainSafeForOrdinaryDelivery() throws Exception {
        server = startServer(exchange -> respond(exchange, 202, "{}", Map.of()));
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(Long.MAX_VALUE))) {
            DebugBundleTransport transport = TransportFactory.create(DebugBundleConfig.builder()
                    .environment("production").projectToken("token").endpoint(endpoint("/events"))
                    .requestTimeout(timeout).build());
            assertThat(transport.send(new EventBatchRequest(List.of())).statusCode()).isEqualTo(202);
        }
    }

    @Test
    void serviceUnavailableHonorsRetryAfterAndRecovers() throws Exception {
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        FakeTransport transport = new FakeTransport(List.of(new TransportResponse(503, Long.MAX_VALUE),
                new TransportResponse(202, null)));
        try (DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder().projectToken("dbundle_proj_test")
                        .flushInterval(Duration.ofHours(1)).build(), transport, now::get)) {
            client.captureMessage("retain", LogLevel.ERROR, Map.of());
            client.flush().join();
            now.addAndGet(299_000L);
            client.flush().join();
            assertThat(transport.calls()).hasSize(1);
            assertThat(client.lastEventAt()).isEmpty();
            now.addAndGet(2_000L);
            client.flush().join();
            assertThat(transport.calls()).hasSize(2);
            assertThat(client.lastEventAt()).isPresent();
        }
    }

    @Test
    void retryAfterAcceptsHttpDatesAndHugeFiniteValues() throws Exception {
        AtomicReference<String> header = new AtomicReference<>();
        server = startServer(exchange -> respond(exchange, 503, "{}", Map.of("Retry-After", header.get())));
        HttpTransport transport = new HttpTransport(endpoint("/events"), "token");
        for (String value : List.of("Wed, 01 Jan 2031 00:00:00 GMT", "Wednesday, 01-Jan-31 00:00:00 GMT",
                "Wed Jan  1 00:00:00 2031", "1e300")) {
            header.set(value);
            assertThat(transport.send(new EventBatchRequest(List.of())).retryAfterMillis()).as(value).isEqualTo(300_000L);
        }
        for (String value : List.of("NaN", "Infinity", "tomorrow")) {
            header.set(value);
            assertThat(transport.send(new EventBatchRequest(List.of())).retryAfterMillis()).as(value).isNull();
        }
        header.set("Sun, 06 Nov 1994 08:49:37 GMT");
        assertThat(transport.send(new EventBatchRequest(List.of())).retryAfterMillis()).isZero();
    }


    @Test
    void builtInHttpRetainsUnacknowledgedBatchAndRecovers() throws Exception {
        AtomicReference<String> plannedBody = new AtomicReference<>();
        List<String> requests = new ArrayList<>();
        server = startServer(exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 202, plannedBody.get(), Map.of("Retry-After", "300"));
        });
        for (String invalidBody : List.of("",
                "{\"accepted\":2,\"rejected\":0,\"errors\":[]} trailing",
                "{\"accepted\":null,\"rejected\":2,\"errors\":[{\"index\":0,\"reason\":\"rate_limited\"},{\"index\":1,\"reason\":\"rate_limited\"}]}",
                "{\"accepted\":2,\"rejected\":0,\"errors\":null}",
                "{\"accepted\":1,\"rejected\":1,\"errors\":[{\"index\":4294967296,\"reason\":\"rate_limited\"}]}",
                "{\"accepted\":1,\"rejected\":1,\"errors\":{\"one\":{\"index\":1,\"reason\":\"rate_limited\"}}}", "<html>proxy</html>", "{}", "[]", "null",
                "{\"accepted\":1,\"rejected\":0,\"errors\":[]}",
                "{\"accepted\":0,\"rejected\":2,\"errors\":[{\"index\":0,\"reason\":\"rate_limited\"},{\"index\":0,\"reason\":\"rate_limited\"}]}",
                "{\"accepted\":1,\"rejected\":1,\"errors\":[{\"index\":2,\"reason\":\"rate_limited\"}]}")) {
            requests.clear();
            plannedBody.set(invalidBody);
            AtomicLong now = new AtomicLong(1_700_000_000_000L);
            DebugBundleConfig config = DebugBundleConfig.builder().projectToken("dbundle_proj_test")
                    .environment("production").flushInterval(Duration.ofHours(1))
                    .remoteConfigFetcher(request -> new RemoteConfigResponse(304, null, null)).build();
            try (DefaultDebugBundleClient client = new DefaultDebugBundleClient(config,
                    new HttpTransport(endpoint("/events"), "dbundle_proj_test"), now::get)) {
                client.captureMessage("first", LogLevel.ERROR, Map.of());
                client.captureMessage("second", LogLevel.ERROR, Map.of());
                client.flush().join();
                assertThat(client.lastEventAt()).as(invalidBody).isEmpty();
                assertThat(client.status()).isEqualTo(DebugBundleStatus.DEGRADED);
                client.flush().join();
                assertThat(requests).hasSize(1);
                plannedBody.set("{\"accepted\":2,\"rejected\":0,\"errors\":[]}");
                now.addAndGet(301_000L);
                client.flush().join();
                assertThat(requests).hasSize(2);
                assertThat(requests.get(1)).isEqualTo(requests.get(0));
                assertThat(client.lastEventAt()).isPresent();
            }
        }
    }

    private static void assertPending(DefaultDebugBundleClient client, int expected) throws InterruptedException {
        // The nonblocking inspector reports its capacity sentinel during a short
        // queue lock. Observe the settled count without changing capture safety.
        int count = client.pendingEventCount();
        for (int attempt = 0; attempt < 100 && count == 1000; attempt++) {
            Thread.sleep(2); count = client.pendingEventCount();
        }
        assertThat(count).isEqualTo(expected);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void retryAfterCannotOverflowBeforeBounding() throws Exception {
        server = startServer(exchange -> respond(exchange, 429, "{}", Map.of("Retry-After", "9223372036854775807")));
        TransportResponse response = new HttpTransport(endpoint("/events"), "token").send(new EventBatchRequest(List.of()));
        assertThat(response.retryAfterMillis()).isEqualTo(300_000L);
    }

    @Test
    void eventTransportSendsAuthorizedEnvelopeAndClampsRetryAfter() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 429, "{\"accepted\":0,\"rejected\":1}", Map.of("Retry-After", "999"));
        });

        HttpTransport transport = new HttpTransport(endpoint("/v1/events"), "dbundle_proj_test");
        TransportResponse response = transport.send(new EventBatchRequest(List.of(Map.of("event_id", "evt-1"))));

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.retryAfterMillis()).isEqualTo(300_000L);
        assertThat(response.body()).contains("\"accepted\":0");
        assertThat(authorization).hasValue("Bearer dbundle_proj_test");
        assertThat(body.get()).contains("\"events\"").contains("\"event_id\":\"evt-1\"");
    }

    @Test
    void eventTransportIgnoresMalformedRetryAfterAndContainsInvalidEndpoints() throws Exception {
        server = startServer(exchange -> respond(exchange, 202, "{}", Map.of("Retry-After", "tomorrow")));

        TransportResponse accepted = new HttpTransport(endpoint("/events"), "token")
                .send(new EventBatchRequest(List.of()));
        TransportResponse failed = new HttpTransport("://invalid", "token")
                .send(new EventBatchRequest(List.of()));

        assertThat(accepted.statusCode()).isEqualTo(202);
        assertThat(accepted.retryAfterMillis()).isNull();
        assertThat(failed.statusCode()).isEqualTo(500);
    }

    @Test
    void remoteConfigFetcherSendsSdkAndConditionalHeaders() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> sdkName = new AtomicReference<>();
        AtomicReference<String> sdkVersion = new AtomicReference<>();
        AtomicReference<String> ifNoneMatch = new AtomicReference<>();
        server = startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            sdkName.set(exchange.getRequestHeaders().getFirst("x-debugbundle-sdk"));
            sdkVersion.set(exchange.getRequestHeaders().getFirst("x-debugbundle-sdk-version"));
            ifNoneMatch.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            respond(exchange, 200, "{\"probes_enabled\":true}", Map.of("ETag", "\"cfg-v2\""));
        });

        RemoteConfigResponse response = new HttpRemoteConfigFetcher().fetch(new RemoteConfigRequest(
                endpoint("/v1/sdk/config"),
                "dbundle_proj_test",
                "@debugbundle/sdk-java",
                "2.0.0",
                "\"cfg-v1\"",
                Duration.ofSeconds(2)
        ));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.responseBody()).contains("\"probes_enabled\":true");
        assertThat(response.etag()).isEqualTo("\"cfg-v2\"");
        assertThat(authorization).hasValue("Bearer dbundle_proj_test");
        assertThat(sdkName).hasValue("@debugbundle/sdk-java");
        assertThat(sdkVersion).hasValue("2.0.0");
        assertThat(ifNoneMatch).hasValue("\"cfg-v1\"");
    }

    @Test
    void remoteConfigFetcherOmitsBlankEtagAndContainsInvalidEndpoints() throws Exception {
        AtomicReference<String> ifNoneMatch = new AtomicReference<>("not-observed");
        server = startServer(exchange -> {
            ifNoneMatch.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            respond(exchange, 304, "", Map.of());
        });
        HttpRemoteConfigFetcher fetcher = new HttpRemoteConfigFetcher();

        RemoteConfigResponse notModified = fetcher.fetch(new RemoteConfigRequest(
                endpoint("/config"), "token", "sdk", "1", " ", Duration.ofSeconds(2)));
        RemoteConfigResponse failed = fetcher.fetch(new RemoteConfigRequest(
                "://invalid", "token", "sdk", "1", null, Duration.ofSeconds(2)));

        assertThat(notModified.statusCode()).isEqualTo(304);
        assertThat(ifNoneMatch).hasValue(null);
        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(failed.responseBody()).isNull();
        assertThat(failed.etag()).isNull();
    }

    private HttpServer startServer(ExchangeHandler handler) throws IOException {
        HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        created.createContext("/", exchange -> handler.handle(exchange));
        created.start();
        return created;
    }

    private String endpoint(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            String body,
            Map<String, String> headers
    ) throws IOException {
        headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}

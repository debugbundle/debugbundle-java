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

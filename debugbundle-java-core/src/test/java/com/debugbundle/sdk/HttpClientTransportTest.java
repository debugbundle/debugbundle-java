package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpClientTransportTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
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
                "1.3.0",
                "\"cfg-v1\"",
                Duration.ofSeconds(2)
        ));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.responseBody()).contains("\"probes_enabled\":true");
        assertThat(response.etag()).isEqualTo("\"cfg-v2\"");
        assertThat(authorization).hasValue("Bearer dbundle_proj_test");
        assertThat(sdkName).hasValue("@debugbundle/sdk-java");
        assertThat(sdkVersion).hasValue("1.3.0");
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

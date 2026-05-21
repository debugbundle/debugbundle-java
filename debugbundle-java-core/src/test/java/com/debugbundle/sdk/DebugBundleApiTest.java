package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebugBundleApiTest {
    private static final long SUPPRESSION_TEST_START_MS = 1_773_446_400_000L;

    @Test
    void initWithProjectTokenProducesHealthyClient() {
        DebugBundleClient client = DebugBundle.init(DebugBundleConfig.builder()
                .projectToken("dbundle_proj_test")
                .service("checkout-api")
                .environment("test")
                .build());

        assertThat(client.status()).isEqualTo(DebugBundleStatus.HEALTHY);
        assertThat(DebugBundle.status()).isEqualTo(DebugBundleStatus.HEALTHY);
    }

    @Test
    void missingProjectTokenDegradesSilently() {
        DebugBundleClient client = DebugBundle.init(DebugBundleConfig.builder()
                .service("checkout-api")
                .environment("test")
                .build());

        assertThat(client.status()).isEqualTo(DebugBundleStatus.DISCONNECTED);
        assertThat(DebugBundle.lastEventAt()).isEqualTo(Optional.empty());
    }

    @Test
    void facadeMethodsDoNotThrowWhenSdkIsDisconnected() {
        DebugBundle.init(DebugBundleConfig.builder().enabled(false).build());

        DebugBundle.captureException(new IllegalStateException("boom"));
        DebugBundle.captureError(new IllegalArgumentException("boom"));
        DebugBundle.captureLog("warn", LogLevel.WARNING);
        DebugBundle.captureMessage("info");
        DebugBundle.captureRequest(new Object(), new Object(), null);
        DebugBundle.setContext("user", "abc");
        DebugBundle.probe("checkout.latency", "slow");
        DebugBundle.probe("checkout.tax", () -> "value");
        DebugBundle.probe("checkout.heavy", () -> "value", ProbeOptions.heavyOption());
        DebugBundle.captureUncaughtExceptions();
        DebugBundle.captureJavaUtilLogging();

        assertThat(DebugBundle.flush()).isCompleted();
        assertThat(DebugBundle.status()).isEqualTo(DebugBundleStatus.DISCONNECTED);
    }

    @Test
    void flushesWhenBatchSizeIsReached() {
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .batchSize(2)
                        .build(),
                transport,
                System::currentTimeMillis
        );

        client.captureMessage("first", LogLevel.ERROR, Map.of());
        client.captureMessage("second", LogLevel.ERROR, Map.of());

        assertThat(transport.calls()).hasSize(1);
        assertThat(transport.calls().get(0).events()).hasSize(2);
    }

    @Test
    void retainsBufferedEventsWhenTransportFails() {
        ManualClock clock = new ManualClock();
        FakeTransport transport = new FakeTransport(List.of(
                new TransportResponse(500, null),
                new TransportResponse(202, null)
        ));
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .build(),
                transport,
                clock::nowMillis
        );

        client.captureException(new RuntimeException("database unavailable"));
        client.flush();
        clock.advanceMillis(1_001L);
        client.flush();

        assertThat(transport.calls()).hasSize(2);
        assertThat(transport.calls().get(1).events().get(0).get("payload"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("message", "database unavailable");
    }

    @Test
    void appliesRetryBackoffAfter429Response() {
        ManualClock clock = new ManualClock();
        FakeTransport transport = new FakeTransport(List.of(
                new TransportResponse(429, 1_000L),
                new TransportResponse(202, null)
        ));
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .build(),
                transport,
                clock::nowMillis
        );

        client.captureMessage("retry me", LogLevel.ERROR, Map.of());
        client.flush();
        client.flush();

        assertThat(transport.calls()).hasSize(1);

        clock.advanceMillis(1_001L);
        client.flush();

        assertThat(transport.calls()).hasSize(2);
    }

    @Test
    void capsRetryAfterBackoffAtFiveMinutes() {
        ManualClock clock = new ManualClock();
        FakeTransport transport = new FakeTransport(List.of(
                new TransportResponse(429, 600_000L),
                new TransportResponse(202, null)
        ));
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .build(),
                transport,
                clock::nowMillis
        );

        client.captureMessage("retry me", LogLevel.ERROR, Map.of());
        client.flush();
        clock.advanceMillis(300_001L);
        client.flush();

        assertThat(transport.calls()).hasSize(2);
    }

    @Test
    void instanceClientSwallowsSupplierAndTransportFailures() {
        DebugBundleTransport throwingTransport = request -> {
            throw new IllegalStateException("transport down");
        };
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .build(),
                throwingTransport,
                System::currentTimeMillis
        );

        assertThatCode(() -> client.probe("checkout.tax", () -> {
            throw new IllegalStateException("supplier failed");
        })).doesNotThrowAnyException();
        assertThatCode(() -> client.captureException(new RuntimeException("boom"))).doesNotThrowAnyException();
        assertThatCode(client::flush).doesNotThrowAnyException();
        assertThat(client.status()).isEqualTo(DebugBundleStatus.DEGRADED);
    }

    @Test
    void instanceClientSwallowsRemoteConfigFetcherFailuresOnInit() {
        assertThatCode(() -> new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .remoteConfigFetcher(request -> {
                            throw new IllegalStateException("config down");
                        })
                        .build(),
                new FakeTransport(),
                System::currentTimeMillis
        )).doesNotThrowAnyException();
    }

    @Test
    void sampleRateZeroDropsCapturedEventsBeforeTransport() {
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .sampleRate(0.0d)
                        .build(),
                transport,
                System::currentTimeMillis
        );

        client.captureMessage("drop me", LogLevel.ERROR, Map.of());
        client.captureException(new RuntimeException("drop me too"));
        client.flush();

        assertThat(transport.calls()).isEmpty();
    }

    @Test
    void flushIntervalFlushesWithoutAnotherCapturedEvent() throws Exception {
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .flushInterval(Duration.ofMillis(50))
                        .build(),
                transport,
                System::currentTimeMillis
        );

        client.captureMessage("timer flush", LogLevel.ERROR, Map.of());
        Thread.sleep(250L);

        assertThat(transport.calls()).hasSize(1);
        client.close();
    }

    @Test
    void redactsSensitiveRequestFieldsBeforeTransport() {
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .build(),
                transport,
                System::currentTimeMillis
        );

        client.captureException(new RuntimeException("login failed"), Map.of(
                "request", Map.of(
                        "method", "POST",
                        "path", "/login",
                        "headers", Map.of("authorization", "Bearer secret-token"),
                        "query", Map.of("token", "query-secret"),
                        "body", Map.of("password", "super-secret")
                ),
                "response", Map.of("status_code", 401)
        ));
        client.flush();

        Map<String, Object> event = transport.calls().get(0).events().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) payload.get("request");
        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) request.get("headers");
        @SuppressWarnings("unchecked")
        Map<String, Object> query = (Map<String, Object>) request.get("query");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) request.get("body");

        assertThat(headers).containsEntry("authorization", "[REDACTED]");
        assertThat(query).containsEntry("token", "[REDACTED]");
        assertThat(body).containsEntry("password", "[REDACTED]");
    }

    @Test
    void redactsDefaultSensitiveFieldsWithSegmentsAndCircularProtection() {
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .build(),
                transport,
                System::currentTimeMillis
        );
        Map<String, Object> cyclic = new java.util.LinkedHashMap<>();
        cyclic.put("apiKey", "api-secret");
        cyclic.put("accessToken", "access-secret");
        cyclic.put("user_password", "password-secret");
        cyclic.put("sessionId", "session-secret");
        cyclic.put("self", cyclic);

        client.captureLog("redaction check", LogLevel.ERROR, Map.of("sensitive", cyclic));
        client.flush();

        Map<String, Object> event = transport.calls().get(0).events().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) payload.get("attributes");
        @SuppressWarnings("unchecked")
        Map<String, Object> sensitive = (Map<String, Object>) attributes.get("sensitive");

        assertThat(sensitive).containsEntry("apiKey", "[REDACTED]");
        assertThat(sensitive).containsEntry("accessToken", "[REDACTED]");
        assertThat(sensitive).containsEntry("user_password", "[REDACTED]");
        assertThat(sensitive).containsEntry("sessionId", "[REDACTED]");
        assertThat(sensitive).containsEntry("self", "[Circular]");
    }

    @Test
    void redactionTruncatesOversizedCollectionsAndStrings() {
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .build(),
                transport,
                System::currentTimeMillis
        );
        List<Integer> largeList = new ArrayList<>();
        for (int index = 0; index < 150; index++) {
            largeList.add(index);
        }

        client.captureLog("size check", LogLevel.ERROR, Map.of(
                "long_string", "a".repeat(9_000),
                "large_list", largeList
        ));
        client.flush();

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) transport.calls().get(0).events().get(0).get("payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) payload.get("attributes");
        assertThat((String) attributes.get("long_string")).endsWith("[Truncated]");
        assertThat((List<?>) attributes.get("large_list")).hasSize(101);
    }

    @Test
    void flushesAlwaysOnProbeDataAndKeepsHeavyProbesDormant() {
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .build(),
                transport,
                System::currentTimeMillis
        );

        int[] invocationCount = {0};
        client.probe("checkout.tax", Map.of("secret", "tax-secret", "rate", 0.2));
        client.probe("db.query-plan", () -> {
            invocationCount[0]++;
            return Map.of("plan", "full scan");
        }, ProbeOptions.heavyOption());
        client.captureException(new RuntimeException("checkout failed"));
        client.flush();

        assertThat(invocationCount[0]).isZero();

        Map<String, Object> event = transport.calls().get(0).events().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> probeData = (Map<String, Object>) payload.get("probe_data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) probeData.get("items");

        assertThat(items.get(0)).containsEntry("label", "checkout.tax");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) items.get(0).get("data");
        assertThat(data).containsEntry("secret", "[REDACTED]");
    }

    @Test
    void emitsContractShapedEventEnvelopes() {
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .build(),
                transport,
                System::currentTimeMillis
        );

        client.captureMessage("error raised", LogLevel.ERROR, Map.of("tenant", "acme"));
        client.captureRequest(
                Map.of("method", "GET", "path", "/orders", "headers", Map.of("x-request-id", "req_1"), "query", Map.of("page", "1")),
                Map.of("status_code", 503, "duration_ms", 45),
                Map.of()
        );
        client.captureException(new RuntimeException("checkout failed"), Map.of(
                "request", Map.of("method", "POST", "path", "/checkout", "headers", Map.of("authorization", "secret"), "query", Map.of()),
                "response", Map.of("status_code", 500)
        ));
        client.flush();

        List<Map<String, Object>> events = transport.calls().get(0).events();
        assertThat(events).hasSize(3);
        assertThat(events.get(0)).containsEntry("schema_version", "2026-03-01");
        assertThat(events.get(0)).containsEntry("sdk_name", "@debugbundle/sdk-java");

        @SuppressWarnings("unchecked")
        Map<String, Object> service = (Map<String, Object>) events.get(0).get("service");
        assertThat(service).containsEntry("name", "checkout-api");
        assertThat(service).containsEntry("runtime", "java");
        assertThat(service).containsEntry("environment", "production");

        @SuppressWarnings("unchecked")
        Map<String, Object> logPayload = (Map<String, Object>) events.get(0).get("payload");
        assertThat(logPayload).containsEntry("message", "error raised");
        assertThat(logPayload).containsEntry("level", "error");
    }

    @Test
    void sendsFirstThreeDuplicateExceptionsAndAggregatesTheRest() {
        ManualClock clock = new ManualClock();
        clock.setNowMillis(SUPPRESSION_TEST_START_MS);
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .build(),
                transport,
                clock::nowMillis
        );

        for (int index = 0; index < 5; index++) {
            client.captureException(new RuntimeException("duplicate checkout failure"));
        }
        client.flush();

        List<Map<String, Object>> events = transport.calls().get(0).events();
        assertThat(events).hasSize(4);
        assertThat(events).extracting(event -> event.get("event_type"))
                .containsExactly("backend_exception", "backend_exception", "backend_exception", "error_suppressed");

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) events.get(3).get("payload");
        assertThat(payload).containsEntry("suppressed_count", 2);
        assertThat(payload).containsEntry("window_seconds", 30);
        assertThat(payload).containsEntry("first_seen", "2026-03-14T00:00:00Z");
        assertThat(payload).containsEntry("last_seen", "2026-03-14T00:00:00Z");
        assertThat(payload.get("fingerprint")).asString().hasSize(64);
    }

    @Test
    void keepsLoopingDuplicatesSuppressedUntilSilenceResetsCapture() {
        ManualClock clock = new ManualClock();
        clock.setNowMillis(SUPPRESSION_TEST_START_MS);
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .build(),
                transport,
                clock::nowMillis
        );

        for (int index = 0; index < 11; index++) { captureRecursiveFailure(client); }
        client.flush();

        assertThat(transport.calls()).hasSize(1);
        assertThat(transport.calls().get(0).events())
                .extracting(event -> event.get("event_type"))
                .containsExactly("backend_exception", "backend_exception", "backend_exception", "error_suppressed");

        clock.advanceMillis(30_000L);
        for (int index = 0; index < 2; index++) { captureRecursiveFailure(client); }
        client.flush();

        assertThat(transport.calls()).hasSize(2);
        List<Map<String, Object>> checkpointEvents = transport.calls().get(1).events();
        assertThat(checkpointEvents).hasSize(1);
        assertThat(checkpointEvents.get(0)).containsEntry("event_type", "error_suppressed");

        @SuppressWarnings("unchecked")
        Map<String, Object> checkpointPayload = (Map<String, Object>) checkpointEvents.get(0).get("payload");
        assertThat(checkpointPayload).containsEntry("suppressed_count", 2);

        clock.advanceMillis(61_000L);
        captureRecursiveFailure(client);
        client.flush();

        assertThat(transport.calls()).hasSize(3);
        List<Map<String, Object>> recoveredEvents = transport.calls().get(2).events();
        assertThat(recoveredEvents).hasSize(1);
        assertThat(recoveredEvents.get(0)).containsEntry("event_type", "backend_exception");

        @SuppressWarnings("unchecked")
        Map<String, Object> recoveredPayload = (Map<String, Object>) recoveredEvents.get(0).get("payload");
        assertThat(recoveredPayload).containsEntry("message", "recursive failure");
    }

    @Test
    void writesLocalEventFilesInDevelopmentMode(@TempDir Path tempDir) throws Exception {
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("development")
                        .localEventsDir(tempDir.toString())
                        .build()
        );

        client.captureMessage("local event", LogLevel.WARNING, Map.of("tenant", "acme"));
        client.flush();

        try (var files = Files.list(tempDir)) {
            List<Path> writtenFiles = files.toList();
            assertThat(writtenFiles).hasSize(1);
            String content = Files.readString(writtenFiles.get(0));
            assertThat(content).contains("local event");
            assertThat(content).contains("@debugbundle/sdk-java");
            assertThat(Files.getPosixFilePermissions(tempDir))
                    .isEqualTo(EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE
                    ));
            assertThat(Files.getPosixFilePermissions(writtenFiles.get(0)))
                    .isEqualTo(EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE
                    ));
        }
    }

    private void captureRecursiveFailure(DefaultDebugBundleClient client) {
        client.captureException(new RuntimeException("recursive failure"));
    }
}

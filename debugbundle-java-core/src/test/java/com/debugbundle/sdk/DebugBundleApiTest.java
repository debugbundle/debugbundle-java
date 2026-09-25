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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
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
    void shutdownDetachesGlobalJulHandlerBeforeRedeploy() {
        DebugBundle.init(DebugBundleConfig.builder().projectToken("dbundle_proj_test").environment("local").build());
        DebugBundle.captureJavaUtilLogging();
        assertThat(java.util.Arrays.stream(Logger.getLogger("").getHandlers())
                .filter(handler -> handler instanceof DebugBundleJulHandler).count()).isEqualTo(1);

        DebugBundle.shutdown();
        assertThat(java.util.Arrays.stream(Logger.getLogger("").getHandlers())
                .filter(handler -> handler instanceof DebugBundleJulHandler).count()).isZero();

        DebugBundle.captureJavaUtilLogging();
        assertThat(java.util.Arrays.stream(Logger.getLogger("").getHandlers())
                .filter(handler -> handler instanceof DebugBundleJulHandler).count()).isEqualTo(1);
        DebugBundle.shutdown();

        Logger redirectedStderr = Logger.getLogger("debugbundle.smoke.stderr");
        DebugBundle.captureJavaUtilLogging(redirectedStderr);
        assertThat(java.util.Arrays.stream(redirectedStderr.getHandlers())
                .filter(handler -> handler instanceof DebugBundleJulHandler).count()).isEqualTo(1);
        DebugBundle.shutdown();
        assertThat(java.util.Arrays.stream(redirectedStderr.getHandlers())
                .filter(handler -> handler instanceof DebugBundleJulHandler).count()).isZero();
    }

    @Test
    void shutdownDrainsRedirectedStackBeforeClosingItsClient(@TempDir Path eventsDir) throws Exception {
        Logger redirectedStderr = Logger.getLogger("debugbundle.shutdown.stderr");
        redirectedStderr.setUseParentHandlers(false);
        DebugBundle.init(DebugBundleConfig.builder().projectToken("dbundle_proj_test")
                .environment("local").localEventsDir(eventsDir.toString()).batchSize(25).build());
        DebugBundle.captureJavaUtilLogging(redirectedStderr);
        redirectedStderr.log(Level.SEVERE, "java.lang.IllegalStateException: shutdown trace");
        redirectedStderr.log(Level.SEVERE, "\tat example.Server.stop(Server.java:42)");

        DebugBundle.shutdown();

        boolean persisted = false;
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            try (var files = Files.list(eventsDir)) {
                persisted = files.anyMatch(Files::isRegularFile);
            }
            if (persisted) break;
            Thread.sleep(10);
        }
        assertThat(persisted).isTrue();
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
        client.flush().join();

        assertThat(transport.calls()).hasSize(1);
        assertThat(transport.calls().get(0).events()).hasSize(2);
    }

    @Test
    void beforeSendRunsAfterRedactionAndMutatesBeforeQueueing() {
        FakeTransport transport = new FakeTransport();
        List<Object> observedPasswords = new ArrayList<>();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .beforeSend(event -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> payload = (Map<String, Object>) event.get("payload");
                            @SuppressWarnings("unchecked")
                            Map<String, Object> attributes = (Map<String, Object>) payload.get("attributes");
                            observedPasswords.add(attributes.get("password"));
                            payload.put("message", "mutated");
                            return event;
                        })
                        .build(),
                transport,
                System::currentTimeMillis
        );

        client.captureMessage("original", LogLevel.ERROR, Map.of("password", "secret"));
        client.flush().join();

        assertThat(observedPasswords).containsExactly("[REDACTED]");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload =
                (Map<String, Object>) transport.calls().get(0).events().get(0).get("payload");
        assertThat(payload).containsEntry("message", "mutated");
    }

    @Test
    void mandatoryProtectionCoversContextHookAndFinalTransport() {
        FakeTransport transport = new FakeTransport();
        List<String> hookInputs = new ArrayList<>();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .beforeSend(event -> {
                            hookInputs.add(event.toString());
                            @SuppressWarnings("unchecked")
                            Map<String, Object> payload = (Map<String, Object>) event.get("payload");
                            payload.put("message", "token=SYNTHETIC_HOOK_SECRET");
                            return event;
                        }).build(),
                transport,
                System::currentTimeMillis
        );
        client.setContext("user_password", "SYNTHETIC_CONTEXT_SECRET");
        client.captureMessage("Authorization: Bearer SYNTHETIC_CAPTURE_SECRET", LogLevel.ERROR, Map.of("safe", "ok"));
        client.flush().join();

        assertThat(hookInputs).hasSize(1);
        assertThat(hookInputs.get(0)).doesNotContain("SYNTHETIC_");
        assertThat(transport.calls()).hasSize(1);
        Map<String, Object> event = transport.calls().get(0).events().get(0);
        assertThat(event.toString()).doesNotContain("SYNTHETIC_");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        assertThat(payload.get("message")).isEqualTo("token=[REDACTED]");
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) payload.get("attributes");
        assertThat(attributes.get("user_password")).isEqualTo("[REDACTED]");
        assertThat(event).doesNotContainKey("project_token");
    }

    @Test
    void beforeSendDropInvalidFailureAndSamplingAreSafe() {
        FakeTransport transport = new FakeTransport();
        AtomicInteger calls = new AtomicInteger();
        DefaultDebugBundleClient droppingClient = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .beforeSend(event -> {
                            calls.incrementAndGet();
                            return null;
                        })
                        .build(),
                transport,
                System::currentTimeMillis
        );
        droppingClient.captureMessage("drop", LogLevel.ERROR, Map.of());
        droppingClient.flush().join();
        assertThat(calls).hasValue(1);
        assertThat(transport.calls()).isEmpty();

        DefaultDebugBundleClient invalidClient = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .beforeSend(event -> Map.of("invalid", true))
                        .build(),
                transport,
                System::currentTimeMillis
        );
        invalidClient.captureMessage("preserve invalid", LogLevel.ERROR, Map.of());
        invalidClient.flush().join();

        DefaultDebugBundleClient failingClient = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .beforeSend(event -> {
                            throw new IllegalStateException("hook failed");
                        })
                        .build(),
                transport,
                System::currentTimeMillis
        );
        failingClient.captureMessage("preserve failure", LogLevel.ERROR, Map.of());
        failingClient.flush().join();

        assertThat(transport.calls()).hasSize(2);
        assertThat(message(transport.calls().get(0).events().get(0))).isEqualTo("preserve invalid");
        assertThat(message(transport.calls().get(1).events().get(0))).isEqualTo("preserve failure");

        AtomicInteger sampledCalls = new AtomicInteger();
        DefaultDebugBundleClient sampledClient = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .sampleRate(0)
                        .beforeSend(event -> {
                            sampledCalls.incrementAndGet();
                            return event;
                        })
                        .build(),
                transport,
                System::currentTimeMillis
        );
        sampledClient.captureMessage("sampled out", LogLevel.ERROR, Map.of());
        sampledClient.flush().join();
        assertThat(sampledCalls).hasValue(0);
        assertThat(transport.calls()).hasSize(2);
    }

    @Test
    void decoratedRunnablePropagatesRequestCorrelationAcrossAsyncWork() {
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

        DebugBundleRequestScope scope = client.beginRequest(Map.of(
                "method", "GET",
                "path", "/checkout",
                "headers", Map.of(
                        "X-DebugBundle-Trace-Id", "trace-123",
                        "X-Request-Id", "req-123"
                ),
                "query", Map.of()
        ));
        Runnable decorated = client.decorate(() -> client.captureException(new RuntimeException("async failure")));
        client.endRequest(scope);

        decorated.run();
        client.flush().join();

        assertThat(transport.calls()).hasSize(1);

        Map<String, Object> event = transport.calls().get(0).events().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> correlation = (Map<String, Object>) event.get("correlation");

        assertThat(correlation).containsEntry("trace_id", "trace-123");
        assertThat(correlation).containsEntry("request_id", "req-123");
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
        client.flush().join();
        clock.advanceMillis(1_001L);
        client.flush().join();

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
        client.flush().join();
        client.flush().join();

        assertThat(transport.calls()).hasSize(1);

        clock.advanceMillis(1_001L);
        client.flush().join();

        assertThat(transport.calls()).hasSize(2);
    }

    @Test
    void retriesOnlyIndexedRetryableIngestionRejections() {
        ManualClock clock = new ManualClock();
        FakeTransport transport = new FakeTransport(List.of(
                new TransportResponse(
                        202,
                        1_000L,
                        """
                        {"accepted":1,"rejected":1,"errors":[{"index":1,"reason":"rate_limited"}]}
                        """
                ),
                new TransportResponse(202, null, "{\"accepted\":1,\"rejected\":0,\"errors\":[]}")
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
        client.captureMessage("accepted", LogLevel.ERROR, Map.of());
        client.captureMessage("retry", LogLevel.ERROR, Map.of());

        client.flush().join();
        assertThat(client.status()).isEqualTo(DebugBundleStatus.DEGRADED);
        assertThat(client.lastEventAt()).isPresent();

        clock.advanceMillis(1_001L);
        client.flush().join();
        assertThat(transport.calls().get(1).events()).hasSize(1);
        assertThat(transport.calls().get(1).events().get(0).get("payload"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("message", "retry");
        assertThat(client.status()).isEqualTo(DebugBundleStatus.HEALTHY);
    }

    @Test
    void allTerminalRejectionsDoNotAdvanceDeliveryState() {
        FakeTransport transport = new FakeTransport(List.of(
                new TransportResponse(
                        202,
                        null,
                        """
                        {"accepted":0,"rejected":1,"errors":[{"index":0,"reason":"capture_policy_rejected"}]}
                        """
                )
        ));
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("checkout-api")
                        .environment("production")
                        .build(),
                transport,
                System::currentTimeMillis
        );
        client.captureMessage("terminal", LogLevel.ERROR, Map.of());

        client.flush().join();
        client.flush().join();

        assertThat(client.status()).isEqualTo(DebugBundleStatus.DISCONNECTED);
        assertThat(client.lastEventAt()).isEmpty();
        assertThat(transport.calls()).hasSize(1);
    }

    @Test
    void inconsistentAcknowledgementRetainsFullBatch() {
        ManualClock clock = new ManualClock();
        FakeTransport transport = new FakeTransport(List.of(
                new TransportResponse(202, 1_000L, "{\"accepted\":1,\"rejected\":0,\"errors\":[]}"),
                new TransportResponse(202, null, "{\"accepted\":2,\"rejected\":0,\"errors\":[]}")
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
        client.captureMessage("first", LogLevel.ERROR, Map.of());
        client.captureMessage("second", LogLevel.ERROR, Map.of());

        client.flush().join();
        assertThat(client.status()).isEqualTo(DebugBundleStatus.DEGRADED);
        assertThat(client.lastEventAt()).isEmpty();

        clock.advanceMillis(1_001L);
        client.flush().join();
        assertThat(transport.calls().get(1).events()).hasSize(2);
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
        client.flush().join();
        clock.advanceMillis(300_001L);
        client.flush().join();

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
        assertThatCode(() -> client.flush().join()).doesNotThrowAnyException();
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
        client.flush().join();

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
        client.flush().join();

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
        client.flush().join();

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
        client.flush().join();

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
        client.flush().join();

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
        client.flush().join();

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
        Map<String, Object> logPayload = (Map<String, Object>) events.stream()
                .filter(event -> "log_event".equals(event.get("event_type")))
                .findFirst().orElseThrow().get("payload");
        assertThat(logPayload).containsEntry("message", "error raised");
        assertThat(logPayload).containsEntry("level", "error");

        @SuppressWarnings("unchecked")
        Map<String, Object> requestPayload = (Map<String, Object>) events.stream()
                .filter(event -> "request_event".equals(event.get("event_type")))
                .findFirst().orElseThrow().get("payload");
        assertThat(requestPayload).containsEntry("method", "GET");
        assertThat(requestPayload).containsEntry("path", "/orders");
        assertThat(requestPayload).containsEntry("response_status", 503);
        assertThat(requestPayload).containsEntry("duration_ms", 45);
        assertThat(requestPayload).doesNotContainKey("attributes");
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

        List<RuntimeException> retained = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            RuntimeException failure = new RuntimeException("duplicate checkout failure");
            retained.add(failure);
            client.captureException(failure);
        }
        client.flush().join();
        java.lang.ref.Reference.reachabilityFence(retained);

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
        // Caller admission deliberately sheds under queue-lock contention. Exercise
        // the tracker directly so all eleven inputs reach the loop threshold.
        EventSuppressionTracker tracker = new EventSuppressionTracker();
        String key = "recursive failure";
        long now = SUPPRESSION_TEST_START_MS;
        for (int index = 0; index < 11; index++) {
            assertThat(tracker.shouldCapture(key, now)).isEqualTo(index < 3);
        }
        assertThat(tracker.drainAggregates(now)).singleElement()
                .extracting(EventSuppressionTracker.SuppressionAggregate::suppressedCount)
                .isEqualTo(8);

        now += 30_000L;
        assertThat(tracker.shouldCapture(key, now)).isFalse();
        assertThat(tracker.shouldCapture(key, now)).isFalse();
        assertThat(tracker.drainAggregates(now)).singleElement()
                .extracting(EventSuppressionTracker.SuppressionAggregate::suppressedCount)
                .isEqualTo(2);

        now += 61_000L;
        assertThat(tracker.shouldCapture(key, now)).isTrue();
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
        client.flush().join();

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

        @Test
        void localOnlyModeRemainsUsableWithoutProjectToken(@TempDir Path tempDir) throws Exception {
                DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                                DebugBundleConfig.builder()
                                                .service("checkout-api")
                                                .environment("production")
                                                .projectMode("local-only")
                                                .localEventsDir(tempDir.toString())
                                                .build()
                );

                client.captureMessage("local-only event", LogLevel.WARNING, Map.of("tenant", "acme"));
                client.flush().join();

                assertThat(client.status()).isEqualTo(DebugBundleStatus.HEALTHY);
                try (var files = Files.list(tempDir)) {
                        List<Path> writtenFiles = files.toList();
                        assertThat(writtenFiles).hasSize(1);
                        assertThat(Files.readString(writtenFiles.get(0))).contains("local-only event");
                }
        }

    @SuppressWarnings("unchecked")
    private static String message(Map<String, Object> event) {
        return (String) ((Map<String, Object>) event.get("payload")).get("message");
    }
}

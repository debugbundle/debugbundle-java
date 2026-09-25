package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DebugBundleCaptureSafetyTest {
    private static final String TOKEN = "dbundle_proj_test";

    @Test
    void filteredLogsNeverInvokeTheEventHook() {
        AtomicInteger hookCalls = new AtomicInteger();
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder()
                        .projectToken(TOKEN)
                        .logLevel(LogLevel.WARNING)
                        .beforeSend(event -> {
                            hookCalls.incrementAndGet();
                            return event;
                        })
                        .build(),
                request -> new TransportResponse(202, null)
        );
        try {
            long started = System.nanoTime();
            for (int index = 0; index < 10_000; index++) {
                client.captureLog("filtered INFO " + index, LogLevel.INFO, Map.of("index", index));
            }
            long elapsed = System.nanoTime() - started;
            assertThat(hookCalls).hasValue(0);
            // The pre-fix capture path took about 18 seconds for this same burst.
            assertThat(elapsed).isLessThan(TimeUnit.SECONDS.toNanos(2));
        } finally {
            client.close();
        }
    }

    @Test
    void filteredLogsDoNotScanLargeRejectedMessages() {
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder().projectToken(TOKEN).logLevel(LogLevel.WARNING).build(),
                request -> new TransportResponse(202, null)
        );
        try {
            String largeBlankMessage = " ".repeat(4 * 1024 * 1024);
            long started = System.nanoTime();
            for (int index = 0; index < 10_000; index++) {
                if ((index & 1) == 0) client.captureLog(largeBlankMessage, LogLevel.INFO);
                else client.captureMessage(largeBlankMessage, LogLevel.INFO, Map.of());
            }
            assertThat(System.nanoTime() - started).isLessThan(TimeUnit.SECONDS.toNanos(2));
            assertThat(client.pendingEventCount()).isZero();
        } finally {
            client.close();
        }
    }

    @Test
    void fullErrorQueueRejectsLargeMessagesBeforeScanningTheirContents() {
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder()
                        .projectToken(TOKEN)
                        .environment("local")
                        .logLevel(LogLevel.WARNING)
                        .batchSize(2_000)
                        .flushInterval(Duration.ofMinutes(5))
                        .build(),
                request -> new TransportResponse(429, 300_000L)
        );
        try {
            for (int index = 0; index < 1_000; index++) {
                client.captureLog("error " + index, LogLevel.ERROR);
            }
            assertThat(client.pendingEventCount()).isEqualTo(1_000);
            String largeBlankMessage = " ".repeat(4 * 1024 * 1024);
            long started = System.nanoTime();
            for (int index = 0; index < 10_000; index++) {
                if ((index & 1) == 0) client.captureLog(largeBlankMessage, LogLevel.ERROR);
                else client.captureMessage(largeBlankMessage, LogLevel.ERROR, Map.of());
            }
            assertThat(System.nanoTime() - started).isLessThan(TimeUnit.SECONDS.toNanos(2));
            assertThat(client.pendingEventCount()).isEqualTo(1_000);
        } finally {
            client.close();
        }
    }

    @Test
    void acceptedOversizedLogKeepsTheExistingPrivacyPlaceholder() {
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder().projectToken(TOKEN).environment("local").build(),
                transport
        );
        try {
            client.captureLog("private".repeat(4_000), LogLevel.ERROR);
            client.flush().join();
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) transport.calls().get(0).events().get(0).get("payload");
            assertThat(payload.get("message")).isEqualTo("[REDACTED]");
        } finally {
            client.close();
        }
    }

    @Test
    void acceptedExceptionBurstStaysWithinTheCallerBudget() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder().projectToken(TOKEN).environment("local")
                        .batchSize(1_000).flushInterval(Duration.ofMinutes(5))
                        .beforeSend(event -> {
                            entered.countDown();
                            try { release.await(3, TimeUnit.SECONDS); }
                            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                            return event;
                        }).build(),
                request -> new TransportResponse(202, null)
        );
        try {
            // Hold the real worker so the accepted burst has a deterministic
            // ownership baseline and no concurrent snapshot admission races.
            client.captureLog("worker anchor", LogLevel.ERROR);
            client.flush();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            List<Throwable> errors = new ArrayList<>();
            for (int index = 0; index < 350; index++) errors.add(new IllegalStateException("failure " + index));
            long started = System.nanoTime();
            for (Throwable error : errors) client.captureException(error);
            long elapsed = System.nanoTime() - started;
            assertThat(client.pendingEventCount()).isEqualTo(351);
            assertThat(elapsed).as("350 accepted exception captures took %d ms", TimeUnit.NANOSECONDS.toMillis(elapsed))
                    .isLessThan(TimeUnit.SECONDS.toNanos(1));
        } finally {
            release.countDown();
            client.close();
        }
    }

    @Test
    void acceptedLogsWithConfiguredPrivacyStayWithinTheCallerBudget() {
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder().projectToken(TOKEN).environment("local")
                        .redactFields(List.of("account_pin", "internal_credential", "session_secret",
                                "customer_token", "auth_header", "billing_reference", "trace_key", "private_note"))
                        .batchSize(1_000).flushInterval(Duration.ofMinutes(5)).build(),
                request -> new TransportResponse(202, null)
        );
        try {
            long started = System.nanoTime();
            for (int index = 0; index < 1_000; index++) {
                client.captureLog("warning " + index, LogLevel.WARNING,
                        Map.of("route", "/chart", "index", index));
            }
            long elapsed = System.nanoTime() - started;
            assertThat(client.pendingEventCount()).isEqualTo(1_000);
            assertThat(elapsed).as("1,000 accepted configured log captures took %d ms",
                    TimeUnit.NANOSECONDS.toMillis(elapsed)).isLessThan(TimeUnit.SECONDS.toNanos(2));
        } finally {
            client.close();
        }
    }

    @Test
    void optedInInfoBreadcrumbsStayLocalUntilTheirRequestException() {
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder()
                        .projectToken(TOKEN)
                        .environment("local")
                        .logLevel(LogLevel.WARNING)
                        .infoBreadcrumbs(true)
                        .build(),
                transport
        );
        try {
            DebugBundleRequestScope request = client.beginRequest(Map.of("path", "/chart"));
            for (int index = 0; index < 10_000; index++) {
                client.captureLog("chart step " + index, LogLevel.INFO);
            }
            client.flush().join();
            assertThat(transport.calls()).isEmpty();

            client.captureException(new IllegalStateException("chart failed"));
            client.endRequest(request);
            client.flush().join();

            assertThat(transport.calls()).hasSize(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) transport.calls().get(0).events().get(0).get("payload");
            @SuppressWarnings("unchecked")
            Map<String, Object> probes = (Map<String, Object>) payload.get("probe_data");
            assertThat((java.util.List<?>) probes.get("items")).hasSizeBetween(1, 20);
            assertThat(transport.calls().get(0).events().get(0).toString()).contains("chart step");
        } finally {
            client.close();
        }
    }

    @Test
    void heldSenderDoesNotHoldApplicationCaptureThreads() throws Exception {
        CountDownLatch sendEntered = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        DebugBundleTransport blockedTransport = request -> {
            sendEntered.countDown();
            try {
                releaseSend.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new TransportResponse(202, null);
        };
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder()
                        .projectToken(TOKEN)
                        .logLevel(LogLevel.WARNING)
                        .batchSize(1)
                        .build(),
                blockedTransport
        );
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<?> retainedCapture = callers.submit(() -> client.captureLog("root error", LogLevel.ERROR));
            assertThat(sendEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<?> filteredCapture = callers.submit(() -> client.captureLog("filtered", LogLevel.INFO));

            assertThatCode(() -> retainedCapture.get(250, TimeUnit.MILLISECONDS)).doesNotThrowAnyException();
            assertThatCode(() -> filteredCapture.get(250, TimeUnit.MILLISECONDS)).doesNotThrowAnyException();
        } finally {
            releaseSend.countDown();
            callers.shutdownNow();
            client.close();
        }
    }

    @Test
    void slowBeforeSendHookDoesNotRunOnApplicationCaller() throws Exception {
        CountDownLatch hookEntered = new CountDownLatch(1);
        CountDownLatch releaseHook = new CountDownLatch(1);
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder()
                        .projectToken(TOKEN)
                        .environment("local")
                        .batchSize(1)
                        .beforeSend(event -> {
                            hookEntered.countDown();
                            try {
                                releaseHook.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            return event;
                        })
                        .build(),
                request -> new TransportResponse(202, null)
        );
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> capture = caller.submit(() -> client.captureLog("error", LogLevel.ERROR));
            assertThat(hookEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatCode(() -> capture.get(250, TimeUnit.MILLISECONDS)).doesNotThrowAnyException();
        } finally {
            releaseHook.countDown();
            caller.shutdownNow();
            client.close();
        }
    }

    @Test
    void concurrentExplicitFlushCallsDoNotAccumulateUnboundedWaiters() throws Exception {
        CountDownLatch sendEntered = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder().projectToken(TOKEN).environment("local").batchSize(1).build(),
                request -> {
                    sendEntered.countDown();
                    try {
                        releaseSend.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return new TransportResponse(202, null);
                }
        );
        try {
            client.captureLog("retain", LogLevel.ERROR);
            assertThat(sendEntered.await(2, TimeUnit.SECONDS)).isTrue();
            for (int index = 0; index < 10_000; index++) client.flush();

            var deliveryField = DefaultDebugBundleClient.class.getDeclaredField("delivery");
            deliveryField.setAccessible(true);
            Object delivery = deliveryField.get(client);
            var waitersField = EventDeliveryWorker.class.getDeclaredField("flushWaiters");
            waitersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentLinkedQueue<?> waiters = (ConcurrentLinkedQueue<?>) waitersField.get(delivery);
            assertThat(waiters.size()).isLessThanOrEqualTo(64);
            var executorField = EventDeliveryWorker.class.getDeclaredField("executor");
            executorField.setAccessible(true);
            ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) executorField.get(delivery);
            assertThat(executor.getQueue().size()).isLessThanOrEqualTo(2);
        } finally {
            releaseSend.countDown();
            client.close();
        }
    }

    @Test
    void closeCompletesConcurrentFlushesDuringHeldDelivery() throws Exception {
        CountDownLatch sendEntered = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder().projectToken(TOKEN).environment("local").batchSize(1).build(),
                request -> {
                    sendEntered.countDown();
                    try {
                        releaseSend.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return new TransportResponse(202, null);
                }
        );
        ExecutorService callers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            client.captureLog("retain", LogLevel.ERROR);
            assertThat(sendEntered.await(2, TimeUnit.SECONDS)).isTrue();
            List<Future<List<CompletableFuture<Void>>>> tasks = new ArrayList<>();
            for (int caller = 0; caller < 8; caller++) {
                tasks.add(callers.submit(() -> {
                    start.await();
                    List<CompletableFuture<Void>> results = new ArrayList<>();
                    for (int index = 0; index < 100; index++) results.add(client.flush());
                    return results;
                }));
            }
            start.countDown();
            client.close();
            for (Future<List<CompletableFuture<Void>>> task : tasks) {
                for (CompletableFuture<Void> result : task.get(2, TimeUnit.SECONDS)) {
                    assertThat(result).isCompleted();
                }
            }
        } finally {
            releaseSend.countDown();
            callers.shutdownNow();
            client.close();
        }
    }

    @Test
    void flushRequestedDuringAnActiveSendWaitsForItsOwnBufferedEvent() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicInteger sends = new AtomicInteger();
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder().projectToken(TOKEN).environment("local").batchSize(100).build(),
                request -> {
                    if (sends.incrementAndGet() == 1) {
                        firstEntered.countDown();
                        try { releaseFirst.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    } else {
                        secondEntered.countDown();
                        try { releaseSecond.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }
                    return new TransportResponse(202, null);
                }
        );
        try {
            client.captureLog("first", LogLevel.ERROR);
            CompletableFuture<Void> firstFlush = client.flush();
            assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
            client.captureLog("second", LogLevel.ERROR);
            CompletableFuture<Void> secondFlush = client.flush();
            releaseFirst.countDown();
            assertThat(secondEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(firstFlush).isCompleted();
            assertThat(secondFlush).isNotCompleted();
            releaseSecond.countDown();
            secondFlush.get(2, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            client.close();
        }
    }

    @Test
    void repeatedRateLimitsDoNotGrowThePendingQueueWithoutBound() throws Exception {
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder()
                        .projectToken(TOKEN)
                        .logLevel(LogLevel.ERROR)
                        .batchSize(25)
                        .build(),
                request -> new TransportResponse(429, 300_000L)
        );
        try {
            for (int index = 0; index < 2_000; index++) {
                client.captureLog("unique error " + index, LogLevel.ERROR);
            }

            assertThat(client.pendingEventCount()).isLessThanOrEqualTo(1_000);
        } finally {
            client.close();
        }
    }

    @Test
    void retainedEventsHaveAByteBudgetEvenWhenTransportIsRateLimited() {
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder()
                        .projectToken(TOKEN)
                        .environment("local")
                        .batchSize(2_000)
                        .flushInterval(Duration.ofMinutes(5))
                        .build(),
                request -> new TransportResponse(429, 300_000L)
        );
        try {
            String body = "x".repeat(15_000);
            for (int index = 0; index < 600; index++) {
                client.captureLog(index + body, LogLevel.ERROR);
            }
            assertThat(client.pendingEventCount()).isLessThan(600);
            assertThat(client.pendingBytes()).isLessThanOrEqualTo(8 * 1024 * 1024);
        } finally {
            client.close();
        }
    }

    @Test
    void errorEvictsLowerPriorityEventWhenPendingQueueIsFull() {
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder()
                        .projectToken(TOKEN)
                        .environment("local")
                        .logLevel(LogLevel.WARNING)
                        .batchSize(2_000)
                        .flushInterval(Duration.ofMinutes(5))
                        .build(),
                request -> new TransportResponse(429, 300_000L)
        );
        try {
            for (int index = 0; index < 1_100; index++) {
                client.captureLog("warning " + index, LogLevel.WARNING);
            }
            client.captureLog("priority exception", LogLevel.ERROR);
            assertThat(client.pendingEventCount()).isLessThanOrEqualTo(1_000);
            assertThat(client.pendingHighPriorityCount()).isEqualTo(1);
        } finally {
            client.close();
        }
    }

    @Test
    void incidentEligibleRequestFailuresTakePriorityOverQueuedWarnings() {
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder()
                        .projectToken(TOKEN)
                        .environment("local")
                        .logLevel(LogLevel.WARNING)
                        .batchSize(2_000)
                        .flushInterval(Duration.ofMinutes(5))
                        .build(),
                request -> new TransportResponse(429, 300_000L)
        );
        try {
            for (int index = 0; index < 1_100; index++) {
                client.captureLog("warning " + index, LogLevel.WARNING);
            }
            assertThat(client.pendingEventCount()).isEqualTo(1_000);
            client.captureRequest(Map.of("method", "GET", "path", "/failed"), Map.of("status_code", 503), Map.of());
            client.captureRequest(Map.of("method", "GET", "path", "/throttled"), Map.of("status_code", 429), Map.of());
            assertThat(client.pendingEventCount()).isEqualTo(1_000);
            assertThat(client.pendingHighPriorityCount()).isEqualTo(2);
        } finally {
            client.close();
        }
    }

    @Test
    void fullQueueRejectsWarningBeforeTouchingApplicationContext() {
        AtomicInteger contextReads = new AtomicInteger();
        Map<String, Object> context = new AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                contextReads.incrementAndGet();
                return Set.of(Map.entry("safe", "value"));
            }
        };
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder()
                        .projectToken(TOKEN)
                        .environment("local")
                        .logLevel(LogLevel.WARNING)
                        .batchSize(2_000)
                        .flushInterval(Duration.ofMinutes(5))
                        .build(),
                request -> new TransportResponse(429, 300_000L)
        );
        try {
            for (int index = 0; index < 1_100; index++) {
                client.captureLog("warning " + index, LogLevel.WARNING);
            }
            assertThat(client.pendingEventCount()).isEqualTo(1_000);
            long started = System.nanoTime();
            for (int index = 0; index < 10_000; index++) {
                client.captureLog("discarded " + index, LogLevel.WARNING, context);
            }
            long elapsed = System.nanoTime() - started;
            client.captureLog("discarded", LogLevel.WARNING, context);
            assertThat(contextReads).hasValue(0);
            assertThat(elapsed).isLessThan(TimeUnit.SECONDS.toNanos(2));
        } finally {
            client.close();
        }
    }

    @Test
    void allErrorQueueRejectsBurstWithoutRenderingMoreExceptions() {
        AtomicInteger messageReads = new AtomicInteger();
        RuntimeException hostile = new RuntimeException("synthetic") {
            @Override
            public String getMessage() {
                messageReads.incrementAndGet();
                return "discarded";
            }
        };
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder()
                        .projectToken(TOKEN)
                        .environment("local")
                        .logLevel(LogLevel.ERROR)
                        .batchSize(2_000)
                        .flushInterval(Duration.ofMinutes(5))
                        .build(),
                request -> new TransportResponse(429, 300_000L)
        );
        try {
            for (int index = 0; index < 1_100; index++) {
                client.captureLog("error " + index, LogLevel.ERROR);
            }
            assertThat(client.pendingEventCount()).isEqualTo(1_000);
            long started = System.nanoTime();
            for (int index = 0; index < 100_000; index++) {
                client.captureException(hostile);
            }
            long elapsed = System.nanoTime() - started;
            assertThat(messageReads).hasValue(0);
            assertThat(client.pendingEventCount()).isEqualTo(1_000);
            assertThat(elapsed).isLessThan(TimeUnit.SECONDS.toNanos(2));
        } finally {
            client.close();
        }
    }

    @Test
    void hostileExceptionMessageDoesNotEscapeThePublicClient() {
        RuntimeException hostile = new RuntimeException("synthetic") {
            @Override
            public String getMessage() {
                throw new IllegalStateException("synthetic getter failure");
            }
        };
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder().projectToken(TOKEN).build(),
                request -> new TransportResponse(202, null)
        );
        try {
            assertThatCode(() -> client.captureException(hostile)).doesNotThrowAnyException();
        } finally {
            client.close();
        }
    }

    @Test
    void hostileErrorGetterAndProbeSupplierRemainInsideSdkBoundary() {
        RuntimeException hostile = new RuntimeException("synthetic") {
            @Override
            public String getMessage() {
                throw new AssertionError("synthetic error getter");
            }
        };
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder().projectToken(TOKEN).environment("local").build(),
                request -> new TransportResponse(202, null)
        );
        try {
            assertThatCode(() -> client.captureException(hostile)).doesNotThrowAnyException();
            assertThatCode(() -> client.probe("failure", () -> {
                throw new AssertionError("synthetic supplier");
            })).doesNotThrowAnyException();
        } finally {
            client.close();
        }
    }

    @Test
    void correlationIsPrivacyProtectedBeforeQueueRetentionAndTransport() {
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder().projectToken(TOKEN).environment("local").build(),
                transport
        );
        try {
            client.captureLog("failure", LogLevel.ERROR, Map.of(
                    "correlation", Map.of("trace_id", "Authorization: Bearer SYNTHETIC_CORRELATION_SECRET")
            ));
            client.flush().join();
            assertThat(transport.calls()).hasSize(1);
            assertThat(transport.calls().get(0).events().toString())
                    .doesNotContain("SYNTHETIC_CORRELATION_SECRET");
        } finally {
            client.close();
        }
    }

    @Test
    void hostileLogContextDoesNotEscapeThePublicClient() {
        Map<String, Object> hostileContext = new AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                throw new IllegalStateException("synthetic context failure");
            }
        };
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder().projectToken(TOKEN).build(),
                request -> new TransportResponse(202, null)
        );
        try {
            assertThatCode(() -> client.captureLog("accepted warning", LogLevel.WARNING, hostileContext))
                    .doesNotThrowAnyException();
        } finally {
            client.close();
        }
    }

    @Test
    void hostileRequestAndMessageContextDoNotEscapeThePublicClient() {
        Map<String, Object> hostileContext = new AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                throw new IllegalStateException("synthetic request context failure");
            }
        };
        DefaultDebugBundleClient client = client(
                DebugBundleConfig.builder().projectToken(TOKEN).build(),
                request -> new TransportResponse(202, null)
        );
        try {
            assertThatCode(() -> client.captureRequest(Map.of("method", "GET"), Map.of(), hostileContext))
                    .doesNotThrowAnyException();
            assertThatCode(() -> client.captureMessage("accepted warning", LogLevel.WARNING, hostileContext))
                    .doesNotThrowAnyException();
        } finally {
            client.close();
        }
    }

    private static DefaultDebugBundleClient client(DebugBundleConfig config, DebugBundleTransport transport) {
        return new DefaultDebugBundleClient(config, transport, System::currentTimeMillis);
    }
}

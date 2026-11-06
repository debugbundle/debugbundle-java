package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

class StackContinuityTest {
    @Test
    void identityClassificationDoesNotInvokeApplicationMapMethods() {
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var context = new java.util.HashMap<String, Object>() {
            @Override public boolean containsKey(Object key) { reads.incrementAndGet(); throw new AssertionError("host accessor"); }
        };
        context.put("safe", "synthetic");
        var transport = new FakeTransport();
        try (var client = new DefaultDebugBundleClient(DebugBundleConfig.builder().projectToken("synthetic-token")
                .batchSize(1000).flushInterval(Duration.ofHours(1)).build(), transport, System::currentTimeMillis)) {
            client.captureException(new IllegalStateException("native failure"), context);
            client.flush().join();
            assertThat(reads).hasValue(0);
            assertThat(transport.calls().stream().flatMap(call -> call.events().stream()).toList()).hasSize(1);
        }
    }

    @Test
    void concurrentOrphanDrainKeepsEverySummaryBoundedAndAdmissible() throws Exception {
        var transport = new FakeTransport();
        try (var client = new DefaultDebugBundleClient(DebugBundleConfig.builder().projectToken("synthetic-token")
                .logLevel(LogLevel.ERROR).batchSize(1000).flushInterval(Duration.ofHours(1)).build(), transport, System::currentTimeMillis)) {
            var handler = new DebugBundleJulHandler(() -> client);
            var producer = Executors.newSingleThreadExecutor();
            try {
                var completed = producer.submit(() -> {
                    for (int i = 0; i < 1000; i++) line(handler, 7, "\tat app.Orphan.run(Unknown Source)");
                });
                for (int i = 0; i < 10; i++) handler.drainPendingStacks().get(2, TimeUnit.SECONDS);
                completed.get(3, TimeUnit.SECONDS);
                handler.drainPendingStacks().join(); client.flush().join();
                var events = transport.calls().stream().flatMap(call -> call.events().stream())
                        .filter(event -> event.get("event_type").equals("log_event")).toList();
                assertThat(events).isNotEmpty().allSatisfy(event -> {
                    var payload = (Map<?, ?>) event.get("payload");
                    var attributes = (Map<?, ?>) payload.get("attributes");
                    assertThat(payload.get("level")).isEqualTo("error");
                    if (Boolean.TRUE.equals(attributes.get("java_stack_continuation_context")))
                        assertThat((java.util.List<?>) attributes.get("stack_continuations")).hasSizeLessThanOrEqualTo(16);
                    else assertThat(payload.get("message")).isEqualTo("\tat app.Orphan.run(Unknown Source)");
                });
            } finally { producer.shutdownNow(); handler.close(); }
        }
    }

    @Test
    void nativeAndFilteredRecordsEndTextTraceContinuity() {
        var transport = new FakeTransport();
        try (var client = new DefaultDebugBundleClient(DebugBundleConfig.builder().projectToken("synthetic-token")
                .logLevel(LogLevel.ERROR).batchSize(1000).flushInterval(Duration.ofHours(1)).build(), transport, System::currentTimeMillis)) {
            var handler = new DebugBundleJulHandler(() -> client);
            try {
                for (boolean nativeError : new boolean[] {true, false}) {
                    line(handler, 7, "app.TextException: before boundary " + nativeError);
                    var boundary = new LogRecord(nativeError ? Level.SEVERE : Level.INFO, "independent record");
                    boundary.setLoggerName("stderr"); boundary.setLongThreadID(7);
                    if (nativeError) boundary.setThrown(new IllegalStateException("native boundary"));
                    handler.publish(boundary);
                    line(handler, 7, "\tat app.Unmatched.run(Unknown Source)");
                }
                handler.drainPendingStacks().join(); client.flush().join();
                var events = transport.calls().stream().flatMap(call -> call.events().stream()).toList();
                assertThat(events).filteredOn(event -> event.get("event_type").equals("backend_exception"))
                        .hasSize(3).allSatisfy(event -> assertThat(event.toString()).doesNotContain("Unmatched.run"));
                assertThat(events.toString()).contains("unmatched_count=2");
            } finally { handler.close(); }
        }
    }

    @Test
    void repeatedNativeRecordsAndRejectedFirstCaptureRetainGenuineFailures() throws Exception {
        for (boolean dropFirst : new boolean[] {false, true}) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var transport = new FakeTransport();
            var config = DebugBundleConfig.builder().projectToken("synthetic-token").batchSize(1000)
                    .flushInterval(Duration.ofHours(1));
            if (dropFirst) config.beforeSend(event -> calls.getAndIncrement() == 0 ? null : event);
            try (var client = new DefaultDebugBundleClient(config.build(), transport, System::currentTimeMillis)) {
                var request = client.beginRequest(Map.of("method", "GET", "path", "/"));
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                class HeldException extends IllegalStateException {
                    @Override public String getMessage() {
                        entered.countDown();
                        try { release.await(3, TimeUnit.SECONDS); }
                        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                        return "reused native object";
                    }
                }
                var error = new HeldException();
                try {
                    client.captureException(error, Map.of("logger", "synthetic"));
                    // Hold deferred projection outside the queue lock so this tests
                    // identity admission independently of documented contention drops.
                    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                    client.captureException(error, dropFirst ? Map.of() : Map.of("logger", "synthetic"));
                } finally { release.countDown(); }
                client.flush().join(); client.endRequest(request);
                assertThat(transport.calls().stream().flatMap(call -> call.events().stream()).toList())
                        .hasSize(dropFirst ? 1 : 2);
            }
        }
    }

    private static void line(DebugBundleJulHandler handler, long thread, String message) {
        var record = new LogRecord(Level.SEVERE, message);
        record.setLoggerName("stderr"); record.setLongThreadID(thread); handler.publish(record);
    }

    @Test
    void expiredAndCrossRequestContinuationsRemainRedactedUnmatchedEvidence() {
        var clock = new AtomicLong(1777000000000L);
        var transport = new FakeTransport();
        var config = DebugBundleConfig.builder().projectToken("synthetic-token").logLevel(LogLevel.ERROR).batchSize(1000)
                .flushInterval(Duration.ofHours(1)).build();
        try (var client = new DefaultDebugBundleClient(config, transport, clock::get)) {
            var handler = new DebugBundleJulHandler(() -> client, new JavaStackTraceAssembler(clock::get));
            try {
                var first = client.beginRequest(Map.of("method", "GET", "path", "/first"));
                line(handler, 7, "java.lang.IllegalStateException: first");
                line(handler, 7, "\tat app.First.run(First.java:10)");
                client.endRequest(first);
                var second = client.beginRequest(Map.of("method", "GET", "path", "/second"));
                line(handler, 7, "\tat app.Unmatched.run(Unknown Source)");
                line(handler, 7, "java.lang.IllegalArgumentException: second");
                clock.addAndGet(20_001);
                line(handler, 7, "\tat app.Late.run(Native Method)");
                line(handler, 7, "\tSuppressed: app.CustomFailure: token=synthetic-secret");
                client.endRequest(second);
                handler.drainPendingStacks().join(); client.flush().join();
                var events = transport.calls().stream().flatMap(call -> call.events().stream()).toList();
                assertThat(events).filteredOn(event -> event.get("event_type").equals("backend_exception")).hasSize(2)
                        .allSatisfy(event -> assertThat(event.toString()).doesNotContain("Unmatched", "Late.run"));
                assertThat(events.toString()).contains("Unmatched.run", "Late.run", "unmatched_count=3")
                        .doesNotContain("synthetic-secret");
            } finally { handler.close(); }
        }
    }

    @Test
    void nativeLoggerAndMiddlewareCountOncePerRequestButDistinctErrorsRemainDistinct() {
        var transport = new FakeTransport();
        try (var client = new DefaultDebugBundleClient(DebugBundleConfig.builder().projectToken("synthetic-token")
                .batchSize(1000).flushInterval(Duration.ofHours(1)).build(), transport, System::currentTimeMillis)) {
            var error = new IllegalStateException("same object");
            for (int i = 0; i < 2; i++) {
                var request = client.beginRequest(Map.of("method", "GET", "path", "/"));
                client.captureException(error, Map.of("logger", "synthetic"));
                client.captureException(error);
                client.captureException(new IllegalArgumentException("distinct " + i));
                client.endRequest(request);
            }
            client.flush().join();
            assertThat(transport.calls().stream().flatMap(call -> call.events().stream()).toList()).hasSize(4);
        }
    }

    @Test
    void saturatedAssemblerCannotWaitForUnavailableTransport() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        DebugBundleTransport transport = request -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return new TransportResponse(503, null);
        };
        try (var client = new DefaultDebugBundleClient(DebugBundleConfig.builder().projectToken("synthetic-token")
                .batchSize(1).build(), transport, System::currentTimeMillis)) {
            var handler = new DebugBundleJulHandler(() -> client);
            var callers = Executors.newSingleThreadExecutor();
            try {
                client.captureLog("ordinary failure", LogLevel.ERROR);
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                callers.submit(() -> {
                    for (int i = 0; i < 300; i++) {
                        line(handler, i, "app.SyntheticException: failure " + i);
                        line(handler, i, "\tat loader/module-name@1/app.Service.run(Service.java:42)");
                    }
                    handler.drainPendingStacks().join();
                }).get(3, TimeUnit.SECONDS);
            } finally { release.countDown(); callers.shutdownNow(); handler.close(); }
        }
    }
}

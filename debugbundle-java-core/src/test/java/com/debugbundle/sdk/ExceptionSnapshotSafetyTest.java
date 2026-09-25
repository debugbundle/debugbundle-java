package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExceptionSnapshotSafetyTest {
    @Test
    void blockedOverrideNeverRunsOnCaptureCallerAndOriginalEvidenceSurvives() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger callerReads = new AtomicInteger();
        Thread caller = Thread.currentThread();
        class WaitingException extends RuntimeException {
            WaitingException() { super("Authorization: Bearer SYNTHETIC_PRIVATE", new IllegalStateException("original cause")); }
            @Override public String getMessage() {
                if (Thread.currentThread() == caller) callerReads.incrementAndGet();
                entered.countDown();
                await(release);
                return super.getMessage();
            }
        }
        WaitingException error = new WaitingException();
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = client(transport);
        client.captureLog("warmup", LogLevel.ERROR);
        client.flush().get(3, TimeUnit.SECONDS);
        transport.calls().clear();
        Thread unblock = new Thread(() -> { await(entered); sleep(300); release.countDown(); });
        unblock.start();
        try {
            long start = System.nanoTime();
            client.captureException(error, Map.of("password", "SYNTHETIC_CONTEXT_PRIVATE"));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(200);
            assertThat(callerReads).hasValue(0);
            var drain = client.flush();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(drain.isDone()).isFalse();
            release.countDown();
            drain.get(3, TimeUnit.SECONDS);
            String sent = transport.calls().get(0).events().toString();
            assertThat(sent).contains("original cause", "blockedOverrideNeverRunsOnCaptureCaller")
                    .doesNotContain("SYNTHETIC_PRIVATE", "SYNTHETIC_CONTEXT_PRIVATE");
        } finally {
            release.countDown(); client.close(); unblock.join(1000);
        }
    }

    @Test
    void ordinaryThrowableMonitorCannotBlockPublicCapture() throws Exception {
        RuntimeException error = new RuntimeException("normal failure", new IllegalStateException("normal cause"));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread owner = new Thread(() -> { synchronized (error) { held.countDown(); await(release); } });
        owner.start();
        assertThat(held.await(2, TimeUnit.SECONDS)).isTrue();
        Thread unblock = new Thread(() -> { sleep(300); release.countDown(); });
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = client(transport);
        unblock.start();
        try {
            long start = System.nanoTime();
            client.captureException(error);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(200);
            var drain = client.flush();
            assertThat(drain.isDone()).isFalse();
            release.countDown();
            drain.get(3, TimeUnit.SECONDS);
            assertThat(transport.calls().get(0).events().toString())
                    .contains("normal failure", "normal cause", "ordinaryThrowableMonitorCannotBlockPublicCapture");
        } finally {
            release.countDown(); client.close(); owner.join(1000); unblock.join(1000);
        }
    }

    @Test
    void heldSnapshotKeepsWeakQueueFiniteAndCloseDoesNotWait() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        class WaitingException extends RuntimeException {
            @Override public String getMessage() {
                reads.incrementAndGet(); entered.countDown(); await(release); return "held failure";
            }
        }
        RuntimeException error = new WaitingException();
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = client(transport);
        try {
            client.captureException(error);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            for (int index = 0; index < 1500; index++) client.captureException(error);
            assertThat(client.pendingEventCount()).isEqualTo(1000);
            assertThat(client.pendingBytes()).isLessThanOrEqualTo(8 * 1024 * 1024);
            assertThat(weakHandles(client)).hasSize(1000);
            assertThat(reads).hasValue(1);
            var drain = client.flush();
            long started = System.nanoTime();
            client.close();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(200);
            assertThat(drain.isDone()).isTrue();
            assertThat(client.pendingEventCount()).isZero();
            assertThat(transport.calls()).isEmpty();
        } finally { release.countDown(); client.close(); }
    }

    @Test
    void clearedWeakHandleEmitsExplicitSafeFallback() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        class WaitingException extends RuntimeException {
            @Override public String getMessage() { entered.countDown(); await(release); return "held failure"; }
        }
        RuntimeException first = new WaitingException();
        RuntimeException second = new RuntimeException("SYNTHETIC_PRIVATE_RAW_INPUT");
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = client(transport);
        try {
            client.captureException(first);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            client.captureException(second, Map.of("password", "SYNTHETIC_PRIVATE_CONTEXT"));
            List<WeakReference<?>> handles = weakHandles(client);
            assertThat(handles).hasSize(2);
            assertThat(handles.get(1).get()).isSameAs(second);
            // Deterministic equivalent of GC clearing the non-owning handle.
            handles.get(1).clear();
            release.countDown();
            client.flush().get(3, TimeUnit.SECONDS);
            String sent = transport.calls().get(0).events().toString();
            assertThat(sent).contains("exception details unavailable", "input no longer reachable")
                    .doesNotContain("SYNTHETIC_PRIVATE_RAW_INPUT", "SYNTHETIC_PRIVATE_CONTEXT");
        } finally { release.countDown(); client.close(); }
    }

    @Test
    void expandedHookOutputsAreChargedBeforeLaterHookBlocksAndRetriesDoNotRerunHooks() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger hooks = new AtomicInteger();
        AtomicLong clock = new AtomicLong(1000);
        FakeTransport transport = new FakeTransport(List.of(new TransportResponse(500, null), new TransportResponse(202, null)));
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(DebugBundleConfig.builder()
                .projectToken("dbundle_proj_test").environment("local").batchSize(1000)
                .flushInterval(Duration.ofMinutes(5)).beforeSend(event -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> original = (Map<String, Object>) event.get("payload");
                    if (!String.valueOf(original.get("message")).startsWith("private original")) return event;
                    if (hooks.incrementAndGet() == 100) { entered.countDown(); await(release); }
                    Map<String, Object> replacement = new LinkedHashMap<>(event);
                    replacement.put("event_id", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
                    Map<String, Object> payload = new LinkedHashMap<>(original);
                    payload.put("message", "sanitized replacement");
                    Map<String, Object> attributes = new LinkedHashMap<>();
                    for (int index = 0; index < 20; index++) attributes.put("field" + index, "x".repeat(4096));
                    payload.put("attributes", attributes);
                    replacement.put("payload", payload);
                    return replacement;
                }).build(), transport, clock::get);
        try {
            for (int index = 0; index < 150; index++) client.captureLog("private original " + index, LogLevel.ERROR);
            var drain = client.flush();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(client.pendingBytes()).isBetween(4 * 1024 * 1024, 8 * 1024 * 1024);
            assertThat(client.pendingEventCount()).isLessThan(150);
            release.countDown();
            drain.get(5, TimeUnit.SECONDS);
            assertThat(hooks).hasValue(150);
            assertThat(transport.calls().get(0).events().toString()).doesNotContain("private original");
            clock.addAndGet(2000);
            client.flush().get(5, TimeUnit.SECONDS);
            assertThat(hooks).hasValue(150);
            assertThat(transport.calls().get(1).events()).containsAll(transport.calls().get(0).events());
        } finally { release.countDown(); client.close(); }
    }

    private static List<WeakReference<?>> weakHandles(DefaultDebugBundleClient client) throws Exception {
        Field deliveryField = DefaultDebugBundleClient.class.getDeclaredField("delivery");
        deliveryField.setAccessible(true);
        Object delivery = deliveryField.get(client);
        Field pendingField = EventDeliveryWorker.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        List<?> pending = (List<?>) pendingField.get(delivery);
        List<WeakReference<?>> handles = new ArrayList<>();
        for (Object item : pending) {
            var accessor = item.getClass().getDeclaredMethod("exception");
            accessor.setAccessible(true);
            if (accessor.invoke(item) instanceof WeakReference<?> handle) handles.add(handle);
        }
        return handles;
    }

    private static DefaultDebugBundleClient client(DebugBundleTransport transport) {
        return new DefaultDebugBundleClient(DebugBundleConfig.builder().projectToken("dbundle_proj_test")
                .environment("local").batchSize(1000).flushInterval(Duration.ofMinutes(5)).build(),
                transport, System::currentTimeMillis);
    }
    private static void await(CountDownLatch latch) {
        try { latch.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}

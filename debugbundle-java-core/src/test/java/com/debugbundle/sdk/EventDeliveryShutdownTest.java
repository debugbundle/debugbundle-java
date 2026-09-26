package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EventDeliveryShutdownTest {
    @Test
    void closeDuringAdmissionReleasesQueueAfterOwnerFinishesWithoutWaiting() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeTransport transport = new FakeTransport();
        EventDeliveryWorker worker = new EventDeliveryWorker(
                DebugBundleConfig.builder().projectToken("dbundle_proj_test").batchSize(1000)
                        .flushInterval(Duration.ofHours(1)).build(),
                transport, () -> {
                    entered.countDown();
                    try { release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    return 1000L;
                }, () -> {}, count -> null, event -> event, (event, error) -> event);
        Thread admission = new Thread(() -> worker.offer(Map.of("payload", Map.of("message", "retained")), true));
        admission.start();
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            worker.close();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(200);
            assertThat(worker.flush().isDone()).isTrue();
            release.countDown();
            admission.join(2000);
            assertThat(admission.isAlive()).isFalse();
            // Check actual retained storage, not only the public closed-state counters.
            var pending = EventDeliveryWorker.class.getDeclaredField("pending");
            pending.setAccessible(true);
            assertThat((List<?>) pending.get(worker)).isEmpty();
            assertThat(worker.pendingBytes()).isZero();
            assertThat(worker.pendingCount()).isZero();
            assertThat(transport.calls()).isEmpty();
        } finally {
            release.countDown();
            admission.join(2000);
            worker.close();
        }
    }
}

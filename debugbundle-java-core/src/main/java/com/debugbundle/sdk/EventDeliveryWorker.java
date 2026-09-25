package com.debugbundle.sdk;

import java.time.Instant;
import java.lang.ref.WeakReference;
import java.util.function.BiFunction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/** Owns finite, privacy-safe pending events and all blocking transport calls. */
final class EventDeliveryWorker {
    private static final int MAX_EVENTS = 1_000;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CAPTURE_RESERVATIONS = 64;
    private static final int MAX_FLUSH_WAITERS = 64;

    private final DebugBundleConfig config;
    private final DebugBundleTransport transport;
    private final Supplier<Long> clockMillis;
    private final Runnable beforeFlush;
    private final LongFunction<Map<String, Object>> pressureAggregate;
    private final Function<Map<String, Object>, Map<String, Object>> finalizeEvent;
    private final BiFunction<Map<String, Object>, Throwable, Map<String, Object>> snapshotException;
    private final AtomicBoolean snapshotQueued = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1,
            new DebugBundleThreadFactory("debugbundle-java-flush")
    );
    private final ReentrantLock queueLock = new ReentrantLock();
    private final List<PendingEvent> pending = new ArrayList<>();
    private final AtomicLong pressureDrops = new AtomicLong();
    private final AtomicInteger reservations = new AtomicInteger();
    private final AtomicInteger flushWaiterCount = new AtomicInteger();
    private final AtomicBoolean explicitFlushQueued = new AtomicBoolean();
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> flushWaiters = new ConcurrentLinkedQueue<>();

    private ScheduledFuture<?> flushTask;
    private int pendingBytes;
    private int inFlightCount;
    private int evictableLowPriorityCount;
    private long firstBufferedAtMillis;
    private long nextRetryAtMillis;
    private long nextPressureReportAtMillis;
    private volatile int consecutiveFailures;
    private volatile DebugBundleStatus status = DebugBundleStatus.HEALTHY;
    private volatile Optional<Instant> lastEventAt = Optional.empty();
    private volatile boolean closed;

    EventDeliveryWorker(
            DebugBundleConfig config,
            DebugBundleTransport transport,
            Supplier<Long> clockMillis,
            Runnable beforeFlush,
            LongFunction<Map<String, Object>> pressureAggregate,
            Function<Map<String, Object>, Map<String, Object>> finalizeEvent,
            BiFunction<Map<String, Object>, Throwable, Map<String, Object>> snapshotException
    ) {
        this.config = config;
        this.transport = transport;
        this.clockMillis = clockMillis;
        this.beforeFlush = beforeFlush;
        this.pressureAggregate = pressureAggregate;
        this.finalizeEvent = finalizeEvent;
        this.snapshotException = snapshotException;
        // Cancelled coalesced wakeups must not accumulate behind a held sender.
        executor.setRemoveOnCancelPolicy(true);
    }

    void offer(Map<String, Object> event, boolean incidentPriority) {
        offerInternal(event, true, incidentPriority || isHighPriority(event));
    }

    void offerException(Map<String, Object> shell, Throwable error) {
        if (offerInternal(shell, true, true, new WeakReference<>(error))) scheduleSnapshots();
    }

    private void scheduleSnapshots() {
        if (closed || !snapshotQueued.compareAndSet(false, true)) return;
        try {
            // Coalesce short capture bursts before scanning their snapshots. This
            // avoids needless admission contention while keeping weak-handle delay
            // independent of the much longer transport batching interval.
            executor.schedule(() -> {
                try {
                    if (!closed) snapshotPending();
                } catch (Throwable ignored) {
                    // A hostile accessor stalls at most this one existing daemon worker.
                } finally {
                    snapshotQueued.set(false);
                    queueLock.lock();
                    try {
                        if (!closed && pending.stream().anyMatch(item -> item.exception() != null)) scheduleSnapshots();
                    } finally {
                        queueLock.unlock();
                    }
                }
            }, 10L, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            snapshotQueued.set(false);
        }
    }

    boolean tryReserve(boolean highPriority) {
        if (closed || !queueLock.tryLock()) {
            pressureDrops.incrementAndGet();
            return false;
        }
        try {
            if (reservations.get() >= MAX_CAPTURE_RESERVATIONS) {
                pressureDrops.incrementAndGet();
                return false;
            }
            boolean full = pending.size() + reservations.get() >= MAX_EVENTS || pendingBytes >= MAX_BYTES;
            if (full && (!highPriority || evictableLowPriorityCount <= reservations.get())) {
                pressureDrops.incrementAndGet();
                return false;
            }
            reservations.incrementAndGet();
            return true;
        } finally {
            queueLock.unlock();
        }
    }

    void releaseReservation() {
        reservations.decrementAndGet();
    }

    private boolean offerInternal(Map<String, Object> event, boolean accountDrop, boolean highPriority) {
        return offerInternal(event, accountDrop, highPriority, null);
    }

    private boolean offerInternal(Map<String, Object> event, boolean accountDrop, boolean highPriority,
            WeakReference<Throwable> exception) {
        int bytes = estimateBytes(event);
        if (closed || bytes > MAX_BYTES || !queueLock.tryLock()) {
            if (accountDrop) pressureDrops.incrementAndGet();
            return false;
        }
        try {
            if (closed) return false;
            while (pending.size() >= MAX_EVENTS || pendingBytes + bytes > MAX_BYTES) {
                if (!evictLowerPriority(highPriority)) {
                    if (accountDrop) pressureDrops.incrementAndGet();
                    return false;
                }
            }
            if (pending.isEmpty()) {
                firstBufferedAtMillis = now();
                scheduleFlush(config.flushInterval().toMillis());
            }
            PendingEvent admitted = new PendingEvent(event, bytes, highPriority, false, exception);
            if (highPriority) {
                int insertion = inFlightCount;
                while (insertion < pending.size() && pending.get(insertion).highPriority()) insertion++;
                pending.add(insertion, admitted);
            } else {
                pending.add(admitted);
                evictableLowPriorityCount++;
            }
            pendingBytes += bytes;
            if (pending.size() - inFlightCount >= config.batchSize()
                    || now() - firstBufferedAtMillis >= config.flushInterval().toMillis()) {
                scheduleFlush(1L);
            }
            return true;
        } finally {
            queueLock.unlock();
        }
    }

    private boolean evictLowerPriority(boolean incomingHighPriority) {
        if (!incomingHighPriority) return false;
        for (int index = inFlightCount; index < pending.size(); index++) {
            if (!pending.get(index).highPriority()) {
                pendingBytes -= pending.remove(index).bytes();
                evictableLowPriorityCount--;
                pressureDrops.incrementAndGet();
                return true;
            }
        }
        return false;
    }

    CompletableFuture<Void> flush() {
        if (closed) return CompletableFuture.completedFuture(null);
        while (true) {
            int current = flushWaiterCount.get();
            if (current >= MAX_FLUSH_WAITERS) return CompletableFuture.completedFuture(null);
            if (flushWaiterCount.compareAndSet(current, current + 1)) break;
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        flushWaiters.add(result);
        if (closed) {
            if (flushWaiters.remove(result)) flushWaiterCount.decrementAndGet();
            result.complete(null);
            return result;
        }
        requestExplicitFlush();
        return result;
    }

    private void requestExplicitFlush() {
        if (closed || !explicitFlushQueued.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                // Requests arriving after this snapshot need another worker pass.
                int waitersAtStart = flushWaiters.size();
                try {
                    flushSafely();
                } finally {
                    completeFlushWaiters(waitersAtStart);
                    explicitFlushQueued.set(false);
                    if (!flushWaiters.isEmpty()) requestExplicitFlush();
                }
            });
        } catch (Throwable ignored) {
            explicitFlushQueued.set(false);
            completeFlushWaiters(Integer.MAX_VALUE);
        }
    }

    private void completeFlushWaiters(int limit) {
        CompletableFuture<Void> waiter;
        for (int index = 0; index < limit && (waiter = flushWaiters.poll()) != null; index++) {
            flushWaiterCount.decrementAndGet();
            waiter.complete(null);
        }
    }

    void close() {
        closed = true;
        if (queueLock.tryLock()) {
            try {
                cancelFlush();
                pending.clear();
                pendingBytes = 0;
                evictableLowPriorityCount = 0;
                inFlightCount = 0;
            } finally {
                queueLock.unlock();
            }
        }
        executor.shutdownNow();
        completeFlushWaiters(Integer.MAX_VALUE);
    }

    DebugBundleStatus status() {
        return consecutiveFailures >= 3 ? DebugBundleStatus.DISCONNECTED : status;
    }

    Optional<Instant> lastEventAt() {
        return lastEventAt;
    }

    int pendingCount() {
        if (!queueLock.tryLock()) return MAX_EVENTS;
        try {
            return pending.size();
        } finally {
            queueLock.unlock();
        }
    }

    int pendingBytes() {
        if (!queueLock.tryLock()) return MAX_BYTES;
        try {
            return pendingBytes;
        } finally {
            queueLock.unlock();
        }
    }

    int pendingHighPriorityCount() {
        if (!queueLock.tryLock()) return 0;
        try {
            return (int) pending.stream().filter(PendingEvent::highPriority).count();
        } finally {
            queueLock.unlock();
        }
    }

    private void flushSafely() {
        try {
            if (!closed) flushInternal();
        } catch (Throwable ignored) {
            // A worker failure must never escape to application threads.
        } finally {
            reportPressureOnce();
        }
    }

    private void flushInternal() {
        snapshotPending();
        beforeFlush.run();
        List<PendingEvent> batch;
        queueLock.lock();
        try {
            if (pending.isEmpty() || (nextRetryAtMillis > 0 && now() < nextRetryAtMillis)) return;
            batch = new ArrayList<>(pending.subList(0, Math.min(config.batchSize(), pending.size())));
            inFlightCount = batch.size();
            for (PendingEvent event : batch) {
                if (!event.highPriority()) evictableLowPriorityCount--;
            }
        } finally {
            queueLock.unlock();
        }

        finalizeBatch(batch, false);
        queueLock.lock();
        try {
            if (closed || inFlightCount == 0) {
                scheduleNextIfBuffered();
                return;
            }
            batch = List.copyOf(pending.subList(0, inFlightCount));
        } finally {
            queueLock.unlock();
        }

        TransportResponse response;
        try {
            response = transport.send(new EventBatchRequest(batch.stream().map(PendingEvent::event).toList()));
        } catch (Throwable ignored) {
            response = new TransportResponse(500, null);
        }

        queueLock.lock();
        try {
            inFlightCount = 0;
            for (PendingEvent event : batch) {
                if (!event.highPriority()) evictableLowPriorityCount++;
            }
            if (response.isSuccess()) {
                applyAcknowledgement(response, batch);
            } else if (response.isRateLimited()) {
                markRetry(boundedRetryAfterMillis(response.retryAfterMillis()));
            } else if (!response.isRetryableFailure()) {
                removeSent(batch.size());
                nextRetryAtMillis = 0L;
                consecutiveFailures = 0;
                status = DebugBundleStatus.HEALTHY;
                scheduleNextIfBuffered();
            } else {
                markRetry(1_000L);
            }
        } finally {
            queueLock.unlock();
        }
    }

    private void snapshotPending() {
        List<PendingEvent> batch;
        queueLock.lock();
        try {
            if (closed || pending.stream().noneMatch(item -> item.exception() != null)) return;
            batch = new ArrayList<>(pending);
            inFlightCount = batch.size();
            evictableLowPriorityCount = 0;
        } finally {
            queueLock.unlock();
        }
        finalizeBatch(batch, true);
        queueLock.lock();
        try {
            inFlightCount = 0;
            evictableLowPriorityCount = (int) pending.stream().filter(item -> !item.highPriority()).count();
        } finally {
            queueLock.unlock();
        }
    }

    private Map<String, Object> snapshot(PendingEvent candidate) {
        // The handle never extends input lifetime. Only this worker temporarily
        // owns a raw graph while reading it; clear the handle before hook work.
        try {
            return snapshotException.apply(candidate.event(), candidate.exception().get());
        } catch (Throwable ignored) {
            return candidate.event();
        } finally {
            candidate.exception().clear();
        }
    }

    private void finalizeBatch(List<PendingEvent> batch, boolean snapshotsOnly) {
        for (int index = 0; index < batch.size(); index++) {
            // Release old batch slots incrementally; a later blocked accessor/hook
            // must not retain prior originals in addition to charged replacements.
            PendingEvent candidate = batch.set(index, null);
            if (closed) return;
            if (candidate.finalized() || (snapshotsOnly && candidate.exception() == null)) continue;
            Map<String, Object> output = candidate.exception() == null ? candidate.event() : snapshot(candidate);
            if (!snapshotsOnly && output != null) {
                try {
                    output = finalizeEvent.apply(output);
                } catch (Throwable ignored) {
                    // Keep the already protected snapshot on hook failure.
                }
            }
            int bytes = output == null ? 0 : estimateBytes(output);
            queueLock.lock();
            try {
                if (closed) return;
                int position = -1;
                for (int item = 0; item < inFlightCount; item++) {
                    if (pending.get(item) == candidate) { position = item; break; }
                }
                if (position < 0) continue;
                // Reserved sender/snapshot events are never eviction candidates.
                while (output != null && bytes <= MAX_BYTES && pendingBytes - candidate.bytes() + bytes > MAX_BYTES) {
                    if (!evictLowerPriority(candidate.highPriority())) break;
                }
                pendingBytes -= candidate.bytes();
                if (output == null || bytes > MAX_BYTES || pendingBytes + bytes > MAX_BYTES) {
                    pending.remove(position);
                    inFlightCount--;
                    if (output != null) pressureDrops.incrementAndGet();
                } else {
                    pending.set(position, new PendingEvent(output, bytes,
                            candidate.highPriority() || isHighPriority(output), !snapshotsOnly, null));
                    pendingBytes += bytes;
                }
            } finally {
                queueLock.unlock();
            }
        }
    }

    private void applyAcknowledgement(TransportResponse response, List<PendingEvent> batch) {
        IngestionAcknowledgementDecision acknowledgement = IngestionAcknowledgementDecision.decide(
                response.body(), batch.size()
        );
        if (acknowledgement.kind() == IngestionAcknowledgementDecision.Kind.PROTOCOL_FAILURE) {
            markRetry(boundedRetryAfterMillis(response.retryAfterMillis()));
            return;
        }
        if (acknowledgement.kind() == IngestionAcknowledgementDecision.Kind.LEGACY) {
            removeSent(batch.size());
            nextRetryAtMillis = 0L;
            consecutiveFailures = 0;
            status = DebugBundleStatus.HEALTHY;
            lastEventAt = Optional.of(Instant.ofEpochMilli(now()));
            scheduleNextIfBuffered();
            return;
        }

        List<PendingEvent> retryable = acknowledgement.retryableIndices().stream()
                .filter(index -> index >= 0 && index < batch.size())
                .map(batch::get)
                .toList();
        removeSent(batch.size());
        pending.addAll(0, retryable);
        for (PendingEvent event : retryable) {
            pendingBytes += event.bytes();
            if (!event.highPriority()) evictableLowPriorityCount++;
        }
        if (acknowledgement.accepted() > 0) {
            lastEventAt = Optional.of(Instant.ofEpochMilli(now()));
        }
        if (!retryable.isEmpty()) {
            markRetry(boundedRetryAfterMillis(response.retryAfterMillis()));
            return;
        }
        nextRetryAtMillis = 0L;
        consecutiveFailures = acknowledgement.accepted() > 0 ? 0 : 3;
        status = acknowledgement.accepted() > 0 ? DebugBundleStatus.HEALTHY : DebugBundleStatus.DISCONNECTED;
        scheduleNextIfBuffered();
    }

    private void removeSent(int count) {
        for (int index = 0; index < count; index++) {
            PendingEvent event = pending.get(index);
            pendingBytes -= event.bytes();
            if (index >= inFlightCount && !event.highPriority()) evictableLowPriorityCount--;
        }
        pending.subList(0, count).clear();
    }

    private void markRetry(long delayMillis) {
        consecutiveFailures++;
        status = DebugBundleStatus.DEGRADED;
        nextRetryAtMillis = now() + delayMillis;
        scheduleFlush(delayMillis);
    }

    private void scheduleNextIfBuffered() {
        if (pending.isEmpty()) {
            firstBufferedAtMillis = 0L;
            cancelFlush();
        } else {
            firstBufferedAtMillis = now();
            scheduleFlush(pending.size() >= config.batchSize() ? 1L : config.flushInterval().toMillis());
        }
    }

    private void scheduleFlush(long delayMillis) {
        if (closed) return;
        cancelFlush();
        flushTask = executor.schedule(this::flushSafely, Math.max(1L, delayMillis), TimeUnit.MILLISECONDS);
    }

    private void cancelFlush() {
        if (flushTask != null) {
            flushTask.cancel(false);
            flushTask = null;
        }
    }

    private void reportPressureOnce() {
        if (now() < nextPressureReportAtMillis) return;
        long count = pressureDrops.getAndSet(0L);
        if (count == 0 || closed) return;
        try {
            Map<String, Object> summary = pressureAggregate.apply(count);
            if (summary == null || !offerInternal(summary, false, isHighPriority(summary))) {
                pressureDrops.addAndGet(count);
            } else {
                nextPressureReportAtMillis = now() + 60_000L;
            }
        } catch (Throwable ignored) {
            pressureDrops.addAndGet(count);
        }
    }

    private boolean isHighPriority(Map<String, Object> event) {
        if ("backend_exception".equals(event.get("event_type"))) return true;
        if (!(event.get("payload") instanceof Map<?, ?> payload)) return false;
        Object level = payload.get("level");
        return "error".equals(level) || "critical".equals(level);
    }

    private int estimateBytes(Object event) {
        int bytes = 0;
        int visited = 0;
        ArrayDeque<Object> nodes = new ArrayDeque<>();
        nodes.add(event);
        while (!nodes.isEmpty() && bytes <= MAX_BYTES && visited++ < 8_192) {
            Object value = nodes.removeLast();
            if (value instanceof String text) {
                bytes += (int) Math.min(MAX_BYTES + 1L, (long) text.length() * 2L + 16L);
            } else if (value instanceof Map<?, ?> map) {
                bytes += 64;
                map.forEach((key, item) -> {
                    if (key != null) nodes.add(key);
                    if (item != null) nodes.add(item);
                });
            } else if (value instanceof List<?> list) {
                bytes += 32;
                for (Object item : list) if (item != null) nodes.add(item);
            } else {
                bytes += 32;
            }
        }
        return visited >= 8_192 ? MAX_BYTES + 1 : bytes;
    }

    private long boundedRetryAfterMillis(Long retryAfterMillis) {
        return retryAfterMillis == null || retryAfterMillis <= 0L
                ? 1_000L : Math.min(retryAfterMillis, 300_000L);
    }

    private long now() {
        return clockMillis.get();
    }

    private record PendingEvent(Map<String, Object> event, int bytes, boolean highPriority, boolean finalized,
            WeakReference<Throwable> exception) {}
}

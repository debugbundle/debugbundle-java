package com.debugbundle.sdk;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.logging.LogRecord;
import java.util.function.Supplier;

/** Reassembles privacy-safe redirected stderr lines without retaining raw records. */
final class JavaStackTraceAssembler {
    private static final int MAX_ACTIVE = 128;
    private static final int MAX_READY = 128;
    private static final int MAX_ORPHAN_CLIENTS = 128;
    private static final int MAX_LINES = 128;
    private static final int MAX_CHARS = 32_768;
    private static final long IDLE_MILLIS = 20_000L;
    private static final long MAX_AGE_MILLIS = 45_000L;
    private final ConcurrentHashMap<Key, Assembly> active = new ConcurrentHashMap<>();
    private final Semaphore activeSlots = new Semaphore(MAX_ACTIVE);
    private final ArrayDeque<Assembly> ready = new ArrayDeque<>();
    private final ReentrantLock readyLock = new ReentrantLock();
    private final ConcurrentHashMap<DefaultDebugBundleClient, Orphans> orphanLines = new ConcurrentHashMap<>();
    private final AtomicInteger orphanClientCount = new AtomicInteger();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(
            new DebugBundleThreadFactory("debugbundle-java-stack-assembler")
    );
    private final Supplier<Long> clockMillis;
    private long lastOrphanSummaryAtMillis;
    private final AtomicReference<CompletableFuture<Void>> pendingDrain = new AtomicReference<>();

    JavaStackTraceAssembler() {
        this(System::currentTimeMillis);
    }

    JavaStackTraceAssembler(Supplier<Long> clockMillis) {
        this.clockMillis = clockMillis;
        sweeper.scheduleAtFixedRate(() -> sweep(false), 1L, 1L, TimeUnit.SECONDS);
    }

    boolean accept(LogRecord record, String message, LogLevel level,
                   Map<String, Object> context, DefaultDebugBundleClient client) {
        if (message == null || message.length() > 16_384) return false;
        String loggerName = record.getLoggerName();
        if (!JavaStackSyntax.redirected(loggerName)) return false;
        Key key = new Key(client, record.getLongThreadID(), loggerName, client.stackScopeIdentity());
        if (level != LogLevel.ERROR && level != LogLevel.CRITICAL) {
            Assembly previous = remove(key);
            if (previous != null) enqueueReady(previous);
            return false;
        }
        Matcher root = JavaStackSyntax.ROOT.matcher(message);
        if (root.matches()) {
            String safeLine = client.scrubStackLine(message);
            if (safeLine == null) return true;
            Map<String, Object> safeContext = client.snapshotStackContext(context);
            long now = clockMillis.get();
            Assembly next = new Assembly(client, root.group(1), safeLine, safeContext, now);
            Assembly previous = active.get(key);
            if (previous == null) {
                if (!activeSlots.tryAcquire()) {
                    next.emit();
                    return true;
                }
                previous = active.putIfAbsent(key, next);
                if (previous != null) activeSlots.release();
                else return true;
            }
            if (!active.replace(key, previous, next)) {
                next.emit();
                return true;
            }
            if (previous != null) enqueueReady(previous);
            return true;
        }

        if (JavaStackSyntax.CONTINUATION.matcher(message).matches()) {
            Assembly current = active.get(key);
            long now = clockMillis.get();
            if (current != null && current.expired(now) && active.remove(key, current)) {
                activeSlots.release();
                enqueueReady(current);
                current = null;
            }
            if (current == null) {
                return recordOrphan(client, message);
            }
            String safeLine = client.scrubStackLine(message);
            if (safeLine != null && !current.append(safeLine, now)) return recordOrphan(client, safeLine);
            return true;
        }

        Assembly previous = remove(key);
        if (previous != null) enqueueReady(previous);
        return false;
    }

    CompletableFuture<Void> drainPending() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        var pending = pendingDrain.compareAndExchange(null, done);
        if (pending != null) return pending;
        try {
            sweeper.execute(() -> {
                try { sweep(true); }
                finally { pendingDrain.compareAndSet(done, null); done.complete(null); }
            });
        } catch (RuntimeException ignored) {
            pendingDrain.compareAndSet(done, null);
            done.complete(null);
        }
        return done;
    }

    void boundary(LogRecord record, DefaultDebugBundleClient client) {
        if (!JavaStackSyntax.redirected(record.getLoggerName())) return;
        Assembly previous = remove(new Key(client, record.getLongThreadID(), record.getLoggerName(), client.stackScopeIdentity()));
        if (previous != null) enqueueReady(previous);
    }

    CompletableFuture<Void> sweepNow() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            sweeper.execute(() -> {
                sweep(false);
                done.complete(null);
            });
        } catch (RuntimeException ignored) {
            done.complete(null);
        }
        return done;
    }

    void close() {
        drainPending().whenComplete((ignored, failure) -> {
            sweeper.shutdownNow(); orphanLines.clear(); orphanClientCount.set(0);
        });
    }

    private void sweep(boolean force) {
        try {
            long now = clockMillis.get();
            for (Map.Entry<Key, Assembly> entry : active.entrySet()) {
                Assembly assembly = entry.getValue();
                if ((force || assembly.expired(now))
                        && active.remove(entry.getKey(), assembly)) {
                    activeSlots.release();
                    enqueueReady(assembly);
                }
            }
            List<Assembly> batch = new ArrayList<>();
            if (readyLock.tryLock()) {
                try {
                    while (!ready.isEmpty()) batch.add(ready.removeFirst());
                } finally {
                    readyLock.unlock();
                }
            }
            for (Assembly assembly : batch) assembly.emit();
            if (force || now - lastOrphanSummaryAtMillis >= 60_000L) {
                boolean reported = false;
                for (Map.Entry<DefaultDebugBundleClient, Orphans> entry : orphanLines.entrySet()) {
                    OrphanBatch batchLines = entry.getValue().drain();
                    if (batchLines == null) continue;
                    if (orphanLines.remove(entry.getKey(), entry.getValue())) orphanClientCount.decrementAndGet();
                    if (batchLines.count() > 0) {
                        reported = true;
                        entry.getKey().captureStackContinuationContext(batchLines.count() + " orphan Java stack continuation lines observed",
                                Map.of("logger", "stderr", "java_stack_continuation_context", true, "stack_continuations", batchLines.samples(),
                                        "unmatched_count", batchLines.count(), "truncated", batchLines.count() > batchLines.samples().size()));
                    }
                }
                if (reported) lastOrphanSummaryAtMillis = now;
            }
        } catch (Throwable ignored) {
            // Keep the next sweep alive after malformed logging input.
        }
    }

    private void enqueueReady(Assembly assembly) {
        if (!readyLock.tryLock()) {
            assembly.emit();
            return;
        }
        try {
            if (ready.size() >= MAX_READY) {
                assembly.emit();
                return;
            }
            ready.addLast(assembly);
        } finally {
            readyLock.unlock();
        }
    }

    private Assembly remove(Key key) {
        Assembly previous = active.remove(key);
        if (previous != null) activeSlots.release();
        return previous;
    }

    private boolean recordOrphan(DefaultDebugBundleClient client, String line) {
        Orphans count = orphanLines.get(client);
        if (count == null) {
            while (true) {
                int current = orphanClientCount.get();
                if (current >= MAX_ORPHAN_CLIENTS) return false;
                if (orphanClientCount.compareAndSet(current, current + 1)) break;
            }
            Orphans added = new Orphans();
            Orphans existing = orphanLines.putIfAbsent(client, added);
            if (existing == null) count = added;
            else {
                orphanClientCount.decrementAndGet();
                count = existing;
            }
        }
        String safe = client.scrubStackLine(line);
        return count.record(safe);
    }

    private record OrphanBatch(long count, List<String> samples) { }

    private static final class Orphans {
        private final ReentrantLock lock = new ReentrantLock();
        private final List<String> samples = new ArrayList<>();
        private long count;
        private boolean retired;

        boolean record(String safe) {
            if (!lock.tryLock()) return false;
            try {
                // A sweep may retire the bucket between map lookup and admission.
                // Let the handler retain that line through ordinary protected capture.
                if (retired) return false;
                count++;
                if (safe != null && samples.size() < 16) samples.add(safe.substring(0, Math.min(1024, safe.length())));
                return true;
            } finally { lock.unlock(); }
        }

        OrphanBatch drain() {
            if (!lock.tryLock()) return null;
            try {
                if (retired) return null;
                retired = true;
                // One bounded snapshot owns both count and evidence. Removing the
                // retired bucket also expires references to replaced SDK clients.
                return new OrphanBatch(count, List.copyOf(samples));
            } finally { lock.unlock(); }
        }
    }

    // A reinitialized logger can reuse both thread and name while its previous client still drains.
    private record Key(DefaultDebugBundleClient client, long threadId, String loggerName, Object requestOwner) {}

    private static final class Assembly {
        private final DefaultDebugBundleClient client;
        private final String name;
        private final Map<String, Object> context;
        private final ReentrantLock lock = new ReentrantLock();
        private final List<String> lines = new ArrayList<>();
        private final AtomicInteger omitted = new AtomicInteger();
        private final long startedAtMillis;
        private volatile long lastAtMillis;
        private int chars;
        private final AtomicBoolean sealed = new AtomicBoolean();
        private volatile List<String> snapshot;

        private Assembly(DefaultDebugBundleClient client, String name, String rootLine,
                         Map<String, Object> context, long now) {
            this.client = client;
            this.name = name;
            this.context = context;
            this.startedAtMillis = now;
            this.lastAtMillis = now;
            this.lines.add(rootLine);
            this.snapshot = List.of(rootLine);
            this.chars = rootLine.length();
        }

        private boolean expired(long now) {
            return now - lastAtMillis >= IDLE_MILLIS || now - startedAtMillis >= MAX_AGE_MILLIS;
        }

        private boolean append(String line, long now) {
            if (!lock.tryLock()) {
                return false;
            }
            try {
                if (sealed.get()) return false;
                if (lines.size() >= MAX_LINES || chars + line.length() > MAX_CHARS) {
                    omitted.incrementAndGet();
                    return true;
                }
                lines.add(line);
                snapshot = List.copyOf(lines);
                chars += line.length();
                lastAtMillis = now;
                return !sealed.get();
            } finally {
                lock.unlock();
            }
        }

        private void emit() {
            if (!sealed.compareAndSet(false, true)) return;
            // Immutable published snapshots let draining avoid waiting on a producer.
            List<String> output = new ArrayList<>(snapshot);
            if (omitted.get() > 0) output.add("... " + omitted.get() + " continuation lines omitted");
            client.captureReportedJavaStack(name, output.get(0), String.join("\n", output),
                    context, Instant.ofEpochMilli(startedAtMillis));
        }
    }
}

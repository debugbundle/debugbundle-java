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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.LogRecord;
import java.util.function.Supplier;

/** Reassembles privacy-safe redirected stderr lines without retaining raw records. */
final class JavaStackTraceAssembler {
    private static final int MAX_ACTIVE = 128;
    private static final int MAX_READY = 128;
    private static final int MAX_ORPHAN_CLIENTS = 128;
    private static final int MAX_LINES = 128;
    private static final int MAX_CHARS = 32_768;
    private static final int MAX_LOGGER_NAME_CHARS = 256;
    private static final long IDLE_MILLIS = 20_000L;
    private static final long MAX_AGE_MILLIS = 45_000L;
    private static final Pattern ROOT = Pattern.compile(
            "^(?:Exception in thread \\\"[^\\\"]{1,128}\\\"\\s+)?([\\w.$/]+(?:Exception|Error|Throwable))(?::\\s*.*)?$"
    );
    private static final Pattern CONTINUATION = Pattern.compile(
            "^\\s*(?:at\\s+[\\w.$/]+\\([^\\r\\n]*\\)|\\.\\.\\.\\s+\\d+\\s+more|"
                    + "(?:Caused by|Suppressed):\\s+[\\w.$/]+(?:Exception|Error|Throwable)(?::|\\s|$).*)\\s*$"
    );

    private final ConcurrentHashMap<Key, Assembly> active = new ConcurrentHashMap<>();
    private final ArrayDeque<Assembly> ready = new ArrayDeque<>();
    private final ReentrantLock readyLock = new ReentrantLock();
    private final ConcurrentHashMap<DefaultDebugBundleClient, AtomicLong> orphanLines = new ConcurrentHashMap<>();
    private final AtomicInteger orphanClientCount = new AtomicInteger();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(
            new DebugBundleThreadFactory("debugbundle-java-stack-assembler")
    );
    private final Supplier<Long> clockMillis;
    private long lastOrphanSummaryAtMillis;

    JavaStackTraceAssembler() {
        this(System::currentTimeMillis);
    }

    JavaStackTraceAssembler(Supplier<Long> clockMillis) {
        this.clockMillis = clockMillis;
        sweeper.scheduleAtFixedRate(() -> sweep(false), 1L, 1L, TimeUnit.SECONDS);
    }

    boolean accept(LogRecord record, String message, LogLevel level,
                   Map<String, Object> context, DefaultDebugBundleClient client) {
        if (message == null) return false;
        String loggerName = record.getLoggerName();
        if (loggerName != null && loggerName.length() > MAX_LOGGER_NAME_CHARS) {
            // Keep an unbounded logger name out of retained state. Its root falls back to a
            // normal log; continuation lines become one pressure aggregate, not many incidents.
            if ((level == LogLevel.ERROR || level == LogLevel.CRITICAL)
                    && CONTINUATION.matcher(message).matches()) {
                recordOrphan(client);
                return true;
            }
            return false;
        }
        Key key = new Key(client, record.getLongThreadID(), loggerName);
        if (level != LogLevel.ERROR && level != LogLevel.CRITICAL) {
            Assembly previous = active.remove(key);
            if (previous != null) enqueueReady(previous);
            return false;
        }
        Matcher root = ROOT.matcher(message);
        if (root.matches()) {
            if (!active.containsKey(key) && active.size() >= MAX_ACTIVE) return false;
            String safeLine = client.scrubStackLine(message);
            if (safeLine == null) return true;
            Map<String, Object> safeContext = client.snapshotStackContext(context);
            long now = clockMillis.get();
            Assembly next = new Assembly(client, root.group(1), safeLine, safeContext, now);
            Assembly previous = active.put(key, next);
            if (previous != null) enqueueReady(previous);
            return true;
        }

        if (CONTINUATION.matcher(message).matches()) {
            Assembly current = active.get(key);
            if (current == null) {
                recordOrphan(client);
                return true;
            }
            String safeLine = client.scrubStackLine(message);
            if (safeLine != null) current.append(safeLine, clockMillis.get());
            return true;
        }

        Assembly previous = active.remove(key);
        if (previous != null) enqueueReady(previous);
        return false;
    }

    CompletableFuture<Void> drainPending() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            sweeper.execute(() -> {
                sweep(true);
                done.complete(null);
            });
        } catch (RuntimeException ignored) {
            done.complete(null);
        }
        return done;
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
        drainPending().whenComplete((ignored, failure) -> sweeper.shutdownNow());
    }

    private void sweep(boolean force) {
        try {
            long now = clockMillis.get();
            for (Map.Entry<Key, Assembly> entry : active.entrySet()) {
                Assembly assembly = entry.getValue();
                if ((force || now - assembly.lastAtMillis >= IDLE_MILLIS
                        || now - assembly.startedAtMillis >= MAX_AGE_MILLIS)
                        && active.remove(entry.getKey(), assembly)) {
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
                for (Map.Entry<DefaultDebugBundleClient, AtomicLong> entry : orphanLines.entrySet()) {
                    long count = entry.getValue().getAndSet(0L);
                    if (count > 0) {
                        reported = true;
                        entry.getKey().captureLog(count + " orphan Java stack continuation lines observed", LogLevel.WARNING);
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
            recordOrphan(assembly.client);
            return;
        }
        try {
            if (ready.size() >= MAX_READY) {
                recordOrphan(assembly.client);
                return;
            }
            ready.addLast(assembly);
        } finally {
            readyLock.unlock();
        }
    }

    private void recordOrphan(DefaultDebugBundleClient client) {
        AtomicLong count = orphanLines.get(client);
        if (count == null) {
            while (true) {
                int current = orphanClientCount.get();
                if (current >= MAX_ORPHAN_CLIENTS) return;
                if (orphanClientCount.compareAndSet(current, current + 1)) break;
            }
            AtomicLong added = new AtomicLong();
            AtomicLong existing = orphanLines.putIfAbsent(client, added);
            if (existing == null) count = added;
            else {
                orphanClientCount.decrementAndGet();
                count = existing;
            }
        }
        count.incrementAndGet();
    }

    // A reinitialized logger can reuse both thread and name while its previous client still drains.
    private record Key(DefaultDebugBundleClient client, long threadId, String loggerName) {}

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

        private Assembly(DefaultDebugBundleClient client, String name, String rootLine,
                         Map<String, Object> context, long now) {
            this.client = client;
            this.name = name;
            this.context = context;
            this.startedAtMillis = now;
            this.lastAtMillis = now;
            this.lines.add(rootLine);
            this.chars = rootLine.length();
        }

        private void append(String line, long now) {
            if (!lock.tryLock()) {
                omitted.incrementAndGet();
                return;
            }
            try {
                if (lines.size() >= MAX_LINES || chars + line.length() > MAX_CHARS) {
                    omitted.incrementAndGet();
                    return;
                }
                lines.add(line);
                chars += line.length();
                lastAtMillis = now;
            } finally {
                lock.unlock();
            }
        }

        private void emit() {
            List<String> snapshot;
            lock.lock();
            try {
                snapshot = new ArrayList<>(lines);
            } finally {
                lock.unlock();
            }
            if (omitted.get() > 0) snapshot.add("... " + omitted.get() + " continuation lines omitted");
            client.captureReportedJavaStack(name, snapshot.get(0), String.join("\n", snapshot),
                    context, Instant.ofEpochMilli(startedAtMillis));
        }
    }
}

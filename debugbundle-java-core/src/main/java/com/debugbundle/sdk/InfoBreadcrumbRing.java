package com.debugbundle.sdk;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Per-request sanitized breadcrumbs; burst sampling keeps INFO capture cost finite. */
final class InfoBreadcrumbRing {
    private static final int MAX_ENTRIES = 20;
    private static final int MAX_BYTES = 16 * 1024;
    private static final long MAX_AGE_MILLIS = 60_000L;

    private final Set<String> sensitiveFields;
    private final Supplier<Long> clockMillis;
    private final AtomicLong seen = new AtomicLong();
    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private int retainedBytes;

    InfoBreadcrumbRing(Set<String> sensitiveFields, Supplier<Long> clockMillis) {
        this.sensitiveFields = sensitiveFields;
        this.clockMillis = clockMillis;
    }

    void record(String message) {
        long index = seen.updateAndGet(previous -> previous == Long.MAX_VALUE ? previous : previous + 1L);
        if (index > MAX_ENTRIES && (index & (index - 1L)) != 0L) return;

        String safe;
        try {
            safe = (String) TelemetryPrivacy.protect(message, sensitiveFields);
        } catch (RuntimeException ignored) {
            return;
        }
        if (safe.length() > 512) safe = safe.substring(0, 512);
        int bytes = safe.length() * 2 + 96;
        if (!lock.tryLock()) return;
        try {
            long now = clockMillis.get();
            prune(now);
            while (!entries.isEmpty() && (entries.size() >= MAX_ENTRIES || retainedBytes + bytes > MAX_BYTES)) {
                retainedBytes -= entries.removeFirst().bytes();
            }
            entries.addLast(new Entry(safe, now, bytes));
            retainedBytes += bytes;
        } finally {
            lock.unlock();
        }
    }

    List<Map<String, Object>> snapshot() {
        if (!lock.tryLock()) return List.of();
        try {
            prune(clockMillis.get());
            List<Map<String, Object>> result = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("label", "log.info");
                item.put("data", Map.of("message", entry.message()));
                item.put("timestamp", Instant.ofEpochMilli(entry.createdAtMillis()).toString());
                item.put("activation_id", null);
                result.add(item);
            }
            return List.copyOf(result);
        } finally {
            lock.unlock();
        }
    }

    private void prune(long now) {
        while (!entries.isEmpty() && now - entries.peekFirst().createdAtMillis() > MAX_AGE_MILLIS) {
            retainedBytes -= entries.removeFirst().bytes();
        }
    }

    private record Entry(String message, long createdAtMillis, int bytes) {}
}

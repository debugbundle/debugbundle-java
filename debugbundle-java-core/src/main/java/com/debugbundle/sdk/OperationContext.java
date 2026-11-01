package com.debugbundle.sdk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Thread-local ownership with revocable leases, including decorated continuations. */
final class OperationContext {
    static final class Lease implements AutoCloseable {
        private final OperationContext owner;
        final Lease previous;
        final Map<String, Object> scope;
        final String kind;
        final String name;
        final Double limit;
        final long started = System.nanoTime();
        final AtomicBoolean ended = new AtomicBoolean();
        Lease(OperationContext owner, Lease previous, String kind, String name, Double limit) {
            this.owner = owner; this.previous = previous; this.kind = kind; this.name = name; this.limit = limit;
            scope = Map.of("kind", "operation", "operation_id", UUID.randomUUID().toString());
        }
        boolean active() {
            if (owner.closed || ended.get()) return false;
            Lease parent = previous;
            for (int depth = 0; parent != null && depth < 32; depth++, parent = parent.previous)
                if (parent.ended.get()) return false;
            return true;
        }
        @Override public void close() {
            ended.set(true);
            if (owner.current.get() == this) {
                if (previous != null && previous.active()) owner.current.set(previous); else owner.current.remove();
            }
        }
    }
    private final ThreadLocal<Lease> current = new ThreadLocal<>();
    private final Set<String> sensitive;
    private volatile boolean closed;
    OperationContext(Set<String> sensitive) { this.sensitive = sensitive; }

    Lease begin(Map<String, Object> options) {
        if (closed) return null;
        Lease previous = current();
        int depth = 0;
        for (Lease parent = previous; parent != null; parent = parent.previous) if (++depth >= 32) return null;
        String kind = options.get("kind") instanceof String value && Set.of("request", "command", "job", "unknown").contains(value) ? value : "unknown";
        String name = options.get("name") instanceof String value ? ExceptionSnapshot.clip((String) TelemetryPrivacy.protect(value, sensitive), 128) : null;
        Object rawLimit = options.get("limitMs");
        Double limit = rawLimit instanceof Integer || rawLimit instanceof Long || rawLimit instanceof Double || rawLimit instanceof Float
                ? ((Number) rawLimit).doubleValue() : null;
        if (limit != null && (!Double.isFinite(limit) || limit < 0)) limit = null;
        Lease next = new Lease(this, previous, kind, name, limit); current.set(next); return next;
    }
    Lease current() {
        Lease value = current.get();
        return value != null && value.active() ? value : null;
    }
    Map<String, Object> execution() {
        Lease lease = current();
        if (lease == null) return null;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", 1); value.put("kind", lease.kind); value.put("name", lease.name);
        value.put("elapsed_ms", Math.max(0, (System.nanoTime() - lease.started) / 1_000_000d)); value.put("limit_ms", lease.limit);
        return value;
    }
    <T> T run(Map<String, Object> options, Supplier<T> action) {
        Lease lease;
        try { lease = begin(options == null ? Map.of() : options); } catch (Throwable ignored) { lease = null; }
        try { return action.get(); } finally { if (lease != null) lease.close(); }
    }
    Runnable decorate(Runnable action) {
        Lease captured = current();
        return () -> {
            Lease previous = current();
            if (captured != null && captured.active()) current.set(captured); else current.remove();
            try { action.run(); } finally {
                if (previous != null && previous.active()) current.set(previous); else current.remove();
            }
        };
    }
    void close() { closed = true; current.remove(); }
}

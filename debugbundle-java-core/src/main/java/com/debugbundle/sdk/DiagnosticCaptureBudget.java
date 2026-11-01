package com.debugbundle.sdk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Counts metadata and pending payloads together; expiry never renews offline receipts. */
final class DiagnosticCaptureBudget {
    private static final long WINDOW = 300000;
    private static final int MAX_BYTES = 65536;
    private static final class Window {
        final long start;
        Long failureAt;
        String failureId;
        boolean ambiguous;
        final Set<String> attempts = new HashSet<>();
        int observations;
        int pending;
        Window(long now) { start = now; }
        int cost(String key) { return 256 + key.length() + (failureId == null ? 0 : failureId.length()) + attempts.size() * 40; }
    }
    private record Receipt(String key, String attempt, int bytes) { int cost(String id) { return bytes + key.length() + id.length() + 96; } }
    private final Map<String, Window> windows = new HashMap<>();
    private final Map<String, Receipt> pending = new HashMap<>();

    @SuppressWarnings("unchecked")
    private static String key(Map<String, Object> payload) {
        Map<String, Object> scope = (Map<String, Object>) payload.get("scope");
        return payload.get("failure_ref") + ":" + scope.get("kind") + ":" + scope.get("operation_id") + ":" + scope.get("page_id");
    }
    private void prune(long now) { windows.entrySet().removeIf(entry -> entry.getValue().pending == 0 && now >= entry.getValue().start + WINDOW); }
    private int bytes() {
        int total = 0;
        for (var entry : windows.entrySet()) total += entry.getValue().cost(entry.getKey());
        for (var entry : pending.entrySet()) total += entry.getValue().cost(entry.getKey());
        return total;
    }
    synchronized boolean register(Map<String, Object> reference, String eventId, long occurred) {
        prune(occurred);
        String key = key(reference);
        Window value = windows.get(key);
        if (value == null) {
            if (windows.size() >= 50 || bytes() + 1024 > MAX_BYTES) return false;
            value = new Window(occurred); windows.put(key, value);
        }
        if (value.failureId != null && !value.failureId.equals(eventId)) { value.ambiguous = true; return false; }
        value.failureId = eventId;
        if (value.failureAt == null) value.failureAt = occurred;
        return !value.ambiguous;
    }
    synchronized boolean reserve(String id, Map<String, Object> payload, int size, long now) {
        if (size < 0 || size > MAX_BYTES || pending.containsKey(id) || !"recovery".equals(payload.get("kind"))) return false;
        prune(now);
        String key = key(payload), attempt = (String) payload.get("attempt_id");
        Window value = windows.get(key);
        if (value == null) {
            if (windows.size() >= 50) return false;
            value = new Window(now);
        }
        if (value.ambiguous || now < value.start || now > (value.failureAt == null ? value.start : value.failureAt) + WINDOW
                || value.observations >= 20 || !value.attempts.contains(attempt) && value.attempts.size() >= 5) return false;
        Receipt receipt = new Receipt(key, attempt, size);
        int growth = receipt.cost(id) + (windows.containsKey(key) ? 0 : value.cost(key)) + (value.attempts.contains(attempt) ? 0 : 40);
        if (bytes() + growth > MAX_BYTES) return false;
        value.pending++; value.observations++; value.attempts.add(attempt);
        windows.put(key, value); pending.put(id, receipt); return true;
    }
    synchronized boolean replace(String originalId, String id, Map<String, Object> payload, int size, long now) {
        Receipt original = pending.get(originalId);
        if (original != null && original.key.equals(key(payload)) && original.attempt.equals(payload.get("attempt_id"))) {
            Window value = windows.get(original.key);
            if (size < 0 || size > MAX_BYTES || value == null || value.ambiguous || !originalId.equals(id) && pending.containsKey(id)
                    || bytes() - original.cost(originalId) + new Receipt(original.key, original.attempt, size).cost(id) > MAX_BYTES) {
                release(originalId); return false;
            }
            pending.remove(originalId); pending.put(id, new Receipt(original.key, original.attempt, size)); return true;
        }
        release(originalId);
        return reserve(id, payload, size, now);
    }
    synchronized boolean eligible(String id, Map<String, Object> payload) {
        Receipt receipt = pending.get(id);
        Window window = windows.get(key(payload));
        return receipt != null && receipt.key.equals(key(payload)) && receipt.attempt.equals(payload.get("attempt_id"))
                && window != null && !window.ambiguous;
    }
    synchronized void release(String id) {
        Receipt receipt = pending.remove(id);
        if (receipt != null && windows.containsKey(receipt.key)) windows.get(receipt.key).pending--;
    }
    synchronized void clear() { pending.clear(); windows.clear(); }
}

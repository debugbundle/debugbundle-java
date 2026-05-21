package com.debugbundle.sdk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EventSuppressionTracker {
    private static final long DUPLICATE_WINDOW_MS = 30_000L;
    private static final long LOOP_WINDOW_MS = 2_000L;
    private static final int LOOP_THRESHOLD = 10;
    private static final long LOOP_RESET_AFTER_MS = 60_000L;
    private static final long LOOP_CHECKPOINT_MS = 30_000L;
    private static final int MAX_NORMAL_EVENTS_PER_WINDOW = 3;

    private final Map<String, SuppressionState> states = new LinkedHashMap<>();

    boolean shouldCapture(String key, long nowMillis) {
        SuppressionState state = states.computeIfAbsent(key, ignored -> new SuppressionState(nowMillis));

        if (state.suppressionMode && nowMillis - state.lastSeenAtMillis >= LOOP_RESET_AFTER_MS) {
            state.reset(nowMillis);
        }

        if (nowMillis - state.windowStartedAtMillis >= DUPLICATE_WINDOW_MS) {
            state.windowStartedAtMillis = nowMillis;
            state.emittedCount = 0;
        }

        if (nowMillis - state.loopWindowStartedAtMillis >= LOOP_WINDOW_MS) {
            state.loopWindowStartedAtMillis = nowMillis;
            state.loopHitCount = 0;
        }

        state.loopHitCount += 1;
        state.lastSeenAtMillis = nowMillis;

        if (state.loopHitCount > LOOP_THRESHOLD) {
            state.suppressionMode = true;
        }

        if (state.suppressionMode) {
            state.markSuppressed(nowMillis);
            return false;
        }

        if (state.emittedCount < MAX_NORMAL_EVENTS_PER_WINDOW) {
            state.emittedCount += 1;
            return true;
        }

        state.markSuppressed(nowMillis);
        return false;
    }

    List<SuppressionAggregate> drainAggregates(long nowMillis) {
        List<SuppressionAggregate> aggregates = new ArrayList<>();

        for (Map.Entry<String, SuppressionState> entry : states.entrySet()) {
            SuppressionState state = entry.getValue();
            if (state.pendingSuppressedCount == 0
                    || state.pendingFirstSeenAtMillis == null
                    || state.pendingLastSeenAtMillis == null) {
                continue;
            }

            if (state.suppressionMode
                    && state.lastAggregateEmittedAtMillis != null
                    && nowMillis - state.lastAggregateEmittedAtMillis < LOOP_CHECKPOINT_MS) {
                continue;
            }

            aggregates.add(new SuppressionAggregate(
                    fingerprint(entry.getKey()),
                    state.pendingSuppressedCount,
                    Instant.ofEpochMilli(state.pendingFirstSeenAtMillis),
                    Instant.ofEpochMilli(state.pendingLastSeenAtMillis),
                    (int) (DUPLICATE_WINDOW_MS / 1_000L)
            ));

            state.pendingSuppressedCount = 0;
            state.pendingFirstSeenAtMillis = null;
            state.pendingLastSeenAtMillis = null;
            state.lastAggregateEmittedAtMillis = nowMillis;
        }

        return aggregates;
    }

    private String fingerprint(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 not available", error);
        }
    }

    private static final class SuppressionState {
        private long windowStartedAtMillis;
        private int emittedCount;
        private int pendingSuppressedCount;
        private Long pendingFirstSeenAtMillis;
        private Long pendingLastSeenAtMillis;
        private Long lastAggregateEmittedAtMillis;
        private long loopWindowStartedAtMillis;
        private int loopHitCount;
        private boolean suppressionMode;
        private long lastSeenAtMillis;

        private SuppressionState(long nowMillis) {
            reset(nowMillis);
        }

        private void reset(long nowMillis) {
            windowStartedAtMillis = nowMillis;
            emittedCount = 0;
            pendingSuppressedCount = 0;
            pendingFirstSeenAtMillis = null;
            pendingLastSeenAtMillis = null;
            lastAggregateEmittedAtMillis = null;
            loopWindowStartedAtMillis = nowMillis;
            loopHitCount = 0;
            suppressionMode = false;
            lastSeenAtMillis = nowMillis;
        }

        private void markSuppressed(long nowMillis) {
            if (pendingSuppressedCount == 0) {
                pendingFirstSeenAtMillis = windowStartedAtMillis;
            }

            pendingSuppressedCount += 1;
            pendingLastSeenAtMillis = nowMillis;
        }
    }

    record SuppressionAggregate(
            String fingerprint,
            int suppressedCount,
            Instant firstSeen,
            Instant lastSeen,
            int windowSeconds
    ) {}
}

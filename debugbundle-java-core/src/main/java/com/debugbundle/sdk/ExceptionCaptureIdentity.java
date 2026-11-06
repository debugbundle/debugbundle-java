package com.debugbundle.sdk;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

/** Cross-adapter deduplication uses native identity, never message, stack or temporal similarity. */
final class ExceptionCaptureIdentity {
    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayList<Entry> entries = new ArrayList<>();

    boolean admit(Throwable error, Object scope, boolean automatic, long now) {
        if (scope == null) return true;
        if (!lock.tryLock()) return true; // Uncertain identity retains the failure.
        try {
            entries.removeIf(entry -> entry.error.get() == null || now - entry.at >= 30_000L);
            for (Entry entry : entries) {
                if (entry.error.get() == error && entry.scope == scope) {
                    if (automatic != entry.automatic) {
                        entries.remove(entry);
                        return false;
                    }
                    // A repeated record from the same path can be another failure.
                    return true;
                }
            }
            return true;
        } finally { lock.unlock(); }
    }

    void remember(Throwable error, Object scope, boolean automatic, long now) {
        if (scope == null || !lock.tryLock()) return;
        try {
            entries.removeIf(entry -> entry.error.get() == null || now - entry.at >= 30_000L
                    || entry.error.get() == error && entry.scope == scope);
            if (entries.size() == 128) entries.remove(0);
            entries.add(new Entry(new WeakReference<>(error), scope, automatic, now));
        } finally { lock.unlock(); }
    }

    private record Entry(WeakReference<Throwable> error, Object scope, boolean automatic, long at) { }
}

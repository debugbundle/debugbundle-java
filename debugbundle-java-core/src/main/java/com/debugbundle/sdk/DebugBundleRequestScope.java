package com.debugbundle.sdk;

public final class DebugBundleRequestScope {
    private static final DebugBundleRequestScope NOOP = new DebugBundleRequestScope(null);

    private final Object state;

    public DebugBundleRequestScope() {
        this(new Object());
    }

    public static DebugBundleRequestScope noop() {
        return NOOP;
    }

    DebugBundleRequestScope(Object state) {
        this.state = state;
    }

    Object state() {
        return state;
    }
}

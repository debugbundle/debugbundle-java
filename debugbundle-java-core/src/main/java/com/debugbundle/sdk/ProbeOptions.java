package com.debugbundle.sdk;

public final class ProbeOptions {
    private static final ProbeOptions DEFAULT = new ProbeOptions(false);

    private final boolean heavy;

    public ProbeOptions(boolean heavy) {
        this.heavy = heavy;
    }

    public boolean heavy() {
        return heavy;
    }

    public static ProbeOptions defaultOptions() {
        return DEFAULT;
    }

    public static ProbeOptions heavyOption() {
        return new ProbeOptions(true);
    }
}

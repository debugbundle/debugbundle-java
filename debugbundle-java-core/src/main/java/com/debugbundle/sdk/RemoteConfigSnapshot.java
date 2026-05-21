package com.debugbundle.sdk;

import java.util.List;

record RemoteConfigSnapshot(
        boolean probesEnabled,
        boolean remoteProbesEnabled,
        List<RemoteProbeDirective> directives,
        long pollIntervalMillis,
        String triggerTokenKey,
        CapturePolicy capturePolicy
) {
    RemoteConfigSnapshot {
        directives = List.copyOf(directives);
    }

    static RemoteConfigSnapshot balanced(long pollIntervalMillis) {
        return new RemoteConfigSnapshot(true, false, List.of(), pollIntervalMillis, null, CapturePolicy.BALANCED);
    }

    static RemoteConfigSnapshot minimal(long pollIntervalMillis) {
        return new RemoteConfigSnapshot(true, false, List.of(), pollIntervalMillis, null, CapturePolicy.MINIMAL);
    }
}

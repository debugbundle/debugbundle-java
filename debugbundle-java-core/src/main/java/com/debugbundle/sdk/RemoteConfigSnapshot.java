package com.debugbundle.sdk;

import java.util.List;
import java.util.Map;

record RemoteConfigSnapshot(
        boolean probesEnabled,
        boolean remoteProbesEnabled,
        List<RemoteProbeDirective> directives,
        long pollIntervalMillis,
        String triggerTokenKey,
        CapturePolicy capturePolicy,
        Map<String, Object> evidenceCapabilities
) {
    RemoteConfigSnapshot(boolean probes, boolean remote, List<RemoteProbeDirective> directives, long interval, String key, CapturePolicy policy) {
        this(probes, remote, directives, interval, key, policy, EvidenceDeliveryState.descriptor(null));
    }

    RemoteConfigSnapshot {
        evidenceCapabilities = EvidenceDeliveryState.descriptor(evidenceCapabilities);
        directives = List.copyOf(directives);
    }

    static RemoteConfigSnapshot balanced(long pollIntervalMillis) {
        return new RemoteConfigSnapshot(true, false, List.of(), pollIntervalMillis, null, CapturePolicy.BALANCED);
    }

    static RemoteConfigSnapshot minimal(long pollIntervalMillis) {
        return new RemoteConfigSnapshot(true, false, List.of(), pollIntervalMillis, null, CapturePolicy.MINIMAL);
    }
}

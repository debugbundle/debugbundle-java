package com.debugbundle.sdk;

record RemoteProbeDirective(
        String id,
        String labelPattern,
        String service,
        String environment,
        String expiresAt
) {}

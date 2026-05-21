package com.debugbundle.sdk;

import java.time.Duration;

record RemoteConfigRequest(
        String endpoint,
        String projectToken,
        String sdkName,
        String sdkVersion,
        String ifNoneMatch,
        Duration timeout
) {}

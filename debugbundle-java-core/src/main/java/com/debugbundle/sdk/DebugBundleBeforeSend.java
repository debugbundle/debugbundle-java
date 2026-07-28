package com.debugbundle.sdk;

import java.util.Map;

@FunctionalInterface
public interface DebugBundleBeforeSend {
    Map<String, Object> apply(Map<String, Object> event);
}

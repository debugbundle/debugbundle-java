package com.debugbundle.sdk.web;

import com.debugbundle.sdk.DebugBundleClient;

public final class DebugBundleWebDeployment {
    public static final String CLIENT_ATTRIBUTE = DebugBundleWebDeployment.class.getName() + ".client";

    private DebugBundleWebDeployment() {
    }

    public static DebugBundleClient clientFromAttribute(Object attribute) {
        return attribute instanceof DebugBundleClient client ? client : null;
    }

    public static String serviceNameFromContextPath(String contextPath) {
        String deploymentKey = deploymentKeyFromContextPath(contextPath);
        return deploymentKey == null ? "root-webapp" : deploymentKey;
    }

    public static String deploymentKeyFromContextPath(String contextPath) {
        if (contextPath == null || contextPath.isBlank() || "/".equals(contextPath.trim())) {
            return "root-webapp";
        }
        String normalized = contextPath.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.replace('/', '-');
        return normalized.isBlank() ? "root-webapp" : normalized;
    }
}
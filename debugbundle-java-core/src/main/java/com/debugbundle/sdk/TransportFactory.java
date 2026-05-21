package com.debugbundle.sdk;

import java.util.Locale;

final class TransportFactory {
    private TransportFactory() {
    }

    static DebugBundleTransport create(DebugBundleConfig config) {
        if (isLocalEnvironment(config.environment()) || "local-only".equals(normalize(config.projectMode()))) {
            return new FileTransport(config.localEventsDir(), config.service());
        }

        return new HttpTransport(config.endpoint(), config.projectToken());
    }

    static boolean isLocalEnvironment(String environment) {
        String normalized = normalize(environment);
        return "local".equals(normalized) || "development".equals(normalized);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}

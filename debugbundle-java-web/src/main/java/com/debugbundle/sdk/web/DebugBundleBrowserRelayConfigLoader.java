package com.debugbundle.sdk.web;

import java.util.ArrayList;
import java.util.List;

public final class DebugBundleBrowserRelayConfigLoader {
    private DebugBundleBrowserRelayConfigLoader() {
    }

    @FunctionalInterface
    public interface ValueLookup {
        String get(String key);
    }

    public static DebugBundleBrowserRelay.Config load(ValueLookup initParamLookup, ValueLookup contextParamLookup) {
        return load(initParamLookup, contextParamLookup, System::getProperty, System::getenv);
    }

    static DebugBundleBrowserRelay.Config load(
            ValueLookup initParamLookup,
            ValueLookup contextParamLookup,
            ValueLookup systemPropertyLookup,
            ValueLookup envLookup
    ) {
        ValueLookup initLookup = safeLookup(initParamLookup);
        ValueLookup contextLookup = safeLookup(contextParamLookup);
        ValueLookup systemLookup = safeLookup(systemPropertyLookup);
        ValueLookup environmentLookup = safeLookup(envLookup);

        return new DebugBundleBrowserRelay.Config(
                firstNonBlank(
                        initLookup.get("debugbundle.project-token"),
                        contextLookup.get("debugbundle.project-token"),
                        systemLookup.get("debugbundle.project-token"),
                        environmentLookup.get("DEBUGBUNDLE_PROJECT_TOKEN"),
                        environmentLookup.get("DEBUGBUNDLE_TOKEN")
                ),
                firstNonBlank(
                        initLookup.get("debugbundle.endpoint"),
                        contextLookup.get("debugbundle.endpoint"),
                        systemLookup.get("debugbundle.endpoint"),
                        environmentLookup.get("DEBUGBUNDLE_ENDPOINT")
                ),
                firstNonBlank(
                        initLookup.get("debugbundle.project-mode"),
                        contextLookup.get("debugbundle.project-mode"),
                        systemLookup.get("debugbundle.project-mode"),
                        environmentLookup.get("DEBUGBUNDLE_PROJECT_MODE")
                ),
                firstNonBlank(
                        initLookup.get("debugbundle.local-events-dir"),
                        contextLookup.get("debugbundle.local-events-dir"),
                        systemLookup.get("debugbundle.local-events-dir"),
                        environmentLookup.get("DEBUGBUNDLE_LOCAL_EVENTS_DIR")
                ),
                parseInt(
                        firstNonBlank(
                                initLookup.get("debugbundle.relay.rate-limit-per-minute"),
                                contextLookup.get("debugbundle.relay.rate-limit-per-minute"),
                                systemLookup.get("debugbundle.relay.rate-limit-per-minute"),
                                environmentLookup.get("DEBUGBUNDLE_RELAY_RATE_LIMIT_PER_MINUTE")
                        ),
                        60
                ),
                parseBoolean(
                        firstNonBlank(
                                initLookup.get("debugbundle.relay.durable-write"),
                                contextLookup.get("debugbundle.relay.durable-write"),
                                systemLookup.get("debugbundle.relay.durable-write"),
                                environmentLookup.get("DEBUGBUNDLE_RELAY_DURABLE_WRITE")
                        ),
                        true
                ),
                firstNonBlank(
                        initLookup.get("debugbundle.relay.spool-dir"),
                        contextLookup.get("debugbundle.relay.spool-dir"),
                        systemLookup.get("debugbundle.relay.spool-dir"),
                        environmentLookup.get("DEBUGBUNDLE_RELAY_SPOOL_DIR")
                ),
                parseList(
                        firstNonBlank(
                                initLookup.get("debugbundle.relay.allowed-origins"),
                                contextLookup.get("debugbundle.relay.allowed-origins"),
                                systemLookup.get("debugbundle.relay.allowed-origins"),
                                environmentLookup.get("DEBUGBUNDLE_RELAY_ALLOWED_ORIGINS")
                        )
                ),
                firstNonBlank(
                    initLookup.get("debugbundle.relay.service"),
                    contextLookup.get("debugbundle.relay.service"),
                    systemLookup.get("debugbundle.relay.service"),
                    environmentLookup.get("DEBUGBUNDLE_RELAY_SERVICE")
                ),
                firstNonBlank(
                    initLookup.get("debugbundle.relay.environment"),
                    contextLookup.get("debugbundle.relay.environment"),
                    systemLookup.get("debugbundle.relay.environment"),
                    environmentLookup.get("DEBUGBUNDLE_RELAY_ENVIRONMENT")
                )
        );
    }

    private static ValueLookup safeLookup(ValueLookup lookup) {
        return lookup == null ? ignored -> null : lookup;
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            return defaultValue;
        }
    }

    private static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static List<String> parseList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String candidate : value.split(",")) {
            if (candidate != null && !candidate.isBlank()) {
                items.add(candidate.trim());
            }
        }
        return items;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
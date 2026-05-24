package com.debugbundle.agent;

import java.util.LinkedHashMap;
import java.util.Map;

final class DebugBundleAgentOptions {
    private final Map<String, String> configValues;
    private final String configPath;
    private final boolean captureUncaught;
    private final boolean captureJul;

    private DebugBundleAgentOptions(
            Map<String, String> configValues,
            String configPath,
            boolean captureUncaught,
            boolean captureJul
    ) {
        this.configValues = Map.copyOf(configValues);
        this.configPath = configPath;
        this.captureUncaught = captureUncaught;
        this.captureJul = captureJul;
    }

    static DebugBundleAgentOptions parse(String rawArgs) {
        Map<String, String> configValues = new LinkedHashMap<>();
        boolean captureUncaught = true;
        boolean captureJul = true;
        String configPath = firstNonBlank(System.getProperty("debugbundle.config"), System.getenv("DEBUGBUNDLE_CONFIG"));

        if (rawArgs != null && !rawArgs.isBlank()) {
            for (String segment : rawArgs.split(",")) {
                String token = segment == null ? null : segment.trim();
                if (token == null || token.isEmpty()) {
                    continue;
                }

                int equalsIndex = token.indexOf('=');
                if (equalsIndex < 0) {
                    configPath = token;
                    continue;
                }

                String key = token.substring(0, equalsIndex).trim();
                String value = token.substring(equalsIndex + 1).trim();
                switch (key) {
                    case "config", "config-path", "debugbundle.config" -> configPath = value;
                    case "capture-uncaught" -> captureUncaught = parseBoolean(value, true);
                    case "capture-jul" -> captureJul = parseBoolean(value, true);
                    default -> {
                        String normalizedKey = normalizeConfigKey(key);
                        if (normalizedKey != null && !value.isBlank()) {
                            configValues.put(normalizedKey, value);
                        }
                    }
                }
            }
        }

        return new DebugBundleAgentOptions(configValues, configPath, captureUncaught, captureJul);
    }

    String lookup(String key) {
        return configValues.get(key);
    }

    String configPath() {
        return configPath;
    }

    boolean captureUncaught() {
        return captureUncaught;
    }

    boolean captureJul() {
        return captureJul;
    }

    private static String normalizeConfigKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.startsWith("debugbundle.")) {
            return key;
        }
        return switch (key) {
            case "project-token" -> "debugbundle.project-token";
            case "enabled" -> "debugbundle.enabled";
            case "environment" -> "debugbundle.environment";
            case "service" -> "debugbundle.service";
            case "endpoint" -> "debugbundle.endpoint";
            case "project-mode" -> "debugbundle.project-mode";
            case "local-events-dir" -> "debugbundle.local-events-dir";
            case "sample-rate" -> "debugbundle.sample-rate";
            case "batch-size" -> "debugbundle.batch-size";
            case "flush-interval" -> "debugbundle.flush-interval";
            case "log-level" -> "debugbundle.log-level";
            default -> null;
        };
    }

    private static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
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
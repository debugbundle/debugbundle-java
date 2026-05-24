package com.debugbundle.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public final class DebugBundleConfigLoader {
    private DebugBundleConfigLoader() {
    }

    @FunctionalInterface
    public interface ValueLookup {
        String get(String key);
    }

    public static DebugBundleConfig load(ValueLookup primaryLookup, ValueLookup secondaryLookup, String fallbackService) {
        ValueLookup primary = safeLookup(primaryLookup);
        ValueLookup secondary = safeLookup(secondaryLookup);

        return DebugBundleConfig.builder()
                .projectToken(firstNonBlank(
                        primary.get("debugbundle.project-token"),
                        secondary.get("debugbundle.project-token"),
                        System.getProperty("debugbundle.project-token"),
                        System.getenv("DEBUGBUNDLE_PROJECT_TOKEN"),
                        System.getenv("DEBUGBUNDLE_TOKEN")
                ))
                .environment(firstNonBlank(
                        primary.get("debugbundle.environment"),
                        secondary.get("debugbundle.environment"),
                        System.getProperty("debugbundle.environment"),
                        System.getenv("DEBUGBUNDLE_ENVIRONMENT")
                ))
                .service(firstNonBlank(
                        primary.get("debugbundle.service"),
                        secondary.get("debugbundle.service"),
                        System.getProperty("debugbundle.service"),
                        System.getenv("DEBUGBUNDLE_SERVICE"),
                        fallbackService
                ))
                .enabled(parseBoolean(firstNonBlank(
                        primary.get("debugbundle.enabled"),
                        secondary.get("debugbundle.enabled"),
                        System.getProperty("debugbundle.enabled"),
                        System.getenv("DEBUGBUNDLE_ENABLED")
                ), true))
                .redactFields(parseStringList(firstNonBlank(
                    primary.get("debugbundle.redact-fields"),
                    secondary.get("debugbundle.redact-fields"),
                    System.getProperty("debugbundle.redact-fields"),
                    System.getenv("DEBUGBUNDLE_REDACT_FIELDS")
                )))
                .sampleRate(parseDouble(firstNonBlank(
                        primary.get("debugbundle.sample-rate"),
                        secondary.get("debugbundle.sample-rate"),
                        System.getProperty("debugbundle.sample-rate"),
                        System.getenv("DEBUGBUNDLE_SAMPLE_RATE")
                ), 1.0d))
                .batchSize(parseInt(firstNonBlank(
                        primary.get("debugbundle.batch-size"),
                        secondary.get("debugbundle.batch-size"),
                        System.getProperty("debugbundle.batch-size"),
                        System.getenv("DEBUGBUNDLE_BATCH_SIZE")
                ), 25))
                .flushInterval(parseDuration(firstNonBlank(
                        primary.get("debugbundle.flush-interval"),
                        secondary.get("debugbundle.flush-interval"),
                        System.getProperty("debugbundle.flush-interval"),
                        System.getenv("DEBUGBUNDLE_FLUSH_INTERVAL")
                ), Duration.ofSeconds(5)))
                .endpoint(firstNonBlank(
                        primary.get("debugbundle.endpoint"),
                        secondary.get("debugbundle.endpoint"),
                        System.getProperty("debugbundle.endpoint"),
                        System.getenv("DEBUGBUNDLE_ENDPOINT")
                ))
                .probesPollInterval(parseDuration(firstNonBlank(
                    primary.get("debugbundle.probes-poll-interval"),
                    secondary.get("debugbundle.probes-poll-interval"),
                    System.getProperty("debugbundle.probes-poll-interval"),
                    System.getenv("DEBUGBUNDLE_PROBES_POLL_INTERVAL")
                ), Duration.ofSeconds(60)))
                .maxProbeLabels(parseInt(firstNonBlank(
                    primary.get("debugbundle.max-probe-labels"),
                    secondary.get("debugbundle.max-probe-labels"),
                    System.getProperty("debugbundle.max-probe-labels"),
                    System.getenv("DEBUGBUNDLE_MAX_PROBE_LABELS")
                ), 50))
                .maxProbeEntriesPerLabel(parseInt(firstNonBlank(
                    primary.get("debugbundle.max-probe-entries-per-label"),
                    secondary.get("debugbundle.max-probe-entries-per-label"),
                    System.getProperty("debugbundle.max-probe-entries-per-label"),
                    System.getenv("DEBUGBUNDLE_MAX_PROBE_ENTRIES_PER_LABEL")
                ), 10))
                .probeFlushOnError(parseBoolean(firstNonBlank(
                    primary.get("debugbundle.probe-flush-on-error"),
                    secondary.get("debugbundle.probe-flush-on-error"),
                    System.getProperty("debugbundle.probe-flush-on-error"),
                    System.getenv("DEBUGBUNDLE_PROBE_FLUSH_ON_ERROR")
                ), true))
                .logLevel(LogLevel.fromName(firstNonBlank(
                        primary.get("debugbundle.log-level"),
                        secondary.get("debugbundle.log-level"),
                        System.getProperty("debugbundle.log-level"),
                        System.getenv("DEBUGBUNDLE_LOG_LEVEL")
                )))
                .requestTimeout(parseDuration(firstNonBlank(
                    primary.get("debugbundle.request-timeout"),
                    secondary.get("debugbundle.request-timeout"),
                    System.getProperty("debugbundle.request-timeout"),
                    System.getenv("DEBUGBUNDLE_REQUEST_TIMEOUT")
                ), Duration.ofSeconds(5)))
                .projectMode(firstNonBlank(
                        primary.get("debugbundle.project-mode"),
                        secondary.get("debugbundle.project-mode"),
                        System.getProperty("debugbundle.project-mode"),
                        System.getenv("DEBUGBUNDLE_PROJECT_MODE")
                ))
                .localEventsDir(firstNonBlank(
                        primary.get("debugbundle.local-events-dir"),
                        secondary.get("debugbundle.local-events-dir"),
                        System.getProperty("debugbundle.local-events-dir"),
                        System.getenv("DEBUGBUNDLE_LOCAL_EVENTS_DIR")
                ))
                .build();
    }

    public static ValueLookup propertiesFileLookup(String path) {
        if (path == null || path.isBlank()) {
            return ignored -> null;
        }

        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(Path.of(path.trim()))) {
            properties.load(inputStream);
            return properties::getProperty;
        } catch (IOException | RuntimeException error) {
            return ignored -> null;
        }
    }

    public static ValueLookup deploymentLookup(ValueLookup lookup, String deploymentKey) {
        ValueLookup delegate = safeLookup(lookup);
        if (deploymentKey == null || deploymentKey.isBlank()) {
            return delegate;
        }
        return key -> firstNonBlank(delegate.get(deploymentScopedKey(key, deploymentKey)), delegate.get(key));
    }

    public static String deploymentScopedKey(String key, String deploymentKey) {
        if (key == null || !key.startsWith("debugbundle.") || deploymentKey == null || deploymentKey.isBlank()) {
            return key;
        }
        return "debugbundle.deployments." + deploymentKey + "." + key.substring("debugbundle.".length());
    }

    private static ValueLookup safeLookup(ValueLookup lookup) {
        return lookup == null ? ignored -> null : lookup;
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

    private static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
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

    private static double parseDouble(String value, double defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException error) {
            return defaultValue;
        }
    }

    private static List<String> parseStringList(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        List<String> items = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
        return items.isEmpty() ? null : items;
    }

    private static Duration parseDuration(String value, Duration defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String normalized = value.trim().toLowerCase();
        try {
            if (normalized.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(normalized.substring(0, normalized.length() - 2).trim()));
            }
            if (normalized.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(normalized.substring(0, normalized.length() - 1).trim()));
            }
            if (normalized.chars().allMatch(Character::isDigit)) {
                return Duration.ofMillis(Long.parseLong(normalized));
            }
            return Duration.parse(value.trim());
        } catch (RuntimeException error) {
            return defaultValue;
        }
    }
}
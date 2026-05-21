package com.debugbundle.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class RemoteConfigParser {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RemoteConfigParser() {
    }

    static RemoteConfigSnapshot parse(String body, long fallbackPollIntervalMillis, long nowMillis) {
        if (body == null || body.isBlank()) {
            return null;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                return null;
            }

            boolean probesEnabled = root.path("probes_enabled").asBoolean(false);
            boolean remoteProbesEnabled = root.path("remote_probes_enabled").asBoolean(false);
            long pollIntervalMillis = parsePollInterval(root.path("poll_interval_ms"), fallbackPollIntervalMillis);
            List<RemoteProbeDirective> directives = parseDirectives(root.path("active_probes"), nowMillis);
            CapturePolicy capturePolicy = parseCapturePolicy(root.get("capture_policy"));
            if (capturePolicy == null) {
                return null;
            }

            return new RemoteConfigSnapshot(
                    probesEnabled,
                    remoteProbesEnabled,
                    directives,
                    remoteProbesEnabled ? pollIntervalMillis : fallbackPollIntervalMillis,
                    asNonBlankString(root.get("trigger_token_key")),
                    capturePolicy
            );
        } catch (IOException error) {
            return null;
        }
    }

    private static long parsePollInterval(JsonNode node, long fallbackPollIntervalMillis) {
        if (node != null && node.canConvertToLong()) {
            long value = node.asLong();
            if (value > 0L) {
                return value;
            }
        }
        return fallbackPollIntervalMillis;
    }

    private static List<RemoteProbeDirective> parseDirectives(JsonNode node, long nowMillis) {
        if (node == null || !node.isArray()) {
            return List.of();
        }

        List<RemoteProbeDirective> directives = new ArrayList<>();
        for (JsonNode entry : node) {
            RemoteProbeDirective directive = parseDirective(entry);
            if (directive == null) {
                continue;
            }

            try {
                if (Instant.parse(directive.expiresAt()).toEpochMilli() > nowMillis) {
                    directives.add(directive);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return directives;
    }

    private static RemoteProbeDirective parseDirective(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }

        String id = asNonBlankString(node.get("id"));
        String labelPattern = asNonBlankString(node.get("label_pattern"));
        String service = asNonBlankString(node.get("service"));
        String environment = asNonBlankString(node.get("environment"));
        String expiresAt = asNonBlankString(node.get("expires_at"));
        if (id == null || labelPattern == null || service == null || environment == null || expiresAt == null) {
            return null;
        }

        try {
            Instant.parse(expiresAt);
        } catch (RuntimeException error) {
            return null;
        }

        return new RemoteProbeDirective(id, labelPattern, service, environment, expiresAt);
    }

    private static CapturePolicy parseCapturePolicy(JsonNode node) {
        if (node == null || node.isNull()) {
            return CapturePolicy.BALANCED;
        }
        if (!node.isObject()) {
            return null;
        }

        String preset = asNonBlankString(node.get("preset"));
        CapturePolicy.CaptureLogsMode captureLogs = parseCaptureLogsMode(node.get("capture_logs"));
        CapturePolicy.CaptureRequestEventsMode captureRequestEvents = parseCaptureRequestEventsMode(node.get("capture_request_events"));
        CapturePolicy.CaptureBreadcrumbsMode captureBreadcrumbs = parseCaptureBreadcrumbsMode(node.get("capture_breadcrumbs"));
        CapturePolicy.CaptureProbeEventsMode captureProbeEvents = parseCaptureProbeEventsMode(node.get("capture_probe_events"));
        List<Integer> immediateClientErrorStatuses = parseImmediateClientErrorStatuses(node.get("immediate_client_error_statuses"));

        if (captureLogs == null
                || captureRequestEvents == null
                || captureBreadcrumbs == null
                || captureProbeEvents == null
                || immediateClientErrorStatuses == null) {
            return null;
        }

        return new CapturePolicy(
                preset == null ? CapturePolicy.BALANCED.preset() : preset,
                captureLogs,
                captureRequestEvents,
                captureBreadcrumbs,
                captureProbeEvents,
                immediateClientErrorStatuses
        );
    }

    private static List<Integer> parseImmediateClientErrorStatuses(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            return null;
        }

        Set<Integer> statuses = new LinkedHashSet<>();
        for (JsonNode entry : node) {
            if (!entry.canConvertToInt()) {
                return null;
            }

            int value = entry.asInt();
            if (value < 400 || value > 499) {
                return null;
            }
            statuses.add(value);
            if (statuses.size() > 12) {
                return null;
            }
        }

        List<Integer> sorted = new ArrayList<>(statuses);
        sorted.sort(Integer::compareTo);
        return sorted;
    }

    private static CapturePolicy.CaptureLogsMode parseCaptureLogsMode(JsonNode node) {
        String value = asNonBlankString(node);
        if (value == null) {
            return null;
        }

        return switch (value) {
            case "off" -> CapturePolicy.CaptureLogsMode.OFF;
            case "error" -> CapturePolicy.CaptureLogsMode.ERROR;
            case "warning" -> CapturePolicy.CaptureLogsMode.WARNING;
            case "info" -> CapturePolicy.CaptureLogsMode.INFO;
            default -> null;
        };
    }

    private static CapturePolicy.CaptureRequestEventsMode parseCaptureRequestEventsMode(JsonNode node) {
        String value = asNonBlankString(node);
        if (value == null) {
            return null;
        }

        return switch (value) {
            case "off" -> CapturePolicy.CaptureRequestEventsMode.OFF;
            case "failures_only" -> CapturePolicy.CaptureRequestEventsMode.FAILURES_ONLY;
            case "filtered" -> CapturePolicy.CaptureRequestEventsMode.FILTERED;
            case "all" -> CapturePolicy.CaptureRequestEventsMode.ALL;
            default -> null;
        };
    }

    private static CapturePolicy.CaptureBreadcrumbsMode parseCaptureBreadcrumbsMode(JsonNode node) {
        String value = asNonBlankString(node);
        if (value == null) {
            return null;
        }

        return switch (value) {
            case "local_only" -> CapturePolicy.CaptureBreadcrumbsMode.LOCAL_ONLY;
            case "exception_only" -> CapturePolicy.CaptureBreadcrumbsMode.EXCEPTION_ONLY;
            case "standalone" -> CapturePolicy.CaptureBreadcrumbsMode.STANDALONE;
            default -> null;
        };
    }

    private static CapturePolicy.CaptureProbeEventsMode parseCaptureProbeEventsMode(JsonNode node) {
        String value = asNonBlankString(node);
        if (value == null) {
            return null;
        }

        return switch (value) {
            case "buffer_only" -> CapturePolicy.CaptureProbeEventsMode.BUFFER_ONLY;
            case "standalone_when_activated" -> CapturePolicy.CaptureProbeEventsMode.STANDALONE_WHEN_ACTIVATED;
            default -> null;
        };
    }

    private static String asNonBlankString(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }

        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }
}

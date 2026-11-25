package com.debugbundle.sdk;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Schema-aware protocol preservation at SDK and browser-relay privacy boundaries (SEC-23a). */
final class ProtocolPrivacyBoundary {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final Pattern UUID_PATTERN = Pattern.compile("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");
    private static final Pattern STACK_URL = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern STACK_COORDS_SUFFIX = Pattern.compile(":\\d+(?::\\d+)?[),]*$");
    private static final Pattern STACK_PUNCT_SUFFIX = Pattern.compile("[),]+$");
    private static final Set<String> ALLOWED_TOP_LEVEL_KEYS = Set.of(
            "schema_version", "event_id", "event_type", "sdk_name", "sdk_version", "occurred_at",
            "service", "correlation", "context", "payload", "project_token", "project_id"
    );
    private static final Set<String> ALLOWED_SERVICE_KEYS = Set.of("name", "environment", "runtime", "framework");
    private static final Set<String> ALLOWED_CORRELATION_KEYS = Set.of("request_id", "trace_id", "session_id", "user_id_hash");
    private static final Set<String> ALLOWED_HTML_RESOURCE_TAGS = Set.of(
            "script", "link", "img", "audio", "video", "source", "iframe", "object", "embed", "input"
    );
    private static final Set<String> ALLOWED_READY_STATES = Set.of("loading", "interactive", "complete");
    private static final Set<String> ALLOWED_VISIBILITY_STATES = Set.of("visible", "hidden", "prerender", "unloaded");
    private static final Set<String> ALLOWED_REJECTION_KINDS = Set.of("error", "string", "object", "null", "undefined", "unknown");
    private static final Set<String> ALLOWED_EVIDENCE_LIMIT_REASONS = Set.of("budget_exceeded", "unsafe", "unsupported", "unknown");
    private static final Set<String> ALLOWED_EVIDENCE_LIMIT_KEYS = Set.of("version", "exception_evidence", "execution", "resource_observations");
    private static final Set<String> ALLOWED_BROWSER_EVENT_KEYS = Set.of(
            "kind", "message", "file_name", "line_number", "column_number", "target", "page", "opaque"
    );
    private static final Set<String> ALLOWED_TARGET_KEYS = Set.of("tag_name", "source_url", "attributes");
    private static final List<String> ALLOWED_TARGET_ATTR_STRING_KEYS = List.of("rel", "as", "type", "media", "cross_origin");
    private static final List<String> ALLOWED_TARGET_ATTR_BOOL_KEYS = List.of("async", "defer", "integrity_present");
    private static final Set<String> ALLOWED_TARGET_ATTR_KEYS = Set.of(
            "rel", "as", "type", "media", "cross_origin", "async", "defer", "integrity_present"
    );
    private static final Set<String> ALLOWED_PAGE_KEYS = Set.of("url", "referrer", "ready_state", "visibility_state");
    private static final Set<String> ALLOWED_REJECTION_KEYS = Set.of("kind", "name", "message", "preview");
    private static final Set<String> ALLOWED_RUNTIMES = Set.of(
            "node", "browser", "python", "php", "jvm", "go", "ruby", "dotnet", "android", "swift", "react_native", "unknown"
    );
    private static final Set<String> ALLOWED_TRACE_ORIGINS = Set.of(
            "throw", "native_fatal", "capture", "runtime_reported", "unavailable", "unknown"
    );
    private static final Set<String> ALLOWED_PARSING_STATUSES = Set.of("parsed", "partial", "unparsed", "unavailable", "truncated");
    private static final Set<String> ALLOWED_FRAME_CLASSIFICATIONS = Set.of("application", "framework", "sdk", "unknown");
    private static final Set<String> ALLOWED_RELATIONSHIP_KINDS = Set.of("cause", "context", "aggregate", "suppressed");
    private static final Set<String> ALLOWED_EXECUTION_KINDS = Set.of("request", "command", "job", "unknown");
    private static final Set<String> ALLOWED_AVAILABILITIES = Set.of("observed", "unsupported", "no_match", "withheld", "ambiguous", "unknown");
    private static final Set<String> ALLOWED_DISPOSITIONS = Set.of("enforce", "report");
    private static final Set<String> ALLOWED_RECOVERY_STATES = Set.of(
            "attempt_started", "attempt_completed", "attempt_failed", "recovery_observed", "failure_persisted"
    );
    private static final Set<String> EXCEPTION_EVIDENCE_KEYS = Set.of("version", "runtime", "root_id", "nodes", "truncated", "unavailable_reason");
    private static final Set<String> NODE_KEYS = Set.of("id", "name", "message", "stack", "trace_origin", "parsing_status", "frames", "relationships");
    private static final Set<String> FRAME_KEYS = Set.of("function", "file", "line", "column", "classification");
    private static final Set<String> RELATIONSHIP_KEYS = Set.of("kind", "target_id");
    private static final Set<String> EXECUTION_KEYS = Set.of("version", "kind", "name", "elapsed_ms", "limit_ms");
    private static final Set<String> TIMING_KEYS = Set.of(
            "kind", "observed_at", "source_url", "availability", "response_status", "duration_ms", "transfer_size_bytes"
    );
    private static final Set<String> CSP_KEYS = Set.of(
            "kind", "observed_at", "source_url", "effective_directive", "disposition", "document_status_code"
    );
    private static final Set<String> RECOVERY_ALLOWED_KEYS = Set.of(
            "version", "kind", "failure_ref", "scope", "attempt_id", "state", "operation", "status_code", "duration_ms"
    );
    private static final Set<String> RECOVERY_REQUIRED_KEYS = Set.of("version", "kind", "failure_ref", "scope", "attempt_id", "state");
    private static final Set<String> RESOURCE_DIAGNOSTIC_KEYS = Set.of(
            "version", "kind", "failure_ref", "scope", "source_event_id", "observations"
    );

    private ProtocolPrivacyBoundary() {
    }

    static Map<String, Object> protectClientEvent(Map<String, Object> event, TelemetryPrivacy.CompiledPolicy policy) {
        if (event == null || policy == null) {
            return null;
        }
        Map<String, Object> safe = protectOnce(event, policy, false);
        if (safe != null) {
            return safe;
        }
        Map<String, Object> reduced = IngestionWire.withoutOptional(event, true);
        if (reduced == null) {
            return null;
        }
        safe = protectOnce(reduced, policy, false);
        return safe != null ? safe : protectOnce(IngestionWire.withoutOptional(event, false), policy, false);
    }

    static Map<String, Object> protectRelayEvent(
            Map<String, Object> event,
            TelemetryPrivacy.CompiledPolicy policy,
            boolean typed
    ) {
        if (event == null || policy == null) {
            return null;
        }
        Map<String, Object> safe = protectOnce(event, policy, true);
        if (safe != null && (!typed || BeforeSendProcessor.isValid(safe))) {
            return safe;
        }
        Map<String, Object> reduced = IngestionWire.withoutOptional(event, true);
        if (reduced == null) {
            return null;
        }
        safe = protectOnce(reduced, policy, true);
        if (safe != null && (!typed || BeforeSendProcessor.isValid(safe))) {
            return safe;
        }
        Map<String, Object> stripped = IngestionWire.withoutOptional(event, false);
        safe = stripped == null ? null : protectOnce(stripped, policy, true);
        return safe != null && (!typed || BeforeSendProcessor.isValid(safe)) ? safe : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> protectContext(Map<String, Object> context, TelemetryPrivacy.CompiledPolicy policy) {
        if (context == null || policy == null) {
            return Map.of();
        }
        try {
            Map<String, Object> rawContext = copyStringKeyMap(context);
            if (rawContext == null) {
                return Map.of();
            }
            Map<String, Object> preserved = extractPreservedContext(rawContext);
            Object sanitized = TelemetryPrivacy.protect(rawContext, policy);
            if (!(sanitized instanceof Map<?, ?> safeMap)) {
                return Map.of();
            }
            Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) safeMap);
            merged.putAll(preserved);
            return TelemetryPrivacy.utf8JsonBytes(merged) <= TelemetryPrivacy.MAX_BYTES ? merged : Map.of();
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    static boolean isValidEvidenceLimits(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return false;
        }
        if (!isIntegerOne(map.get("version"))) {
            return false;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !ALLOWED_EVIDENCE_LIMIT_KEYS.contains(key)) {
                return false;
            }
            if ("version".equals(key)) {
                continue;
            }
            if (!(entry.getValue() instanceof String reason) || !ALLOWED_EVIDENCE_LIMIT_REASONS.contains(reason)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> protectOnce(
            Map<String, Object> event,
            TelemetryPrivacy.CompiledPolicy policy,
            boolean relayMode
    ) {
        if (event == null || policy == null) {
            return null;
        }
        try {
            if (!relayMode && !ALLOWED_TOP_LEVEL_KEYS.containsAll(event.keySet())) {
                return null;
            }
            if (relayMode) {
                if (!TelemetryPrivacy.hasSafeEventIdentity(event, policy)) {
                    return null;
                }
            } else {
                Map<String, Object> identity = new LinkedHashMap<>(event);
                identity.remove("correlation");
                if (!TelemetryPrivacy.hasSafeEventIdentity(identity, policy)) {
                    return null;
                }
            }

            Map<String, Object> safeService = sanitizeService(event.get("service"), policy);
            if (safeService == null) {
                return null;
            }

            Map<String, Object> safeCorrelation = null;
            boolean hasCorrelation = event.containsKey("correlation");
            if (hasCorrelation) {
                if (relayMode) {
                    safeCorrelation = copyStringKeyMap(event.get("correlation"));
                } else {
                    safeCorrelation = sanitizeCorrelation(event.get("correlation"), policy);
                }
                if (safeCorrelation == null) {
                    return null;
                }
            }

            Map<String, Object> rawPayload = copyStringKeyMap(event.get("payload"));
            if (rawPayload == null) {
                return null;
            }
            String eventType = event.get("event_type") instanceof String value ? value : null;
            if (eventType == null) {
                return null;
            }

            Map<String, Object> preservedPayload = new LinkedHashMap<>();
            Map<String, Object> diagnosticPayload = null;
            if ("diagnostic_context".equals(eventType)) {
                diagnosticPayload = sanitizeDiagnosticContextPayload(rawPayload, policy);
                if (diagnosticPayload == null) {
                    return null;
                }
            } else if (!"analytics_event".equals(eventType)) {
                extractPreservedPayload(eventType, rawPayload, preservedPayload, policy);
            }

            boolean hasContext = event.containsKey("context");
            Map<String, Object> rawContext = new LinkedHashMap<>();
            Map<String, Object> preservedContext = Map.of();
            if (hasContext) {
                rawContext = copyStringKeyMap(event.get("context"));
                if (rawContext == null) {
                    return null;
                }
                preservedContext = extractPreservedContext(rawContext);
            }

            Object protectedPair = TelemetryPrivacy.protect(
                    List.of(diagnosticPayload == null ? rawPayload : Map.of(), rawContext),
                    policy
            );
            if (!(protectedPair instanceof List<?> pair) || pair.size() != 2
                    || !(pair.get(0) instanceof Map<?, ?> safePayloadPart)
                    || !(pair.get(1) instanceof Map<?, ?> safeContextPart)) {
                return null;
            }

            Map<String, Object> protectedPayload;
            if (diagnosticPayload != null) {
                protectedPayload = diagnosticPayload;
            } else {
                protectedPayload = new LinkedHashMap<>((Map<String, Object>) safePayloadPart);
                protectedPayload.putAll(preservedPayload);
            }

            Map<String, Object> assembled = new LinkedHashMap<>();
            for (String header : List.of("schema_version", "event_id", "event_type", "sdk_name", "sdk_version", "occurred_at")) {
                if (event.containsKey(header)) {
                    assembled.put(header, event.get(header));
                }
            }
            assembled.put("service", safeService);
            if (hasCorrelation) {
                assembled.put("correlation", safeCorrelation);
            }
            assembled.put("payload", protectedPayload);
            if (hasContext) {
                Map<String, Object> protectedContext = new LinkedHashMap<>((Map<String, Object>) safeContextPart);
                protectedContext.putAll(preservedContext);
                assembled.put("context", protectedContext);
            }

            if (TelemetryPrivacy.utf8JsonBytes(assembled) > TelemetryPrivacy.MAX_BYTES) {
                return null;
            }
            return relayMode || BeforeSendProcessor.isValid(assembled) ? assembled : null;
        } catch (RuntimeException ignored) {
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void extractPreservedPayload(
            String eventType,
            Map<String, Object> rawPayload,
            Map<String, Object> preservedPayload,
            TelemetryPrivacy.CompiledPolicy policy
    ) {
        if ("frontend_exception".equals(eventType)) {
            preserveBlock(rawPayload, preservedPayload, "browser_event",
                    value -> sanitizeBrowserEventBlock(value, policy));
            preserveBlock(rawPayload, preservedPayload, "rejection_reason",
                    value -> sanitizeRejectionReasonBlock(value, policy));
            preserveBlock(rawPayload, preservedPayload, "resource_observations",
                    value -> sanitizeResourceObservationsBlock(value, policy));
        }
        if ("backend_exception".equals(eventType) || "frontend_exception".equals(eventType)) {
            preserveBlock(rawPayload, preservedPayload, "exception_evidence",
                    value -> sanitizeExceptionEvidenceBlock(value, policy));
        }
        if ("backend_exception".equals(eventType)) {
            preserveBlock(rawPayload, preservedPayload, "execution",
                    value -> sanitizeExecutionBlock(value, policy));
        }
        if ("error_suppressed".equals(eventType) && "queue_pressure".equals(rawPayload.get("reason"))) {
            preservedPayload.put("reason", "queue_pressure");
            rawPayload.remove("reason");
        }
    }

    private static void preserveBlock(
            Map<String, Object> rawPayload,
            Map<String, Object> preservedPayload,
            String key,
            java.util.function.Function<Object, Map<String, Object>> sanitizer
    ) {
        if (!rawPayload.containsKey(key) || rawPayload.get(key) == null) {
            return;
        }
        Map<String, Object> safe = sanitizer.apply(rawPayload.get(key));
        if (safe != null) {
            preservedPayload.put(key, safe);
            rawPayload.remove(key);
        }
    }

    private static Map<String, Object> extractPreservedContext(Map<String, Object> rawContext) {
        Map<String, Object> preserved = new LinkedHashMap<>();
        if (rawContext.containsKey("evidence_reference") && EvidenceWire.reference(rawContext.get("evidence_reference"))) {
            preserved.put("evidence_reference", copyEvidenceReference(rawContext.get("evidence_reference")));
            rawContext.remove("evidence_reference");
        }
        if (rawContext.containsKey("evidence_limits") && isValidEvidenceLimits(rawContext.get("evidence_limits"))) {
            preserved.put("evidence_limits", copyStringKeyMap(rawContext.get("evidence_limits")));
            rawContext.remove("evidence_limits");
        }
        return preserved;
    }

    private static Map<String, Object> sanitizeService(Object raw, TelemetryPrivacy.CompiledPolicy policy) {
        Map<String, Object> map = copyStringKeyMap(raw);
        if (map == null || !ALLOWED_SERVICE_KEYS.containsAll(map.keySet())
                || !(map.get("name") instanceof String rawName)
                || !(map.get("environment") instanceof String rawEnv)) {
            return null;
        }
        String name = scrubString(rawName, policy);
        String env = scrubString(rawEnv, policy);
        if (name == null || env == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("environment", env);
        for (String field : List.of("runtime", "framework")) {
            if (!map.containsKey(field)) {
                continue;
            }
            Object value = map.get(field);
            if (value == null) {
                out.put(field, null);
            } else if (value instanceof String text) {
                String scrubbed = scrubString(text, policy);
                if (scrubbed == null) {
                    return null;
                }
                out.put(field, scrubbed);
            } else {
                return null;
            }
        }
        return out;
    }

    private static Map<String, Object> sanitizeCorrelation(Object raw, TelemetryPrivacy.CompiledPolicy policy) {
        Map<String, Object> map = copyStringKeyMap(raw);
        if (map == null || !ALLOWED_CORRELATION_KEYS.containsAll(map.keySet())) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                out.put(entry.getKey(), null);
            } else if (value instanceof String text) {
                String scrubbed = scrubString(text, policy);
                if (scrubbed == null) {
                    return null;
                }
                out.put(entry.getKey(), scrubbed);
            } else {
                return null;
            }
        }
        return out;
    }

    private static Map<String, Object> sanitizeBrowserEventBlock(Object raw, TelemetryPrivacy.CompiledPolicy policy) {
        Map<String, Object> map = copyStringKeyMap(raw);
        if (map == null || !ALLOWED_BROWSER_EVENT_KEYS.containsAll(map.keySet())) {
            return null;
        }
        Object kind = map.get("kind");
        Object opaque = map.get("opaque");
        if ((!"window_error".equals(kind) && !"resource_error".equals(kind)) || !(opaque instanceof Boolean)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        if (map.containsKey("message")) {
            ScrubbedNullable message = scrubNullableString(map.get("message"), policy);
            if (!message.ok) {
                return null;
            }
            out.put("message", message.value);
        }
        if (map.containsKey("file_name")) {
            ScrubbedNullable fileName = scrubNullableString(map.get("file_name"), policy);
            if (!fileName.ok) {
                return null;
            }
            out.put("file_name", fileName.value);
        }
        for (String coord : List.of("line_number", "column_number")) {
            if (map.containsKey(coord)) {
                Object val = map.get(coord);
                if (val != null && !isIntegerInRange(val, 0L, MAX_SAFE_INTEGER)) {
                    return null;
                }
                out.put(coord, val);
            }
        }
        if (map.containsKey("target")) {
            Object rawTarget = map.get("target");
            if (rawTarget == null) {
                out.put("target", null);
            } else {
                Map<String, Object> safeTarget = sanitizeBrowserTarget(rawTarget, policy);
                if (safeTarget == null) {
                    return null;
                }
                out.put("target", safeTarget);
            }
        }
        out.put("opaque", opaque);
        if (map.containsKey("page")) {
            Map<String, Object> safePage = sanitizeBrowserPage(map.get("page"), policy);
            if (safePage == null) {
                return null;
            }
            out.put("page", safePage);
        }
        return out;
    }

    private static Map<String, Object> sanitizeBrowserTarget(Object rawTarget, TelemetryPrivacy.CompiledPolicy policy) {
        Map<String, Object> target = copyStringKeyMap(rawTarget);
        if (target == null || !ALLOWED_TARGET_KEYS.containsAll(target.keySet())) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (target.containsKey("tag_name")) {
            Object rawTag = target.get("tag_name");
            if (rawTag == null) {
                out.put("tag_name", null);
            } else if (rawTag instanceof String tag) {
                if (ALLOWED_HTML_RESOURCE_TAGS.contains(TelemetryPrivacy.asciiLower(tag))) {
                    out.put("tag_name", tag);
                } else {
                    Object sanitized = TelemetryPrivacy.protect(Map.of("tag_name", tag), policy);
                    if (!(sanitized instanceof Map<?, ?> safeMap) || !(safeMap.get("tag_name") instanceof String safeTag)) {
                        return null;
                    }
                    out.put("tag_name", safeTag);
                }
            } else {
                return null;
            }
        }
        if (target.containsKey("source_url")) {
            ScrubbedNullable sourceUrl = scrubNullableString(target.get("source_url"), policy);
            if (!sourceUrl.ok) {
                return null;
            }
            out.put("source_url", sourceUrl.value);
        }
        if (target.containsKey("attributes")) {
            Map<String, Object> rawAttrs = copyStringKeyMap(target.get("attributes"));
            if (rawAttrs == null || !ALLOWED_TARGET_ATTR_KEYS.containsAll(rawAttrs.keySet())) {
                return null;
            }
            Map<String, Object> attrsOut = new LinkedHashMap<>();
            for (String key : ALLOWED_TARGET_ATTR_STRING_KEYS) {
                if (rawAttrs.containsKey(key)) {
                    if (!(rawAttrs.get(key) instanceof String val)) {
                        return null;
                    }
                    String scrubbed = scrubString(val, policy);
                    if (scrubbed == null) {
                        return null;
                    }
                    attrsOut.put(key, scrubbed);
                }
            }
            for (String key : ALLOWED_TARGET_ATTR_BOOL_KEYS) {
                if (rawAttrs.containsKey(key)) {
                    if (!(rawAttrs.get(key) instanceof Boolean)) {
                        return null;
                    }
                    attrsOut.put(key, rawAttrs.get(key));
                }
            }
            out.put("attributes", attrsOut);
        }
        return out;
    }

    private static Map<String, Object> sanitizeBrowserPage(Object rawPage, TelemetryPrivacy.CompiledPolicy policy) {
        Map<String, Object> page = copyStringKeyMap(rawPage);
        if (page == null || !ALLOWED_PAGE_KEYS.containsAll(page.keySet())) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String field : List.of("url", "referrer")) {
            if (page.containsKey(field)) {
                ScrubbedNullable scrubbed = scrubNullableString(page.get(field), policy);
                if (!scrubbed.ok) {
                    return null;
                }
                out.put(field, scrubbed.value);
            }
        }
        if (page.containsKey("ready_state")) {
            Object readyState = page.get("ready_state");
            if (readyState != null && (!(readyState instanceof String text) || !ALLOWED_READY_STATES.contains(text))) {
                return null;
            }
            out.put("ready_state", readyState);
        }
        if (page.containsKey("visibility_state")) {
            Object visibility = page.get("visibility_state");
            if (visibility != null && (!(visibility instanceof String text) || !ALLOWED_VISIBILITY_STATES.contains(text))) {
                return null;
            }
            out.put("visibility_state", visibility);
        }
        return out;
    }

    private static Map<String, Object> sanitizeRejectionReasonBlock(Object raw, TelemetryPrivacy.CompiledPolicy policy) {
        Map<String, Object> map = copyStringKeyMap(raw);
        if (map == null || !ALLOWED_REJECTION_KEYS.containsAll(map.keySet())
                || !(map.get("kind") instanceof String kind) || !ALLOWED_REJECTION_KINDS.contains(kind)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        for (String field : List.of("name", "message", "preview")) {
            if (map.containsKey(field)) {
                if (!(map.get(field) instanceof String text) || text.isEmpty()) {
                    return null;
                }
                String scrubbed = scrubString(text, policy);
                if (scrubbed == null) {
                    return null;
                }
                out.put(field, scrubbed);
            }
        }
        return out;
    }

    private static Map<String, Object> sanitizeExceptionEvidenceBlock(Object raw, TelemetryPrivacy.CompiledPolicy policy) {
        Map<String, Object> graph = copyStringKeyMap(raw);
        if (graph == null || !graph.keySet().equals(EXCEPTION_EVIDENCE_KEYS)
                || !isIntegerOne(graph.get("version"))
                || !(graph.get("runtime") instanceof String runtime) || !ALLOWED_RUNTIMES.contains(runtime)
                || !isIntegerInRange(graph.get("root_id"), 0L, 7L)
                || !(graph.get("nodes") instanceof List<?> rawNodes) || rawNodes.isEmpty() || rawNodes.size() > 8
                || !(graph.get("truncated") instanceof Boolean truncated)) {
            return null;
        }
        Object unavailableReason = graph.get("unavailable_reason");
        if (unavailableReason != null && (!(unavailableReason instanceof String text)
                || !ALLOWED_EVIDENCE_LIMIT_REASONS.contains(text))) {
            return null;
        }
        List<Map<String, Object>> nodes = new ArrayList<>(rawNodes.size());
        for (Object rawNode : rawNodes) {
            Map<String, Object> safeNode = sanitizeExceptionNode(rawNode, policy);
            if (safeNode == null) {
                return null;
            }
            nodes.add(safeNode);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", graph.get("version"));
        out.put("runtime", runtime);
        out.put("root_id", graph.get("root_id"));
        out.put("nodes", nodes);
        out.put("truncated", truncated);
        out.put("unavailable_reason", unavailableReason);
        return out;
    }

    private static Map<String, Object> sanitizeExceptionNode(Object rawNode, TelemetryPrivacy.CompiledPolicy policy) {
        Map<String, Object> node = copyStringKeyMap(rawNode);
        if (node == null || !node.keySet().equals(NODE_KEYS)
                || !isIntegerInRange(node.get("id"), 0L, 7L)
                || !(node.get("name") instanceof String rawName)
                || !(node.get("message") instanceof String rawMessage)
                || !(node.get("trace_origin") instanceof String traceOrigin) || !ALLOWED_TRACE_ORIGINS.contains(traceOrigin)
                || !(node.get("parsing_status") instanceof String parsingStatus) || !ALLOWED_PARSING_STATUSES.contains(parsingStatus)
                || !(node.get("frames") instanceof List<?> rawFrames) || rawFrames.size() > 32
                || !(node.get("relationships") instanceof List<?> rawRelations) || rawRelations.size() > 5) {
            return null;
        }
        String name = scrubString(rawName, policy);
        String message = scrubString(rawMessage, policy);
        if (name == null || message == null) {
            return null;
        }
        String stack;
        if (node.get("stack") == null) {
            stack = null;
        } else if (node.get("stack") instanceof String rawStack) {
            stack = scrubString(sanitizeBrowserStackUrls(rawStack), policy);
            if (stack == null) {
                return null;
            }
        } else {
            return null;
        }
        List<Map<String, Object>> frames = new ArrayList<>(rawFrames.size());
        for (Object rawFrame : rawFrames) {
            Map<String, Object> frame = copyStringKeyMap(rawFrame);
            if (frame == null || !frame.keySet().equals(FRAME_KEYS)
                    || !(frame.get("classification") instanceof String classification)
                    || !ALLOWED_FRAME_CLASSIFICATIONS.contains(classification)) {
                return null;
            }
            Object line = frame.get("line");
            Object column = frame.get("column");
            if ((line != null && !isIntegerInRange(line, 1L, MAX_SAFE_INTEGER))
                    || (column != null && !isIntegerInRange(column, 0L, MAX_SAFE_INTEGER))) {
                return null;
            }
            ScrubbedNullable fn = scrubNullableString(frame.get("function"), policy);
            ScrubbedNullable file = frame.get("file") instanceof String rawFile
                    ? scrubNullableString(sanitizeBrowserStackUrls(rawFile), policy)
                    : scrubNullableString(frame.get("file"), policy);
            if (!fn.ok || !file.ok) {
                return null;
            }
            Map<String, Object> safeFrame = new LinkedHashMap<>();
            safeFrame.put("function", fn.value);
            safeFrame.put("file", file.value);
            safeFrame.put("line", line);
            safeFrame.put("column", column);
            safeFrame.put("classification", classification);
            frames.add(safeFrame);
        }
        List<Map<String, Object>> relationships = new ArrayList<>(rawRelations.size());
        for (Object rawEdge : rawRelations) {
            Map<String, Object> edge = copyStringKeyMap(rawEdge);
            if (edge == null || !edge.keySet().equals(RELATIONSHIP_KEYS)
                    || !(edge.get("kind") instanceof String edgeKind) || !ALLOWED_RELATIONSHIP_KINDS.contains(edgeKind)
                    || !isIntegerInRange(edge.get("target_id"), 0L, 7L)) {
                return null;
            }
            Map<String, Object> safeEdge = new LinkedHashMap<>();
            safeEdge.put("kind", edgeKind);
            safeEdge.put("target_id", edge.get("target_id"));
            relationships.add(safeEdge);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", node.get("id"));
        out.put("name", name);
        out.put("message", message);
        out.put("stack", stack);
        out.put("trace_origin", traceOrigin);
        out.put("parsing_status", parsingStatus);
        out.put("frames", frames);
        out.put("relationships", relationships);
        return out;
    }

    private static Map<String, Object> sanitizeExecutionBlock(Object raw, TelemetryPrivacy.CompiledPolicy policy) {
        Map<String, Object> execution = copyStringKeyMap(raw);
        if (execution == null || !execution.keySet().equals(EXECUTION_KEYS)
                || !isIntegerOne(execution.get("version"))
                || !(execution.get("kind") instanceof String kind) || !ALLOWED_EXECUTION_KINDS.contains(kind)) {
            return null;
        }
        Object elapsed = execution.get("elapsed_ms");
        Object limit = execution.get("limit_ms");
        if ((elapsed != null && !isNonNegativeFiniteNumber(elapsed))
                || (limit != null && !isNonNegativeFiniteNumber(limit))) {
            return null;
        }
        ScrubbedNullable name = scrubNullableString(execution.get("name"), policy);
        if (!name.ok) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", execution.get("version"));
        out.put("kind", kind);
        out.put("name", name.value);
        out.put("elapsed_ms", elapsed);
        out.put("limit_ms", limit);
        return out;
    }

    private static Map<String, Object> sanitizeResourceObservationsBlock(Object raw, TelemetryPrivacy.CompiledPolicy policy) {
        Map<String, Object> observations = copyStringKeyMap(raw);
        if (observations == null || !observations.keySet().equals(Set.of("version", "items"))
                || !isIntegerOne(observations.get("version"))
                || !(observations.get("items") instanceof List<?> rawItems)
                || rawItems.isEmpty() || rawItems.size() > 5) {
            return null;
        }
        List<Map<String, Object>> items = new ArrayList<>(rawItems.size());
        for (Object rawItem : rawItems) {
            Map<String, Object> item = copyStringKeyMap(rawItem);
            if (item == null || !(item.get("observed_at") instanceof String observedAt)
                    || !CanonicalEventSchema.timestamp(observedAt)) {
                return null;
            }
            ScrubbedNullable sourceUrl = scrubNullableString(item.get("source_url"), policy);
            if (!sourceUrl.ok) {
                return null;
            }
            Object kind = item.get("kind");
            if ("timing".equals(kind)) {
                Object responseStatus = item.get("response_status");
                Object duration = item.get("duration_ms");
                Object transferSize = item.get("transfer_size_bytes");
                if (!item.keySet().equals(TIMING_KEYS)
                        || !(item.get("availability") instanceof String availability) || !ALLOWED_AVAILABILITIES.contains(availability)
                        || (responseStatus != null && !isIntegerInRange(responseStatus, 100L, 599L))
                        || (duration != null && !isNonNegativeFiniteNumber(duration))
                        || (transferSize != null && !isIntegerInRange(transferSize, 0L, MAX_SAFE_INTEGER))) {
                    return null;
                }
                Map<String, Object> outItem = new LinkedHashMap<>();
                outItem.put("kind", kind);
                outItem.put("observed_at", observedAt);
                outItem.put("source_url", sourceUrl.value);
                outItem.put("availability", availability);
                outItem.put("response_status", responseStatus);
                outItem.put("duration_ms", duration);
                outItem.put("transfer_size_bytes", transferSize);
                items.add(outItem);
            } else if ("csp".equals(kind)) {
                Object status = item.get("document_status_code");
                if (!item.keySet().equals(CSP_KEYS)
                        || !(item.get("effective_directive") instanceof String directive) || !directive.matches("[a-z-]{1,64}")
                        || !(item.get("disposition") instanceof String disposition) || !ALLOWED_DISPOSITIONS.contains(disposition)
                        || (status != null && !isIntegerInRange(status, 100L, 599L))) {
                    return null;
                }
                Map<String, Object> outItem = new LinkedHashMap<>();
                outItem.put("kind", kind);
                outItem.put("observed_at", observedAt);
                outItem.put("source_url", sourceUrl.value);
                outItem.put("effective_directive", directive);
                outItem.put("disposition", disposition);
                outItem.put("document_status_code", status);
                items.add(outItem);
            } else {
                return null;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", observations.get("version"));
        out.put("items", items);
        return out;
    }

    private static Map<String, Object> sanitizeDiagnosticContextPayload(
            Map<String, Object> payload,
            TelemetryPrivacy.CompiledPolicy policy
    ) {
        if (!isIntegerOne(payload.get("version"))
                || !EvidenceWire.fresh(payload.get("failure_ref"))
                || !EvidenceWire.scope(payload.get("scope"))) {
            return null;
        }
        Object kind = payload.get("kind");
        if ("recovery".equals(kind)) {
            if (!RECOVERY_ALLOWED_KEYS.containsAll(payload.keySet())
                    || !payload.keySet().containsAll(RECOVERY_REQUIRED_KEYS)
                    || !EvidenceWire.fresh(payload.get("attempt_id"))
                    || !(payload.get("state") instanceof String state) || !ALLOWED_RECOVERY_STATES.contains(state)) {
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("version", payload.get("version"));
            out.put("kind", kind);
            out.put("failure_ref", payload.get("failure_ref"));
            out.put("scope", copyStringKeyMap(payload.get("scope")));
            out.put("attempt_id", payload.get("attempt_id"));
            out.put("state", state);
            if (payload.containsKey("operation")) {
                if (!(payload.get("operation") instanceof String rawOperation)) {
                    return null;
                }
                String scrubbed = scrubString(rawOperation, policy);
                if (scrubbed == null) {
                    return null;
                }
                out.put("operation", scrubbed);
            }
            if (payload.containsKey("status_code")) {
                if (!isIntegerInRange(payload.get("status_code"), 100L, 599L)) {
                    return null;
                }
                out.put("status_code", payload.get("status_code"));
            }
            if (payload.containsKey("duration_ms")) {
                if (!isNonNegativeFiniteNumber(payload.get("duration_ms"))) {
                    return null;
                }
                out.put("duration_ms", payload.get("duration_ms"));
            }
            return out;
        }
        if ("resource_observation".equals(kind)) {
            if (!payload.keySet().equals(RESOURCE_DIAGNOSTIC_KEYS)
                    || !(payload.get("source_event_id") instanceof String sourceEventId)
                    || !UUID_PATTERN.matcher(sourceEventId).matches()) {
                return null;
            }
            Map<String, Object> observations = sanitizeResourceObservationsBlock(payload.get("observations"), policy);
            if (observations == null) {
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("version", payload.get("version"));
            out.put("kind", kind);
            out.put("failure_ref", payload.get("failure_ref"));
            out.put("scope", copyStringKeyMap(payload.get("scope")));
            out.put("source_event_id", sourceEventId);
            out.put("observations", observations);
            return out;
        }
        return null;
    }

    static String sanitizeBrowserStackUrls(String stack) {
        Matcher matcher = STACK_URL.matcher(stack);
        if (!matcher.find()) {
            return stack;
        }
        StringBuilder out = new StringBuilder(stack.length());
        int lastEnd = 0;
        do {
            out.append(stack, lastEnd, matcher.start());
            String source = matcher.group();
            Matcher suffixMatch = STACK_COORDS_SUFFIX.matcher(source);
            String suffix;
            if (suffixMatch.find()) {
                suffix = suffixMatch.group();
            } else {
                Matcher punctMatch = STACK_PUNCT_SUFFIX.matcher(source);
                suffix = punctMatch.find() ? punctMatch.group() : "";
            }
            String location = suffix.isEmpty() ? source : source.substring(0, source.length() - suffix.length());
            out.append(stripUrlQueryAndUserinfo(location)).append(suffix);
            lastEnd = matcher.end();
        } while (matcher.find());
        out.append(stack, lastEnd, stack.length());
        return out.toString();
    }

    private static String stripUrlQueryAndUserinfo(String location) {
        try {
            URI uri = new URI(location).parseServerAuthority();
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isEmpty()) {
                return "[unavailable-url]";
            }
            String lowerScheme = TelemetryPrivacy.asciiLower(scheme);
            if (!"http".equals(lowerScheme) && !"https".equals(lowerScheme)) {
                return "[unavailable-url]";
            }
            StringBuilder clean = new StringBuilder(lowerScheme).append("://").append(TelemetryPrivacy.asciiLower(host));
            int port = uri.getPort();
            if (port != -1 && !(("http".equals(lowerScheme) && port == 80) || ("https".equals(lowerScheme) && port == 443))) {
                clean.append(':').append(port);
            }
            String path = uri.getRawPath();
            clean.append(path == null || path.isEmpty() ? "/" : path);
            return clean.toString();
        } catch (Exception ignored) {
            return "[unavailable-url]";
        }
    }

    private static String scrubString(String value, TelemetryPrivacy.CompiledPolicy policy) {
        try {
            Object protectedValue = TelemetryPrivacy.protect(value, policy);
            return protectedValue instanceof String text ? text : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static ScrubbedNullable scrubNullableString(Object value, TelemetryPrivacy.CompiledPolicy policy) {
        if (value == null) {
            return new ScrubbedNullable(true, null);
        }
        if (!(value instanceof String text)) {
            return new ScrubbedNullable(false, null);
        }
        String scrubbed = scrubString(text, policy);
        return scrubbed == null ? new ScrubbedNullable(false, null) : new ScrubbedNullable(true, scrubbed);
    }

    private static Map<String, Object> copyEvidenceReference(Object raw) {
        Map<String, Object> ref = copyStringKeyMap(raw);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", ref.get("version"));
        out.put("failure_ref", ref.get("failure_ref"));
        out.put("scope", copyStringKeyMap(ref.get("scope")));
        return out;
    }

    private static Map<String, Object> copyStringKeyMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>(map.size());
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                return null;
            }
            copy.put(key, entry.getValue());
        }
        return copy;
    }

    private static boolean isIntegerOne(Object value) {
        return isIntegerInRange(value, 1L, 1L);
    }

    private static boolean isIntegerInRange(Object value, long min, long max) {
        if (!(value instanceof Number number)) {
            return false;
        }
        double measured = number.doubleValue();
        return Double.isFinite(measured) && measured == Math.floor(measured) && measured >= min && measured <= max;
    }

    private static boolean isNonNegativeFiniteNumber(Object value) {
        return value instanceof Number number
                && Double.isFinite(number.doubleValue())
                && number.doubleValue() >= 0.0d;
    }

    private record ScrubbedNullable(boolean ok, String value) {
    }
}

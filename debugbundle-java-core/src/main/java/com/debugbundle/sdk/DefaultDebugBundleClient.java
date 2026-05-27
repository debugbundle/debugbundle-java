package com.debugbundle.sdk;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class DefaultDebugBundleClient implements DebugBundleClient {
    private final DebugBundleConfig config;
    private final boolean active;
    private final DebugBundleTransport transport;
    private final Supplier<Long> clockMillis;
    private final Map<String, Object> persistentContext = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> probeBuffers = new LinkedHashMap<>();
    private final List<Map<String, Object>> bufferedEvents = new ArrayList<>();
    private final EventFactory eventFactory;
    private final EventSuppressionTracker suppressionTracker = new EventSuppressionTracker();
    private final Set<String> sensitiveFields;
    private final RemoteConfigFetcher remoteConfigFetcher;
    private final ScheduledExecutorService remoteConfigScheduler;
    private final ScheduledExecutorService flushScheduler;
    private final ThreadLocal<RequestScopeState> requestScopeState = new ThreadLocal<>();

    private RemoteConfigSnapshot remoteConfigSnapshot;
    private boolean remoteConfigFetchedOnce;
    private String remoteConfigEtag;
    private ScheduledFuture<?> remoteConfigPollTask;
    private ScheduledFuture<?> flushTask;

    private long nextRetryAtMillis;
    private long firstBufferedAtMillis;
    private int consecutiveFailures;
    private DebugBundleStatus status = DebugBundleStatus.DISCONNECTED;
    private Optional<Instant> lastEventAt = Optional.empty();

    DefaultDebugBundleClient(DebugBundleConfig config) {
        this(config, null, System::currentTimeMillis);
    }

    DefaultDebugBundleClient(
            DebugBundleConfig config,
            DebugBundleTransport transport,
            Supplier<Long> clockMillis
    ) {
        this.config = config;
        this.active = config != null
            && config.enabled()
            && (hasProjectToken(config) || usesLocalOnlyMode(config));
        this.transport = transport == null && active ? TransportFactory.create(config) : transport;
        this.clockMillis = clockMillis == null ? System::currentTimeMillis : clockMillis;
        this.sensitiveFields = config == null
                ? Set.of()
                : config.redactFields().stream()
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toSet());
        this.eventFactory = new EventFactory(config, sensitiveFields, probeBuffers, this.clockMillis);
        this.remoteConfigFetcher = config == null
                ? null
                : (config.remoteConfigFetcher() == null ? new HttpRemoteConfigFetcher() : config.remoteConfigFetcher());
        this.remoteConfigScheduler = shouldPollRemoteConfig()
                ? Executors.newSingleThreadScheduledExecutor(new DebugBundleThreadFactory("debugbundle-java-remote-config"))
                : null;
        this.flushScheduler = active
                ? Executors.newSingleThreadScheduledExecutor(new DebugBundleThreadFactory("debugbundle-java-flush"))
                : null;
        this.remoteConfigSnapshot = config == null
                ? RemoteConfigSnapshot.balanced(60_000L)
                : RemoteConfigSnapshot.balanced(config.probesPollInterval().toMillis());
        this.status = active ? DebugBundleStatus.HEALTHY : DebugBundleStatus.DISCONNECTED;

        if (shouldPollRemoteConfig()) {
            refreshRemoteConfig();
        }
    }

    @Override
    public DebugBundleConfig config() {
        return config;
    }

    @Override
    public synchronized void captureException(Throwable error) {
        captureException(error, Map.of());
    }

    @Override
    public synchronized void captureException(Throwable error, Map<String, Object> context) {
        if (!active || error == null) {
            return;
        }

        bufferEvent(eventFactory.buildExceptionEvent(error, mergedContext(context)));
    }

    @Override
    public synchronized void captureError(Throwable error) {
        captureException(error);
    }

    @Override
    public synchronized void captureLog(String message, LogLevel level) {
        captureLog(message, level, Map.of());
    }

    @Override
    public synchronized void captureLog(String message, LogLevel level, Map<String, Object> context) {
        if (!active || message == null || message.isBlank() || level == null || !shouldCaptureLog(level)) {
            return;
        }

        bufferEvent(eventFactory.buildLogEvent(message, level, mergedContext(context)));
    }

    @Override
    public synchronized void captureRequest(Object request, Object response, Map<String, Object> context) {
        if (!active || request == null || !shouldCaptureRequestEvent(response, context)) {
            return;
        }

        bufferEvent(eventFactory.buildRequestEvent(request, response, mergedContext(context)));
    }

    @Override
    public synchronized void captureMessage(String message) {
        captureMessage(message, LogLevel.INFO, Map.of());
    }

    @Override
    public synchronized void captureMessage(String message, LogLevel level, Map<String, Object> context) {
        LogLevel effectiveLevel = level == null ? LogLevel.INFO : level;
        if (!active || message == null || message.isBlank() || !shouldCaptureLog(effectiveLevel)) {
            return;
        }

        bufferEvent(eventFactory.buildMessageEvent(message, effectiveLevel, mergedContext(context)));
    }

    @Override
    public synchronized void setContext(String key, Object value) {
        if (!active || key == null || key.isBlank()) {
            return;
        }

        if (value == null) {
            persistentContext.remove(key);
            return;
        }

        persistentContext.put(key, value);
    }

    @Override
    public synchronized void probe(String label, Object data) {
        if (!active || label == null || label.isBlank() || !probesEnabled()) {
            return;
        }

        ProbeCaptureDecision decision = getMatchingRemoteProbeDecision(label, now());
        if (!decision.bufferLocally()) {
            return;
        }

        eventFactory.bufferProbe(label, data);
        emitStandaloneProbeEvents(label, data, decision);
    }

    @Override
    public synchronized void probe(String label, Supplier<?> dataSupplier) {
        probe(label, dataSupplier, ProbeOptions.defaultOptions());
    }

    @Override
    public synchronized void probe(String label, Supplier<?> dataSupplier, ProbeOptions options) {
        if (!active || label == null || label.isBlank() || dataSupplier == null || !probesEnabled()) {
            return;
        }

        long nowMillis = now();
        ProbeCaptureDecision decision = getMatchingRemoteProbeDecision(label, nowMillis);
        boolean heavy = options != null && options.heavy();
        if (heavy && decision.directives().isEmpty()) {
            return;
        }

        Object resolved;
        try {
            resolved = dataSupplier.get();
        } catch (RuntimeException error) {
            return;
        }
        if (!heavy && decision.bufferLocally()) {
            eventFactory.bufferProbe(label, resolved);
        }

        emitStandaloneProbeEvents(label, resolved, decision);
    }

    @Override
    public synchronized DebugBundleRequestScope beginRequest(Map<String, Object> request) {
        if (!active) {
            return DebugBundleRequestScope.noop();
        }

        RequestScopeState previous = requestScopeState.get();
        RequestScopeState current = new RequestScopeState(
                previous,
                buildRequestScopeContext(request),
                TriggerTokenResolver.resolveRequestTriggerDirectives(
                        request == null ? Map.of() : request,
                        remoteConfigSnapshot.triggerTokenKey(),
                        now()
                )
        );
        requestScopeState.set(current);
        return new DebugBundleRequestScope(current);
    }

    @Override
    public synchronized void endRequest(DebugBundleRequestScope scope) {
        if (scope == null || !(scope.state() instanceof RequestScopeState state)) {
            return;
        }

        RequestScopeState current = requestScopeState.get();
        if (current != state) {
            return;
        }

        if (state.previous() == null) {
            requestScopeState.remove();
            return;
        }

        requestScopeState.set(state.previous());
    }

    @Override
    public synchronized Runnable decorate(Runnable runnable) {
        if (runnable == null) {
            return null;
        }

        RequestScopeState captured = requestScopeState.get();
        return () -> {
            RequestScopeState previous = requestScopeState.get();
            if (captured == null) {
                requestScopeState.remove();
            } else {
                requestScopeState.set(captured);
            }
            try {
                runnable.run();
            } finally {
                if (previous == null) {
                    requestScopeState.remove();
                } else {
                    requestScopeState.set(previous);
                }
            }
        };
    }

    @Override
    public synchronized CompletableFuture<Void> flush() {
        flushInternal();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized void close() {
        cancelFlush();
        cancelRemoteConfigPoll();
        if (flushScheduler != null) {
            flushScheduler.shutdownNow();
        }
        if (remoteConfigScheduler != null) {
            remoteConfigScheduler.shutdownNow();
        }
    }

    @Override
    public synchronized DebugBundleStatus status() {
        if (!active) {
            return DebugBundleStatus.DISCONNECTED;
        }
        if (consecutiveFailures >= 3) {
            return DebugBundleStatus.DISCONNECTED;
        }
        return status;
    }

    @Override
    public synchronized Optional<Instant> lastEventAt() {
        return lastEventAt;
    }

    synchronized void refreshRemoteConfigNow() {
        refreshRemoteConfig();
    }

    private void bufferEvent(Map<String, Object> event) {
        if (!shouldSample()) {
            return;
        }

        String suppressionKey = SuppressionKeyBuilder.build(event);
        if (suppressionKey != null && !suppressionTracker.shouldCapture(suppressionKey, now())) {
            return;
        }

        bufferEventInternal(event);
    }

    private void bufferEventInternal(Map<String, Object> event) {
        if (bufferedEvents.isEmpty()) {
            firstBufferedAtMillis = now();
            scheduleFlush(config.flushInterval().toMillis());
        }

        bufferedEvents.add(event);

        if (bufferedEvents.size() >= config.batchSize()) {
            flushInternal();
            return;
        }

        if (firstBufferedAtMillis > 0 && now() - firstBufferedAtMillis >= config.flushInterval().toMillis()) {
            flushInternal();
        }
    }

    private void flushInternal() {
        if (!active || transport == null) {
            return;
        }
        enqueueSuppressionAggregates();
        if (bufferedEvents.isEmpty()) {
            return;
        }
        if (nextRetryAtMillis > 0 && now() < nextRetryAtMillis) {
            return;
        }

        TransportResponse response;
        try {
            response = transport.send(new EventBatchRequest(List.copyOf(bufferedEvents)));
        } catch (RuntimeException error) {
            response = new TransportResponse(500, null);
        }
        if (response.isSuccess()) {
            bufferedEvents.clear();
            firstBufferedAtMillis = 0L;
            nextRetryAtMillis = 0L;
            consecutiveFailures = 0;
            status = DebugBundleStatus.HEALTHY;
            lastEventAt = Optional.of(Instant.ofEpochMilli(now()));
            cancelFlush();
            return;
        }

        if (response.isRateLimited()) {
            status = DebugBundleStatus.DEGRADED;
            consecutiveFailures++;
            long retryAfterMillis = boundedRetryAfterMillis(response.retryAfterMillis());
            nextRetryAtMillis = now() + retryAfterMillis;
            scheduleFlush(retryAfterMillis);
            return;
        }

        if (!response.isRetryableFailure()) {
            bufferedEvents.clear();
            firstBufferedAtMillis = 0L;
            nextRetryAtMillis = 0L;
            consecutiveFailures = 0;
            status = DebugBundleStatus.HEALTHY;
            cancelFlush();
            return;
        }

        consecutiveFailures++;
        status = DebugBundleStatus.DEGRADED;
        nextRetryAtMillis = now() + 1000L;
        scheduleFlush(1000L);
    }

    private void enqueueSuppressionAggregates() {
        for (EventSuppressionTracker.SuppressionAggregate aggregate : suppressionTracker.drainAggregates(now())) {
            bufferEventInternal(eventFactory.buildErrorSuppressedEvent(aggregate));
        }
    }

    private synchronized void refreshRemoteConfig() {
        if (!shouldPollRemoteConfig() || remoteConfigFetcher == null || config == null) {
            return;
        }

        RemoteConfigResponse response;
        try {
            response = remoteConfigFetcher.fetch(new RemoteConfigRequest(
                    RemoteConfigEndpoint.fromIngestionEndpoint(config.endpoint()),
                    config.projectToken(),
                    "@debugbundle/sdk-java",
                    "0.1.2",
                    remoteConfigEtag,
                    config.requestTimeout()
            ));
        } catch (RuntimeException error) {
            applyMinimalPolicyFallbackIfNeeded();
            scheduleRemoteConfigPoll(config.probesPollInterval().toMillis());
            return;
        }

        if (response.etag() != null && !response.etag().isBlank()) {
            remoteConfigEtag = response.etag();
        }

        if (response.statusCode() == 304) {
            pruneExpiredRemoteProbeDirectives();
            if (remoteConfigSnapshot.remoteProbesEnabled()) {
                scheduleRemoteConfigPoll(remoteConfigSnapshot.pollIntervalMillis());
            } else {
                cancelRemoteConfigPoll();
            }
            return;
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            applyMinimalPolicyFallbackIfNeeded();
            scheduleRemoteConfigPoll(config.probesPollInterval().toMillis());
            return;
        }

        RemoteConfigSnapshot parsed = RemoteConfigParser.parse(
                response.responseBody(),
                config.probesPollInterval().toMillis(),
                now()
        );
        if (parsed == null) {
            applyMinimalPolicyFallbackIfNeeded();
            scheduleRemoteConfigPoll(config.probesPollInterval().toMillis());
            return;
        }

        remoteConfigSnapshot = parsed;
        remoteConfigFetchedOnce = true;
        if (parsed.remoteProbesEnabled()) {
            scheduleRemoteConfigPoll(parsed.pollIntervalMillis());
        } else {
            cancelRemoteConfigPoll();
        }
    }

    private void applyMinimalPolicyFallbackIfNeeded() {
        if (!remoteConfigFetchedOnce && config != null) {
            remoteConfigSnapshot = RemoteConfigSnapshot.minimal(config.probesPollInterval().toMillis());
        }
    }

    private void pruneExpiredRemoteProbeDirectives() {
        long nowMillis = now();
        List<RemoteProbeDirective> activeDirectives = remoteConfigSnapshot.directives().stream()
                .filter(directive -> {
                    try {
                        return Instant.parse(directive.expiresAt()).toEpochMilli() > nowMillis;
                    } catch (RuntimeException error) {
                        return false;
                    }
                })
                .toList();

        remoteConfigSnapshot = new RemoteConfigSnapshot(
                remoteConfigSnapshot.probesEnabled(),
                remoteConfigSnapshot.remoteProbesEnabled(),
                activeDirectives,
                remoteConfigSnapshot.pollIntervalMillis(),
                remoteConfigSnapshot.triggerTokenKey(),
                remoteConfigSnapshot.capturePolicy()
        );
    }

    private void scheduleRemoteConfigPoll(long delayMillis) {
        if (!shouldPollRemoteConfig() || remoteConfigScheduler == null || !remoteConfigSnapshot.remoteProbesEnabled()) {
            return;
        }

        if (remoteConfigPollTask != null) {
            remoteConfigPollTask.cancel(false);
        }

        long safeDelayMillis = Math.max(1_000L, delayMillis);
        remoteConfigPollTask = remoteConfigScheduler.schedule(this::refreshRemoteConfigSafely, safeDelayMillis, TimeUnit.MILLISECONDS);
    }

    private void cancelRemoteConfigPoll() {
        if (remoteConfigPollTask != null) {
            remoteConfigPollTask.cancel(false);
            remoteConfigPollTask = null;
        }
    }

    private void refreshRemoteConfigSafely() {
        try {
            refreshRemoteConfig();
        } catch (RuntimeException ignored) {
        }
    }

    private void scheduleFlush(long delayMillis) {
        if (flushScheduler == null) {
            return;
        }
        if (flushTask != null) {
            flushTask.cancel(false);
        }
        long safeDelayMillis = Math.max(1L, delayMillis);
        flushTask = flushScheduler.schedule(this::flushSafely, safeDelayMillis, TimeUnit.MILLISECONDS);
    }

    private synchronized void flushSafely() {
        try {
            flushInternal();
        } catch (RuntimeException ignored) {
        }
    }

    private void cancelFlush() {
        if (flushTask != null) {
            flushTask.cancel(false);
            flushTask = null;
        }
    }

    private long boundedRetryAfterMillis(Long retryAfterMillis) {
        if (retryAfterMillis == null || retryAfterMillis <= 0L) {
            return 1000L;
        }
        return Math.min(retryAfterMillis, 300_000L);
    }

    private boolean shouldSample() {
        if (config.sampleRate() >= 1.0d) {
            return true;
        }
        if (config.sampleRate() <= 0.0d) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < config.sampleRate();
    }

    private boolean shouldCaptureLog(LogLevel level) {
        CapturePolicy.CaptureLogsMode policyMode = remoteConfigSnapshot.capturePolicy().captureLogs();
        if (policyMode == CapturePolicy.CaptureLogsMode.OFF) {
            return false;
        }

        return logLevelWeight(level) >= Math.max(
                logLevelWeight(config.logLevel()),
                policyLogWeight(policyMode)
        );
    }

    private boolean shouldCaptureRequestEvent(Object response, Map<String, Object> context) {
        Integer statusCode = extractStatusCode(response);
        if (statusCode == null && context != null) {
            statusCode = asInteger(context.get("response_status"));
        }
        return RequestCapturePolicy.shouldCapture(statusCode, remoteConfigSnapshot.capturePolicy());
    }

    private void emitStandaloneProbeEvents(String label, Object data, ProbeCaptureDecision decision) {
        if (decision.directives().isEmpty()
                || remoteConfigSnapshot.capturePolicy().captureProbeEvents() != CapturePolicy.CaptureProbeEventsMode.STANDALONE_WHEN_ACTIVATED) {
            return;
        }

        for (RemoteProbeDirective directive : decision.directives()) {
            bufferEventInternal(eventFactory.buildProbeEvent(label, data, directive.id(), directive.labelPattern(), mergedContext(Map.of())));
        }
    }

    private ProbeCaptureDecision getMatchingRemoteProbeDecision(String label, long nowMillis) {
        if (!remoteConfigSnapshot.probesEnabled()) {
            return new ProbeCaptureDecision(false, List.of());
        }

        pruneExpiredRemoteProbeDirectives();
        List<RemoteProbeDirective> directives = new ArrayList<>();
        RequestScopeState requestState = requestScopeState.get();
        if (requestState != null) {
            directives.addAll(requestState.directives());
        }
        if (remoteConfigSnapshot.remoteProbesEnabled()) {
            for (RemoteProbeDirective directive : remoteConfigSnapshot.directives()) {
                if (matchesDirective(directive, label, nowMillis)) {
                    directives.add(directive);
                }
            }
        }

        return new ProbeCaptureDecision(true, directives);
    }

    private boolean matchesDirective(RemoteProbeDirective directive, String label, long nowMillis) {
        try {
            if (Instant.parse(directive.expiresAt()).toEpochMilli() <= nowMillis) {
                return false;
            }
        } catch (RuntimeException error) {
            return false;
        }

        if (!"*".equals(directive.service()) && !directive.service().equals(config.service())) {
            return false;
        }

        if (!"*".equals(directive.environment()) && !directive.environment().equals(config.environment())) {
            return false;
        }

        return matchesLabelPattern(directive.labelPattern(), label);
    }

    private boolean matchesLabelPattern(String pattern, String label) {
        if ("*".equals(pattern)) {
            return true;
        }

        if (pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return label.equals(prefix) || label.startsWith(prefix + ".");
        }

        return pattern.equals(label);
    }

    private boolean probesEnabled() {
        return !remoteConfigFetchedOnce || remoteConfigSnapshot.probesEnabled();
    }

    private Map<String, Object> mergedContext(Map<String, Object> eventContext) {
        Map<String, Object> context = new LinkedHashMap<>(persistentContext);
        RequestScopeState requestState = requestScopeState.get();
        if (requestState != null) {
            context.putAll(requestState.context());
        }
        if (eventContext != null) {
            context.putAll(eventContext);
        }
        return context;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildRequestScopeContext(Map<String, Object> request) {
        if (request == null || request.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> context = new LinkedHashMap<>();
        copyIfPresent(context, "method", request.get("method"));
        copyIfPresent(context, "path", request.get("path"));
        copyIfPresent(context, "route_template", request.get("route_template"));
        copyIfPresent(context, "request_id", firstNonBlank(
                asString(request.get("request_id")),
                extractHeaderValue((Map<String, Object>) request.getOrDefault("headers", Map.of()), "x-request-id"),
                extractHeaderValue((Map<String, Object>) request.getOrDefault("headers", Map.of()), "x-correlation-id")
        ));
        copyIfPresent(context, "trace_id", firstNonBlank(
                asString(request.get("trace_id")),
                extractHeaderValue((Map<String, Object>) request.getOrDefault("headers", Map.of()), "x-debugbundle-trace-id")
        ));
        return context;
    }

    private void copyIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String stringValue && stringValue.isBlank()) {
            return;
        }
        target.put(key, value);
    }

    private String asString(Object value) {
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        return null;
    }

    private String extractHeaderValue(Map<String, Object> headers, String headerName) {
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            if (!headerName.equalsIgnoreCase(entry.getKey())) {
                continue;
            }

            Object value = entry.getValue();
            if (value instanceof String stringValue && !stringValue.isBlank()) {
                return stringValue;
            }
            if (value instanceof List<?> listValue) {
                for (Object item : listValue) {
                    if (item instanceof String stringItem && !stringItem.isBlank()) {
                        return stringItem;
                    }
                }
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Integer extractStatusCode(Object response) {
        if (!(response instanceof Map<?, ?> rawMap)) {
            return null;
        }

        Map<String, Object> responseMap = (Map<String, Object>) rawMap;
        Integer statusCode = asInteger(responseMap.get("status_code"));
        if (statusCode != null) {
            return statusCode;
        }
        return asInteger(responseMap.get("status"));
    }

    private Integer asInteger(Object value) {
        if (value instanceof Integer integerValue) {
            return integerValue;
        }
        if (value instanceof Number numberValue) {
            return numberValue.intValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private int logLevelWeight(LogLevel level) {
        return switch (level) {
            case DEBUG -> 10;
            case INFO -> 20;
            case WARNING -> 30;
            case ERROR -> 40;
            case CRITICAL -> 50;
        };
    }

    private int policyLogWeight(CapturePolicy.CaptureLogsMode level) {
        return switch (level) {
            case INFO -> 20;
            case WARNING -> 30;
            case ERROR -> 40;
            case OFF -> Integer.MAX_VALUE;
        };
    }

    private boolean shouldPollRemoteConfig() {
        return active
                && config != null
                && !"local-only".equals(normalize(config.projectMode()))
                && !TransportFactory.isLocalEnvironment(config.environment());
    }

    private boolean hasProjectToken(DebugBundleConfig candidate) {
        return candidate != null
                && candidate.projectToken() != null
                && !candidate.projectToken().isBlank();
    }

    private boolean usesLocalOnlyMode(DebugBundleConfig candidate) {
        return candidate != null && "local-only".equals(normalize(candidate.projectMode()));
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private long now() {
        return clockMillis.get();
    }

    private record ProbeCaptureDecision(
            boolean bufferLocally,
            List<RemoteProbeDirective> directives
    ) {}

    private record RequestScopeState(
            RequestScopeState previous,
            Map<String, Object> context,
            List<RemoteProbeDirective> directives
    ) {
        private RequestScopeState {
            context = Map.copyOf(context);
            directives = List.copyOf(directives);
        }
    }
}

package com.debugbundle.sdk;

import static com.debugbundle.sdk.RequestContextFields.asInteger;
import static com.debugbundle.sdk.RequestContextFields.asString;
import static com.debugbundle.sdk.RequestContextFields.buildRequestScopeContext;
import static com.debugbundle.sdk.RequestContextFields.extractStatusCode;
import static com.debugbundle.sdk.RequestContextFields.firstNonBlank;

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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

final class DefaultDebugBundleClient implements DebugBundleClient {
    private static final int MAX_PERSISTENT_CONTEXT_ENTRIES = 128;
    private static final int MAX_CAPTURE_MESSAGE_CHARS = 16 * 1024;
    private static final String OVERSIZED_MESSAGE = "[REDACTED]";
    private final DebugBundleConfig config;
    private final boolean active;
    private final EventDeliveryWorker delivery;
    private final Supplier<Long> clockMillis;
    private final Map<String, Object> persistentContext = new ConcurrentHashMap<>();
    private final ReentrantLock persistentContextLock = new ReentrantLock();
    private final Map<String, List<Map<String, Object>>> probeBuffers = new LinkedHashMap<>();
    private final EventFactory eventFactory;
    private final EventSuppressionTracker suppressionTracker = new EventSuppressionTracker();
    private final Set<String> sensitiveFields;
    private final RemoteConfigFetcher remoteConfigFetcher;
    private final ScheduledExecutorService remoteConfigScheduler;
    private final CompletableFuture<Void> initialConfigReady = new CompletableFuture<>();
    private final ThreadLocal<RequestScopeState> requestScopeState = new ThreadLocal<>();

    private volatile RemoteConfigSnapshot remoteConfigSnapshot;
    private volatile boolean remoteConfigFetchedOnce;
    private String remoteConfigEtag;
    private ScheduledFuture<?> remoteConfigPollTask;


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
        DebugBundleTransport resolvedTransport = transport == null && active ? TransportFactory.create(config) : transport;
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
        this.delivery = active && resolvedTransport != null
                ? new EventDeliveryWorker(config, resolvedTransport, this.clockMillis,
                        this::enqueueSuppressionAggregates, this::buildPressureAggregate,
                        this::applyBeforeSendOnWorker, this::snapshotExceptionOnWorker)
                : null;
        this.remoteConfigSnapshot = config == null
                ? RemoteConfigSnapshot.balanced(60_000L)
                : RemoteConfigSnapshot.balanced(config.probesPollInterval().toMillis());

        if (shouldPollRemoteConfig()) {
            remoteConfigScheduler.execute(() -> {
                refreshRemoteConfigSafely();
                initialConfigReady.complete(null);
            });
        } else {
            initialConfigReady.complete(null);
        }
    }

    @Override
    public DebugBundleConfig config() {
        return config;
    }

    @Override
    public void captureException(Throwable error) {
        captureException(error, Map.of());
    }

    @Override
    public void captureException(Throwable error, Map<String, Object> context) {
        if (!active || error == null || delivery == null || !delivery.tryReserve(true)) {
            return;
        }

        try {
            RequestScopeState request = requestScopeState.get();
            if (!shouldSample()) return;
            Map<String, Object> shell = prepareEvent(eventFactory.buildExceptionShell(
                    error.getClass().getSimpleName(), mergedContext(context),
                    request == null ? List.of() : request.breadcrumbs().snapshot()
            ));
            if (shell != null) delivery.offerException(shell, error);
        } catch (Throwable ignored) {
            // Application exception accessors must not make SDK capture escape into the host.
        } finally {
            delivery.releaseReservation();
        }
    }

    @Override
    public void captureError(Throwable error) {
        captureException(error);
    }

    @Override
    public void captureLog(String message, LogLevel level) {
        captureLog(message, level, Map.of());
    }

    @Override
    public void captureLog(String message, LogLevel level, Map<String, Object> context) {
        try {
            if (!active || level == null) return;
            boolean standalone = shouldCaptureLog(level);
            boolean breadcrumb = shouldCaptureInfoBreadcrumb(level);
            if (!standalone && !breadcrumb) return;
            if (message == null) return;
            // The privacy boundary already replaces oversized strings; avoid scanning them first.
            String boundedMessage = message.length() > MAX_CAPTURE_MESSAGE_CHARS ? OVERSIZED_MESSAGE : message;
            if (breadcrumb) {
                if (boundedMessage.isBlank()) return;
                requestScopeState.get().breadcrumbs().record(boundedMessage);
            }
            if (!standalone) return;
            if (delivery == null || !delivery.tryReserve(level == LogLevel.ERROR || level == LogLevel.CRITICAL)) return;

            try {
                if (boundedMessage.isBlank()) return;
                Map<String, Object> event = prepareEvent(eventFactory.buildLogEvent(boundedMessage, level, mergedContext(context)));
                if (event != null) bufferEvent(event);
            } finally {
                delivery.releaseReservation();
            }
        } catch (Throwable ignored) {
            // Application context accessors and SDK failures must not interrupt logging.
        }
    }

    @Override
    public void captureRequest(Object request, Object response, Map<String, Object> context) {
        try {
            if (!active || request == null) {
                return;
            }
            RequestCaptureDecision decision = requestCaptureDecision(request, response, context);
            if (!decision.capture() || delivery == null || !delivery.tryReserve(decision.incident())) return;

            try {
                Map<String, Object> event = prepareEvent(eventFactory.buildRequestEvent(request, response, mergedContext(context)));
                if (event != null) bufferEvent(event, decision.incident());
            } finally {
                delivery.releaseReservation();
            }
        } catch (Throwable ignored) {
            // A hostile request object must not change the application's request outcome.
        }
    }

    @Override
    public void captureMessage(String message) {
        captureMessage(message, LogLevel.INFO, Map.of());
    }

    @Override
    public void captureMessage(String message, LogLevel level, Map<String, Object> context) {
        try {
            LogLevel effectiveLevel = level == null ? LogLevel.INFO : level;
            if (!active) return;
            boolean standalone = shouldCaptureLog(effectiveLevel);
            boolean breadcrumb = shouldCaptureInfoBreadcrumb(effectiveLevel);
            if (!standalone && !breadcrumb) return;
            if (message == null) return;
            String boundedMessage = message.length() > MAX_CAPTURE_MESSAGE_CHARS ? OVERSIZED_MESSAGE : message;
            if (breadcrumb) {
                if (boundedMessage.isBlank()) return;
                requestScopeState.get().breadcrumbs().record(boundedMessage);
            }
            if (!standalone) return;
            if (delivery == null || !delivery.tryReserve(effectiveLevel == LogLevel.ERROR
                    || effectiveLevel == LogLevel.CRITICAL)) return;

            try {
                if (boundedMessage.isBlank()) return;
                Map<String, Object> event = prepareEvent(
                        eventFactory.buildMessageEvent(boundedMessage, effectiveLevel, mergedContext(context))
                );
                if (event != null) bufferEvent(event);
            } finally {
                delivery.releaseReservation();
            }
        } catch (Throwable ignored) {
            // Message context failures must remain local to the SDK.
        }
    }

    @Override
    public void setContext(String key, Object value) {
        if (!active || key == null || key.isBlank()) {
            return;
        }

        if (value == null) {
            persistentContext.remove(key);
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> safe = (Map<String, Object>) TelemetryPrivacy.protect(Map.of(key, value), sensitiveFields);
            if (!safe.containsKey(key) || !persistentContextLock.tryLock()) return;
            try {
                if (!persistentContext.containsKey(key) && persistentContext.size() >= MAX_PERSISTENT_CONTEXT_ENTRIES) return;
                persistentContext.put(key, safe.get(key));
            } finally {
                persistentContextLock.unlock();
            }
        } catch (Throwable ignored) {
            // Unsupported context must not enter SDK-owned retention.
        }
    }

    @Override
    public void probe(String label, Object data) {
        try {
            if (!active || label == null || label.isBlank() || !probesEnabled()) return;
            ProbeCaptureDecision decision = getMatchingRemoteProbeDecision(label, now());
            if (!decision.bufferLocally()) return;
            eventFactory.bufferProbe(label, data);
            emitStandaloneProbeEvents(label, data, decision);
        } catch (Throwable ignored) {
            // Probe data and activation must never affect the host application.
        }
    }

    @Override
    public void probe(String label, Supplier<?> dataSupplier) {
        probe(label, dataSupplier, ProbeOptions.defaultOptions());
    }

    @Override
    public void probe(String label, Supplier<?> dataSupplier, ProbeOptions options) {
        try {
            if (!active || label == null || label.isBlank() || dataSupplier == null || !probesEnabled()) return;
            long nowMillis = now();
            ProbeCaptureDecision decision = getMatchingRemoteProbeDecision(label, nowMillis);
            boolean heavy = options != null && options.heavy();
            if (heavy && decision.directives().isEmpty()) return;
            Object resolved = dataSupplier.get();
            if (!heavy && decision.bufferLocally()) eventFactory.bufferProbe(label, resolved);
            emitStandaloneProbeEvents(label, resolved, decision);
        } catch (Throwable ignored) {
            // Host suppliers and probe serialization must not escape SDK capture.
        }
    }

    @Override
    public DebugBundleRequestScope beginRequest(Map<String, Object> request) {
        if (!active) return DebugBundleRequestScope.noop();
        try {
            RequestScopeState previous = requestScopeState.get();
            RequestScopeState current = new RequestScopeState(
                    previous,
                    buildRequestScopeContext(request),
                    TriggerTokenResolver.resolveRequestTriggerDirectives(
                            request == null ? Map.of() : request,
                            remoteConfigSnapshot.triggerTokenKey(),
                            now()
                    ),
                    new InfoBreadcrumbRing(sensitiveFields, clockMillis)
            );
            requestScopeState.set(current);
            return new DebugBundleRequestScope(current);
        } catch (Throwable ignored) {
            return DebugBundleRequestScope.noop();
        }
    }

    @Override
    public void endRequest(DebugBundleRequestScope scope) {
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
    public Runnable decorate(Runnable runnable) {
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
    public CompletableFuture<Void> flush() {
        return delivery == null ? CompletableFuture.completedFuture(null) : delivery.flush();
    }

    @Override
    public void close() {
        cancelRemoteConfigPoll();
        if (delivery != null) delivery.close();
        if (remoteConfigScheduler != null) remoteConfigScheduler.shutdownNow();
    }

    @Override
    public DebugBundleStatus status() {
        return delivery == null ? DebugBundleStatus.DISCONNECTED : delivery.status();
    }

    @Override
    public Optional<Instant> lastEventAt() {
        return delivery == null ? Optional.empty() : delivery.lastEventAt();
    }

    int pendingEventCount() {
        return delivery == null ? 0 : delivery.pendingCount();
    }

    int pendingBytes() {
        return delivery == null ? 0 : delivery.pendingBytes();
    }

    int pendingHighPriorityCount() {
        return delivery == null ? 0 : delivery.pendingHighPriorityCount();
    }

    String scrubStackLine(String line) {
        try {
            return (String) TelemetryPrivacy.protect(line, sensitiveFields);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> snapshotStackContext(Map<String, Object> context) {
        try {
            return (Map<String, Object>) TelemetryPrivacy.protect(mergedContext(context), sensitiveFields);
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    void captureReportedJavaStack(
            String name, String message, String stack, Map<String, Object> safeContext, Instant occurredAt
    ) {
        if (!active || delivery == null || !delivery.tryReserve(true)) return;
        try {
            bufferPreparedEvent(eventFactory.buildReportedStackEvent(name, message, stack, safeContext, occurredAt));
        } catch (Throwable ignored) {
            // Redirected stderr content must not escape the SDK worker.
        } finally {
            delivery.releaseReservation();
        }
    }

    void refreshRemoteConfigNow() {
        refreshRemoteConfigSafely();
    }

    CompletableFuture<Void> initialConfigReady() {
        return initialConfigReady;
    }

    private void bufferPreparedEvent(Map<String, Object> event) {
        Map<String, Object> prepared = prepareEvent(event);
        if (prepared != null) {
            bufferEvent(prepared);
        }
    }

    private Map<String, Object> prepareEvent(Map<String, Object> event) {
        return protectEvent(event);
    }

    private Map<String, Object> snapshotExceptionOnWorker(Map<String, Object> shell, Throwable error) {
        Map<String, Object> event = prepareEvent(eventFactory.completeExceptionEvent(shell, error));
        if (event == null) return null;
        String key = SuppressionKeyBuilder.build(event);
        return key == null || suppressionTracker.shouldCapture(key, now()) ? event : null;
    }

    private Map<String, Object> applyBeforeSendOnWorker(Map<String, Object> safeEvent) {
        Map<String, Object> prepared = BeforeSendProcessor.apply(safeEvent, config.beforeSend());
        if (prepared == null) return null;
        Map<String, Object> protectedReplacement = protectEvent(prepared);
        return protectedReplacement == null ? safeEvent : protectedReplacement;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> protectEvent(Map<String, Object> event) {
        try {
            Map<String, Object> identity = new LinkedHashMap<>(event);
            // Correlation is application data: scrub it, while rejecting unsafe SDK identity fields.
            identity.remove("correlation");
            if (!TelemetryPrivacy.hasSafeEventIdentity(identity, sensitiveFields)) return null;
            Map<String, Object> safe = (Map<String, Object>) TelemetryPrivacy.protect(event, sensitiveFields);
            safe.remove("project_token");
            safe.remove("project_id");
            return BeforeSendProcessor.isValid(safe) ? safe : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void bufferEvent(Map<String, Object> event) {
        bufferEvent(event, false);
    }

    private void bufferEvent(Map<String, Object> event, boolean incidentPriority) {
        if (!shouldSample()) {
            return;
        }

        String suppressionKey = SuppressionKeyBuilder.build(event);
        if (suppressionKey != null && !suppressionTracker.shouldCapture(suppressionKey, now())) {
            return;
        }

        offerPreparedEvent(event, incidentPriority);
    }

    private void offerPreparedEvent(Map<String, Object> safeEvent) {
        offerPreparedEvent(safeEvent, false);
    }

    private void offerPreparedEvent(Map<String, Object> safeEvent, boolean incidentPriority) {
        // Every caller has already passed the mandatory scrub before SDK-owned retention.
        if (safeEvent != null && delivery != null) delivery.offer(safeEvent, incidentPriority);
    }

    private Map<String, Object> buildPressureAggregate(long count) {
        return prepareEvent(eventFactory.buildLogEvent(
                count + " SDK events suppressed due to queue pressure",
                LogLevel.WARNING,
                Map.of("suppressed_count", count, "reason", "queue_pressure")
        ));
    }

    private void enqueueSuppressionAggregates() {
        for (EventSuppressionTracker.SuppressionAggregate aggregate : suppressionTracker.drainAggregates(now())) {
            Map<String, Object> event = prepareEvent(eventFactory.buildErrorSuppressedEvent(aggregate));
            if (event != null) {
                offerPreparedEvent(event);
            }
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
                    "3.0.1",
                    remoteConfigEtag,
                    config.requestTimeout()
            ));
        } catch (Throwable error) {
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
        } catch (Throwable ignored) {
        }
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

    boolean shouldCaptureLog(LogLevel level) {
        if (!active || level == null) {
            return false;
        }
        CapturePolicy.CaptureLogsMode policyMode = remoteConfigSnapshot.capturePolicy().captureLogs();
        if (policyMode == CapturePolicy.CaptureLogsMode.OFF) {
            return false;
        }

        return logLevelWeight(level) >= Math.max(
                logLevelWeight(config.logLevel()),
                policyLogWeight(policyMode)
        );
    }

    boolean shouldCaptureLogOrBreadcrumb(LogLevel level) {
        return shouldCaptureLog(level) || shouldCaptureInfoBreadcrumb(level);
    }

    private boolean shouldCaptureInfoBreadcrumb(LogLevel level) {
        return active && level == LogLevel.INFO && config.infoBreadcrumbs()
                && requestScopeState.get() != null
                && remoteConfigSnapshot.capturePolicy().captureBreadcrumbs()
                != CapturePolicy.CaptureBreadcrumbsMode.LOCAL_ONLY;
    }

    @SuppressWarnings("unchecked")
    private RequestCaptureDecision requestCaptureDecision(Object request, Object response, Map<String, Object> context) {
        Integer statusCode = extractStatusCode(response);
        if (statusCode == null && context != null) {
            statusCode = asInteger(context.get("response_status"));
        }
        String requestPath = null;
        String httpMethod = null;
        if (request instanceof Map<?, ?> rawMap) {
            Map<String, Object> requestMap = (Map<String, Object>) rawMap;
            requestPath = firstNonBlank(asString(requestMap.get("path")), asString(requestMap.get("url")));
            httpMethod = asString(requestMap.get("method"));
        }
        CapturePolicy policy = remoteConfigSnapshot.capturePolicy();
        return new RequestCaptureDecision(
                RequestCapturePolicy.shouldCapture(statusCode, requestPath, httpMethod, policy),
                statusCode != null && RequestCapturePolicy.isImmediateRequestIncidentStatus(
                        statusCode, requestPath, httpMethod, policy
                )
        );
    }

    private record RequestCaptureDecision(boolean capture, boolean incident) {}

    private void emitStandaloneProbeEvents(String label, Object data, ProbeCaptureDecision decision) {
        if (decision.directives().isEmpty()) {
            return;
        }

        for (RemoteProbeDirective directive : decision.directives()) {
            Map<String, Object> event = prepareEvent(
                    eventFactory.buildProbeEvent(
                            label,
                            data,
                            directive.id(),
                            directive.labelPattern(),
                            mergedContext(Map.of())
                    )
            );
            if (event != null
                    && remoteConfigSnapshot.capturePolicy().captureProbeEvents()
                    == CapturePolicy.CaptureProbeEventsMode.STANDALONE_WHEN_ACTIVATED) {
                offerPreparedEvent(event);
            }
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
            List<RemoteProbeDirective> directives,
            InfoBreadcrumbRing breadcrumbs
    ) {
        private RequestScopeState {
            context = Map.copyOf(context);
            directives = List.copyOf(directives);
        }
    }
}

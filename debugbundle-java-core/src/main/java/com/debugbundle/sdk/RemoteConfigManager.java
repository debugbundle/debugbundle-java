package com.debugbundle.sdk;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Owns the existing single remote-config worker and its cached policy snapshot. */
final class RemoteConfigManager {
    private final DebugBundleConfig config;
    private final boolean active;
    private final Supplier<Long> clockMillis;
    private final EvidenceDeliveryState evidence;
    private final RemoteConfigFetcher remoteConfigFetcher;
    private final ScheduledExecutorService remoteConfigScheduler;
    final CompletableFuture<Void> initialConfigReady = new CompletableFuture<>();
    volatile RemoteConfigSnapshot remoteConfigSnapshot;
    volatile boolean remoteConfigFetchedOnce;
    private String remoteConfigEtag;
    private ScheduledFuture<?> remoteConfigPollTask;

    RemoteConfigManager(DebugBundleConfig config, boolean active, Supplier<Long> clockMillis, EvidenceDeliveryState evidence) {
        this.config = config;
        this.active = active;
        this.clockMillis = clockMillis;
        this.evidence = evidence;
        remoteConfigFetcher = config == null ? null : config.remoteConfigFetcher() == null
                ? new HttpRemoteConfigFetcher() : config.remoteConfigFetcher();
        remoteConfigSnapshot = RemoteConfigSnapshot.balanced(config == null ? 60000L : config.probesPollInterval().toMillis());
        remoteConfigScheduler = shouldPollRemoteConfig() ? Executors.newSingleThreadScheduledExecutor(
                new DebugBundleThreadFactory("debugbundle-java-remote-config")) : null;
    }

    void start() {
        if (remoteConfigScheduler == null) initialConfigReady.complete(null);
        else remoteConfigScheduler.execute(() -> { refreshRemoteConfigSafely(); initialConfigReady.complete(null); });
    }

    void refresh() { refreshRemoteConfigSafely(); }

    void close() {
        cancelRemoteConfigPoll();
        if (remoteConfigScheduler != null) remoteConfigScheduler.shutdownNow();
    }

    private synchronized void refreshRemoteConfig() {
        if (!shouldPollRemoteConfig() || remoteConfigFetcher == null || config == null) {
            return;
        }

        RemoteConfigResponse response;
        try {
            response = remoteConfigFetcher.fetch(new RemoteConfigRequest(
                    EvidenceDeliveryState.url(RemoteConfigEndpoint.fromIngestionEndpoint(config.endpoint())),
                    config.projectToken(),
                    "@debugbundle/sdk-java",
                    "3.1.0",
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

        evidence.observe(parsed.evidenceCapabilities());
        remoteConfigSnapshot = parsed;
        remoteConfigFetchedOnce = true;
        if (parsed.remoteProbesEnabled()) {
            scheduleRemoteConfigPoll(parsed.pollIntervalMillis());
        } else {
            cancelRemoteConfigPoll();
        }
    }

    private void applyMinimalPolicyFallbackIfNeeded() {
        evidence.observe(null);
        if (!remoteConfigFetchedOnce && config != null) {
            remoteConfigSnapshot = RemoteConfigSnapshot.minimal(config.probesPollInterval().toMillis());
        }
    }

    void pruneExpiredRemoteProbeDirectives() {
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
                remoteConfigSnapshot.capturePolicy(),
                remoteConfigSnapshot.evidenceCapabilities()
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

    private boolean shouldPollRemoteConfig() {
        return active && config != null && !"local-only".equals(config.projectMode().trim().toLowerCase(Locale.ROOT))
                && !TransportFactory.isLocalEnvironment(config.environment());
    }

    private long now() { return clockMillis.get(); }
}

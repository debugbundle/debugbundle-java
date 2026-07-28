package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;

class CorePolicyUtilitiesTest {
    @Test
    void logLevelsNormalizeJulAndTextInputs() {
        assertThat(LogLevel.fromJulLevel(Level.SEVERE)).isEqualTo(LogLevel.ERROR);
        assertThat(LogLevel.fromJulLevel(Level.WARNING)).isEqualTo(LogLevel.WARNING);
        assertThat(LogLevel.fromJulLevel(Level.INFO)).isEqualTo(LogLevel.INFO);
        assertThat(LogLevel.fromJulLevel(Level.FINE)).isEqualTo(LogLevel.DEBUG);
        assertThat(LogLevel.fromName(null)).isEqualTo(LogLevel.WARNING);
        assertThat(LogLevel.fromName(" ")).isEqualTo(LogLevel.WARNING);
        assertThat(LogLevel.fromName(" warn ")).isEqualTo(LogLevel.WARNING);
        assertThat(LogLevel.fromName("critical")).isEqualTo(LogLevel.CRITICAL);
        assertThat(LogLevel.fromName("unknown")).isEqualTo(LogLevel.WARNING);
    }

    @Test
    void remoteConfigEndpointPreservesCompatibleEndpointForms() {
        assertThat(RemoteConfigEndpoint.fromIngestionEndpoint(null))
                .isEqualTo("https://api.debugbundle.com/v1/sdk/config");
        assertThat(RemoteConfigEndpoint.fromIngestionEndpoint(" "))
                .isEqualTo("https://api.debugbundle.com/v1/sdk/config");
        assertThat(RemoteConfigEndpoint.fromIngestionEndpoint("https://api.test/v1/events"))
                .isEqualTo("https://api.test/v1/sdk/config");
        assertThat(RemoteConfigEndpoint.fromIngestionEndpoint("https://api.test/v1/"))
                .isEqualTo("https://api.test/v1/sdk/config");
        assertThat(RemoteConfigEndpoint.fromIngestionEndpoint("https://api.test/v1"))
                .isEqualTo("https://api.test/v1/sdk/config");
    }

    @Test
    void requestScopeAndClientDefaultsAreSafe() {
        DebugBundleRequestScope scope = new DebugBundleRequestScope();
        DebugBundleRequestScope noop = DebugBundleRequestScope.noop();
        StubClient client = new StubClient();
        Runnable task = () -> { };

        assertThat(scope.state()).isNotNull();
        assertThat(noop.state()).isNull();
        assertThat(DebugBundleRequestScope.noop()).isSameAs(noop);
        assertThat(client.decorate(task)).isSameAs(task);
        client.close();
    }

    @Test
    void requestCapturePolicyCoversPresetsModesAndConfiguredOverrides() {
        assertThat(RequestCapturePolicy.shouldCapture(null, "/", "GET", policy(
                "minimal", CapturePolicy.CaptureRequestEventsMode.ALL, List.of(), List.of()))).isTrue();
        assertThat(RequestCapturePolicy.shouldCapture(null, "/", "GET", policy(
                "minimal", CapturePolicy.CaptureRequestEventsMode.OFF, List.of(), List.of()))).isFalse();
        assertThat(RequestCapturePolicy.shouldCapture(503, "/", "GET", policy(
                "minimal", CapturePolicy.CaptureRequestEventsMode.OFF, List.of(), List.of()))).isTrue();
        assertThat(RequestCapturePolicy.shouldCapture(401, "/", "GET", policy(
                "minimal", CapturePolicy.CaptureRequestEventsMode.FAILURES_ONLY, List.of(), List.of()))).isFalse();
        assertThat(RequestCapturePolicy.shouldCapture(401, "/", "GET", policy(
                "minimal", CapturePolicy.CaptureRequestEventsMode.FILTERED, List.of(), List.of()))).isFalse();
        assertThat(RequestCapturePolicy.shouldCapture(200, "/", "GET", policy(
                "minimal", CapturePolicy.CaptureRequestEventsMode.ALL, List.of(), List.of()))).isTrue();
        assertThat(RequestCapturePolicy.shouldCapture(418, "/", "GET", policy(
                "minimal", CapturePolicy.CaptureRequestEventsMode.OFF, List.of(418), List.of()))).isTrue();
        assertThat(RequestCapturePolicy.shouldCapture(409, "/", "GET", policy(
                "investigative", CapturePolicy.CaptureRequestEventsMode.OFF, List.of(), List.of()))).isTrue();
        assertThat(RequestCapturePolicy.shouldCapture(429, "/", "GET", policy(
                "balanced", CapturePolicy.CaptureRequestEventsMode.OFF, List.of(), List.of()))).isTrue();
        assertThat(RequestCapturePolicy.shouldCapture(401, "/", "GET", policy(
                "custom", CapturePolicy.CaptureRequestEventsMode.OFF, List.of(), List.of()))).isFalse();
    }

    @Test
    void requestCapturePathRulesNormalizeUrisAndEnforceMethods() {
        List<ImmediateClientErrorPathRule> rules = List.of(
                new ImmediateClientErrorPathRule(404, "/checkout/*", List.of("POST")),
                new ImmediateClientErrorPathRule(409, "/orders", List.of())
        );
        CapturePolicy policy = policy("custom", CapturePolicy.CaptureRequestEventsMode.OFF, List.of(), rules);

        assertThat(RequestCapturePolicy.shouldCapture(
                404, "https://shop.test/checkout/cart?coupon=1#summary", " post ", policy)).isTrue();
        assertThat(RequestCapturePolicy.shouldCapture(404, "/checkout/cart?coupon=1", "GET", policy)).isFalse();
        assertThat(RequestCapturePolicy.shouldCapture(404, "/checkout/cart", null, policy)).isFalse();
        assertThat(RequestCapturePolicy.shouldCapture(409, "/orders#top", null, policy)).isTrue();
        assertThat(RequestCapturePolicy.shouldCapture(409, "not a valid URI?x=1", "GET", policy)).isFalse();
        assertThat(RequestCapturePolicy.shouldCapture(399, "/orders", "GET", policy)).isFalse();
        assertThat(RequestCapturePolicy.shouldCapture(409, " ", "GET", policy)).isFalse();
    }

    @Test
    void suppressionKeysAreStableAcrossSupportedEventShapes() {
        String backend = SuppressionKeyBuilder.build(Map.of(
                "event_type", "backend_exception",
                "payload", Map.of(
                        "name", "Failure",
                        "message", "boom",
                        "stack", "at Example.run(Example.java:10)\nat Example.call(Example.java:20)",
                        "request", Map.of("path", "/orders"),
                        "response", Map.of("status_code", 500)
                )
        ));
        String frameBackend = SuppressionKeyBuilder.build(Map.of(
                "event_type", "backend_exception",
                "payload", Map.of(
                        "stack", List.of(
                                "ignored",
                                Map.of("class", "Example", "method", "run", "file", "Example.java")
                        )
                )
        ));
        String log = SuppressionKeyBuilder.build(Map.of(
                "event_type", "log_event",
                "payload", Map.of("level", "error", "message", "boom", "attributes", Map.of("tenant", "acme"))
        ));
        String request = SuppressionKeyBuilder.build(Map.of(
                "event_type", "request_event",
                "payload", Map.of("method", "GET", "path", "/orders", "response_status", 503)
        ));

        assertThat(backend).contains("Example.java:?)").contains("\"path\":\"/orders\"");
        assertThat(frameBackend).contains("\"class\":\"Example\"").contains("\"method\":\"run\"");
        assertThat(log).contains("\"event_type\":\"log_event\"").contains("\"tenant\":\"acme\"");
        assertThat(request).contains("\"event_type\":\"request_event\"").contains("\"status\":503");
        assertThat(SuppressionKeyBuilder.build(Map.of("event_type", "probe_event"))).isNull();
        assertThat(SuppressionKeyBuilder.build(Map.of())).isNull();
    }

    private static CapturePolicy policy(
            String preset,
            CapturePolicy.CaptureRequestEventsMode requestMode,
            List<Integer> statuses,
            List<ImmediateClientErrorPathRule> rules
    ) {
        return new CapturePolicy(
                preset,
                CapturePolicy.CaptureLogsMode.WARNING,
                requestMode,
                CapturePolicy.CaptureBreadcrumbsMode.EXCEPTION_ONLY,
                CapturePolicy.CaptureProbeEventsMode.BUFFER_ONLY,
                statuses,
                rules
        );
    }

    private static final class StubClient implements DebugBundleClient {
        @Override public DebugBundleConfig config() { return DebugBundleConfig.builder().enabled(false).build(); }
        @Override public void captureException(Throwable error) { }
        @Override public void captureException(Throwable error, Map<String, Object> context) { }
        @Override public void captureError(Throwable error) { }
        @Override public void captureLog(String message, LogLevel level) { }
        @Override public void captureLog(String message, LogLevel level, Map<String, Object> context) { }
        @Override public void captureRequest(Object request, Object response, Map<String, Object> context) { }
        @Override public void captureMessage(String message) { }
        @Override public void captureMessage(String message, LogLevel level, Map<String, Object> context) { }
        @Override public void setContext(String key, Object value) { }
        @Override public void probe(String label, Object data) { }
        @Override public void probe(String label, java.util.function.Supplier<?> supplier) { }
        @Override public void probe(String label, java.util.function.Supplier<?> supplier, ProbeOptions options) { }
        @Override public DebugBundleRequestScope beginRequest(Map<String, Object> request) {
            return DebugBundleRequestScope.noop();
        }
        @Override public void endRequest(DebugBundleRequestScope scope) { }
        @Override public java.util.concurrent.CompletableFuture<Void> flush() {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        @Override public DebugBundleStatus status() { return DebugBundleStatus.DISCONNECTED; }
        @Override public java.util.Optional<java.time.Instant> lastEventAt() { return java.util.Optional.empty(); }
    }
}

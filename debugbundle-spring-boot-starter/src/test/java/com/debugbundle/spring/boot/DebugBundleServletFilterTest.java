package com.debugbundle.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.DebugBundleConfig;
import com.debugbundle.sdk.DebugBundleRequestScope;
import com.debugbundle.sdk.DebugBundleStatus;
import com.debugbundle.sdk.LogLevel;
import com.debugbundle.sdk.ProbeOptions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class DebugBundleServletFilterTest {
    @Test
    void filterBeginsAndEndsRequestScopeWithNormalizedTriggerInputs() throws Exception {
        RecordingClient client = new RecordingClient();
        DebugBundleServletFilter filter = new DebugBundleServletFilter(client);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/checkout");
        request.setParameter("_debug_probe", "query-token");
        request.addHeader("X-DebugBundle-Probe-Trigger", "header-token");
        request.addHeader("X-DebugBundle-Trace-Id", "trace-123");
        request.addHeader("X-Request-Id", "req-123");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/checkout/{id}");

        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(201);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(client.begunRequests).hasSize(1);
        assertThat(client.endedScopes).hasSize(1);
        assertThat(client.endedScopes.get(0)).isSameAs(client.begunScope);
        assertThat(client.capturedRequests).hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> begunHeaders = (Map<String, Object>) client.begunRequests.get(0).get("headers");
        @SuppressWarnings("unchecked")
        Map<String, Object> begunQuery = (Map<String, Object>) client.begunRequests.get(0).get("query");

        assertThat(client.begunRequests.get(0)).containsEntry("method", "GET");
        assertThat(client.begunRequests.get(0)).containsEntry("path", "/checkout");
        assertThat(begunHeaders).containsEntry("X-DebugBundle-Probe-Trigger", "header-token");
        assertThat(begunHeaders).containsEntry("X-DebugBundle-Trace-Id", "trace-123");
        assertThat(begunQuery).containsEntry("_debug_probe", "query-token");
    }

    @Test
    void filterSwallowsSdkFailuresWithoutBreakingServletChain() {
        DebugBundleServletFilter filter = new DebugBundleServletFilter(new ThrowingClient());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/checkout");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatCode(() -> filter.doFilter(request, response, new MockFilterChain()))
                .doesNotThrowAnyException();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static final class RecordingClient implements DebugBundleClient {
        private final List<Map<String, Object>> begunRequests = new ArrayList<>();
        private final List<DebugBundleRequestScope> endedScopes = new ArrayList<>();
        private final List<Map<String, Object>> capturedRequests = new ArrayList<>();
        private DebugBundleRequestScope begunScope;

        @Override
        public DebugBundleConfig config() {
            return DebugBundleConfig.builder().enabled(false).build();
        }

        @Override
        public void captureException(Throwable error) {
        }

        @Override
        public void captureException(Throwable error, Map<String, Object> context) {
        }

        @Override
        public void captureError(Throwable error) {
        }

        @Override
        public void captureLog(String message, LogLevel level) {
        }

        @Override
        public void captureLog(String message, LogLevel level, Map<String, Object> context) {
        }

        @Override
        public void captureRequest(Object request, Object response, Map<String, Object> context) {
            capturedRequests.add(context);
        }

        @Override
        public void captureMessage(String message) {
        }

        @Override
        public void captureMessage(String message, LogLevel level, Map<String, Object> context) {
        }

        @Override
        public void setContext(String key, Object value) {
        }

        @Override
        public void probe(String label, Object data) {
        }

        @Override
        public void probe(String label, Supplier<?> dataSupplier) {
        }

        @Override
        public void probe(String label, Supplier<?> dataSupplier, ProbeOptions options) {
        }

        @Override
        public DebugBundleRequestScope beginRequest(Map<String, Object> request) {
            begunRequests.add(request);
            begunScope = new DebugBundleRequestScope();
            return begunScope;
        }

        @Override
        public void endRequest(DebugBundleRequestScope scope) {
            endedScopes.add(scope);
        }

        @Override
        public CompletableFuture<Void> flush() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public DebugBundleStatus status() {
            return DebugBundleStatus.HEALTHY;
        }

        @Override
        public Optional<Instant> lastEventAt() {
            return Optional.empty();
        }
    }

    private static final class ThrowingClient implements DebugBundleClient {
        @Override
        public DebugBundleConfig config() {
            throw new IllegalStateException("config failed");
        }

        @Override
        public void captureException(Throwable error) {
            throw new IllegalStateException("capture failed");
        }

        @Override
        public void captureException(Throwable error, Map<String, Object> context) {
            throw new IllegalStateException("capture failed");
        }

        @Override
        public void captureError(Throwable error) {
            throw new IllegalStateException("capture failed");
        }

        @Override
        public void captureLog(String message, LogLevel level) {
            throw new IllegalStateException("capture failed");
        }

        @Override
        public void captureLog(String message, LogLevel level, Map<String, Object> context) {
            throw new IllegalStateException("capture failed");
        }

        @Override
        public void captureRequest(Object request, Object response, Map<String, Object> context) {
            throw new IllegalStateException("capture failed");
        }

        @Override
        public void captureMessage(String message) {
            throw new IllegalStateException("capture failed");
        }

        @Override
        public void captureMessage(String message, LogLevel level, Map<String, Object> context) {
            throw new IllegalStateException("capture failed");
        }

        @Override
        public void setContext(String key, Object value) {
            throw new IllegalStateException("context failed");
        }

        @Override
        public void probe(String label, Object data) {
            throw new IllegalStateException("probe failed");
        }

        @Override
        public void probe(String label, Supplier<?> dataSupplier) {
            throw new IllegalStateException("probe failed");
        }

        @Override
        public void probe(String label, Supplier<?> dataSupplier, ProbeOptions options) {
            throw new IllegalStateException("probe failed");
        }

        @Override
        public DebugBundleRequestScope beginRequest(Map<String, Object> request) {
            throw new IllegalStateException("begin failed");
        }

        @Override
        public void endRequest(DebugBundleRequestScope scope) {
            throw new IllegalStateException("end failed");
        }

        @Override
        public CompletableFuture<Void> flush() {
            throw new IllegalStateException("flush failed");
        }

        @Override
        public DebugBundleStatus status() {
            return DebugBundleStatus.DEGRADED;
        }

        @Override
        public Optional<Instant> lastEventAt() {
            return Optional.empty();
        }
    }
}

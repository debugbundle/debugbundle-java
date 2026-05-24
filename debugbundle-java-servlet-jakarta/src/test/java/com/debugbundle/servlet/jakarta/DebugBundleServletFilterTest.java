package com.debugbundle.servlet.jakarta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.DebugBundleConfig;
import com.debugbundle.sdk.DebugBundleRequestScope;
import com.debugbundle.sdk.DebugBundleStatus;
import com.debugbundle.sdk.LogLevel;
import com.debugbundle.sdk.ProbeOptions;
import jakarta.servlet.ServletException;
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

class DebugBundleServletFilterTest {
    @Test
    void filterBeginsAndEndsRequestScopeWithNormalizedTriggerInputs() throws Exception {
        RecordingClient client = new RecordingClient();
        DebugBundleServletFilter filter = new DebugBundleServletFilter(client);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders/42");
        request.setParameter("_debug_probe", "query-token");
        request.addHeader("X-DebugBundle-Probe-Trigger", "header-token");
        request.addHeader("X-DebugBundle-Trace-Id", "trace-123");
        request.addHeader("X-Request-Id", "req-123");

        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(201);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(client.begunRequests).hasSize(1);
        assertThat(client.endedScopes).containsExactly(client.begunScope);
        assertThat(client.capturedRequests).hasSize(1);
        assertThat(client.begunRequests.get(0))
                .containsEntry("method", "GET")
                .containsEntry("path", "/orders/42")
            .containsEntry("route_template", null);

        assertThat(objectMap(client.begunRequests.get(0).get("headers")))
                .containsEntry("X-DebugBundle-Probe-Trigger", "header-token")
                .containsEntry("X-DebugBundle-Trace-Id", "trace-123");
        assertThat(objectMap(client.begunRequests.get(0).get("query")))
                .containsEntry("_debug_probe", "query-token");
        assertThat(client.capturedContexts.get(0))
                .containsEntry("response_status", 201)
                .containsEntry("trace_id", "trace-123")
                .containsEntry("request_id", "req-123");
    }

    @Test
    void filterCapturesExceptionsAndMarksRequestBeforeRethrowing() {
        RecordingClient client = new RecordingClient();
        DebugBundleServletFilter filter = new DebugBundleServletFilter(client);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            throw new ServletException("boom");
        })).isInstanceOf(ServletException.class);

        assertThat(client.capturedExceptions).hasSize(1);
        assertThat(client.capturedContexts.get(0))
                .containsEntry("method", "POST")
                .containsEntry("path", "/orders");
        assertThat(request.getAttribute(DebugBundleServletRequestAttributes.EXCEPTION_CAPTURED)).isEqualTo(Boolean.TRUE);
        assertThat(client.endedScopes).containsExactly(client.begunScope);
    }

    @Test
    void filterSwallowsSdkFailuresWithoutBreakingServletChain() {
        DebugBundleServletFilter filter = new DebugBundleServletFilter(new ThrowingClient());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatCode(() -> filter.doFilter(request, response, new MockFilterChain()))
                .doesNotThrowAnyException();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static final class RecordingClient implements DebugBundleClient {
        private final List<Map<String, Object>> begunRequests = new ArrayList<>();
        private final List<DebugBundleRequestScope> endedScopes = new ArrayList<>();
        private final List<Object> capturedRequests = new ArrayList<>();
        private final List<Throwable> capturedExceptions = new ArrayList<>();
        private final List<Map<String, Object>> capturedContexts = new ArrayList<>();
        private DebugBundleRequestScope begunScope;

        @Override
        public DebugBundleConfig config() {
            return DebugBundleConfig.builder().enabled(false).build();
        }

        @Override
        public void captureException(Throwable error) {
            capturedExceptions.add(error);
        }

        @Override
        public void captureException(Throwable error, Map<String, Object> context) {
            capturedExceptions.add(error);
            capturedContexts.add(context);
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
            capturedRequests.add(request);
            capturedContexts.add(context);
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
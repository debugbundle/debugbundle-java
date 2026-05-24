package com.debugbundle.servlet.javax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.DebugBundleConfig;
import com.debugbundle.sdk.DebugBundleRequestScope;
import com.debugbundle.sdk.DebugBundleStatus;
import com.debugbundle.sdk.LogLevel;
import com.debugbundle.sdk.ProbeOptions;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

class DebugBundleServletFilterTest {
    @Test
    void filterBeginsAndEndsRequestScopeWithNormalizedTriggerInputs() throws Exception {
        RecordingClient client = new RecordingClient();
        DebugBundleServletFilter filter = new DebugBundleServletFilter(client);
        TestRequest request = new TestRequest("GET", "/legacy/orders/42")
            .header("X-DebugBundle-Probe-Trigger", "header-token")
            .header("X-DebugBundle-Trace-Id", "trace-123")
            .header("X-Request-Id", "req-123")
            .parameter("_debug_probe", "query-token");
        TestResponse response = new TestResponse(201);
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilter(request.servletRequest(), response.servletResponse(), chain);

        assertThat(chain.invoked).isTrue();
        assertThat(client.begunRequests).hasSize(1);
        assertThat(client.endedScopes).containsExactly(client.begunScope);
        assertThat(client.capturedRequests).hasSize(1);
        assertThat(client.begunRequests.get(0))
                .containsEntry("method", "GET")
                .containsEntry("path", "/legacy/orders/42")
            .containsEntry("route_template", null);
        assertThat(objectMap(client.begunRequests.get(0).get("headers")))
                .containsEntry("X-DebugBundle-Probe-Trigger", "header-token")
                .containsEntry("X-DebugBundle-Trace-Id", "trace-123");
        assertThat(objectMap(client.begunRequests.get(0).get("query")))
                .containsEntry("_debug_probe", "query-token");
    }

    @Test
    void filterCapturesExceptionsAndMarksRequestBeforeRethrowing() throws Exception {
        RecordingClient client = new RecordingClient();
        DebugBundleServletFilter filter = new DebugBundleServletFilter(client);
        TestRequest request = new TestRequest("POST", "/legacy/orders");
        TestResponse response = new TestResponse(500);
        ServletException failure = new ServletException("boom");
        RecordingFilterChain chain = new RecordingFilterChain(failure);

        assertThatThrownBy(() -> filter.doFilter(request.servletRequest(), response.servletResponse(), chain))
                .isSameAs(failure);

        assertThat(client.capturedExceptions).containsExactly(failure);
        assertThat(client.capturedContexts.get(0))
                .containsEntry("method", "POST")
                .containsEntry("path", "/legacy/orders")
                .containsEntry("response_status", 500);
        assertThat(request.attribute(DebugBundleServletRequestAttributes.EXCEPTION_CAPTURED)).isEqualTo(Boolean.TRUE);
        assertThat(client.endedScopes).containsExactly(client.begunScope);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Byte.TYPE) {
            return (byte) 0;
        }
        if (returnType == Short.TYPE) {
            return (short) 0;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == Float.TYPE) {
            return 0.0f;
        }
        if (returnType == Double.TYPE) {
            return 0.0d;
        }
        if (returnType == Character.TYPE) {
            return '\0';
        }
        return null;
    }

    private static final class TestRequest {
        private final String method;
        private final String path;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final Map<String, String[]> parameters = new LinkedHashMap<>();
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private final HttpServletRequest servletRequest;

        private TestRequest(String method, String path) {
            this.method = method;
            this.path = path;
            this.servletRequest = (HttpServletRequest) Proxy.newProxyInstance(
                    HttpServletRequest.class.getClassLoader(),
                    new Class<?>[] {HttpServletRequest.class},
                    (proxy, invokedMethod, args) -> handle(proxy, invokedMethod.getName(), invokedMethod.getReturnType(), args)
            );
        }

        private TestRequest header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        private TestRequest parameter(String name, String... values) {
            parameters.put(name, values);
            return this;
        }

        private HttpServletRequest servletRequest() {
            return servletRequest;
        }

        private Object attribute(String name) {
            return attributes.get(name);
        }

        private Object handle(Object proxy, String methodName, Class<?> returnType, Object[] args) {
            return switch (methodName) {
                case "getMethod" -> method;
                case "getRequestURI" -> path;
                case "getHeader" -> headers.get((String) args[0]);
                case "getParameterNames" -> Collections.enumeration(parameters.keySet());
                case "getParameterValues" -> parameters.get((String) args[0]);
                case "getAttribute" -> attributes.get((String) args[0]);
                case "setAttribute" -> {
                    attributes.put((String) args[0], args[1]);
                    yield null;
                }
                case "toString" -> "TestHttpServletRequest{" + method + " " + path + "}";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> defaultValue(returnType);
            };
        }
    }

    private static final class TestResponse {
        private final int status;
        private final HttpServletResponse servletResponse;

        private TestResponse(int status) {
            this.status = status;
            this.servletResponse = (HttpServletResponse) Proxy.newProxyInstance(
                    HttpServletResponse.class.getClassLoader(),
                    new Class<?>[] {HttpServletResponse.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getStatus" -> status;
                        case "toString" -> "TestHttpServletResponse{" + status + "}";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    }
            );
        }

        private HttpServletResponse servletResponse() {
            return servletResponse;
        }
    }

    private static final class RecordingFilterChain implements FilterChain {
        private final ServletException failure;
        private boolean invoked;

        private RecordingFilterChain() {
            this(null);
        }

        private RecordingFilterChain(ServletException failure) {
            this.failure = failure;
        }

        @Override
        public void doFilter(javax.servlet.ServletRequest request, javax.servlet.ServletResponse response)
                throws ServletException {
            invoked = true;
            if (failure != null) {
                throw failure;
            }
        }
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
}
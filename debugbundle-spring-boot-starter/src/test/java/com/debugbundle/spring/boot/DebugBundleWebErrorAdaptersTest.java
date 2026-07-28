package com.debugbundle.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.DebugBundleConfig;
import com.debugbundle.sdk.DebugBundleRequestScope;
import com.debugbundle.sdk.DebugBundleStatus;
import com.debugbundle.sdk.LogLevel;
import com.debugbundle.sdk.ProbeOptions;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DebugBundleWebErrorAdaptersTest {
    @Test
    void exceptionResolverCapturesOnceWithRequestCorrelationAndPreservesMvcFlow() {
        RecordingClient client = new RecordingClient(false);
        DebugBundleExceptionResolver resolver = new DebugBundleExceptionResolver(client);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders/42");
        request.addHeader("X-DebugBundle-Trace-Id", "trace-123");
        request.addHeader("X-Request-Id", "request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(503);
        Exception failure = new IllegalStateException("failed");

        assertThat(resolver.resolveException(request, response, new Object(), failure)).isNull();
        assertThat(resolver.resolveException(request, response, new Object(), failure)).isNull();

        assertThat(client.exceptions).containsExactly(failure);
        assertThat(client.contexts.get(0))
                .containsEntry("method", "GET")
                .containsEntry("path", "/orders/42")
                .containsEntry("response_status", 503)
                .containsEntry("trace_id", "trace-123")
                .containsEntry("request_id", "request-123");
        assertThat(request.getAttribute(DebugBundleRequestAttributes.EXCEPTION_CAPTURED)).isEqualTo(Boolean.TRUE);
        assertThat(resolver.getOrder()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void exceptionResolverContainsSdkFailuresAndHandlesMissingResponse() {
        DebugBundleExceptionResolver resolver = new DebugBundleExceptionResolver(new RecordingClient(true));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/checkout");

        assertThat(resolver.resolveException(
                request, null, null, new RuntimeException("failed"))).isNull();
        assertThat(request.getAttribute(DebugBundleRequestAttributes.EXCEPTION_CAPTURED)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void relayControllerHandlesPreflightAcceptedOversizedAndUnreadableBodies() {
        DebugBundleProperties properties = new DebugBundleProperties();
        properties.setProjectMode("connected");
        properties.setProjectToken("dbundle_proj_test");
        properties.getRelay().setDurableWrite(false);
        properties.getRelay().setAllowedOrigins(List.of("https://app.example.test"));
        DebugBundleRelayController controller = new DebugBundleRelayController(
                new DebugBundleBrowserRelayHandler(properties, events -> true));

        MockHttpServletRequest options = request("OPTIONS");
        ResponseEntity<?> preflight = controller.relay(options);

        MockHttpServletRequest post = request("POST");
        post.setContent("{\"batch\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ResponseEntity<?> accepted = controller.relay(post);

        MockHttpServletRequest oversized = request("POST");
        oversized.setContent(new byte[DebugBundleBrowserRelayHandler.DEFAULT_MAX_BODY_BYTES + 1]);
        ResponseEntity<?> tooLarge = controller.relay(oversized);

        MockHttpServletRequest unreadable = new MockHttpServletRequest("POST", "/debugbundle/browser") {
            @Override
            public jakarta.servlet.ServletInputStream getInputStream() {
                return new jakarta.servlet.ServletInputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("unreadable");
                    }

                    @Override
                    public boolean isFinished() {
                        return false;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(jakarta.servlet.ReadListener readListener) {
                    }
                };
            }
        };
        unreadable.setRemoteAddr("127.0.0.4");
        unreadable.addHeader("Origin", "https://app.example.test");
        unreadable.addHeader("Host", "app.example.test");
        unreadable.setContentType("application/json");
        ResponseEntity<?> badRequest = controller.relay(unreadable);

        assertThat(preflight.getStatusCode().value()).isEqualTo(204);
        assertThat(preflight.getHeaders().getFirst("Access-Control-Allow-Origin"))
                .isEqualTo("https://app.example.test");
        assertThat(accepted.getStatusCode().value()).isEqualTo(202);
        assertThat(accepted.getBody()).isEqualTo(Map.of("accepted", 0, "rejected", 0, "errors", List.of()));
        assertThat(tooLarge.getStatusCode().value()).isEqualTo(413);
        assertThat(badRequest.getStatusCode().value()).isEqualTo(400);
        assertThat(badRequest.getBody()).isEqualTo(
                Map.of("errors", List.of("Relay request body could not be read.")));
    }

    private static MockHttpServletRequest request(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/debugbundle/browser");
        request.setRemoteAddr("127.0.0.3");
        request.addHeader("Origin", "https://app.example.test");
        request.addHeader("Host", "app.example.test");
        request.setContentType("application/json");
        return request;
    }

    private static final class RecordingClient implements DebugBundleClient {
        private final boolean throwOnCapture;
        private final List<Throwable> exceptions = new ArrayList<>();
        private final List<Map<String, Object>> contexts = new ArrayList<>();

        private RecordingClient(boolean throwOnCapture) {
            this.throwOnCapture = throwOnCapture;
        }

        @Override public DebugBundleConfig config() { return DebugBundleConfig.builder().enabled(false).build(); }
        @Override public void captureException(Throwable error) { }
        @Override public void captureException(Throwable error, Map<String, Object> context) {
            if (throwOnCapture) {
                throw new IllegalStateException("capture failed");
            }
            exceptions.add(error);
            contexts.add(context);
        }
        @Override public void captureError(Throwable error) { }
        @Override public void captureLog(String message, LogLevel level) { }
        @Override public void captureLog(String message, LogLevel level, Map<String, Object> context) { }
        @Override public void captureRequest(Object request, Object response, Map<String, Object> context) { }
        @Override public void captureMessage(String message) { }
        @Override public void captureMessage(String message, LogLevel level, Map<String, Object> context) { }
        @Override public void setContext(String key, Object value) { }
        @Override public void probe(String label, Object data) { }
        @Override public void probe(String label, Supplier<?> dataSupplier) { }
        @Override public void probe(String label, Supplier<?> dataSupplier, ProbeOptions options) { }
        @Override public DebugBundleRequestScope beginRequest(Map<String, Object> request) {
            return DebugBundleRequestScope.noop();
        }
        @Override public void endRequest(DebugBundleRequestScope scope) { }
        @Override public CompletableFuture<Void> flush() { return CompletableFuture.completedFuture(null); }
        @Override public DebugBundleStatus status() { return DebugBundleStatus.DISCONNECTED; }
        @Override public Optional<Instant> lastEventAt() { return Optional.empty(); }
    }
}

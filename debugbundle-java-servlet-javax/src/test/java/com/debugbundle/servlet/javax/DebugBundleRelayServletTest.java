package com.debugbundle.servlet.javax;

import static org.assertj.core.api.Assertions.assertThat;

import com.debugbundle.sdk.web.DebugBundleBrowserRelay;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.ReadListener;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

class DebugBundleRelayServletTest {
    @Test
    void relayServletReturnsAcceptedJsonResponse() throws Exception {
        DebugBundleRelayServlet servlet = new DebugBundleRelayServlet(new DebugBundleBrowserRelay(
                new DebugBundleBrowserRelay.Config(
                        "dbundle_proj_test",
                        "https://api.debugbundle.com/v1/events",
                        "connected",
                        ".debugbundle/local/events",
                        60,
                        false,
                        ".debugbundle/local/browser-relay-spool",
                        List.of()
                ),
                events -> true
        ));
        TestHttpServletRequest request = new TestHttpServletRequest(
                "POST",
                "application/json",
                "127.0.0.1",
                Map.of(
                        "Host", "app.example.com",
                        "Origin", "https://app.example.com"
                ),
                "{\"batch\":[{" +
                        "\"schema_version\":\"2026-03-01\"," +
                        "\"event_id\":\"11111111-1111-4111-8111-111111111111\"," +
                        "\"event_type\":\"frontend_exception\"," +
                        "\"sdk_version\":\"1.0.0\"," +
                        "\"occurred_at\":\"2026-05-21T06:00:00Z\"," +
                        "\"service\":{\"name\":\"checkout-web\",\"environment\":\"production\"}," +
                        "\"payload\":{\"message\":\"boom\"}}]}"
        );
        TestHttpServletResponse response = new TestHttpServletResponse();

        servlet.doPost(request.proxy(), response.proxy());

        assertThat(response.status()).isEqualTo(202);
        assertThat(response.contentType()).isEqualTo("application/json");
        assertThat(response.body()).contains("\"accepted\":1");
    }

    @Test
    void relayServletHandlesPreflightOversizedAndUnreadableBodies() throws Exception {
        DebugBundleRelayServlet servlet = relayServlet();

        TestHttpServletRequest options = new TestHttpServletRequest(
                "OPTIONS", null, "127.0.0.2",
                Map.of("Host", "app.example.com", "Origin", "https://app.example.com"), "");
        TestHttpServletResponse preflight = new TestHttpServletResponse();
        servlet.doOptions(options.proxy(), preflight.proxy());

        TestHttpServletRequest oversized = new TestHttpServletRequest(
                "POST", "application/json", "127.0.0.3",
                Map.of("Host", "app.example.com", "Origin", "https://app.example.com"),
                "x".repeat(DebugBundleBrowserRelay.DEFAULT_MAX_BODY_BYTES + 1));
        TestHttpServletResponse tooLarge = new TestHttpServletResponse();
        servlet.doPost(oversized.proxy(), tooLarge.proxy());

        TestHttpServletRequest unreadable = new TestHttpServletRequest(
                "POST", "application/json", "127.0.0.4",
                Map.of("Host", "app.example.com", "Origin", "https://app.example.com"), null);
        TestHttpServletResponse badRequest = new TestHttpServletResponse();
        servlet.doPost(unreadable.proxy(), badRequest.proxy());

        assertThat(preflight.status()).isEqualTo(204);
        assertThat(preflight.header("Access-Control-Allow-Origin")).isEqualTo("https://app.example.com");
        assertThat(tooLarge.status()).isEqualTo(413);
        assertThat(badRequest.status()).isEqualTo(400);
        assertThat(badRequest.body()).contains("Relay request body could not be read.");
    }

    @Test
    void relayServletInitializesFromServletContextParameters() throws Exception {
        Map<String, String> parameters = Map.of(
                "debugbundle.project-mode", "connected",
                "debugbundle.relay.durable-write", "false"
        );
        ServletContext context = (ServletContext) Proxy.newProxyInstance(
                ServletContext.class.getClassLoader(),
                new Class<?>[] {ServletContext.class},
                (instance, method, args) -> switch (method.getName()) {
                    case "getInitParameter" -> parameters.get((String) args[0]);
                    case "toString" -> "TestServletContext";
                    case "hashCode" -> System.identityHashCode(instance);
                    case "equals" -> instance == args[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
        ServletConfig config = (ServletConfig) Proxy.newProxyInstance(
                ServletConfig.class.getClassLoader(),
                new Class<?>[] {ServletConfig.class},
                (instance, method, args) -> switch (method.getName()) {
                    case "getServletContext" -> context;
                    case "getInitParameter" -> parameters.get((String) args[0]);
                    case "getServletName" -> "debugbundle-relay";
                    case "toString" -> "TestServletConfig";
                    case "hashCode" -> System.identityHashCode(instance);
                    case "equals" -> instance == args[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
        DebugBundleRelayServlet servlet = new DebugBundleRelayServlet();
        servlet.init(config);
        TestHttpServletRequest request = new TestHttpServletRequest(
                "POST", "application/json", "127.0.0.5",
                Map.of("Host", "app.example.com", "Origin", "https://app.example.com"),
                "{\"batch\":[]}");
        TestHttpServletResponse response = new TestHttpServletResponse();

        servlet.doPost(request.proxy(), response.proxy());

        assertThat(response.status()).isEqualTo(202);
    }

    private DebugBundleRelayServlet relayServlet() {
        return new DebugBundleRelayServlet(new DebugBundleBrowserRelay(
                new DebugBundleBrowserRelay.Config(
                        "dbundle_proj_test",
                        "https://api.debugbundle.com/v1/events",
                        "connected",
                        ".debugbundle/local/events",
                        60,
                        false,
                        ".debugbundle/local/browser-relay-spool",
                        List.of()
                ),
                events -> true
        ));
    }

    private static final class TestHttpServletRequest {
        private final HttpServletRequest proxy;

        private TestHttpServletRequest(
                String method,
                String contentType,
                String remoteAddr,
                Map<String, String> headers,
                String body
        ) {
            byte[] requestBody = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
            proxy = (HttpServletRequest) Proxy.newProxyInstance(
                    HttpServletRequest.class.getClassLoader(),
                    new Class<?>[] {HttpServletRequest.class},
                    (instance, invokedMethod, args) -> switch (invokedMethod.getName()) {
                        case "getMethod" -> method;
                        case "getContentType" -> contentType;
                        case "getRemoteAddr" -> remoteAddr;
                        case "getHeader" -> headers.get((String) args[0]);
                        case "getInputStream" -> {
                            if (requestBody == null) {
                                throw new IOException("unreadable");
                            }
                            yield new ByteArrayServletInputStream(requestBody);
                        }
                        case "toString" -> "TestHttpServletRequest{" + method + "}";
                        case "hashCode" -> System.identityHashCode(instance);
                        case "equals" -> instance == args[0];
                        default -> defaultValue(invokedMethod.getReturnType());
                    }
            );
        }

        private HttpServletRequest proxy() {
            return proxy;
        }
    }

    private static final class TestHttpServletResponse {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int status;
        private String contentType;
        private final Map<String, String> headers = new java.util.HashMap<>();
        private final HttpServletResponse proxy;

        private TestHttpServletResponse() {
            proxy = (HttpServletResponse) Proxy.newProxyInstance(
                    HttpServletResponse.class.getClassLoader(),
                    new Class<?>[] {HttpServletResponse.class},
                    (instance, invokedMethod, args) -> switch (invokedMethod.getName()) {
                        case "setStatus" -> {
                            status = (Integer) args[0];
                            yield null;
                        }
                        case "setContentType" -> {
                            contentType = (String) args[0];
                            yield null;
                        }
                        case "setHeader" -> {
                            headers.put((String) args[0], (String) args[1]);
                            yield null;
                        }
                        case "getOutputStream" -> new ByteArrayServletOutputStream(output);
                        case "toString" -> "TestHttpServletResponse{" + status + "}";
                        case "hashCode" -> System.identityHashCode(instance);
                        case "equals" -> instance == args[0];
                        default -> defaultValue(invokedMethod.getReturnType());
                    }
            );
        }

        private HttpServletResponse proxy() {
            return proxy;
        }

        private int status() {
            return status;
        }

        private String contentType() {
            return contentType;
        }

        private String body() {
            return output.toString(StandardCharsets.UTF_8);
        }

        private String header(String name) {
            return headers.get(name);
        }
    }

    private static final class ByteArrayServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream input;

        private ByteArrayServletInputStream(byte[] body) {
            this.input = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return input.read();
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
        }
    }

    private static final class ByteArrayServletOutputStream extends ServletOutputStream {
        private final ByteArrayOutputStream output;

        private ByteArrayServletOutputStream(ByteArrayOutputStream output) {
            this.output = output;
        }

        @Override
        public void write(int value) throws IOException {
            output.write(value);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
        }
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
}

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
                        "\"sdk_version\":\"0.1.0\"," +
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

    private static final class TestHttpServletRequest {
        private final HttpServletRequest proxy;

        private TestHttpServletRequest(
                String method,
                String contentType,
                String remoteAddr,
                Map<String, String> headers,
                String body
        ) {
            byte[] requestBody = body.getBytes(StandardCharsets.UTF_8);
            proxy = (HttpServletRequest) Proxy.newProxyInstance(
                    HttpServletRequest.class.getClassLoader(),
                    new Class<?>[] {HttpServletRequest.class},
                    (instance, invokedMethod, args) -> switch (invokedMethod.getName()) {
                        case "getMethod" -> method;
                        case "getContentType" -> contentType;
                        case "getRemoteAddr" -> remoteAddr;
                        case "getHeader" -> headers.get((String) args[0]);
                        case "getInputStream" -> new ByteArrayServletInputStream(requestBody);
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
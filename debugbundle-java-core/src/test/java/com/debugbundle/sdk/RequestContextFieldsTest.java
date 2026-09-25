package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestContextFieldsTest {
    @Test
    void requestContextUsesBoundedHeaderFallbacksAndIgnoresBlankValues() {
        Map<String, Object> context = RequestContextFields.buildRequestScopeContext(Map.of(
                "method", "GET",
                "path", "/orders",
                "request_id", " ",
                "headers", Map.of(
                        "X-Request-ID", List.of(" ", "request-1"),
                        "X-DebugBundle-Trace-ID", "trace-1"
                )
        ));

        assertThat(context).containsEntry("method", "GET")
                .containsEntry("path", "/orders")
                .containsEntry("request_id", "request-1")
                .containsEntry("trace_id", "trace-1");
        assertThat(RequestContextFields.buildRequestScopeContext(Map.of())).isEmpty();
        assertThat(RequestContextFields.buildRequestScopeContext(null)).isEmpty();
    }

    @Test
    void malformedHeaderAndStatusInputsCannotBreakRequestCapture() {
        assertThat(RequestContextFields.buildRequestScopeContext(Map.of("headers", "invalid"))).isEmpty();
        assertThat(RequestContextFields.extractStatusCode(Map.of("status_code", "503"))).isEqualTo(503);
        assertThat(RequestContextFields.extractStatusCode(Map.of("status", 429L))).isEqualTo(429);
        assertThat(RequestContextFields.extractStatusCode(Map.of("status", "invalid"))).isNull();
        assertThat(RequestContextFields.extractStatusCode("invalid")).isNull();
        assertThat(RequestContextFields.asString(12)).isNull();
        assertThat(RequestContextFields.firstNonBlank(null, " ", "usable")).isEqualTo("usable");
    }
}

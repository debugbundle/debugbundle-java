package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import org.jboss.logmanager.LogContext;
import org.jboss.stdio.LoggingOutputStream;
import org.junit.jupiter.api.Test;

/** Synthetic native printStackTrace -> the same JBoss stdio writer used by WildFly. */
class RedirectedStderrTest {
    @Test
    void sixLongWildFlyTracesRemainSixCoherentExceptions() throws Exception {
        FakeTransport transport = new FakeTransport();
        var config = DebugBundleConfig.builder().projectToken("synthetic-token").environment("test")
                .batchSize(100).flushInterval(Duration.ofHours(1)).build();
        try (var client = new DefaultDebugBundleClient(config, transport, System::currentTimeMillis);
             var logs = LogContext.create()) {
            var handler = new DebugBundleJulHandler(() -> client);
            var logger = logs.getLogger("stderr");
            logger.setUseParentHandlers(false);
            logger.addHandler(handler);
            try (var stderr = new PrintStream(new LoggingOutputStream(logger, Level.SEVERE), true, StandardCharsets.UTF_8)) {
                for (int occurrence = 0; occurrence < 6; occurrence++) {
                    var request = client.beginRequest(Map.of("method", occurrence < 3 ? "GET" : "POST",
                            "path", "/synthetic", "request_id", "synthetic-" + occurrence));
                    try {
                        failure().printStackTrace(stderr);
                    } finally { client.endRequest(request); }
                }
                handler.drainPendingStacks().join();
                client.flush().join();
                var events = transport.calls().stream().flatMap(call -> call.events().stream()).toList();
                assertThat(events).hasSize(6);
                assertThat(events.stream().map(event -> (String) ((Map<?, ?>) ((Map<?, ?>) event.get("payload")).get("request")).get("method")))
                        .containsExactlyInAnyOrder("GET", "GET", "GET", "POST", "POST", "POST");
                assertThat(events).allSatisfy(event -> {
                    assertThat(event.get("event_type")).isEqualTo("backend_exception");
                    var payload = (Map<?, ?>) event.get("payload");
                    assertThat(payload.get("stack").toString()).contains("ExecutionException", "Caused by: java.lang.NullPointerException",
                            "SyntheticService.load", "SyntheticService.java:42", "Unknown Source", "Native Method", "Suppressed:");
                });
            } finally { handler.close(); }
        }
    }

    static ExecutionException failure() {
        var cause = new NullPointerException("synthetic missing value");
        var frames = new java.util.ArrayList<StackTraceElement>();
        frames.add(frame("deployment.synthetic-app.war", null, "example.SyntheticService", "load", "SyntheticService.java", 42));
        for (int i = 0; i < 16; i++)
            frames.add(frame("org.jboss.as.weld", "35.0.1.Final", "org.jboss.SyntheticProxy" + i, "invoke", null, -1));
        frames.add(frame("java.base", "21", "jdk.internal.reflect.NativeMethodAccessorImpl", "invoke0", null, -2));
        cause.setStackTrace(frames.toArray(StackTraceElement[]::new));
        var wrapper = new ExecutionException(cause);
        wrapper.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("java.util.concurrent.CompletableFuture", "reportGet", "CompletableFuture.java", 396),
                new StackTraceElement("java.util.concurrent.CompletableFuture", "get", "CompletableFuture.java", 2073)});
        var suppressed = new java.io.IOException("synthetic cleanup failure");
        suppressed.setStackTrace(new StackTraceElement[]{frame("deployment.synthetic-app.war", null,
                "example.Resource", "close", "Resource.java", 19)});
        cause.addSuppressed(suppressed);
        return wrapper;
    }

    private static StackTraceElement frame(String module, String version, String type, String method, String file, int line) {
        return new StackTraceElement("deployment.synthetic-app.war", module, version, type, method, file, line);
    }
}

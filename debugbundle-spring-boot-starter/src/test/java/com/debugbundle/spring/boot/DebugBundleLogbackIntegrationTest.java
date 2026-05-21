package com.debugbundle.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.DebugBundleConfig;
import com.debugbundle.sdk.DebugBundleRequestScope;
import com.debugbundle.sdk.DebugBundleStatus;
import com.debugbundle.sdk.LogLevel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class DebugBundleLogbackIntegrationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DebugBundleAutoConfiguration.class))
            .withUserConfiguration(RecordingClientConfiguration.class);

    @Test
    void autoRegisteredLogbackAppenderCapturesThrowableAndMdcContext() {
        contextRunner
                .withPropertyValues(
                        "debugbundle.project-token=dbundle_proj_test",
                        "debugbundle.log-level=warning"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(DebugBundleLogbackAppenderRegistrar.class);
                    assertThat(context).hasSingleBean(RecordingDebugBundleClient.class);

                    RecordingDebugBundleClient client = context.getBean(RecordingDebugBundleClient.class);
                    client.clear();

                    org.slf4j.Logger logger = LoggerFactory.getLogger("checkout.logback");
                    try {
                        MDC.put("requestId", "req-123");
                        logger.error("payment failed", new IllegalStateException("db down"));
                    } finally {
                        MDC.clear();
                    }

                    assertThat(client.logs()).hasSize(1);
                    CapturedLog captured = client.logs().get(0);
                    assertThat(captured.message()).isEqualTo("payment failed");
                    assertThat(captured.level()).isEqualTo(LogLevel.ERROR);
                    assertThat(captured.context()).containsEntry("logger", "checkout.logback");
                    assertThat(captured.context()).containsEntry("thread_name", Thread.currentThread().getName());
                    assertThat(captured.context().get("mdc"))
                            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                            .containsEntry("requestId", "req-123");
                    assertThat(captured.context().get("throwable"))
                            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                            .containsEntry("class", "java.lang.IllegalStateException")
                            .containsEntry("message", "db down");
                    assertThat(((Map<?, ?>) captured.context().get("throwable")).get("stacktrace"))
                            .asString()
                            .contains("IllegalStateException: db down");
                });
    }

    @Test
    void logbackAppenderRespectsConfiguredLogLevel() {
        contextRunner
                .withPropertyValues(
                        "debugbundle.project-token=dbundle_proj_test",
                        "debugbundle.log-level=error"
                )
                .run(context -> {
                    RecordingDebugBundleClient client = context.getBean(RecordingDebugBundleClient.class);
                    client.clear();

                    org.slf4j.Logger logger = LoggerFactory.getLogger("checkout.logback.levels");
                    logger.warn("suppressed warning");
                    logger.error("captured error");

                    assertThat(client.logs()).extracting(CapturedLog::message).containsExactly("captured error");
                    assertThat(client.logs()).extracting(CapturedLog::level).containsExactly(LogLevel.ERROR);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingClientConfiguration {
        @Bean
        RecordingDebugBundleClient debugBundleClient(DebugBundleProperties properties) {
            return new RecordingDebugBundleClient(LogLevel.fromName(properties.getLogLevel()));
        }
    }

    static final class RecordingDebugBundleClient implements DebugBundleClient {
        private final List<CapturedLog> logs = new ArrayList<>();
        private final LogLevel configuredLogLevel;

        RecordingDebugBundleClient(LogLevel configuredLogLevel) {
            this.configuredLogLevel = configuredLogLevel;
        }

        List<CapturedLog> logs() {
            return List.copyOf(logs);
        }

        void clear() {
            logs.clear();
        }

        @Override
        public DebugBundleConfig config() {
            return DebugBundleConfig.builder()
                    .projectToken("dbundle_proj_test")
                    .service("checkout-api")
                    .environment("test")
                    .logLevel(configuredLogLevel)
                    .build();
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
            captureLog(message, level, Map.of());
        }

        @Override
        public void captureLog(String message, LogLevel level, Map<String, Object> context) {
            logs.add(new CapturedLog(message, level, new LinkedHashMap<>(context)));
        }

        @Override
        public void captureRequest(Object request, Object response, Map<String, Object> context) {
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
        public void probe(String label, Supplier<?> dataSupplier, com.debugbundle.sdk.ProbeOptions options) {
        }

        @Override
        public DebugBundleRequestScope beginRequest(Map<String, Object> request) {
            return new DebugBundleRequestScope();
        }

        @Override
        public void endRequest(DebugBundleRequestScope scope) {
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

    record CapturedLog(String message, LogLevel level, Map<String, Object> context) {
    }
}

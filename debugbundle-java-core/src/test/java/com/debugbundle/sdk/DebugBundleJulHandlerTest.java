package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.logging.Level;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;

class DebugBundleJulHandlerTest {
        static {
                System.setProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager");
        }

    @Test
    void jbossLogManagerRecordsCaptureMdcThrowableAndThreadName() {
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .service("orders-service")
                        .environment("test")
                        .batchSize(1)
                        .build(),
                transport,
                () -> 1_777_000_000_000L
        );
        DebugBundleJulHandler handler = new DebugBundleJulHandler(() -> client);

        ExtLogRecord record = new ExtLogRecord(Level.SEVERE, "payment failed", DebugBundleJulHandlerTest.class.getName());
        record.setLoggerName("org.wildfly.orders");
        record.setThreadName("default task-1");
        record.putMdc("requestId", "req-123");
        record.setThrown(new IllegalStateException("db down"));

        handler.publish(record);
        client.flush();

        assertThat(transport.calls()).hasSize(1);

        Map<String, Object> event = transport.calls().get(0).events().get(0);
        assertThat(event).containsEntry("event_type", "log_event");

        @SuppressWarnings("unchecked")
        Map<String, Object> service = (Map<String, Object>) event.get("service");
        assertThat(service).containsEntry("name", "orders-service");

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        assertThat(payload).containsEntry("level", "error");
        assertThat(payload).containsEntry("message", "payment failed");

        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) payload.get("attributes");
        assertThat(attributes).containsEntry("logger", "org.wildfly.orders");
        assertThat(attributes).containsEntry("thread_name", "default task-1");
        assertThat(attributes.get("thread_id")).isNotNull();
        assertThat(attributes.get("mdc"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("requestId", "req-123");
        assertThat(attributes.get("throwable"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("class", "java.lang.IllegalStateException")
                .containsEntry("message", "db down");
        assertThat(((Map<?, ?>) attributes.get("throwable")).get("stacktrace"))
                .asString()
                .contains("IllegalStateException: db down");
    }
}
package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;

class DebugBundleJulHandlerTest {
        static {
                System.setProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager");
        }

    @Test
    void rejectedJulRecordIsNotFormattedOrExpanded() {
        AtomicInteger messageReads = new AtomicInteger();
        AtomicInteger throwableReads = new AtomicInteger();
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .logLevel(LogLevel.WARNING)
                        .build(),
                transport,
                System::currentTimeMillis
        );
        LogRecord record = new LogRecord(Level.INFO, "filtered INFO") {
            @Override
            public String getMessage() {
                messageReads.incrementAndGet();
                return super.getMessage();
            }

            @Override
            public Throwable getThrown() {
                throwableReads.incrementAndGet();
                return super.getThrown();
            }
        };
        try {
            new DebugBundleJulHandler(() -> client).publish(record);
            assertThat(messageReads).hasValue(0);
            assertThat(throwableReads).hasValue(0);
            assertThat(transport.calls()).isEmpty();
        } finally {
            client.close();
        }
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
        record.setThrown(new IllegalStateException("db down", new java.util.ConcurrentModificationException("chart state")));

        handler.publish(record);
        client.flush().join();

        assertThat(transport.calls()).hasSize(1);

        Map<String, Object> event = transport.calls().get(0).events().get(0);
        assertThat(event).containsEntry("event_type", "backend_exception");

        @SuppressWarnings("unchecked")
        Map<String, Object> service = (Map<String, Object>) event.get("service");
        assertThat(service).containsEntry("name", "orders-service");

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        assertThat(payload).containsEntry("name", "IllegalStateException");
        assertThat(payload).containsEntry("message", "db down");
        assertThat(payload.get("stack")).asString()
                .contains("IllegalStateException: db down")
                .contains("Caused by: java.util.ConcurrentModificationException: chart state");

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) event.get("context");
        assertThat(context).containsEntry("logger", "org.wildfly.orders");
        assertThat(context).containsEntry("thread_name", "default task-1");
        assertThat(context).containsEntry("log_message", "payment failed");
        assertThat(context.get("thread_id")).isNotNull();
        assertThat(context.get("mdc"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("requestId", "req-123");
        handler.close();
        client.close();
    }

    @Test
    void ninetyFourRedirectedWildFlyStackLinesProduceOneException() {
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder()
                        .projectToken("dbundle_proj_test")
                        .environment("local")
                        .batchSize(25)
                        .build(),
                transport,
                System::currentTimeMillis
        );
        DebugBundleJulHandler handler = new DebugBundleJulHandler(() -> client);
        try {
            List<String> lines = new java.util.ArrayList<>();
            lines.add("java.util.concurrent.ExecutionException: java.util.ConcurrentModificationException");
            for (int index = 0; index < 45; index++) {
                lines.add("\tat example.ChartService.render(ChartService.java:" + (index + 10) + ")");
            }
            lines.add("Caused by: java.util.ConcurrentModificationException");
            for (int index = 0; index < 46; index++) {
                lines.add("\tat example.ChartModel.read(ChartModel.java:" + (index + 20) + ")");
            }
            lines.add("... 12 more");
            assertThat(lines).hasSize(94);
            for (String line : lines) {
                LogRecord record = new LogRecord(Level.SEVERE, line);
                record.setLoggerName("stderr");
                handler.publish(record);
            }
            handler.drainPendingStacks().join();
            client.flush().join();

            assertThat(transport.calls()).hasSize(1);
            assertThat(transport.calls().get(0).events()).hasSize(1);
            Map<String, Object> event = transport.calls().get(0).events().get(0);
            assertThat(event).containsEntry("event_type", "backend_exception");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) event.get("payload");
            assertThat(payload.get("stack")).asString()
                    .contains("Caused by: java.util.ConcurrentModificationException")
                    .contains("... 12 more");
        } finally {
            handler.close();
            client.close();
        }
    }

    @Test
    void delayedCauseAndInterleavedThreadsRemainTwoCompleteExceptions() {
        AtomicLong clock = new AtomicLong(1_777_000_000_000L);
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder().projectToken("dbundle_proj_test")
                        .environment("local").batchSize(25).build(),
                transport,
                clock::get
        );
        DebugBundleJulHandler handler = new DebugBundleJulHandler(
                () -> client, new JavaStackTraceAssembler(clock::get)
        );
        try {
            publishStderr(handler, 117L, "java.util.concurrent.ExecutionException: first failure");
            publishStderr(handler, 117L, "\tat example.First.render(First.java:42)");
            publishStderr(handler, 119L, "java.lang.IllegalStateException: second failure");
            publishStderr(handler, 119L, "\tat example.Second.render(Second.java:71)");

            clock.addAndGet(17_000L);
            publishStderr(handler, 117L, "Caused by: java.util.ConcurrentModificationException");
            publishStderr(handler, 117L, "\tat example.First.iterate(First.java:90)");
            handler.sweepStacks().join();
            client.flush().join();
            assertThat(transport.calls()).isEmpty();

            clock.addAndGet(20_001L);
            handler.sweepStacks().join();
            client.flush().join();
            assertThat(transport.calls()).hasSize(1);
            assertThat(transport.calls().get(0).events()).hasSize(2);
            String first = transport.calls().get(0).events().stream()
                    .filter(event -> ((Map<?, ?>) event.get("payload")).get("name")
                            .equals("java.util.concurrent.ExecutionException"))
                    .findFirst().orElseThrow().toString();
            String second = transport.calls().get(0).events().stream()
                    .filter(event -> ((Map<?, ?>) event.get("payload")).get("name")
                            .equals("java.lang.IllegalStateException"))
                    .findFirst().orElseThrow().toString();
            assertThat(first).contains("First.iterate").contains("ConcurrentModificationException")
                    .doesNotContain("Second.render");
            assertThat(second).contains("Second.render").doesNotContain("First.iterate");
        } finally {
            handler.close();
            client.close();
        }
    }

    @Test
    void redirectedStackCannotCrossClientReplacementOnTheSameThreadAndLogger() {
        FakeTransport previousTransport = new FakeTransport();
        FakeTransport nextTransport = new FakeTransport();
        DefaultDebugBundleClient previous = new DefaultDebugBundleClient(
                DebugBundleConfig.builder().projectToken("dbundle_proj_previous")
                        .batchSize(25).build(), previousTransport, System::currentTimeMillis
        );
        DefaultDebugBundleClient next = new DefaultDebugBundleClient(
                DebugBundleConfig.builder().projectToken("dbundle_proj_next")
                        .batchSize(25).build(), nextTransport, System::currentTimeMillis
        );
        AtomicReference<DebugBundleClient> current = new AtomicReference<>(previous);
        DebugBundleJulHandler handler = new DebugBundleJulHandler(current::get);
        try {
            publishStderr(handler, 117L, "java.lang.IllegalStateException: previous application");
            publishStderr(handler, 117L, "\tat previous.App.run(App.java:10)");
            current.set(next);
            publishStderr(handler, 117L, "\tat next.Orphan.run(Orphan.java:20)");
            publishStderr(handler, 117L, "java.lang.IllegalArgumentException: next application");
            publishStderr(handler, 117L, "\tat next.App.run(App.java:30)");
            handler.drainPendingStacks().join();
            previous.flush().join();
            next.flush().join();

            String previousEvents = previousTransport.calls().toString();
            assertThat(previousEvents).contains("previous.App.run")
                    .doesNotContain("next.Orphan.run", "next.App.run");
            assertThat(nextTransport.calls().toString()).contains("next.App.run")
                    .doesNotContain("previous.App.run", "next.Orphan.run");
        } finally {
            handler.close();
            previous.close();
            next.close();
        }
    }

    @Test
    void redirectedStacksFromCollidingLoggerNamesStaySeparate() {
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode());
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder().projectToken("dbundle_proj_test")
                        .batchSize(25).build(), transport, System::currentTimeMillis
        );
        DebugBundleJulHandler handler = new DebugBundleJulHandler(() -> client);
        try {
            publishStderr(handler, 117L, "Aa", "java.lang.IllegalStateException: first logger");
            publishStderr(handler, 117L, "BB", "java.lang.IllegalArgumentException: second logger");
            publishStderr(handler, 117L, "Aa", "\tat first.App.run(App.java:10)");
            publishStderr(handler, 117L, "BB", "\tat second.App.run(App.java:20)");
            handler.drainPendingStacks().join();
            client.flush().join();

            List<Map<String, Object>> events = transport.calls().stream()
                    .flatMap(call -> call.events().stream())
                    .filter(event -> "backend_exception".equals(event.get("event_type"))).toList();
            assertThat(events).hasSize(2);
            String first = events.stream().filter(event -> event.toString().contains("first logger"))
                    .findFirst().orElseThrow().toString();
            String second = events.stream().filter(event -> event.toString().contains("second logger"))
                    .findFirst().orElseThrow().toString();
            assertThat(first).contains("first.App.run").doesNotContain("second.App.run");
            assertThat(second).contains("second.App.run").doesNotContain("first.App.run");
        } finally {
            handler.close();
            client.close();
        }
    }

    @Test
    void oversizedLoggerNameKeepsRootAndSummarizesContinuations() {
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder().projectToken("dbundle_proj_test")
                        .batchSize(25).build(), transport, System::currentTimeMillis
        );
        DebugBundleJulHandler handler = new DebugBundleJulHandler(() -> client);
        try {
            String logger = "x".repeat(257);
            publishStderr(handler, 117L, logger, "java.lang.IllegalStateException: oversized logger root");
            for (int index = 0; index < 100; index++) {
                publishStderr(handler, 117L, logger, "\tat example.App.run(App.java:20)");
            }
            handler.drainPendingStacks().join();
            client.flush().join();

            List<Map<String, Object>> events = transport.calls().stream()
                    .flatMap(call -> call.events().stream()).toList();
            assertThat(events).hasSize(2);
            assertThat(events.toString()).contains("oversized logger root")
                    .contains("100 orphan Java stack continuation lines observed");
        } finally {
            handler.close();
            client.close();
        }
    }

    @Test
    void orphanContinuationCountsStayWithTheirOwnClient() {
        FakeTransport previousTransport = new FakeTransport();
        FakeTransport nextTransport = new FakeTransport();
        DefaultDebugBundleClient previous = new DefaultDebugBundleClient(
                DebugBundleConfig.builder().projectToken("dbundle_proj_previous")
                        .batchSize(25).build(), previousTransport, System::currentTimeMillis
        );
        DefaultDebugBundleClient next = new DefaultDebugBundleClient(
                DebugBundleConfig.builder().projectToken("dbundle_proj_next")
                        .batchSize(25).build(), nextTransport, System::currentTimeMillis
        );
        AtomicReference<DebugBundleClient> current = new AtomicReference<>(previous);
        DebugBundleJulHandler handler = new DebugBundleJulHandler(current::get);
        try {
            publishStderr(handler, 117L, "\tat previous.Orphan.run(Orphan.java:10)");
            current.set(next);
            publishStderr(handler, 117L, "\tat next.Orphan.run(Orphan.java:20)");
            handler.drainPendingStacks().join();
            previous.flush().join();
            next.flush().join();

            assertThat(previousTransport.calls().toString())
                    .contains("1 orphan Java stack continuation lines observed");
            assertThat(nextTransport.calls().toString())
                    .contains("1 orphan Java stack continuation lines observed")
                    .doesNotContain("2 orphan Java stack continuation lines observed");
        } finally {
            handler.close();
            previous.close();
            next.close();
        }
    }

    @Test
    void separateFailuresOnOneRedirectedThreadRemainSeparateIncidents() {
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder().projectToken("dbundle_proj_test")
                        .environment("local").batchSize(25).build(),
                transport,
                System::currentTimeMillis
        );
        DebugBundleJulHandler handler = new DebugBundleJulHandler(() -> client);
        try {
            publishStderr(handler, 117L, "java.lang.IllegalStateException: first failure");
            publishStderr(handler, 117L, "\tat example.First.render(First.java:42)");
            publishStderr(handler, 117L, "java.lang.IllegalArgumentException: second failure");
            publishStderr(handler, 117L, "\tat example.Second.render(Second.java:71)");
            handler.drainPendingStacks().join();
            client.flush().join();

            List<Map<String, Object>> events = transport.calls().stream()
                    .flatMap(call -> call.events().stream()).toList();
            assertThat(events).hasSize(2);
            assertThat(events.get(0).toString()).contains("First.render").doesNotContain("Second.render");
            assertThat(events.get(1).toString()).contains("Second.render").doesNotContain("First.render");
        } finally {
            handler.close();
            client.close();
        }
    }

    @Test
    void nonStackWarningTerminatesRedirectedTraceWithoutWaitingForIdleTimeout() {
        FakeTransport transport = new FakeTransport();
        DefaultDebugBundleClient client = new DefaultDebugBundleClient(
                DebugBundleConfig.builder().projectToken("dbundle_proj_test")
                        .environment("local").batchSize(25).build(),
                transport,
                System::currentTimeMillis
        );
        DebugBundleJulHandler handler = new DebugBundleJulHandler(() -> client);
        try {
            publishStderr(handler, 117L, "java.lang.IllegalStateException: synthetic root");
            publishStderr(handler, 117L, "\tat example.Chart.render(Chart.java:42)");
            LogRecord marker = new LogRecord(Level.WARNING, "synthetic stack complete");
            marker.setLoggerName("stderr");
            marker.setLongThreadID(117L);
            handler.publish(marker);
            handler.sweepStacks().join();
            client.flush().join();
            assertThat(transport.calls().stream().flatMap(call -> call.events().stream())
                    .filter(event -> "backend_exception".equals(event.get("event_type"))).count()).isEqualTo(1);
        } finally {
            handler.close();
            client.close();
        }
    }

    private void publishStderr(DebugBundleJulHandler handler, long threadId, String line) {
        publishStderr(handler, threadId, "stderr", line);
    }

    private void publishStderr(DebugBundleJulHandler handler, long threadId, String logger, String line) {
        LogRecord record = new LogRecord(Level.SEVERE, line);
        record.setLoggerName(logger);
        record.setLongThreadID(threadId);
        handler.publish(record);
    }
}

package com.debugbundle.sdk;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Local synthetic comparison: no network, application hook or timed fixture setup. */
public final class EvidenceCapture {
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private record Call(Throwable error, Map<String, Object> context, Map<String, Object> observation) {}

    public static void main(String[] args) throws Exception {
        if (!THREADS.isThreadAllocatedMemorySupported() || !THREADS.isThreadCpuTimeSupported()) {
            throw new IllegalStateException("JVM allocation/CPU counters are required for this qualification");
        }
        THREADS.setThreadAllocatedMemoryEnabled(true);
        THREADS.setThreadCpuTimeEnabled(true);
        compare(true, false, 0);
        for (int repeat = 1; repeat <= 3; repeat++) {
            for (boolean pressure : new boolean[]{false, true}) {
                for (boolean enabled : new boolean[]{false, true}) compare(enabled, pressure, repeat);
            }
        }
    }

    private static List<Call> calls() {
        var calls = new ArrayList<Call>();
        for (int index = 0; index < 20; index++) {
            var error = new IllegalStateException("outer " + index, new IllegalArgumentException("inner " + index));
            error.setStackTrace(new StackTraceElement[]{new StackTraceElement("app.Job", "run", "Job.java", 24)});
            error.getCause().setStackTrace(new StackTraceElement[]{new StackTraceElement("app.Inner", "load", "Inner.java", 12)});
            var scope = Map.of("kind", "operation", "operation_id", UUID.randomUUID().toString());
            var failure = UUID.randomUUID().toString();
            calls.add(new Call(error, Map.of("failureRef", failure, "evidenceScope", scope),
                    Map.of("failureRef", failure, "evidenceScope", scope, "attemptId", UUID.randomUUID().toString(),
                            "state", "attempt_completed", "statusCode", 200)));
        }
        return calls;
    }

    private static void compare(boolean enabled, boolean pressure, int repeat) throws Exception {
        var config = DebugBundleConfig.builder().projectToken("synthetic").environment("local").projectMode("local-only")
                .batchSize(2000).flushInterval(Duration.ofHours(1))
                .captureExceptionEvidence(enabled).captureRecoveryObservations(enabled)
                .evidenceCapabilities(Map.of("version", 1, "features", EvidenceWire.FEATURES)).build();
        try (var client = new DefaultDebugBundleClient(config, request -> new TransportResponse(202, null), System::currentTimeMillis)) {
            var delivery = field(client, "delivery");
            var executor = (ScheduledThreadPoolExecutor) field(delivery, "executor");
            long workerId = executor.submit(() -> Thread.currentThread().getId()).get();
            long callerId = Thread.currentThread().getId();
            var calls = calls();
            if (pressure) {
                for (int index = 0; index < 1000; index++) client.captureLog("warning " + index, LogLevel.WARNING);
                require(client.pendingEventCount() == 1000, "warning queue not filled");
            }
            long callerNanos = 0, callerBytes = 0, workerBytes = 0, workerCpu = 0;
            for (var call : calls) {
                long beforeCaller = THREADS.getThreadAllocatedBytes(callerId);
                long beforeWorker = THREADS.getThreadAllocatedBytes(workerId);
                long beforeCpu = THREADS.getThreadCpuTime(workerId);
                long started = System.nanoTime();
                client.captureException(call.error(), call.context());
                client.captureRecovery(call.observation());
                callerNanos += System.nanoTime() - started;
                callerBytes += THREADS.getThreadAllocatedBytes(callerId) - beforeCaller;
                // The real existing worker owns throwable projection. Include its
                // allocation and CPU costs separately from the nonblocking caller.
                settle(delivery);
                workerBytes += THREADS.getThreadAllocatedBytes(workerId) - beforeWorker;
                workerCpu += THREADS.getThreadCpuTime(workerId) - beforeCpu;
            }
            int failures = 0, diagnostics = 0;
            for (var event : events(delivery)) {
                if ("backend_exception".equals(event.get("event_type"))) {
                    failures++;
                    var payload = (Map<?, ?>) event.get("payload");
                    require(payload.containsKey("exception_evidence") == enabled,
                            "requested evidence mode not exercised: " + JsonWriter.write(event));
                    if (enabled) require(((List<?>) ((Map<?, ?>) payload.get("exception_evidence")).get("nodes")).size() == 2,
                            "native cause was not preserved");
                }
                if ("diagnostic_context".equals(event.get("event_type"))) diagnostics++;
            }
            require(failures == calls.size(), "original failures displaced");
            require(diagnostics == (enabled && !pressure ? calls.size() : 0), "recovery admission changed");
            int diagnosticBytes = (Integer) invoke(field(field(client, "evidenceController"), "budget"), "bytes");
            require(client.pendingEventCount() <= 1000 && client.pendingBytes() <= 8 * 1024 * 1024, "queue ceiling exceeded");
            require(diagnosticBytes <= 65536, "diagnostic ceiling exceeded");
            if (repeat > 0) System.out.println(JsonWriter.write(Map.ofEntries(
                    Map.entry("java", System.getProperty("java.version")), Map.entry("repeat", repeat),
                    Map.entry("evidence", enabled), Map.entry("pressure", pressure), Map.entry("pairs", calls.size()),
                    Map.entry("caller_ns_per_pair", callerNanos / calls.size()), Map.entry("caller_allocated_bytes_per_pair", callerBytes / calls.size()),
                    Map.entry("worker_cpu_ns_per_pair", workerCpu / calls.size()), Map.entry("worker_allocated_bytes_per_pair", workerBytes / calls.size()),
                    Map.entry("retained_bytes", client.pendingBytes()), Map.entry("diagnostic_bytes", diagnosticBytes))));
        }
    }

    private static void settle(Object delivery) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        var lock = (ReentrantLock) field(delivery, "queueLock");
        while (System.nanoTime() < deadline) {
            boolean ready;
            lock.lock();
            try {
                ready = (Integer) field(delivery, "inFlightCount") == 0;
                for (Object entry : (List<?>) field(delivery, "pending")) {
                    if (invoke(entry, "exception") != null) ready = false;
                }
            } finally { lock.unlock(); }
            if (ready) return;
            Thread.sleep(1);
        }
        throw new IllegalStateException("native worker did not finish bounded evidence projection");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> events(Object delivery) throws Exception {
        var result = new ArrayList<Map<String, Object>>();
        var lock = (ReentrantLock) field(delivery, "queueLock");
        lock.lock();
        try {
            for (Object entry : (List<?>) field(delivery, "pending")) result.add((Map<String, Object>) invoke(entry, "event"));
        } finally { lock.unlock(); }
        return result;
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static Object invoke(Object owner, String name) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(owner);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

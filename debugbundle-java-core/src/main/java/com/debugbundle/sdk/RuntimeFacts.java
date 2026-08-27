package com.debugbundle.sdk;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class RuntimeFacts {
    private static final long STARTED_AT_NANOS = System.nanoTime();

    private RuntimeFacts() {
    }

    static Map<String, Object> capture() {
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("version", System.getProperty("java.version"));
        facts.put("platform", System.getProperty("os.name"));
        facts.put("arch", System.getProperty("os.arch"));
        facts.put("pid", ProcessHandle.current().pid());
        facts.put("cwd", Path.of("").toAbsolutePath().normalize().toString());
        facts.put("uptime_sec", (System.nanoTime() - STARTED_AT_NANOS) / 1_000_000_000.0d);
        facts.put("hostname", hostname());
        facts.put("thread_id", Thread.currentThread().getId());
        long heapTotal = runtime.totalMemory();
        long heapFree = runtime.freeMemory();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("rss", null);
        memory.put("heap_total", heapTotal);
        memory.put("heap_used", Math.max(0L, heapTotal - heapFree));
        memory.put("external", null);
        memory.put("peak", null);
        facts.put("memory", memory);
        facts.put("framework_version", null);
        Map<String, Object> frameworkExtras = new LinkedHashMap<>();
        frameworkExtras.put("jvm_name", ManagementFactory.getRuntimeMXBean().getVmName());
        frameworkExtras.put("jvm_max_bytes", runtime.maxMemory());
        facts.put("framework_extras", frameworkExtras);
        return facts;
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException error) {
            return "unknown";
        }
    }
}

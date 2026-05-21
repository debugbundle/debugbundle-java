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
        facts.put("memory", Map.of(
                "max_bytes", runtime.maxMemory(),
                "total_bytes", runtime.totalMemory(),
                "free_bytes", runtime.freeMemory()
        ));
        facts.put("framework_version", null);
        facts.put("framework_extras", null);
        facts.put("jvm_name", ManagementFactory.getRuntimeMXBean().getVmName());
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

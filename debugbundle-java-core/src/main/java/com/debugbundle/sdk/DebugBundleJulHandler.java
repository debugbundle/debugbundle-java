package com.debugbundle.sdk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

final class DebugBundleJulHandler extends Handler {
    private final Supplier<DebugBundleClient> clientSupplier;

    DebugBundleJulHandler(Supplier<DebugBundleClient> clientSupplier) {
        this.clientSupplier = clientSupplier;
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || !isLoggable(record)) {
            return;
        }

        try {
            DebugBundleClient client = clientSupplier.get();
            if (client == null) {
                return;
            }

            Map<String, Object> context = new LinkedHashMap<>();
            context.put("logger", record.getLoggerName());
            context.put("thread_id", record.getLongThreadID());

            client.captureLog(
                    record.getMessage(),
                    LogLevel.fromJulLevel(record.getLevel()),
                    context
            );
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}

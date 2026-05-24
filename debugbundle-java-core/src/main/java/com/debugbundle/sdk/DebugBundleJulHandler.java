package com.debugbundle.sdk;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

final class DebugBundleJulHandler extends Handler {
    private static final ThreadLocal<Boolean> IN_PUBLISH = ThreadLocal.withInitial(() -> false);

    private final Supplier<DebugBundleClient> clientSupplier;

    DebugBundleJulHandler(Supplier<DebugBundleClient> clientSupplier) {
        this.clientSupplier = clientSupplier;
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || !isLoggable(record) || Boolean.TRUE.equals(IN_PUBLISH.get())) {
            return;
        }

        try {
            DebugBundleClient client = clientSupplier.get();
            if (client == null) {
                return;
            }

            IN_PUBLISH.set(true);
            Map<String, Object> context = new LinkedHashMap<>();
            putIfNotBlank(context, "logger", record.getLoggerName());
            context.put("thread_id", record.getLongThreadID());
            putIfNotBlank(context, "thread_name", firstNonBlank(threadName(record), Thread.currentThread().getName()));

            Map<String, Object> mdc = mdc(record);
            if (!mdc.isEmpty()) {
                context.put("mdc", mdc);
            }

            Map<String, Object> throwable = throwable(record.getThrown());
            if (!throwable.isEmpty()) {
                context.put("throwable", throwable);
            }

            client.captureLog(
                    resolvedMessage(record),
                    LogLevel.fromJulLevel(record.getLevel()),
                    context
            );
        } catch (RuntimeException ignored) {
        } finally {
            IN_PUBLISH.remove();
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    private String resolvedMessage(LogRecord record) {
        return firstNonBlank(asString(invoke(record, "getFormattedMessage")), record.getMessage());
    }

    private String threadName(LogRecord record) {
        return asString(invoke(record, "getThreadName"));
    }

    private Map<String, Object> mdc(LogRecord record) {
        Object value = invoke(record, "getMdcCopy");
        if (!(value instanceof Map<?, ?> mapValue) || mapValue.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> mdc = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            mdc.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return mdc;
    }

    private Map<String, Object> throwable(Throwable error) {
        if (error == null) {
            return Map.of();
        }

        Map<String, Object> throwable = new LinkedHashMap<>();
        throwable.put("class", error.getClass().getName());
        throwable.put("message", error.getMessage());
        throwable.put("stacktrace", stackTrace(error));
        return throwable;
    }

    private String stackTrace(Throwable error) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        error.printStackTrace(printWriter);
        printWriter.flush();
        return stringWriter.toString();
    }

    private Object invoke(LogRecord record, String methodName) {
        try {
            return record.getClass().getMethod(methodName).invoke(record);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private String asString(Object value) {
        return value instanceof String stringValue ? stringValue : null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void putIfNotBlank(Map<String, Object> context, String key, String value) {
        if (key == null || value == null || value.isBlank()) {
            return;
        }
        context.put(key, value);
    }
}

package com.debugbundle.sdk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

final class DebugBundleJulHandler extends Handler {
    private static final ThreadLocal<Boolean> IN_PUBLISH = ThreadLocal.withInitial(() -> false);

    private final Supplier<DebugBundleClient> clientSupplier;
    private final JavaStackTraceAssembler stackTraces;

    DebugBundleJulHandler(Supplier<DebugBundleClient> clientSupplier) {
        this(clientSupplier, new JavaStackTraceAssembler());
    }

    DebugBundleJulHandler(Supplier<DebugBundleClient> clientSupplier, JavaStackTraceAssembler stackTraces) {
        this.clientSupplier = clientSupplier;
        this.stackTraces = stackTraces;
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

            LogLevel level = LogLevel.fromJulLevel(record.getLevel());
            boolean logEligible = !(client instanceof DefaultDebugBundleClient defaultClient)
                    || defaultClient.shouldCaptureLogOrBreadcrumb(level);
            if (!logEligible && level != LogLevel.ERROR && level != LogLevel.CRITICAL) return;
            Throwable thrown = record.getThrown();
            if (!logEligible && thrown == null) {
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

            String message = resolvedMessage(record);
            if (message != null && message.length() > 16_384) message = message.substring(0, 16_384);
            if (thrown != null) {
                putIfNotBlank(context, "log_message", message);
                client.captureException(thrown, context);
                return;
            }

            if (client instanceof DefaultDebugBundleClient defaultClient
                    && stackTraces.accept(record, message, level, context, defaultClient)) return;
            client.captureLog(message, level, context);
        } catch (Throwable ignored) {
        } finally {
            IN_PUBLISH.remove();
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
        stackTraces.close();
    }

    CompletableFuture<Void> drainPendingStacks() {
        return stackTraces.drainPending();
    }

    CompletableFuture<Void> sweepStacks() {
        return stackTraces.sweepNow();
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
            if (mdc.size() >= 64) break;
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            mdc.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return mdc;
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

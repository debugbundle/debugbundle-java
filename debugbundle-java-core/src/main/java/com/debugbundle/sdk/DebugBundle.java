package com.debugbundle.sdk;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class DebugBundle {
    private static final AtomicReference<DebugBundleClient> CLIENT =
            new AtomicReference<>(new DefaultDebugBundleClient(DebugBundleConfig.builder().enabled(false).build()));
    private static final AtomicBoolean JUL_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean UNCAUGHT_HANDLER_INSTALLED = new AtomicBoolean(false);

    private DebugBundle() {
    }

    public static DebugBundleClient init(DebugBundleConfig config) {
        DebugBundleClient client = create(config);
        DebugBundleClient previous = CLIENT.getAndSet(client);
        swallow(previous::close);
        return client;
    }

    public static DebugBundleClient create(DebugBundleConfig config) {
        return new DefaultDebugBundleClient(config == null ? DebugBundleConfig.builder().enabled(false).build() : config);
    }

    public static DebugBundleClient client() {
        return CLIENT.get();
    }

    public static void captureException(Throwable error) {
        swallow(() -> client().captureException(error));
    }

    public static void captureException(Throwable error, Map<String, Object> context) {
        swallow(() -> client().captureException(error, context == null ? Map.of() : context));
    }

    public static void captureError(Throwable error) {
        swallow(() -> client().captureError(error));
    }

    public static void captureLog(String message, LogLevel level) {
        swallow(() -> client().captureLog(message, level));
    }

    public static void captureLog(String message, LogLevel level, Map<String, Object> context) {
        swallow(() -> client().captureLog(message, level, context == null ? Map.of() : context));
    }

    public static void captureRequest(Object request, Object response, Map<String, Object> context) {
        swallow(() -> client().captureRequest(request, response, context == null ? Map.of() : context));
    }

    public static void captureMessage(String message) {
        swallow(() -> client().captureMessage(message));
    }

    public static void captureMessage(String message, LogLevel level, Map<String, Object> context) {
        swallow(() -> client().captureMessage(message, level, context == null ? Map.of() : context));
    }

    public static void setContext(String key, Object value) {
        swallow(() -> client().setContext(key, value));
    }

    public static void probe(String label, Object data) {
        swallow(() -> client().probe(label, data));
    }

    public static void probe(String label, Supplier<?> dataSupplier) {
        swallow(() -> client().probe(label, dataSupplier));
    }

    public static void probe(String label, Supplier<?> dataSupplier, ProbeOptions options) {
        swallow(() -> client().probe(label, dataSupplier, options));
    }

    public static DebugBundleRequestScope beginRequest(Map<String, Object> request) {
        try {
            return client().beginRequest(request == null ? Map.of() : request);
        } catch (RuntimeException error) {
            return DebugBundleRequestScope.noop();
        }
    }

    public static void endRequest(DebugBundleRequestScope scope) {
        swallow(() -> client().endRequest(scope));
    }

    public static CompletableFuture<Void> flush() {
        try {
            return client().flush();
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(null);
        }
    }

    public static DebugBundleStatus status() {
        try {
            return client().status();
        } catch (RuntimeException error) {
            return DebugBundleStatus.DISCONNECTED;
        }
    }

    public static Optional<Instant> lastEventAt() {
        try {
            return client().lastEventAt();
        } catch (RuntimeException error) {
            return Optional.empty();
        }
    }

    public static void captureUncaughtExceptions() {
        if (!UNCAUGHT_HANDLER_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            captureException(throwable, Map.of("thread", thread == null ? "unknown" : thread.getName()));
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    public static void captureJavaUtilLogging() {
        if (!JUL_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        Logger.getLogger("").addHandler(new DebugBundleJulHandler(DebugBundle::client));
    }

    private static void swallow(Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException ignored) {
        }
    }
}

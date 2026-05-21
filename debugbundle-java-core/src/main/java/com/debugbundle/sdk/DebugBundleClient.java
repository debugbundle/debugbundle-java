package com.debugbundle.sdk;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public interface DebugBundleClient extends AutoCloseable {
    DebugBundleConfig config();

    void captureException(Throwable error);

    void captureException(Throwable error, Map<String, Object> context);

    void captureError(Throwable error);

    void captureLog(String message, LogLevel level);

    void captureLog(String message, LogLevel level, Map<String, Object> context);

    void captureRequest(Object request, Object response, Map<String, Object> context);

    void captureMessage(String message);

    void captureMessage(String message, LogLevel level, Map<String, Object> context);

    void setContext(String key, Object value);

    void probe(String label, Object data);

    void probe(String label, Supplier<?> dataSupplier);

    void probe(String label, Supplier<?> dataSupplier, ProbeOptions options);

    DebugBundleRequestScope beginRequest(Map<String, Object> request);

    void endRequest(DebugBundleRequestScope scope);

    CompletableFuture<Void> flush();

    DebugBundleStatus status();

    Optional<Instant> lastEventAt();

    @Override
    default void close() {
    }
}

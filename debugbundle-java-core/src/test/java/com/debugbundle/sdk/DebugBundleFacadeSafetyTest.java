package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DebugBundleFacadeSafetyTest {
    private AtomicReference<DebugBundleClient> clientReference;
    private DebugBundleClient original;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void installThrowingClient() throws Exception {
        Field field = DebugBundle.class.getDeclaredField("CLIENT");
        field.setAccessible(true);
        clientReference = (AtomicReference<DebugBundleClient>) field.get(null);
        original = clientReference.getAndSet(new ThrowingClient());
    }

    @AfterEach
    void restoreClient() {
        clientReference.set(original);
    }

    @Test
    void facadeContainsEveryClientFailureAndReturnsSafeFallbacks() {
        RuntimeException failure = new RuntimeException("failure");

        assertThatCode(() -> DebugBundle.captureException(failure)).doesNotThrowAnyException();
        assertThatCode(() -> DebugBundle.captureException(failure, null)).doesNotThrowAnyException();
        assertThatCode(() -> DebugBundle.captureError(failure)).doesNotThrowAnyException();
        assertThatCode(() -> DebugBundle.captureLog("log", LogLevel.ERROR)).doesNotThrowAnyException();
        assertThatCode(() -> DebugBundle.captureLog("log", LogLevel.ERROR, null)).doesNotThrowAnyException();
        assertThatCode(() -> DebugBundle.captureRequest(new Object(), new Object(), null)).doesNotThrowAnyException();
        assertThatCode(() -> DebugBundle.captureMessage("message")).doesNotThrowAnyException();
        assertThatCode(() -> DebugBundle.captureMessage("message", LogLevel.ERROR, null)).doesNotThrowAnyException();
        assertThatCode(() -> DebugBundle.setContext("tenant", "acme")).doesNotThrowAnyException();
        assertThatCode(() -> DebugBundle.probe("probe", Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> DebugBundle.probe("probe", () -> Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> DebugBundle.probe("probe", () -> Map.of(), ProbeOptions.heavyOption()))
                .doesNotThrowAnyException();
        assertThatCode(() -> DebugBundle.endRequest(DebugBundleRequestScope.noop())).doesNotThrowAnyException();

        assertThat(DebugBundle.beginRequest(null)).isSameAs(DebugBundleRequestScope.noop());
        assertThat(DebugBundle.flush()).isCompleted();
        assertThat(DebugBundle.status()).isEqualTo(DebugBundleStatus.DISCONNECTED);
        assertThat(DebugBundle.lastEventAt()).isEmpty();
    }

    private static final class ThrowingClient implements DebugBundleClient {
        private RuntimeException failure() {
            return new IllegalStateException("client failed");
        }

        @Override public DebugBundleConfig config() { throw failure(); }
        @Override public void captureException(Throwable error) { throw failure(); }
        @Override public void captureException(Throwable error, Map<String, Object> context) { throw failure(); }
        @Override public void captureError(Throwable error) { throw failure(); }
        @Override public void captureLog(String message, LogLevel level) { throw failure(); }
        @Override public void captureLog(String message, LogLevel level, Map<String, Object> context) { throw failure(); }
        @Override public void captureRequest(Object request, Object response, Map<String, Object> context) {
            throw failure();
        }
        @Override public void captureMessage(String message) { throw failure(); }
        @Override public void captureMessage(String message, LogLevel level, Map<String, Object> context) {
            throw failure();
        }
        @Override public void setContext(String key, Object value) { throw failure(); }
        @Override public void probe(String label, Object data) { throw failure(); }
        @Override public void probe(String label, Supplier<?> dataSupplier) { throw failure(); }
        @Override public void probe(String label, Supplier<?> dataSupplier, ProbeOptions options) { throw failure(); }
        @Override public DebugBundleRequestScope beginRequest(Map<String, Object> request) { throw failure(); }
        @Override public void endRequest(DebugBundleRequestScope scope) { throw failure(); }
        @Override public CompletableFuture<Void> flush() { throw failure(); }
        @Override public DebugBundleStatus status() { throw failure(); }
        @Override public Optional<Instant> lastEventAt() { throw failure(); }
    }
}

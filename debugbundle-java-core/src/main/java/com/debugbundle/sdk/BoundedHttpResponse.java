package com.debugbundle.sdk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Internal HTTP primitive shared by maintained SDK adapters. Reads bounded UTF-8
 * replies without decompression and cancels the body at the request deadline.
 * Native DNS/connect cancellation still follows the JVM HTTP client's behaviour. */
public final class BoundedHttpResponse {
    private static final int MAX_BYTES = 256 * 1024;

    private BoundedHttpResponse() { }

    /** Execute one adapter-owned request using its existing timeout, at most 60 seconds.
     * Rejected, oversized or malformed bodies return null without changing status/headers.
     * @param client existing native client
     * @param request adapter-owned request
     * @return native response with a bounded body
     * @throws IOException native request failure or deadline exhaustion
     * @throws InterruptedException caller interruption after cancelling the request
     */
    public static HttpResponse<String> send(HttpClient client, HttpRequest request) throws IOException, InterruptedException {
        Duration timeout = request.timeout().orElse(Duration.ofSeconds(5));
        if (timeout.isZero() || timeout.isNegative()) timeout = Duration.ofSeconds(5);
        if (timeout.compareTo(Duration.ofSeconds(60)) > 0) timeout = Duration.ofSeconds(60);
        long deadline = System.nanoTime() + timeout.toNanos();
        AtomicReference<Subscriber> subscriber = new AtomicReference<>();
        CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, info -> {
            Subscriber body = new Subscriber();
            subscriber.set(body);
            String encoding = info.headers().firstValue("Content-Encoding").orElse("identity").trim();
            boolean rejected = !encoding.isEmpty() && !encoding.equalsIgnoreCase("identity");
            String length = info.headers().firstValue("Content-Length").orElse(null);
            if (length != null) {
                try { long size = Long.parseLong(length); rejected |= size < 0 || size > MAX_BYTES; }
                catch (NumberFormatException ignored) { rejected = true; }
            }
            if (rejected) body.cancel();
            return body;
        });
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ignored) {
            throw new HttpTimeoutException("SDK HTTP response deadline exhausted");
        } catch (ExecutionException ignored) {
            throw new IOException("SDK HTTP response unavailable");
        } finally {
            // Future cancellation propagates to the native exchange. Explicit
            // subscription cancellation also stops an already-started body.
            if (!future.isDone() || future.isCompletedExceptionally()) {
                future.cancel(true);
                Subscriber body = subscriber.get();
                if (body != null) body.cancel();
            }
        }
    }

    private static final class Subscriber implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private volatile ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);

        @Override
        public CompletionStage<String> getBody() { return body; }

        @Override
        public void onSubscribe(Flow.Subscription incoming) {
            if (!subscription.compareAndSet(null, incoming) || body.isDone()) incoming.cancel();
            else incoming.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (body.isDone()) return;
            ByteArrayOutputStream owned = buffer;
            if (owned == null) return;
            for (ByteBuffer item : items) {
                if (item.remaining() > MAX_BYTES - owned.size()) { cancel(); return; }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                owned.writeBytes(chunk);
            }
            Flow.Subscription current = subscription.get();
            if (current != null && !body.isDone()) current.request(1);
        }

        @Override
        public void onError(Throwable ignored) { cancel(); }

        @Override
        public void onComplete() {
            ByteArrayOutputStream owned = buffer;
            buffer = null;
            if (owned == null || body.isDone()) return;
            try {
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(owned.toByteArray())).toString();
                body.complete(text);
            } catch (java.nio.charset.CharacterCodingException ignored) {
                body.complete(null);
            }
        }

        private void cancel() {
            buffer = null;
            body.complete(null);
            Flow.Subscription current = subscription.get();
            if (current != null) current.cancel();
        }
    }
}

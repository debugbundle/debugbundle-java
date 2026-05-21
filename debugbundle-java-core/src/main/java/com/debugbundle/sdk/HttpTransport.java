package com.debugbundle.sdk;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

final class HttpTransport implements DebugBundleTransport {
    private static final long MAX_RETRY_AFTER_MILLIS = 300_000L;

    private final HttpClient httpClient;
    private final String endpoint;
    private final String projectToken;

    HttpTransport(String endpoint, String projectToken) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.endpoint = endpoint;
        this.projectToken = projectToken;
    }

    @Override
    public TransportResponse send(EventBatchRequest request) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("events", request.events());

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + projectToken)
                    .POST(HttpRequest.BodyPublishers.ofString(JsonWriter.write(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return new TransportResponse(response.statusCode(), parseRetryAfterMillis(response));
        } catch (IOException | InterruptedException | IllegalArgumentException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new TransportResponse(500, null);
        }
    }

    private Long parseRetryAfterMillis(HttpResponse<?> response) {
        return response.headers()
                .firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Math.min(Long.parseLong(value) * 1000L, MAX_RETRY_AFTER_MILLIS);
                    } catch (NumberFormatException error) {
                        return null;
                    }
                })
                .orElse(null);
    }
}

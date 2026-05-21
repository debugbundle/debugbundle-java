package com.debugbundle.spring.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HttpRelayForwarder implements DebugBundleBrowserRelayHandler.RelayForwarder {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DebugBundleProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    HttpRelayForwarder(DebugBundleProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean forward(List<Map<String, Object>> acceptedEvents) {
        if (acceptedEvents.isEmpty()) {
            return true;
        }
        if (properties.getProjectToken() == null || properties.getProjectToken().isBlank()) {
            return false;
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("events", acceptedEvents);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getEndpoint()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getProjectToken())
                    .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (IOException | InterruptedException | IllegalArgumentException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}

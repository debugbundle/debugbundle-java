package com.debugbundle.sdk;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class HttpRemoteConfigFetcher implements RemoteConfigFetcher {
    private final HttpClient httpClient;

    HttpRemoteConfigFetcher() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public RemoteConfigResponse fetch(RemoteConfigRequest request) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(request.endpoint()))
                    .timeout(request.timeout())
                    .header("Authorization", "Bearer " + request.projectToken())
                    .header("x-debugbundle-sdk", request.sdkName())
                    .header("x-debugbundle-sdk-version", request.sdkVersion())
                    .GET();

            if (request.ifNoneMatch() != null && !request.ifNoneMatch().isBlank()) {
                builder.header("If-None-Match", request.ifNoneMatch());
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new RemoteConfigResponse(
                    response.statusCode(),
                    response.body(),
                    response.headers().firstValue("etag").orElse(null)
            );
        } catch (IOException | InterruptedException | IllegalArgumentException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new RemoteConfigResponse(500, null, null);
        }
    }
}

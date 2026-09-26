package com.debugbundle.sdk;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.HashMap;
import java.util.Map;

final class HttpTransport implements DebugBundleTransport {
    private static final long MAX_RETRY_AFTER_MILLIS = 300_000L;

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final String endpoint;
    private final String projectToken;

    HttpTransport(String endpoint, String projectToken) {
        this(endpoint, projectToken, Duration.ofSeconds(5));
    }

    HttpTransport(String endpoint, String projectToken, Duration timeout) {
        this.requestTimeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(5) : timeout.compareTo(Duration.ofSeconds(60)) > 0 ? Duration.ofSeconds(60) : timeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
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
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + projectToken)
                    .POST(HttpRequest.BodyPublishers.ofString(JsonWriter.write(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return new TransportResponse(response.statusCode(), parseRetryAfterMillis(response), response.body());
        } catch (IOException | InterruptedException | IllegalArgumentException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new TransportResponse(500, null);
        }
    }

    private Long parseHttpDate(String value) {
        for (String pattern : new String[]{"EEE, dd MMM yyyy HH:mm:ss 'GMT'",
                "EEEE, dd-MMM-yy HH:mm:ss 'GMT'", "EEE MMM d HH:mm:ss yyyy"}) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("GMT"));
            format.setLenient(false);
            format.set2DigitYearStart(Date.from(ZonedDateTime.now(ZoneOffset.UTC).minusYears(50).toInstant()));
            ParsePosition position = new ParsePosition(0);
            Date date = format.parse(value, position);
            if (date != null && position.getIndex() == value.length()) {
                return (long) Math.min(MAX_RETRY_AFTER_MILLIS, Math.max(0, (double) date.getTime() - System.currentTimeMillis()));
            }
        }
        return null;
    }

    private Long parseRetryAfterMillis(HttpResponse<?> response) {
        return response.headers()
                .firstValue("Retry-After")
                .map(value -> {
                    try {
                        double seconds = Double.parseDouble(value);
                        return Double.isFinite(seconds)
                                ? (long) (Math.min(Math.max(0, seconds), MAX_RETRY_AFTER_MILLIS / 1000L) * 1000) : null;
                    } catch (NumberFormatException error) {
                        return parseHttpDate(value);
                    }
                })
                .orElse(null);
    }
}

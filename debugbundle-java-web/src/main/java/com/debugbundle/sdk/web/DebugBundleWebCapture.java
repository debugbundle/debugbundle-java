package com.debugbundle.sdk.web;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class DebugBundleWebCapture {
    private DebugBundleWebCapture() {
    }

    @FunctionalInterface
    public interface HeaderLookup {
        String get(String name);
    }

    public static Map<String, Object> beginRequestSnapshot(
            String method,
            String path,
            Object routeTemplate,
            Map<String, Object> headers,
            Map<String, Object> query
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("method", method);
        snapshot.put("path", path);
        snapshot.put("route_template", routeTemplate);
        snapshot.put("headers", copyMap(headers));
        snapshot.put("query", copyMap(query));
        return snapshot;
    }

    public static Map<String, Object> requestSnapshot(
            String method,
            String path,
            Map<String, Object> headers,
            Map<String, Object> query
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("method", method);
        snapshot.put("path", path);
        snapshot.put("headers", copyMap(headers));
        snapshot.put("query", copyMap(query));
        return snapshot;
    }

    public static Map<String, Object> responseSnapshot(int statusCode, long durationMillis) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status_code", statusCode);
        snapshot.put("duration_ms", Math.max(0L, durationMillis));
        return snapshot;
    }

    public static Map<String, Object> context(
            String method,
            String path,
            Object routeTemplate,
            int responseStatus,
            long durationMillis,
            String traceId,
            String requestId
    ) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("method", method);
        context.put("path", path);
        context.put("route_template", routeTemplate);
        context.put("response_status", responseStatus);
        context.put("duration_ms", Math.max(0L, durationMillis));
        context.put("trace_id", traceId);
        context.put("request_id", requestId);
        return context;
    }

    public static Map<String, Object> selectedHeaders(
            HeaderLookup headerLookup,
            Iterable<String> headerNames,
            boolean includeNullValues
    ) {
        Map<String, Object> headers = new LinkedHashMap<>();
        if (headerLookup == null || headerNames == null) {
            return headers;
        }

        for (String headerName : headerNames) {
            if (headerName == null || headerName.isBlank()) {
                continue;
            }
            String value = headerLookup.get(headerName);
            if (value != null && !value.isBlank()) {
                headers.put(headerName, value);
                continue;
            }
            if (includeNullValues) {
                headers.put(headerName, null);
            }
        }
        return headers;
    }

    public static Map<String, Object> queryParameters(
            Iterable<String> parameterNames,
            Function<String, String[]> valuesByName
    ) {
        Map<String, Object> query = new LinkedHashMap<>();
        if (parameterNames == null || valuesByName == null) {
            return query;
        }

        for (String name : parameterNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String[] values = valuesByName.apply(name);
            if (values == null || values.length == 0) {
                continue;
            }
            if (values.length == 1) {
                query.put(name, values[0]);
                continue;
            }

            List<String> listValues = new ArrayList<>();
            for (String value : values) {
                if (value != null) {
                    listValues.add(value);
                }
            }
            query.put(name, listValues);
        }
        return query;
    }

    public static long durationMillis(Instant startedAt, Instant endedAt) {
        if (startedAt == null || endedAt == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(startedAt, endedAt).toMillis());
    }

    public static String firstNonBlank(String... values) {
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

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(source);
    }
}

package com.debugbundle.sdk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RequestContextFields {
    private RequestContextFields() {}

    static Map<String, Object> buildRequestScopeContext(Map<String, Object> request) {
        if (request == null || request.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> context = new LinkedHashMap<>();
        Map<?, ?> headers = request.get("headers") instanceof Map<?, ?> headerMap ? headerMap : Map.of();
        copyIfPresent(context, "method", request.get("method"));
        copyIfPresent(context, "path", request.get("path"));
        copyIfPresent(context, "route_template", request.get("route_template"));
        copyIfPresent(context, "request_id", firstNonBlank(
                asString(request.get("request_id")),
                extractHeaderValue(headers, "x-request-id"),
                extractHeaderValue(headers, "x-correlation-id")
        ));
        copyIfPresent(context, "trace_id", firstNonBlank(
                asString(request.get("trace_id")),
                extractHeaderValue(headers, "x-debugbundle-trace-id")
        ));
        return context;
    }

    private static void copyIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String stringValue && stringValue.isBlank()) {
            return;
        }
        target.put(key, value);
    }

    static String asString(Object value) {
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        return null;
    }

    private static String extractHeaderValue(Map<?, ?> headers, String headerName) {
        int inspected = 0;
        for (Map.Entry<?, ?> entry : headers.entrySet()) {
            if (++inspected > 128) break;
            if (!(entry.getKey() instanceof String key) || !headerName.equalsIgnoreCase(key)) {
                continue;
            }

            Object value = entry.getValue();
            if (value instanceof String stringValue && !stringValue.isBlank()) {
                return stringValue;
            }
            if (value instanceof List<?> listValue) {
                int inspectedItems = 0;
                for (Object item : listValue) {
                    if (++inspectedItems > 8) break;
                    if (item instanceof String stringItem && !stringItem.isBlank()) {
                        return stringItem;
                    }
                }
            }
        }
        return null;
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static Integer extractStatusCode(Object response) {
        if (!(response instanceof Map<?, ?> rawMap)) {
            return null;
        }

        Map<String, Object> responseMap = (Map<String, Object>) rawMap;
        Integer statusCode = asInteger(responseMap.get("status_code"));
        if (statusCode != null) {
            return statusCode;
        }
        return asInteger(responseMap.get("status"));
    }

    static Integer asInteger(Object value) {
        if (value instanceof Integer integerValue) {
            return integerValue;
        }
        if (value instanceof Number numberValue) {
            return numberValue.intValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

}

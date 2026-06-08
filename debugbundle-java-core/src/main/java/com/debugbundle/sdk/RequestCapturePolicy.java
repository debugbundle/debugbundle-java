package com.debugbundle.sdk;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

final class RequestCapturePolicy {
    private static final Set<Integer> BALANCED_IMMEDIATE_REQUEST_STATUSES = Set.of(408, 423, 424, 425, 429);
    private static final Set<Integer> INVESTIGATIVE_IMMEDIATE_REQUEST_STATUSES = Set.of(408, 409, 423, 424, 425, 429);

    private RequestCapturePolicy() {
    }

    static boolean shouldCapture(Integer statusCode, String requestPath, String httpMethod, CapturePolicy policy) {
        if (statusCode == null) {
            return policy.captureRequestEvents() == CapturePolicy.CaptureRequestEventsMode.ALL;
        }

        if (isImmediateRequestIncidentStatus(statusCode, requestPath, httpMethod, policy)) {
            return true;
        }

        return switch (policy.captureRequestEvents()) {
            case OFF -> false;
            case FAILURES_ONLY -> statusCode >= 500;
            case FILTERED -> false;
            case ALL -> true;
        };
    }

    private static boolean isImmediateRequestIncidentStatus(int statusCode, String requestPath, String httpMethod, CapturePolicy policy) {
        if (statusCode >= 500) {
            return true;
        }

        if (policy.immediateClientErrorStatuses().contains(statusCode)) {
            return true;
        }

        if (matchesImmediateClientErrorPathRule(statusCode, requestPath, httpMethod, policy)) {
            return true;
        }

        if ("investigative".equals(policy.preset())) {
            return INVESTIGATIVE_IMMEDIATE_REQUEST_STATUSES.contains(statusCode);
        }

        if ("balanced".equals(policy.preset())) {
            return BALANCED_IMMEDIATE_REQUEST_STATUSES.contains(statusCode);
        }

        return false;
    }

    private static boolean matchesImmediateClientErrorPathRule(int statusCode, String requestPath, String httpMethod, CapturePolicy policy) {
        if (statusCode < 400 || statusCode > 499 || requestPath == null || requestPath.isBlank()) {
            return false;
        }

        String normalizedPath = normalizeRequestPath(requestPath);
        String normalizedMethod = httpMethod == null ? null : httpMethod.trim().toUpperCase(Locale.ROOT);
        for (ImmediateClientErrorPathRule rule : policy.immediateClientErrorPathRules()) {
            if (rule.statusCode() != statusCode) {
                continue;
            }
            if (!rule.methods().isEmpty() && (normalizedMethod == null || !rule.methods().contains(normalizedMethod))) {
                continue;
            }
            if (rule.pathPattern().endsWith("*")) {
                if (normalizedPath.startsWith(rule.pathPattern().substring(0, rule.pathPattern().length() - 1))) {
                    return true;
                }
                continue;
            }
            if (normalizedPath.equals(rule.pathPattern())) {
                return true;
            }
        }

        return false;
    }

    private static String normalizeRequestPath(String value) {
        try {
            URI uri = new URI(value);
            if (uri.getPath() != null && !uri.getPath().isBlank()) {
                return uri.getPath();
            }
        } catch (URISyntaxException ignored) {
        }

        int queryIndex = value.indexOf('?');
        int fragmentIndex = value.indexOf('#');
        int end = queryIndex < 0
                ? (fragmentIndex < 0 ? value.length() : fragmentIndex)
                : (fragmentIndex < 0 ? queryIndex : Math.min(queryIndex, fragmentIndex));
        String path = value.substring(0, end);
        return path.startsWith("/") && !path.isBlank() ? path : "/";
    }
}

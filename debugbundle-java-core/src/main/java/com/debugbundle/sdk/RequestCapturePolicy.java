package com.debugbundle.sdk;

import java.util.Set;

final class RequestCapturePolicy {
    private static final Set<Integer> BALANCED_IMMEDIATE_REQUEST_STATUSES = Set.of(408, 423, 424, 425, 429);
    private static final Set<Integer> INVESTIGATIVE_IMMEDIATE_REQUEST_STATUSES = Set.of(408, 409, 423, 424, 425, 429);
    private static final Set<Integer> BALANCED_ANOMALY_STATUSES = Set.of(400, 401, 403, 404, 409, 410, 422);
    private static final Set<Integer> INVESTIGATIVE_ANOMALY_STATUSES = Set.of(400, 401, 403, 404, 409, 410, 422);

    private RequestCapturePolicy() {
    }

    static boolean shouldCapture(Integer statusCode, CapturePolicy policy) {
        if (statusCode == null) {
            return policy.captureRequestEvents() == CapturePolicy.CaptureRequestEventsMode.ALL;
        }

        if (isImmediateRequestIncidentStatus(statusCode, policy)) {
            return true;
        }

        return switch (policy.captureRequestEvents()) {
            case OFF -> false;
            case FAILURES_ONLY -> isRequestAnomalyCandidateStatus(statusCode, policy.preset());
            case FILTERED -> false;
            case ALL -> true;
        };
    }

    private static boolean isImmediateRequestIncidentStatus(int statusCode, CapturePolicy policy) {
        if (statusCode >= 500) {
            return true;
        }

        if (policy.immediateClientErrorStatuses().contains(statusCode)) {
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

    private static boolean isRequestAnomalyCandidateStatus(int statusCode, String preset) {
        if (statusCode < 400 || statusCode >= 500) {
            return false;
        }

        if ("investigative".equals(preset)) {
            return INVESTIGATIVE_ANOMALY_STATUSES.contains(statusCode);
        }

        if ("balanced".equals(preset)) {
            return BALANCED_ANOMALY_STATUSES.contains(statusCode);
        }

        return false;
    }
}

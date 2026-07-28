package com.debugbundle.sdk;

record TransportResponse(int statusCode, Long retryAfterMillis, String body) {
    TransportResponse(int statusCode, Long retryAfterMillis) {
        this(statusCode, retryAfterMillis, null);
    }

    boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    boolean isRateLimited() {
        return statusCode == 429;
    }

    boolean isRetryableFailure() {
        return isRateLimited() || statusCode >= 500;
    }
}

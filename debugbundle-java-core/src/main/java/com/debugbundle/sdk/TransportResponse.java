package com.debugbundle.sdk;

record TransportResponse(int statusCode, Long retryAfterMillis) {
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


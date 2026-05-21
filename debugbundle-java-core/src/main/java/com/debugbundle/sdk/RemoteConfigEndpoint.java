package com.debugbundle.sdk;

final class RemoteConfigEndpoint {
    private RemoteConfigEndpoint() {
    }

    static String fromIngestionEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "https://api.debugbundle.com/v1/sdk/config";
        }

        if (endpoint.endsWith("/events")) {
            return endpoint.substring(0, endpoint.length() - "/events".length()) + "/sdk/config";
        }

        return endpoint.endsWith("/") ? endpoint + "sdk/config" : endpoint + "/sdk/config";
    }
}

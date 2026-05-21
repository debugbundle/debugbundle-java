package com.debugbundle.sdk;

record RemoteConfigResponse(
        int statusCode,
        String responseBody,
        String etag
) {}

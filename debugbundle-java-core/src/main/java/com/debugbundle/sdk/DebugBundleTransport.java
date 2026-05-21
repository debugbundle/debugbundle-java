package com.debugbundle.sdk;

interface DebugBundleTransport {
    TransportResponse send(EventBatchRequest request);
}


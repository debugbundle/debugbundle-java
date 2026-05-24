package com.debugbundle.sdk.web;

import java.io.IOException;
import java.io.InputStream;

public final class DebugBundleBrowserRelayBodyReader {
    private DebugBundleBrowserRelayBodyReader() {
    }

    public static byte[] readBoundedBody(InputStream inputStream) throws IOException, PayloadTooLargeException {
        try (InputStream boundedInput = inputStream) {
            byte[] body = boundedInput.readNBytes(DebugBundleBrowserRelay.DEFAULT_MAX_BODY_BYTES + 1);
            if (body.length > DebugBundleBrowserRelay.DEFAULT_MAX_BODY_BYTES) {
                throw new PayloadTooLargeException();
            }
            return body;
        }
    }

    public static final class PayloadTooLargeException extends IOException {
    }
}
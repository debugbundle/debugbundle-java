package com.debugbundle.spring.boot;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DebugBundleRelayController {
    private final DebugBundleBrowserRelayHandler relayHandler;

    DebugBundleRelayController(DebugBundleBrowserRelayHandler relayHandler) {
        this.relayHandler = relayHandler;
    }

    @PostMapping("/debugbundle/browser")
    ResponseEntity<?> relay(HttpServletRequest request) {
        byte[] body;
        try {
            body = readBoundedBody(request);
        } catch (PayloadTooLargeException error) {
            return ResponseEntity.status(413).build();
        } catch (IOException error) {
            return ResponseEntity.status(400).body(Map.of("errors", java.util.List.of("Relay request body could not be read.")));
        }

        DebugBundleBrowserRelayHandler.RelayResponse response = relayHandler.handle(request, body);
        if (response.body() == null) {
            return ResponseEntity.status(response.status()).build();
        }
        return ResponseEntity.status(response.status()).body(response.body());
    }

    private byte[] readBoundedBody(HttpServletRequest request) throws IOException {
        try (InputStream inputStream = request.getInputStream()) {
            byte[] body = inputStream.readNBytes(DebugBundleBrowserRelayHandler.DEFAULT_MAX_BODY_BYTES + 1);
            if (body.length > DebugBundleBrowserRelayHandler.DEFAULT_MAX_BODY_BYTES) {
                throw new PayloadTooLargeException();
            }
            return body;
        }
    }

    private static final class PayloadTooLargeException extends IOException {
    }
}

package com.debugbundle.spring.boot;

import com.debugbundle.sdk.web.DebugBundleBrowserRelayBodyReader;
import java.io.IOException;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DebugBundleRelayController {
    private final DebugBundleBrowserRelayHandler relayHandler;

    DebugBundleRelayController(DebugBundleBrowserRelayHandler relayHandler) {
        this.relayHandler = relayHandler;
    }

    @RequestMapping(value = "/debugbundle/browser", method = {RequestMethod.POST, RequestMethod.OPTIONS})
    ResponseEntity<?> relay(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return toResponse(relayHandler.handle(request, new byte[0]));
        }

        byte[] body;
        try {
            body = readBoundedBody(request);
        } catch (DebugBundleBrowserRelayBodyReader.PayloadTooLargeException error) {
            return ResponseEntity.status(413).build();
        } catch (IOException error) {
            return ResponseEntity.status(400).body(Map.of("errors", java.util.List.of("Relay request body could not be read.")));
        }

        DebugBundleBrowserRelayHandler.RelayResponse response = relayHandler.handle(request, body);
        return toResponse(response);
    }

    private ResponseEntity<?> toResponse(DebugBundleBrowserRelayHandler.RelayResponse response) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status());
        response.headers().forEach(builder::header);
        if (response.body() == null) {
            return builder.build();
        }
        return builder.body(response.body());
    }

    private byte[] readBoundedBody(HttpServletRequest request) throws IOException {
        return DebugBundleBrowserRelayBodyReader.readBoundedBody(request.getInputStream());
    }
}

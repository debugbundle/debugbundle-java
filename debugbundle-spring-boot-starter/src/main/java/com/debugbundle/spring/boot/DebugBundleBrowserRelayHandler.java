package com.debugbundle.spring.boot;

import com.debugbundle.sdk.web.DebugBundleBrowserRelay;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

final class DebugBundleBrowserRelayHandler {
    static final int DEFAULT_MAX_BODY_BYTES = DebugBundleBrowserRelay.DEFAULT_MAX_BODY_BYTES;

    private final DebugBundleBrowserRelay relay;

    DebugBundleBrowserRelayHandler(DebugBundleProperties properties) {
        this(properties, new DebugBundleBrowserRelay.HttpRelayForwarder(toConfig(properties))::forward);
    }

    DebugBundleBrowserRelayHandler(DebugBundleProperties properties, RelayForwarder relayForwarder) {
        this.relay = new DebugBundleBrowserRelay(toConfig(properties), relayForwarder);
    }

    RelayResponse handle(HttpServletRequest request, byte[] requestBody) {
        DebugBundleBrowserRelay.Response response = relay.handle(toRequest(request), requestBody);
        return new RelayResponse(response.status(), response.body());
    }

    private static DebugBundleBrowserRelay.Config toConfig(DebugBundleProperties properties) {
        DebugBundleProperties resolved = properties == null ? new DebugBundleProperties() : properties;
        return new DebugBundleBrowserRelay.Config(
                resolved.getProjectToken(),
                resolved.getEndpoint(),
                resolved.getProjectMode(),
                resolved.getLocalEventsDir(),
                resolved.getRelay().getRateLimitPerMinute(),
                resolved.getRelay().isDurableWrite(),
                resolved.getRelay().getSpoolDir(),
                resolved.getRelay().getAllowedOrigins(),
                resolved.getService(),
                resolved.getEnvironment()
        );
    }

    private static DebugBundleBrowserRelay.Request toRequest(HttpServletRequest request) {
        return new DebugBundleBrowserRelay.Request(
                request.getMethod(),
                request.getContentType(),
                request.getRemoteAddr(),
                request::getHeader
        );
    }

    interface RelayForwarder extends DebugBundleBrowserRelay.RelayForwarder {
    }

    record RelayResponse(int status, Map<String, Object> body) {
    }
}

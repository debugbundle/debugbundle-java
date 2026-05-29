package com.debugbundle.servlet.jakarta;

import com.debugbundle.sdk.web.DebugBundleBrowserRelay;
import com.debugbundle.sdk.web.DebugBundleBrowserRelayBodyReader;
import com.debugbundle.sdk.web.DebugBundleBrowserRelayConfigLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

public class DebugBundleRelayServlet extends HttpServlet {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private volatile DebugBundleBrowserRelay relay;

    public DebugBundleRelayServlet() {
    }

    DebugBundleRelayServlet(DebugBundleBrowserRelay relay) {
        this.relay = relay;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        if (relay == null) {
            relay = new DebugBundleBrowserRelay(DebugBundleBrowserRelayConfigLoader.load(
                    config::getInitParameter,
                    config.getServletContext()::getInitParameter
            ));
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        byte[] body;
        try {
            body = DebugBundleBrowserRelayBodyReader.readBoundedBody(request.getInputStream());
        } catch (DebugBundleBrowserRelayBodyReader.PayloadTooLargeException error) {
            response.setStatus(413);
            return;
        } catch (IOException error) {
            writeResponse(response, 400, Map.of("errors", java.util.List.of("Relay request body could not be read.")));
            return;
        }

        DebugBundleBrowserRelay.Response relayResponse = relay.handle(toRequest(request), body);
        writeResponse(response, relayResponse.status(), relayResponse.body(), relayResponse.headers());
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        DebugBundleBrowserRelay.Response relayResponse = relay.handle(toRequest(request), new byte[0]);
        writeResponse(response, relayResponse.status(), relayResponse.body(), relayResponse.headers());
    }

    private void writeResponse(HttpServletResponse response, int status, Map<String, Object> body) throws IOException {
        writeResponse(response, status, body, Map.of());
    }

    private void writeResponse(HttpServletResponse response, int status, Map<String, Object> body, Map<String, String> headers) throws IOException {
        response.setStatus(status);
        headers.forEach(response::setHeader);
        if (body == null) {
            return;
        }
        response.setContentType("application/json");
        OBJECT_MAPPER.writeValue(response.getOutputStream(), body);
    }

    private DebugBundleBrowserRelay.Request toRequest(HttpServletRequest request) {
        return new DebugBundleBrowserRelay.Request(
                request.getMethod(),
                request.getContentType(),
                request.getRemoteAddr(),
                request::getHeader
        );
    }
}
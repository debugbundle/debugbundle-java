package com.debugbundle.sdk;

import java.io.IOException;
import java.nio.file.Path;

final class FileTransport implements DebugBundleTransport {
    private final String localEventsDir;
    private final String service;

    FileTransport(String localEventsDir, String service) {
        this.localEventsDir = localEventsDir;
        this.service = service;
    }

    @Override
    public TransportResponse send(EventBatchRequest request) {
        try {
            DebugBundleFileWriter.writeEventFile(Path.of(localEventsDir), service, JsonWriter.write(request.events()));
            return new TransportResponse(202, null);
        } catch (IOException | RuntimeException error) {
            return new TransportResponse(500, null);
        }
    }
}

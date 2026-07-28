package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebugBundleFileWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesPrivateAtomicEventFilesAndMarkers() throws Exception {
        Path event = DebugBundleFileWriter.writeEventFile(
                tempDir.resolve("events"), "checkout api/production", "[{\"event_id\":\"evt-1\"}]");
        Path marker = tempDir.resolve("markers").resolve("ready");
        DebugBundleFileWriter.writeMarker(marker);

        assertThat(event.getFileName().toString()).endsWith("-checkout-api-production.events.json");
        assertThat(Files.readString(event)).isEqualTo("[{\"event_id\":\"evt-1\"}]");
        assertThat(Files.readString(marker)).isEmpty();
        assertThat(DebugBundleFileWriter.sanitizeServiceName(null)).isEqualTo("unknown-service");
        assertThat(DebugBundleFileWriter.sanitizeServiceName(" ")).isEqualTo("unknown-service");
        assertThat(DebugBundleFileWriter.sanitizeServiceName("orders.v2_test")).isEqualTo("orders.v2_test");
    }

    @Test
    void rejectsMissingTraversalSymlinkAndExistingMarkerTargets() throws Exception {
        assertThatThrownBy(() -> DebugBundleFileWriter.writeEventFile(null, "service", "[]"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> DebugBundleFileWriter.writeEventFile(
                tempDir.resolve("safe").resolve("..").resolve("unsafe"), "service", "[]"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("path traversal");

        Path marker = tempDir.resolve("marker");
        Files.writeString(marker, "existing");
        assertThatThrownBy(() -> DebugBundleFileWriter.writeMarker(marker))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unsafe");

        Path realDirectory = Files.createDirectory(tempDir.resolve("real"));
        Path symlink = tempDir.resolve("linked");
        Files.createSymbolicLink(symlink, realDirectory);
        assertThatThrownBy(() -> DebugBundleFileWriter.writeEventFile(symlink, "service", "[]"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("symlink");
    }

    @Test
    void fileTransportContainsFilesystemFailures() {
        TransportResponse response = new FileTransport("\0", "service")
                .send(new EventBatchRequest(java.util.List.of()));

        assertThat(response.statusCode()).isEqualTo(500);
    }
}

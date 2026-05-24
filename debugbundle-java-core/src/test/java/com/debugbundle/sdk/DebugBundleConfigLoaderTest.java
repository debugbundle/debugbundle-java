package com.debugbundle.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DebugBundleConfigLoaderTest {
    @Test
    void deploymentLookupPrefersScopedOverridesBeforeGlobalValues() throws Exception {
        Path propertiesFile = Files.createTempFile("debugbundle", ".properties");
        try {
            Files.writeString(propertiesFile, String.join("\n",
                    "debugbundle.project-token=test-token",
                    "debugbundle.environment=production",
                    "debugbundle.deployments.web-portal.service=web-portal",
                    "debugbundle.service=default-service"
            ));

            DebugBundleConfig config = DebugBundleConfigLoader.load(
                    ignored -> null,
                    DebugBundleConfigLoader.deploymentLookup(
                            DebugBundleConfigLoader.propertiesFileLookup(propertiesFile.toString()),
                            "web-portal"
                    ),
                    "fallback-service"
            );

            assertThat(config.projectToken()).isEqualTo("test-token");
            assertThat(config.environment()).isEqualTo("production");
            assertThat(config.service()).isEqualTo("web-portal");
        } finally {
            Files.deleteIfExists(propertiesFile);
        }
    }

    @Test
    void loadReadsAdvancedCoreOptionsFromPropertiesFile() throws Exception {
        Path propertiesFile = Files.createTempFile("debugbundle", ".properties");
        try {
            Files.writeString(propertiesFile, String.join("\n",
                    "debugbundle.project-token=test-token",
                    "debugbundle.redact-fields=password,customer_secret",
                    "debugbundle.request-timeout=7s",
                    "debugbundle.probes-poll-interval=90s",
                    "debugbundle.max-probe-labels=75",
                    "debugbundle.max-probe-entries-per-label=15",
                    "debugbundle.probe-flush-on-error=false"
            ));

            DebugBundleConfig config = DebugBundleConfigLoader.load(
                    ignored -> null,
                    DebugBundleConfigLoader.propertiesFileLookup(propertiesFile.toString()),
                    "fallback-service"
            );

            assertThat(config.redactFields()).containsExactly("password", "customer_secret");
            assertThat(config.requestTimeout()).isEqualTo(Duration.ofSeconds(7));
            assertThat(config.probesPollInterval()).isEqualTo(Duration.ofSeconds(90));
            assertThat(config.maxProbeLabels()).isEqualTo(75);
            assertThat(config.maxProbeEntriesPerLabel()).isEqualTo(15);
            assertThat(config.probeFlushOnError()).isFalse();
        } finally {
            Files.deleteIfExists(propertiesFile);
        }
    }
}
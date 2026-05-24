package com.debugbundle.sdk.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DebugBundleBrowserRelayConfigLoaderTest {
    @Test
    void loadPreservesRelayServiceAndEnvironmentOverrides() {
        Map<String, String> contextParams = Map.of(
                "debugbundle.service", "patients-web",
                "debugbundle.environment", "production",
                "debugbundle.relay.allowed-origins", "https://app.example.com"
        );

        DebugBundleBrowserRelay.Config config = DebugBundleBrowserRelayConfigLoader.load(
                ignored -> null,
                contextParams::get,
                ignored -> null,
                ignored -> null
        );

        assertThat(config.service()).isEqualTo("patients-web");
        assertThat(config.environment()).isEqualTo("production");
        assertThat(config.allowedOrigins()).containsExactly("https://app.example.com");
    }
}
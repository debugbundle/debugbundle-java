package com.debugbundle.sdk.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DebugBundleBrowserRelayConfigLoaderTest {
    @Test
    void loadPreservesRelayServiceAndEnvironmentOverrides() {
        Map<String, String> contextParams = Map.of(
                "debugbundle.relay.service", "patients-web",
                "debugbundle.relay.environment", "production",
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

    @Test
    void loadDoesNotReuseBackendServiceIdentityAsImplicitRelayOverride() {
        Map<String, String> contextParams = Map.of(
                "debugbundle.service", "api-backend",
                "debugbundle.environment", "production"
        );

        DebugBundleBrowserRelay.Config config = DebugBundleBrowserRelayConfigLoader.load(
                ignored -> null,
                contextParams::get,
                ignored -> null,
                ignored -> null
        );

        assertThat(config.service()).isNull();
        assertThat(config.environment()).isNull();
    }
}
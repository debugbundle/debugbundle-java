package com.debugbundle.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.DebugBundleStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DebugBundleAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DebugBundleAutoConfiguration.class));

    @Test
    void autoConfigurationRegistersCoreBeans() {
        contextRunner
                .withPropertyValues(
                        "debugbundle.project-token=dbundle_proj_test",
                        "debugbundle.service=checkout-api",
                        "debugbundle.environment=test"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(DebugBundleClient.class);
                    assertThat(context).hasSingleBean(DebugBundleServletFilter.class);
                    assertThat(context).hasBean("debugBundleExceptionResolver");
                    assertThat(context).hasSingleBean(DebugBundleBrowserRelayHandler.class);
                    assertThat(context).hasSingleBean(DebugBundleRelayController.class);
                    assertThat(context).hasSingleBean(DebugBundleLogbackAppenderRegistrar.class);

                    DebugBundleClient client = context.getBean(DebugBundleClient.class);
                    assertThat(client.status()).isEqualTo(DebugBundleStatus.HEALTHY);
                });
    }

    @Test
    void missingProjectTokenDegradesInsteadOfFailingContextStartup() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DebugBundleClient.class);
            assertThat(context.getBean(DebugBundleClient.class).status())
                    .isEqualTo(DebugBundleStatus.DISCONNECTED);
        });
    }

    @Test
    void relayBeansBackOffWhenRelayIsDisabled() {
        contextRunner
                .withPropertyValues(
                        "debugbundle.project-token=dbundle_proj_test",
                        "debugbundle.relay.enabled=false"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DebugBundleBrowserRelayHandler.class);
                    assertThat(context).doesNotHaveBean(DebugBundleRelayController.class);
                });
    }
}

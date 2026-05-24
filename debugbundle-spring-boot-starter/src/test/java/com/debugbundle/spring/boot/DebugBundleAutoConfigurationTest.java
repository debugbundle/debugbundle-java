package com.debugbundle.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.DebugBundleStatus;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskDecorator;

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
                    assertThat(context).hasSingleBean(TaskDecorator.class);
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

    @Test
    void autoConfigurationBindsAdvancedCoreProperties() {
        contextRunner
                .withPropertyValues(
                        "debugbundle.project-token=dbundle_proj_test",
                        "debugbundle.redact-fields[0]=password",
                        "debugbundle.redact-fields[1]=customer_secret",
                        "debugbundle.request-timeout=7s",
                        "debugbundle.probes-poll-interval=90s",
                        "debugbundle.max-probe-labels=75",
                        "debugbundle.max-probe-entries-per-label=15",
                        "debugbundle.probe-flush-on-error=false"
                )
                .run(context -> {
                    DebugBundleClient client = context.getBean(DebugBundleClient.class);

                    assertThat(client.config().redactFields()).containsExactly("password", "customer_secret");
                    assertThat(client.config().requestTimeout()).isEqualTo(Duration.ofSeconds(7));
                    assertThat(client.config().probesPollInterval()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(client.config().maxProbeLabels()).isEqualTo(75);
                    assertThat(client.config().maxProbeEntriesPerLabel()).isEqualTo(15);
                    assertThat(client.config().probeFlushOnError()).isFalse();
                });
    }
}

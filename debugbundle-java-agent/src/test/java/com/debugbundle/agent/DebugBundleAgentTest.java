package com.debugbundle.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.debugbundle.sdk.DebugBundleConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class DebugBundleAgentTest {
    @Test
    void installLoadsConfigFromArgsAndPropertiesFileAndInstallsHooks() throws Exception {
        Path propertiesFile = Files.createTempFile("debugbundle-agent", ".properties");
        Files.writeString(propertiesFile, String.join("\n",
                "debugbundle.environment=production",
                "debugbundle.project-mode=local-only"
        ));

        List<String> installedHooks = new ArrayList<>();
        List<DebugBundleConfig> initializedConfigs = new ArrayList<>();

        DebugBundleAgent.install(
                "config=" + propertiesFile + ",project-token=test-token,service=legacy-orders,capture-jul=false",
                initializedConfigs::add,
                () -> installedHooks.add("uncaught"),
                () -> installedHooks.add("jul"),
                new AtomicBoolean(false)
        );

        assertThat(initializedConfigs).hasSize(1);
        assertThat(initializedConfigs.get(0).projectToken()).isEqualTo("test-token");
        assertThat(initializedConfigs.get(0).service()).isEqualTo("legacy-orders");
        assertThat(initializedConfigs.get(0).environment()).isEqualTo("production");
        assertThat(initializedConfigs.get(0).projectMode()).isEqualTo("local-only");
        assertThat(installedHooks).containsExactly("uncaught");

        Files.deleteIfExists(propertiesFile);
    }

    @Test
    void installRunsOnlyOncePerGuard() {
        List<String> installedHooks = new ArrayList<>();
        List<DebugBundleConfig> initializedConfigs = new ArrayList<>();
        AtomicBoolean installed = new AtomicBoolean(false);

        DebugBundleAgent.install(
                "project-token=first",
                initializedConfigs::add,
                () -> installedHooks.add("uncaught"),
                () -> installedHooks.add("jul"),
                installed
        );
        DebugBundleAgent.install(
                "project-token=second",
                initializedConfigs::add,
                () -> installedHooks.add("uncaught"),
                () -> installedHooks.add("jul"),
                installed
        );

        assertThat(initializedConfigs).hasSize(1);
        assertThat(initializedConfigs.get(0).projectToken()).isEqualTo("first");
        assertThat(installedHooks).containsExactly("uncaught", "jul");
    }

    @Test
    void optionsNormalizeSupportedAliasesAndIgnoreUnsafeUnknowns() {
        DebugBundleAgentOptions options = DebugBundleAgentOptions.parse(
                " ,config-path=/tmp/debugbundle.properties,capture-uncaught=,capture-jul=false,"
                        + "enabled=false,environment=test,endpoint=https://api.test/events,"
                        + "project-mode=local-only,local-events-dir=/tmp/events,sample-rate=0.5,"
                        + "batch-size=10,flush-interval=2s,log-level=error,"
                        + "debugbundle.service=checkout,unknown=ignored,empty= ");

        assertThat(options.configPath()).isEqualTo("/tmp/debugbundle.properties");
        assertThat(options.captureUncaught()).isTrue();
        assertThat(options.captureJul()).isFalse();
        assertThat(options.lookup("debugbundle.enabled")).isEqualTo("false");
        assertThat(options.lookup("debugbundle.environment")).isEqualTo("test");
        assertThat(options.lookup("debugbundle.endpoint")).isEqualTo("https://api.test/events");
        assertThat(options.lookup("debugbundle.project-mode")).isEqualTo("local-only");
        assertThat(options.lookup("debugbundle.local-events-dir")).isEqualTo("/tmp/events");
        assertThat(options.lookup("debugbundle.sample-rate")).isEqualTo("0.5");
        assertThat(options.lookup("debugbundle.batch-size")).isEqualTo("10");
        assertThat(options.lookup("debugbundle.flush-interval")).isEqualTo("2s");
        assertThat(options.lookup("debugbundle.log-level")).isEqualTo("error");
        assertThat(options.lookup("debugbundle.service")).isEqualTo("checkout");
        assertThat(options.lookup("unknown")).isNull();
    }

    @Test
    void optionsAcceptBareConfigPathAndNullArguments() {
        assertThat(DebugBundleAgentOptions.parse("/tmp/debugbundle.properties").configPath())
                .isEqualTo("/tmp/debugbundle.properties");
        assertThat(DebugBundleAgentOptions.parse(null).captureUncaught()).isTrue();
        assertThat(DebugBundleAgentOptions.parse("project-token=test").lookup("debugbundle.project-token"))
                .isEqualTo("test");
    }

    @Test
    void publicAgentEntrypointsDelegateToSafeInstallation() throws Exception {
        Field field = DebugBundleAgent.class.getDeclaredField("INSTALLED");
        field.setAccessible(true);
        AtomicBoolean installed = (AtomicBoolean) field.get(null);

        installed.set(false);
        DebugBundleAgent.premain("enabled=false,capture-uncaught=false,capture-jul=false", null);
        assertThat(installed).isTrue();

        installed.set(false);
        DebugBundleAgent.agentmain("enabled=false,capture-uncaught=false,capture-jul=false", null);
        assertThat(installed).isTrue();
        installed.set(false);
    }
}

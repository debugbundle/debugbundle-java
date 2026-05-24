package com.debugbundle.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.debugbundle.sdk.DebugBundleConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
}
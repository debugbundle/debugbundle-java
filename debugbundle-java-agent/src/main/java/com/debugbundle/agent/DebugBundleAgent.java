package com.debugbundle.agent;

import com.debugbundle.sdk.DebugBundle;
import com.debugbundle.sdk.DebugBundleConfig;
import com.debugbundle.sdk.DebugBundleConfigLoader;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DebugBundleAgent {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private DebugBundleAgent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        install(agentArgs);
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        install(agentArgs);
    }

    static void install(String agentArgs) {
        install(
                agentArgs,
                DebugBundle::init,
                DebugBundle::captureUncaughtExceptions,
                DebugBundle::captureJavaUtilLogging,
                INSTALLED
        );
    }

    static void install(
            String agentArgs,
            ConfigInitializer initializer,
            Runnable uncaughtInstaller,
            Runnable julInstaller,
            AtomicBoolean installed
    ) {
        if (!installed.compareAndSet(false, true)) {
            return;
        }

        DebugBundleAgentOptions options = DebugBundleAgentOptions.parse(agentArgs);
        DebugBundleConfig config = DebugBundleConfigLoader.load(
                options::lookup,
                DebugBundleConfigLoader.propertiesFileLookup(options.configPath()),
                null
        );
        initializer.initialize(config);

        if (options.captureUncaught()) {
            uncaughtInstaller.run();
        }
        if (options.captureJul()) {
            julInstaller.run();
        }
    }

    @FunctionalInterface
    interface ConfigInitializer {
        void initialize(DebugBundleConfig config);
    }
}
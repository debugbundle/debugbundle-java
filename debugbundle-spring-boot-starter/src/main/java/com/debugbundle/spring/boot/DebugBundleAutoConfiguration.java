package com.debugbundle.spring.boot;

import com.debugbundle.sdk.DebugBundle;
import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.DebugBundleConfig;
import com.debugbundle.sdk.LogLevel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@AutoConfiguration
@ConditionalOnClass({DebugBundle.class, OncePerRequestFilter.class})
@EnableConfigurationProperties(DebugBundleProperties.class)
public class DebugBundleAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    DebugBundleClient debugBundleClient(DebugBundleProperties properties) {
        return DebugBundle.init(DebugBundleConfig.builder()
                .projectToken(properties.getProjectToken())
                .environment(properties.getEnvironment())
                .service(properties.getService())
                .enabled(properties.isEnabled())
                .redactFields(properties.getRedactFields())
                .sampleRate(properties.getSampleRate())
                .batchSize(properties.getBatchSize())
                .flushInterval(properties.getFlushInterval())
                .endpoint(properties.getEndpoint())
                .probesPollInterval(properties.getProbesPollInterval())
                .maxProbeLabels(properties.getMaxProbeLabels())
                .maxProbeEntriesPerLabel(properties.getMaxProbeEntriesPerLabel())
                .probeFlushOnError(properties.isProbeFlushOnError())
                .logLevel(LogLevel.fromName(properties.getLogLevel()))
                .requestTimeout(properties.getRequestTimeout())
                .projectMode(properties.getProjectMode())
                .localEventsDir(properties.getLocalEventsDir())
                .build());
    }

    @Bean
    @ConditionalOnMissingBean
    DebugBundleServletFilter debugBundleServletFilter(DebugBundleClient client) {
        return new DebugBundleServletFilter(client);
    }

    @Bean
    @ConditionalOnMissingBean(name = "debugBundleServletFilterRegistration")
    FilterRegistrationBean<DebugBundleServletFilter> debugBundleServletFilterRegistration(
            DebugBundleServletFilter filter
    ) {
        FilterRegistrationBean<DebugBundleServletFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("debugBundleServletFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean(name = "debugBundleExceptionResolver")
    HandlerExceptionResolver debugBundleExceptionResolver(DebugBundleClient client) {
        return new DebugBundleExceptionResolver(client);
    }

    @Bean
    @ConditionalOnMissingBean
    TaskDecorator debugBundleTaskDecorator(DebugBundleClient client) {
        return client::decorate;
    }

    @Bean
    @ConditionalOnProperty(prefix = "debugbundle.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    DebugBundleBrowserRelayHandler debugBundleBrowserRelayHandler(DebugBundleProperties properties) {
        return new DebugBundleBrowserRelayHandler(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "debugbundle.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    DebugBundleRelayController debugBundleRelayController(DebugBundleBrowserRelayHandler relayHandler) {
        return new DebugBundleRelayController(relayHandler);
    }

    @Bean
    @ConditionalOnClass(name = {
            "ch.qos.logback.classic.LoggerContext",
            "org.slf4j.LoggerFactory"
    })
    @ConditionalOnMissingBean
    DebugBundleLogbackAppenderRegistrar debugBundleLogbackAppenderRegistrar(DebugBundleClient client) {
        return new DebugBundleLogbackAppenderRegistrar(client);
    }
}

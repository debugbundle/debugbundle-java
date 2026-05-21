package com.debugbundle.spring.boot;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.debugbundle.sdk.DebugBundleClient;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

final class DebugBundleLogbackAppenderRegistrar implements InitializingBean, DisposableBean {
    private final DebugBundleClient client;

    private Logger rootLogger;
    private DebugBundleLogbackAppender appender;

    DebugBundleLogbackAppenderRegistrar(DebugBundleClient client) {
        this.client = client;
    }

    @Override
    public void afterPropertiesSet() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext loggerContext)) {
            return;
        }

        rootLogger = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (rootLogger.getAppender(DebugBundleLogbackAppender.APPENDER_NAME) != null) {
            return;
        }

        appender = new DebugBundleLogbackAppender(client);
        appender.setContext(loggerContext);
        appender.start();
        rootLogger.addAppender(appender);
    }

    @Override
    public void destroy() {
        if (rootLogger == null || appender == null) {
            return;
        }

        rootLogger.detachAppender(appender);
        appender.stop();
    }
}

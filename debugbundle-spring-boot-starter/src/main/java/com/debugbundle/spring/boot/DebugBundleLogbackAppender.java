package com.debugbundle.spring.boot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import com.debugbundle.sdk.DebugBundleClient;
import com.debugbundle.sdk.LogLevel;
import java.util.LinkedHashMap;
import java.util.Map;

final class DebugBundleLogbackAppender extends AppenderBase<ILoggingEvent> {
    static final String APPENDER_NAME = "DebugBundleLogbackAppender";

    private static final ThreadLocal<Boolean> IN_APPEND = ThreadLocal.withInitial(() -> false);

    private final DebugBundleClient client;

    DebugBundleLogbackAppender(DebugBundleClient client) {
        this.client = client;
        setName(APPENDER_NAME);
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event == null || client == null || Boolean.TRUE.equals(IN_APPEND.get())) {
            return;
        }

        LogLevel level = toLogLevel(event.getLevel());
        if (!shouldCapture(level)) {
            return;
        }

        IN_APPEND.set(true);
        try {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("logger", event.getLoggerName());
            context.put("thread_name", event.getThreadName());
            if (event.getMDCPropertyMap() != null && !event.getMDCPropertyMap().isEmpty()) {
                context.put("mdc", new LinkedHashMap<>(event.getMDCPropertyMap()));
            }

            IThrowableProxy throwableProxy = event.getThrowableProxy();
            if (throwableProxy != null) {
                Map<String, Object> throwable = new LinkedHashMap<>();
                throwable.put("class", throwableProxy.getClassName());
                throwable.put("message", throwableProxy.getMessage());
                throwable.put("stacktrace", ThrowableProxyUtil.asString(throwableProxy));
                context.put("throwable", throwable);
            }

            client.captureLog(event.getFormattedMessage(), level, context);
        } catch (RuntimeException ignored) {
        } finally {
            IN_APPEND.remove();
        }
    }

    private LogLevel toLogLevel(Level level) {
        if (level == null) {
            return LogLevel.INFO;
        }
        if (level.isGreaterOrEqual(Level.ERROR)) {
            return LogLevel.ERROR;
        }
        if (level.isGreaterOrEqual(Level.WARN)) {
            return LogLevel.WARNING;
        }
        if (level.isGreaterOrEqual(Level.INFO)) {
            return LogLevel.INFO;
        }
        return LogLevel.DEBUG;
    }

    private boolean shouldCapture(LogLevel level) {
        LogLevel configured = client.config() == null || client.config().logLevel() == null
                ? LogLevel.WARNING
                : client.config().logLevel();
        return level.ordinal() >= configured.ordinal();
    }
}

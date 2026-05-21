package com.debugbundle.sdk;

import java.util.Locale;
import java.util.logging.Level;

public enum LogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
    CRITICAL;

    public static LogLevel fromJulLevel(Level level) {
        int value = level.intValue();
        if (value >= Level.SEVERE.intValue()) {
            return ERROR;
        }
        if (value >= Level.WARNING.intValue()) {
            return WARNING;
        }
        if (value >= Level.INFO.intValue()) {
            return INFO;
        }
        return DEBUG;
    }

    public static LogLevel fromName(String value) {
        if (value == null || value.isBlank()) {
            return WARNING;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("WARN".equals(normalized)) {
            return WARNING;
        }

        try {
            return LogLevel.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return WARNING;
        }
    }
}

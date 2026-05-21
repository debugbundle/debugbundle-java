package com.debugbundle.sdk;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class DebugBundleConfig {
    private final String projectToken;
    private final String environment;
    private final String service;
    private final boolean enabled;
    private final List<String> redactFields;
    private final double sampleRate;
    private final int batchSize;
    private final Duration flushInterval;
    private final String endpoint;
    private final Duration probesPollInterval;
    private final int maxProbeLabels;
    private final int maxProbeEntriesPerLabel;
    private final boolean probeFlushOnError;
    private final LogLevel logLevel;
    private final Duration requestTimeout;
    private final String projectMode;
    private final String localEventsDir;
    private final RemoteConfigFetcher remoteConfigFetcher;

    private DebugBundleConfig(Builder builder) {
        this.projectToken = builder.projectToken;
        this.environment = builder.environment;
        this.service = builder.service;
        this.enabled = builder.enabled;
        this.redactFields = List.copyOf(builder.redactFields);
        this.sampleRate = builder.sampleRate;
        this.batchSize = builder.batchSize;
        this.flushInterval = builder.flushInterval;
        this.endpoint = builder.endpoint;
        this.probesPollInterval = builder.probesPollInterval;
        this.maxProbeLabels = builder.maxProbeLabels;
        this.maxProbeEntriesPerLabel = builder.maxProbeEntriesPerLabel;
        this.probeFlushOnError = builder.probeFlushOnError;
        this.logLevel = builder.logLevel;
        this.requestTimeout = builder.requestTimeout;
        this.projectMode = builder.projectMode;
        this.localEventsDir = builder.localEventsDir;
        this.remoteConfigFetcher = builder.remoteConfigFetcher;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String projectToken() {
        return projectToken;
    }

    public String environment() {
        return environment;
    }

    public String service() {
        return service;
    }

    public boolean enabled() {
        return enabled;
    }

    public List<String> redactFields() {
        return redactFields;
    }

    public double sampleRate() {
        return sampleRate;
    }

    public int batchSize() {
        return batchSize;
    }

    public Duration flushInterval() {
        return flushInterval;
    }

    public String endpoint() {
        return endpoint;
    }

    public Duration probesPollInterval() {
        return probesPollInterval;
    }

    public int maxProbeLabels() {
        return maxProbeLabels;
    }

    public int maxProbeEntriesPerLabel() {
        return maxProbeEntriesPerLabel;
    }

    public boolean probeFlushOnError() {
        return probeFlushOnError;
    }

    public LogLevel logLevel() {
        return logLevel;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public String projectMode() {
        return projectMode;
    }

    public String localEventsDir() {
        return localEventsDir;
    }

    RemoteConfigFetcher remoteConfigFetcher() {
        return remoteConfigFetcher;
    }

    public static final class Builder {
        private String projectToken;
        private String environment = "development";
        private String service = "unknown-service";
        private boolean enabled = true;
        private List<String> redactFields = new ArrayList<>(List.of(
                "password",
                "secret",
                "token",
                "api_key",
                "apikey",
                "access_token",
                "refresh_token",
                "private_key",
                "passwd",
                "card_number",
                "cvv",
                "cvc",
                "pin",
                "expiry",
                "phone",
                "bearer",
                "session_id",
                "otp",
                "verification_code",
                "authorization",
                "cookie",
                "ssn",
                "credit_card"
        ));
        private double sampleRate = 1.0d;
        private int batchSize = 25;
        private Duration flushInterval = Duration.ofSeconds(5);
        private String endpoint = "https://api.debugbundle.com/v1/events";
        private Duration probesPollInterval = Duration.ofSeconds(60);
        private int maxProbeLabels = 50;
        private int maxProbeEntriesPerLabel = 10;
        private boolean probeFlushOnError = true;
        private LogLevel logLevel = LogLevel.WARNING;
        private Duration requestTimeout = Duration.ofSeconds(5);
        private String projectMode = "connected";
        private String localEventsDir = ".debugbundle/local/events";
        private RemoteConfigFetcher remoteConfigFetcher;

        public Builder projectToken(String projectToken) {
            this.projectToken = projectToken;
            return this;
        }

        public Builder environment(String environment) {
            if (environment != null) {
                this.environment = environment;
            }
            return this;
        }

        public Builder service(String service) {
            if (service != null) {
                this.service = service;
            }
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder redactFields(List<String> redactFields) {
            if (redactFields != null) {
                this.redactFields = new ArrayList<>(redactFields);
            }
            return this;
        }

        public Builder sampleRate(double sampleRate) {
            this.sampleRate = Math.max(0.0d, Math.min(1.0d, sampleRate));
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder flushInterval(Duration flushInterval) {
            if (flushInterval != null) {
                this.flushInterval = flushInterval;
            }
            return this;
        }

        public Builder endpoint(String endpoint) {
            if (endpoint != null) {
                this.endpoint = endpoint;
            }
            return this;
        }

        public Builder probesPollInterval(Duration probesPollInterval) {
            if (probesPollInterval != null) {
                this.probesPollInterval = probesPollInterval;
            }
            return this;
        }

        public Builder maxProbeLabels(int maxProbeLabels) {
            this.maxProbeLabels = maxProbeLabels;
            return this;
        }

        public Builder maxProbeEntriesPerLabel(int maxProbeEntriesPerLabel) {
            this.maxProbeEntriesPerLabel = maxProbeEntriesPerLabel;
            return this;
        }

        public Builder probeFlushOnError(boolean probeFlushOnError) {
            this.probeFlushOnError = probeFlushOnError;
            return this;
        }

        public Builder logLevel(LogLevel logLevel) {
            if (logLevel != null) {
                this.logLevel = logLevel;
            }
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            if (requestTimeout != null) {
                this.requestTimeout = requestTimeout;
            }
            return this;
        }

        public Builder projectMode(String projectMode) {
            if (projectMode != null) {
                this.projectMode = projectMode;
            }
            return this;
        }

        public Builder localEventsDir(String localEventsDir) {
            if (localEventsDir != null) {
                this.localEventsDir = localEventsDir;
            }
            return this;
        }

        Builder remoteConfigFetcher(RemoteConfigFetcher remoteConfigFetcher) {
            this.remoteConfigFetcher = remoteConfigFetcher;
            return this;
        }

        public DebugBundleConfig build() {
            return new DebugBundleConfig(this);
        }
    }
}

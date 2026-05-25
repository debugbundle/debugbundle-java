package com.debugbundle.spring.boot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(prefix = "debugbundle")
public class DebugBundleProperties {
    private String projectToken;
    private String environment = "development";
    private String service = "unknown-service";
    private boolean enabled = true;
    private List<String> redactFields;
    private double sampleRate = 1.0d;
    private int batchSize = 25;
    private Duration flushInterval = Duration.ofSeconds(5);
    private String endpoint = "https://api.debugbundle.com/v1/events";
    private Duration probesPollInterval = Duration.ofSeconds(60);
    private int maxProbeLabels = 50;
    private int maxProbeEntriesPerLabel = 10;
    private boolean probeFlushOnError = true;
    private String logLevel = "warning";
    private Duration requestTimeout = Duration.ofSeconds(5);
    private String projectMode = "connected";
    private String localEventsDir = ".debugbundle/local/events";
    @NestedConfigurationProperty
    private Relay relay = new Relay();

    public String getProjectToken() {
        return projectToken;
    }

    public void setProjectToken(String projectToken) {
        this.projectToken = projectToken;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getRedactFields() {
        return redactFields;
    }

    public void setRedactFields(List<String> redactFields) {
        this.redactFields = redactFields == null ? null : new ArrayList<>(redactFields);
    }

    public double getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(double sampleRate) {
        this.sampleRate = sampleRate;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getFlushInterval() {
        return flushInterval;
    }

    public void setFlushInterval(Duration flushInterval) {
        this.flushInterval = flushInterval;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public Duration getProbesPollInterval() {
        return probesPollInterval;
    }

    public void setProbesPollInterval(Duration probesPollInterval) {
        this.probesPollInterval = probesPollInterval;
    }

    public int getMaxProbeLabels() {
        return maxProbeLabels;
    }

    public void setMaxProbeLabels(int maxProbeLabels) {
        this.maxProbeLabels = maxProbeLabels;
    }

    public int getMaxProbeEntriesPerLabel() {
        return maxProbeEntriesPerLabel;
    }

    public void setMaxProbeEntriesPerLabel(int maxProbeEntriesPerLabel) {
        this.maxProbeEntriesPerLabel = maxProbeEntriesPerLabel;
    }

    public boolean isProbeFlushOnError() {
        return probeFlushOnError;
    }

    public void setProbeFlushOnError(boolean probeFlushOnError) {
        this.probeFlushOnError = probeFlushOnError;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(String logLevel) {
        this.logLevel = logLevel;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public String getProjectMode() {
        return projectMode;
    }

    public void setProjectMode(String projectMode) {
        this.projectMode = projectMode;
    }

    public String getLocalEventsDir() {
        return localEventsDir;
    }

    public void setLocalEventsDir(String localEventsDir) {
        this.localEventsDir = localEventsDir;
    }

    public Relay getRelay() {
        return relay;
    }

    public void setRelay(Relay relay) {
        if (relay != null) {
            this.relay = relay;
        }
    }

    public static class Relay {
        private boolean enabled = true;
        private int rateLimitPerMinute = 60;
        private boolean durableWrite = true;
        private String spoolDir = ".debugbundle/local/browser-relay-spool";
        private List<String> allowedOrigins = new ArrayList<>();
        private String service;
        private String environment;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getRateLimitPerMinute() {
            return rateLimitPerMinute;
        }

        public void setRateLimitPerMinute(int rateLimitPerMinute) {
            this.rateLimitPerMinute = rateLimitPerMinute;
        }

        public boolean isDurableWrite() {
            return durableWrite;
        }

        public void setDurableWrite(boolean durableWrite) {
            this.durableWrite = durableWrite;
        }

        public String getSpoolDir() {
            return spoolDir;
        }

        public void setSpoolDir(String spoolDir) {
            this.spoolDir = spoolDir;
        }

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : new ArrayList<>(allowedOrigins);
        }

        public String getService() {
            return service;
        }

        public void setService(String service) {
            this.service = service;
        }

        public String getEnvironment() {
            return environment;
        }

        public void setEnvironment(String environment) {
            this.environment = environment;
        }
    }
}

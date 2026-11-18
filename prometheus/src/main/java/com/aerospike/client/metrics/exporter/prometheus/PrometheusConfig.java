/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import java.util.function.Predicate;

import io.prometheus.metrics.model.registry.PrometheusRegistry;

/**
 * Immutable configuration for Prometheus collection and the optional
 * exporter-managed HTTP server.
 */
public final class PrometheusConfig {
    public static final String DEFAULT_BIND_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 9464;

    private final String bindHost;
    private final int port;
    private final String metricPrefix;
    private final boolean targetInfoEnabled;
    private final boolean scopeInformationEnabled;
    private final Predicate<String> resourceAttributeFilter;
    private final PrometheusRegistry registry;
    private final TlsConfig tlsConfig;

    private PrometheusConfig(Builder builder) {
        bindHost = builder.bindHost;
        port = builder.port;
        metricPrefix = builder.metricPrefix;
        targetInfoEnabled = builder.targetInfoEnabled;
        scopeInformationEnabled = builder.scopeInformationEnabled;
        resourceAttributeFilter = builder.resourceAttributeFilter;
        registry = builder.registry;
        tlsConfig = builder.tlsConfig;
        validate();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static PrometheusConfig defaults() {
        return builder().build();
    }

    public String getBindHost() {
        return bindHost;
    }

    public int getPort() {
        return port;
    }

    public String getMetricPrefix() {
        return metricPrefix;
    }

    public boolean isTargetInfoEnabled() {
        return targetInfoEnabled;
    }

    public boolean isScopeInformationEnabled() {
        return scopeInformationEnabled;
    }

    public Predicate<String> getResourceAttributeFilter() {
        return resourceAttributeFilter;
    }

    /**
     * Return the caller-supplied registry, or {@code null} when the reader
     * should create a private registry.
     */
    public PrometheusRegistry getRegistry() {
        return registry;
    }

    public TlsConfig getTlsConfig() {
        return tlsConfig;
    }

    private void validate() {
        if (bindHost == null || bindHost.trim().isEmpty()) {
            throw new IllegalArgumentException("bindHost must not be blank");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException(
                "port must be between 0 and 65535"
            );
        }
        if (metricPrefix == null) {
            throw new IllegalArgumentException("metricPrefix must not be null");
        }
    }

    public static final class Builder {
        private String bindHost = DEFAULT_BIND_HOST;
        private int port = DEFAULT_PORT;
        private String metricPrefix = "";
        private boolean targetInfoEnabled = true;
        private boolean scopeInformationEnabled = true;
        private Predicate<String> resourceAttributeFilter;
        private PrometheusRegistry registry;
        private TlsConfig tlsConfig;

        private Builder() {
        }

        public Builder setBindHost(String bindHost) {
            this.bindHost = bindHost;
            return this;
        }

        public Builder setPort(int port) {
            this.port = port;
            return this;
        }

        public Builder setMetricPrefix(String metricPrefix) {
            this.metricPrefix = metricPrefix;
            return this;
        }

        public Builder setTargetInfoEnabled(boolean targetInfoEnabled) {
            this.targetInfoEnabled = targetInfoEnabled;
            return this;
        }

        public Builder setScopeInformationEnabled(
                boolean scopeInformationEnabled
        ) {
            this.scopeInformationEnabled = scopeInformationEnabled;
            return this;
        }

        public Builder setResourceAttributeFilter(
                Predicate<String> resourceAttributeFilter
        ) {
            this.resourceAttributeFilter = resourceAttributeFilter;
            return this;
        }

        public Builder setRegistry(PrometheusRegistry registry) {
            this.registry = registry;
            return this;
        }

        public Builder setTlsConfig(TlsConfig tlsConfig) {
            this.tlsConfig = tlsConfig;
            return this;
        }

        public PrometheusConfig build() {
            return new PrometheusConfig(this);
        }
    }
}

/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import java.time.Duration;

import com.aerospike.client.metrics.exporter.common.config.FreshnessConfig;
import com.aerospike.client.metrics.exporter.common.config.TlsConfig;

/**
 * Immutable, fully resolved configuration for the embedded scrape endpoint.
 *
 * <p>Instances are produced by {@link PrometheusMetricsExporterBuilder};
 * callers can inspect them without opening a network socket.</p>
 */
public final class PrometheusExporterConfig {
    private final String bindAddress;
    private final int port;
    private final String path;
    private final Duration httpTimeout;
    private final String username;
    private final String password;
    private final boolean tlsEnabled;
    private final TlsConfig tls;
    private final FreshnessConfig freshness;

    PrometheusExporterConfig(
            String bindAddress,
            int port,
            String path,
            Duration httpTimeout,
            String username,
            String password,
            boolean tlsEnabled,
            TlsConfig tls,
            FreshnessConfig freshness
    ) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.path = path;
        this.httpTimeout = httpTimeout;
        this.username = username;
        this.password = password;
        this.tlsEnabled = tlsEnabled;
        this.tls = tls;
        this.freshness = freshness;
    }

    public String bindAddress() {
        return bindAddress;
    }

    public int port() {
        return port;
    }

    public String path() {
        return path;
    }

    public Duration httpTimeout() {
        return httpTimeout;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    /**
     * Return whether both Basic authentication credentials were configured.
     */
    public boolean hasBasicAuth() {
        return username != null;
    }

    public boolean tlsEnabled() {
        return tlsEnabled;
    }

    public TlsConfig tls() {
        return tls;
    }

    public FreshnessConfig freshness() {
        return freshness;
    }
}

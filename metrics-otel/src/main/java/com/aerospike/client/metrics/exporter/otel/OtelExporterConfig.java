/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.aerospike.client.metrics.exporter.common.config.FreshnessConfig;
import com.aerospike.client.metrics.exporter.common.config.TlsConfig;

public final class OtelExporterConfig {
    public enum Protocol {
        GRPC,
        HTTP_PROTOBUF
    }

    public enum Compression {
        NONE,
        GZIP
    }

    public enum Temporality {
        CUMULATIVE,
        DELTA,
        LOWMEMORY
    }

    private final String serviceName;
    private final URI endpoint;
    private final Protocol protocol;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final Duration exportInterval;
    private final Compression compression;
    private final Temporality temporality;
    private final URI proxyUrl;
    private final TlsConfig tls;
    private final FreshnessConfig freshness;

    OtelExporterConfig(
            String serviceName,
            URI endpoint,
            Protocol protocol,
            Map<String, String> headers,
            Duration timeout,
            Duration exportInterval,
            Compression compression,
            Temporality temporality,
            URI proxyUrl,
            TlsConfig tls,
            FreshnessConfig freshness
    ) {
        this.serviceName = serviceName;
        this.endpoint = endpoint;
        this.protocol = protocol;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<String, String>(headers));
        this.timeout = timeout;
        this.exportInterval = exportInterval;
        this.compression = compression;
        this.temporality = temporality;
        this.proxyUrl = proxyUrl;
        this.tls = tls;
        this.freshness = freshness;
    }

    public String serviceName() {
        return serviceName;
    }

    public URI endpoint() {
        return endpoint;
    }

    public Protocol protocol() {
        return protocol;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public Duration timeout() {
        return timeout;
    }

    public Duration exportInterval() {
        return exportInterval;
    }

    public Compression compression() {
        return compression;
    }

    public Temporality temporality() {
        return temporality;
    }

    public URI proxyUrl() {
        return proxyUrl;
    }

    public TlsConfig tls() {
        return tls;
    }

    public FreshnessConfig freshness() {
        return freshness;
    }
}

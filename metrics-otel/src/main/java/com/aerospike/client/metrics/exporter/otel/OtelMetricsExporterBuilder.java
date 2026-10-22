/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import com.aerospike.client.metrics.exporter.common.ClientSnapshotRegistry;
import com.aerospike.client.metrics.exporter.common.config.ConfigMerger;
import com.aerospike.client.metrics.exporter.common.config.ConfigValues;
import com.aerospike.client.metrics.exporter.common.config.FreshnessConfig;
import com.aerospike.client.metrics.exporter.common.config.PemSupport;
import com.aerospike.client.metrics.exporter.common.config.SecretResolver;
import com.aerospike.client.metrics.exporter.common.config.TlsConfig;
import com.aerospike.client.metrics.exporter.common.config.YamlConfigSource;
import com.aerospike.client.metrics.exporter.common.config.reload.FileWatchConfiguration;
import com.aerospike.client.metrics.exporter.common.config.reload.ReloadableFileReference.Backend;
import com.aerospike.client.metrics.exporter.common.config.reload.ReloadSettings;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporterBuilder;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporterBuilder;
import io.opentelemetry.sdk.common.export.ProxyOptions;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;

/**
 * Builds an Aerospike snapshot exporter backed by OpenTelemetry metrics.
 *
 * <p>Explicit builder values have highest precedence, followed by JVM system
 * properties, environment variables, YAML, and built-in defaults. If an
 * {@link OpenTelemetry} instance is supplied, the application retains
 * ownership of its provider. Otherwise this builder creates a provider and the
 * resulting exporter closes it.</p>
 */
public final class OtelMetricsExporterBuilder {
    static final String PREFIX = "aerospike_metrics_exporter.";
    static final String OTEL = PREFIX + "otel.";
    static final String FRESHNESS = PREFIX + "snapshot_stale_timeout";
    static final String RELOAD = PREFIX + "reload.";
    private static final String OTEL_ENDPOINT = OTEL + "endpoint";
    private static final String ENDPOINT_ORIGIN = OTEL + "endpoint_origin";
    private static final String GENERIC_OTLP_ENDPOINT = "generic";
    private static final String METRICS_SPECIFIC_ENDPOINT = "metrics_specific";
    private static final String METER_NAME = "com.aerospike.client.metrics";

    private final Map<String, String> explicit = new LinkedHashMap<String, String>();
    private final Map<String, String> resourceAttributes = new LinkedHashMap<String, String>();

    private Path yamlPath;
    private OpenTelemetry openTelemetry; //do we need this, not common

    /**
     * Load exporter settings from a YAML file.
     */
    public OtelMetricsExporterBuilder fromYaml(Path path) {
        this.yamlPath = path;
        return this;
    }

    /**
     * Register instruments with an application-owned OpenTelemetry instance.
     * OTLP transport settings are ignored in this mode because readers and
     * exporters belong to the supplied provider.
     */
    public OtelMetricsExporterBuilder openTelemetry(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
        return this;
    }

    /**
     * Set the {@code service.name} resource attribute for an internally owned
     * provider.
     */
    public OtelMetricsExporterBuilder serviceName(String value) {
        return put("service_name", value);
    }

    /**
     * Set the absolute HTTP(S) OTLP metrics endpoint.
     */
    public OtelMetricsExporterBuilder endpoint(String value) {
        put("endpoint", value);
        explicit.put(ENDPOINT_ORIGIN, METRICS_SPECIFIC_ENDPOINT);
        return this;
    }

    /**
     * Select OTLP gRPC or HTTP/protobuf transport.
     */
    public OtelMetricsExporterBuilder protocol(OtelExporterConfig.Protocol value) {
        return put("protocol", value == null ? null : externalName(value));
    }

    /**
     * Add an OTLP request header. Later values replace the same header name.
     */
    public OtelMetricsExporterBuilder header(String name, String value) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("header name must not be empty");
        }
        explicit.put(OTEL + "headers." + name.trim(), value);
        return this;
    }

    /**
     * Set the timeout for one OTLP export attempt.
     */
    public OtelMetricsExporterBuilder timeout(Duration value) {
        return put("timeout", duration(value, "timeout"));
    }

    /**
     * Set how often the internally owned periodic reader collects metrics.
     */
    public OtelMetricsExporterBuilder exportInterval(Duration value) {
        return put("export_interval", duration(value, "exportInterval"));
    }

    /**
     * Select OTLP payload compression.
     */
    public OtelMetricsExporterBuilder compression(OtelExporterConfig.Compression value) {
        return put("compression", value == null ? null : value.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Select the preferred aggregation temporality.
     */
    public OtelMetricsExporterBuilder temporality(OtelExporterConfig.Temporality value) {
        return put("temporality", value == null ? null : value.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Set an HTTP proxy. Proxies are supported only by HTTP/protobuf transport.
     */
    public OtelMetricsExporterBuilder proxy(String value) {
        return put("proxy.url", value);
    }

    /**
     * Trust the PEM certificates in this file for the OTLP server.
     */
    public OtelMetricsExporterBuilder trustedCa(Path value) {
        return put("tls.ca_file", path(value));
    }

    /**
     * Set the PEM client certificate used for mutual TLS.
     */
    public OtelMetricsExporterBuilder clientCertificate(Path value) {
        return put("tls.client_cert_file", path(value));
    }

    /**
     * Set the PKCS#8 PEM private key paired with the client certificate.
     */
    public OtelMetricsExporterBuilder clientKey(Path value) {
        return put("tls.client_key_file", path(value));
    }

    /**
     * Set the optional password for an encrypted PKCS#8 private key.
     */
    public OtelMetricsExporterBuilder clientKeyPassword(String value) {
        return put("tls.client_key_password", value);
    }

    /**
     * Set {@code auto} or a positive duration after which an inactive client's
     * snapshots are omitted.
     */
    public OtelMetricsExporterBuilder snapshotStaleTimeout(String value) {
        explicit.put(FRESHNESS, value);
        return this;
    }

    /**
     * Enable or disable credential-file change detection.
     *
     * <p>Detection does not apply changes to a running OTLP transport.</p>
     */
    public OtelMetricsExporterBuilder reloadEnabled(boolean enabled) {
        explicit.put(RELOAD + "enabled", Boolean.toString(enabled));
        return this;
    }

    /**
     * Set how often credential files are checked for stable content changes.
     */
    public OtelMetricsExporterBuilder reloadInterval(Duration value) {
        if (value == null || value.compareTo(ReloadSettings.MINIMUM_INTERVAL) < 0) {
            throw new IllegalArgumentException(
                "reloadInterval must be at least " + ReloadSettings.MINIMUM_INTERVAL
            );
        }
        explicit.put(RELOAD + "interval", value.toString());
        return this;
    }

    /**
     * Add an OTel resource attribute when this builder owns the provider.
     */
    public OtelMetricsExporterBuilder resourceAttribute(String name, String value) {
        if (name == null || name.trim().isEmpty() || value == null) {
            throw new IllegalArgumentException("resource attribute name and value are required");
        }
        resourceAttributes.put(name, value);
        return this;
    }

    /**
     * Resolve configuration, register instruments, and start an internally
     * owned periodic reader when no OpenTelemetry instance was supplied.
     */
    public OtelMetricsExporter build() {
        if (openTelemetry != null) {
            FreshnessConfig freshness = resolveFreshnessConfig();
            ClientSnapshotRegistry snapshots = new ClientSnapshotRegistry(
                freshness::createPolicy
            );
            Meter meter = openTelemetry.getMeter(METER_NAME);
            return new OtelMetricsExporter(meter, snapshots, null);
        }

        OtelExporterConfig config = resolveConfig();
        ClientSnapshotRegistry snapshots = new ClientSnapshotRegistry(
            config.freshness()::createPolicy
        );
        MetricExporter metricExporter = buildMetricExporter(config);
        PeriodicMetricReader reader = PeriodicMetricReader.builder(metricExporter)
            .setInterval(config.exportInterval())
            .build();

        AttributesBuilder resourceBuilder = io.opentelemetry.api.common.Attributes.builder()
            .put(stringKey("service.name"), config.serviceName());
        for (Map.Entry<String, String> entry : resourceAttributes.entrySet()) {
            resourceBuilder.put(stringKey(entry.getKey()), entry.getValue());
        }
        Resource resource = Resource.getDefault().merge(Resource.create(resourceBuilder.build()));
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(reader)
            .build();

        return new OtelMetricsExporter(
            meterProvider.get(METER_NAME),
            snapshots,
            meterProvider
        );
    }

    /**
     * Resolve and validate configuration without creating telemetry objects.
     */
    public OtelExporterConfig resolveConfig() {
        return createConfig(resolveValues());
    }

    /**
     * Resolve the fixed credential-file paths that can be watched for changes.
     *
     * <p>This does not start a watcher or replace a running OTLP transport.</p>
     */
    public FileWatchConfiguration resolveFileWatchConfiguration() {
        ConfigValues values = resolveValues();
        createConfig(values);
        return FileWatchConfiguration.from(values, Backend.OTEL);
    }

    private ConfigValues resolveValues() {
        Map<String, String> yaml = yamlPath == null
            ? new LinkedHashMap<String, String>()
            : new LinkedHashMap<String, String>(YamlConfigSource.load(yamlPath));
        // Exporter YAML and the Java builder define a complete metrics endpoint.
        yaml.remove(ENDPOINT_ORIGIN);
        if (yaml.containsKey(OTEL_ENDPOINT)) {
            yaml.put(ENDPOINT_ORIGIN, METRICS_SPECIFIC_ENDPOINT);
        }
        ConfigValues values = ConfigMerger.merge(
            defaults(),
            yaml,
            environment(System.getenv()),
            systemProperties(System.getProperties()),
            explicit
        );
        ReloadSettings.from(values);
        return values;
    }

    private OtelExporterConfig createConfig(ConfigValues values) {
        OtelExporterConfig.Protocol protocol = parseProtocol(values.getOrDefault(OTEL + "protocol", "grpc"));
        URI endpoint = resolveEndpoint(values, protocol);
        String proxyValue = values.get(OTEL + "proxy.url");
        URI proxy = proxyValue == null ? null : uri(proxyValue, "otel.proxy.url");
        if (proxy != null && protocol != OtelExporterConfig.Protocol.HTTP_PROTOBUF) {
            throw new IllegalArgumentException("OTLP proxy is supported only with http/protobuf");
        }

        String certPassword = SecretResolver.resolve(values.get(OTEL + "tls.client_key_password"));
        TlsConfig tls = new TlsConfig(
            nullablePath(values.get(OTEL + "tls.ca_file")),
            nullablePath(values.get(OTEL + "tls.client_cert_file")),
            nullablePath(values.get(OTEL + "tls.client_key_file")),
            certPassword,
            TlsConfig.ClientAuth.NONE
        );

        Map<String, String> headers = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> header : values.withPrefix(OTEL + "headers.").entrySet()) {
            headers.put(header.getKey(), SecretResolver.resolve(header.getValue()));
        }

        return new OtelExporterConfig(
            values.getOrDefault(OTEL + "service_name", "aerospike-client"),
            endpoint,
            protocol,
            headers,
            values.getDuration(OTEL + "timeout", Duration.ofSeconds(10)),
            values.getDuration(OTEL + "export_interval", Duration.ofSeconds(60)),
            parseCompression(values.getOrDefault(OTEL + "compression", "none")),
            parseTemporality(values.getOrDefault(OTEL + "temporality", "cumulative")),
            proxy,
            tls,
            new FreshnessConfig(values.getOrDefault(FRESHNESS, FreshnessConfig.AUTO))
        );
    }

    private MetricExporter buildMetricExporter(OtelExporterConfig config) {
        AggregationTemporalitySelector selector = temporalitySelector(config.temporality());
        TlsConfig tls = config.tls();
        String compression = config.compression() == OtelExporterConfig.Compression.GZIP
            ? "gzip"
            : "none";

        if (config.protocol() == OtelExporterConfig.Protocol.GRPC) {
            OtlpGrpcMetricExporterBuilder builder = OtlpGrpcMetricExporter.builder()
                .setEndpoint(config.endpoint().toString())
                .setTimeout(config.timeout())
                .setCompression(compression)
                .setAggregationTemporalitySelector(selector);
            for (Map.Entry<String, String> header : config.headers().entrySet()) {
                builder.addHeader(header.getKey(), header.getValue());
            }
            applyTls(builder, tls);
            return builder.build();
        }

        OtlpHttpMetricExporterBuilder builder = OtlpHttpMetricExporter.builder()
            .setEndpoint(config.endpoint().toString())
            .setTimeout(config.timeout())
            .setCompression(compression)
            .setAggregationTemporalitySelector(selector);
        for (Map.Entry<String, String> header : config.headers().entrySet()) {
            builder.addHeader(header.getKey(), header.getValue());
        }
        if (config.proxyUrl() != null) {
            builder.setProxyOptions(ProxyOptions.create(proxyAddress(config.proxyUrl())));
        }
        applyTls(builder, tls);
        return builder.build();
    }

    static InetSocketAddress proxyAddress(URI proxyUrl) {
        int port = proxyUrl.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(proxyUrl.getScheme()) ? 443 : 80;
        }
        return new InetSocketAddress(proxyUrl.getHost(), port);
    }

    static URI resolveEndpoint(
            ConfigValues values,
            OtelExporterConfig.Protocol protocol
    ) {
        String defaultEndpoint = protocol == OtelExporterConfig.Protocol.GRPC
            ? "http://localhost:4317"
            : "http://localhost:4318/v1/metrics";
        URI endpoint = uri(
            values.getOrDefault(OTEL_ENDPOINT, defaultEndpoint),
            "otel.endpoint"
        );
        if (protocol == OtelExporterConfig.Protocol.HTTP_PROTOBUF
                && GENERIC_OTLP_ENDPOINT.equals(values.get(ENDPOINT_ORIGIN))) {
            return appendSignalPath(endpoint, "v1/metrics");
        }
        return endpoint;
    }

    private static URI appendSignalPath(URI baseEndpoint, String signalPath) {
        String basePath = baseEndpoint.getPath();
        if (basePath == null) {
            basePath = "";
        }
        if (!basePath.endsWith("/")) {
            basePath += "/";
        }
        try {
            return new URI(
                baseEndpoint.getScheme(),
                baseEndpoint.getUserInfo(),
                baseEndpoint.getHost(),
                baseEndpoint.getPort(),
                basePath + signalPath,
                baseEndpoint.getQuery(),
                baseEndpoint.getFragment()
            );
        }
        catch (URISyntaxException e) {
            throw new IllegalArgumentException("Unable to construct OTLP signal endpoint", e);
        }
    }

    private FreshnessConfig resolveFreshnessConfig() {
        Map<String, String> yaml = yamlPath == null
            ? Collections.emptyMap()
            : YamlConfigSource.load(yamlPath);

        Map<String, String> environment = new LinkedHashMap<String, String>();
        map(
            environment,
            FRESHNESS,
            System.getenv("AEROSPIKE_METRICS_EXPORTER_SNAPSHOT_STALE_TIMEOUT")
        );

        Map<String, String> system = new LinkedHashMap<String, String>();
        map(
            system,
            FRESHNESS,
            System.getProperty("aerospike.metrics.exporter.snapshot.stale.timeout")
        );

        Map<String, String> builder = new LinkedHashMap<String, String>();
        map(builder, FRESHNESS, explicit.get(FRESHNESS));

        ConfigValues values = ConfigMerger.merge(
            Collections.singletonMap(FRESHNESS, FreshnessConfig.AUTO),
            yaml,
            environment,
            system,
            builder
        );
        return new FreshnessConfig(values.getOrDefault(FRESHNESS, FreshnessConfig.AUTO));
    }

    private static void applyTls(OtlpGrpcMetricExporterBuilder builder, TlsConfig tls) {
        if (tls.hasCustomTrust()) {
            builder.setTrustedCertificates(PemSupport.read(tls.caFile()));
        }
        if (tls.hasIdentity()) {
            builder.setClientTls(
                PemSupport.unencryptedPrivateKeyPem(tls.privateKeyFile(), tls.privateKeyPassword()),
                PemSupport.read(tls.certificateFile())
            );
        }
    }

    private static void applyTls(OtlpHttpMetricExporterBuilder builder, TlsConfig tls) {
        if (tls.hasCustomTrust()) {
            builder.setTrustedCertificates(PemSupport.read(tls.caFile()));
        }
        if (tls.hasIdentity()) {
            builder.setClientTls(
                PemSupport.unencryptedPrivateKeyPem(tls.privateKeyFile(), tls.privateKeyPassword()),
                PemSupport.read(tls.certificateFile())
            );
        }
    }

    private OtelMetricsExporterBuilder put(String suffix, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(suffix + " must not be empty");
        }
        explicit.put(OTEL + suffix, value);
        return this;
    }

    private static Map<String, String> defaults() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put(OTEL + "service_name", "aerospike-client");
        values.put(OTEL + "protocol", "grpc");
        values.put(OTEL + "timeout", "10s");
        values.put(OTEL + "export_interval", "60s");
        values.put(OTEL + "compression", "none");
        values.put(OTEL + "temporality", "cumulative");
        values.put(FRESHNESS, FreshnessConfig.AUTO);
        return values;
    }

    static Map<String, String> environment(Map<String, String> environment) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        map(values, OTEL + "service_name", environment.get("OTEL_SERVICE_NAME"));
        mapEndpoint(
            values,
            environment.get("OTEL_EXPORTER_OTLP_ENDPOINT"),
            GENERIC_OTLP_ENDPOINT
        );
        mapEndpoint(
            values,
            environment.get("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT"),
            METRICS_SPECIFIC_ENDPOINT
        );
        map(values, OTEL + "protocol", environment.get("OTEL_EXPORTER_OTLP_PROTOCOL"));
        map(values, OTEL + "protocol", environment.get("OTEL_EXPORTER_OTLP_METRICS_PROTOCOL"));
        map(values, OTEL + "timeout", environment.get("OTEL_EXPORTER_OTLP_TIMEOUT"));
        map(values, OTEL + "timeout", environment.get("OTEL_EXPORTER_OTLP_METRICS_TIMEOUT"));
        map(values, OTEL + "export_interval", environment.get("OTEL_METRIC_EXPORT_INTERVAL"));
        map(values, OTEL + "compression", environment.get("OTEL_EXPORTER_OTLP_COMPRESSION"));
        map(values, OTEL + "compression", environment.get("OTEL_EXPORTER_OTLP_METRICS_COMPRESSION"));
        map(values, OTEL + "temporality", environment.get("OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE"));
        map(values, OTEL + "tls.ca_file", environment.get("OTEL_EXPORTER_OTLP_CERTIFICATE"));
        map(values, OTEL + "tls.ca_file", environment.get("OTEL_EXPORTER_OTLP_METRICS_CERTIFICATE"));
        map(values, OTEL + "tls.client_cert_file", environment.get("OTEL_EXPORTER_OTLP_CLIENT_CERTIFICATE"));
        map(values, OTEL + "tls.client_cert_file", environment.get(
            "OTEL_EXPORTER_OTLP_METRICS_CLIENT_CERTIFICATE"
        ));
        map(values, OTEL + "tls.client_key_file", environment.get("OTEL_EXPORTER_OTLP_CLIENT_KEY"));
        map(values, OTEL + "tls.client_key_file", environment.get(
            "OTEL_EXPORTER_OTLP_METRICS_CLIENT_KEY"
        ));
        map(values, OTEL + "tls.client_key_password", environment.get(
            "AEROSPIKE_METRICS_EXPORTER_OTEL_CLIENT_KEY_PASSWORD"
        ));
        map(values, OTEL + "proxy.url", environment.get("AEROSPIKE_METRICS_EXPORTER_OTEL_PROXY_URL"));
        map(values, FRESHNESS, environment.get("AEROSPIKE_METRICS_EXPORTER_SNAPSHOT_STALE_TIMEOUT"));
        map(
            values,
            RELOAD + "enabled",
            environment.get("AEROSPIKE_METRICS_EXPORTER_RELOAD_ENABLED")
        );
        map(
            values,
            RELOAD + "interval",
            environment.get("AEROSPIKE_METRICS_EXPORTER_RELOAD_INTERVAL")
        );
        parseHeaders(values, environment.get("OTEL_EXPORTER_OTLP_HEADERS"));
        parseHeaders(values, environment.get("OTEL_EXPORTER_OTLP_METRICS_HEADERS"));
        return values;
    }

    static Map<String, String> systemProperties(Properties properties) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        map(values, OTEL + "service_name", properties.getProperty("otel.service.name"));
        mapEndpoint(
            values,
            properties.getProperty("otel.exporter.otlp.endpoint"),
            GENERIC_OTLP_ENDPOINT
        );
        mapEndpoint(
            values,
            properties.getProperty("otel.exporter.otlp.metrics.endpoint"),
            METRICS_SPECIFIC_ENDPOINT
        );
        map(values, OTEL + "protocol", properties.getProperty("otel.exporter.otlp.protocol"));
        map(values, OTEL + "protocol", properties.getProperty("otel.exporter.otlp.metrics.protocol"));
        map(values, OTEL + "timeout", properties.getProperty("otel.exporter.otlp.timeout"));
        map(values, OTEL + "timeout", properties.getProperty("otel.exporter.otlp.metrics.timeout"));
        map(values, OTEL + "export_interval", properties.getProperty("otel.metric.export.interval"));
        map(values, OTEL + "compression", properties.getProperty("otel.exporter.otlp.compression"));
        map(values, OTEL + "compression", properties.getProperty("otel.exporter.otlp.metrics.compression"));
        map(values, OTEL + "temporality", properties.getProperty("otel.exporter.otlp.metrics.temporality.preference"));
        map(values, OTEL + "tls.ca_file", properties.getProperty("otel.exporter.otlp.certificate"));
        map(values, OTEL + "tls.ca_file", properties.getProperty("otel.exporter.otlp.metrics.certificate"));
        map(values, OTEL + "tls.client_cert_file", properties.getProperty("otel.exporter.otlp.client.certificate"));
        map(values, OTEL + "tls.client_cert_file", properties.getProperty(
            "otel.exporter.otlp.metrics.client.certificate"
        ));
        map(values, OTEL + "tls.client_key_file", properties.getProperty("otel.exporter.otlp.client.key"));
        map(values, OTEL + "tls.client_key_file", properties.getProperty(
            "otel.exporter.otlp.metrics.client.key"
        ));
        map(values, OTEL + "tls.client_key_password", properties.getProperty(
            "aerospike.metrics.exporter.otel.tls.client_key_password"
        ));
        map(values, OTEL + "proxy.url", properties.getProperty("aerospike.metrics.exporter.otel.proxy.url"));
        map(values, FRESHNESS, properties.getProperty("aerospike.metrics.exporter.snapshot.stale.timeout"));
        map(
            values,
            RELOAD + "enabled",
            properties.getProperty("aerospike.metrics.exporter.reload.enabled")
        );
        map(
            values,
            RELOAD + "interval",
            properties.getProperty("aerospike.metrics.exporter.reload.interval")
        );
        parseHeaders(values, properties.getProperty("otel.exporter.otlp.headers"));
        parseHeaders(values, properties.getProperty("otel.exporter.otlp.metrics.headers"));
        return values;
    }

    private static void parseHeaders(Map<String, String> target, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        for (String pair : raw.split(",")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("OTLP headers must use name=value pairs");
            }
            target.put(
                OTEL + "headers." + pair.substring(0, separator).trim(),
                pair.substring(separator + 1).trim()
            );
        }
    }

    private static void mapEndpoint(
            Map<String, String> target,
            String endpoint,
            String endpointOrigin
    ) {
        if (endpoint != null) {
            target.put(OTEL_ENDPOINT, endpoint);
            target.put(ENDPOINT_ORIGIN, endpointOrigin);
        }
    }

    private static AggregationTemporalitySelector temporalitySelector(
            OtelExporterConfig.Temporality temporality
    ) {
        switch (temporality) {
        case DELTA:
            return AggregationTemporalitySelector.deltaPreferred();
        case LOWMEMORY:
            return AggregationTemporalitySelector.lowMemory();
        case CUMULATIVE:
        default:
            return AggregationTemporalitySelector.alwaysCumulative();
        }
    }

    private static OtelExporterConfig.Protocol parseProtocol(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("grpc".equals(normalized)) {
            return OtelExporterConfig.Protocol.GRPC;
        }
        if ("http/protobuf".equals(normalized) || "http_protobuf".equals(normalized)) {
            return OtelExporterConfig.Protocol.HTTP_PROTOBUF;
        }
        throw new IllegalArgumentException("otel.protocol must be grpc or http/protobuf");
    }

    private static OtelExporterConfig.Compression parseCompression(String value) {
        try {
            return OtelExporterConfig.Compression.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("otel.compression must be none or gzip", e);
        }
    }

    private static OtelExporterConfig.Temporality parseTemporality(String value) {
        try {
            return OtelExporterConfig.Temporality.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "otel.temporality must be cumulative, delta or lowmemory",
                e
            );
        }
    }

    private static String externalName(OtelExporterConfig.Protocol value) {
        return value == OtelExporterConfig.Protocol.HTTP_PROTOBUF ? "http/protobuf" : "grpc";
    }

    private static URI uri(String value, String field) {
        URI uri = nullableUri(value, field);
        if (uri == null
                || uri.getScheme() == null
                || uri.getHost() == null
                || (!"http".equalsIgnoreCase(uri.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException(field + " must be an absolute HTTP(S) URI");
        }
        return uri;
    }

    private static URI nullableUri(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return URI.create(value.trim());
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " is not a valid URI", e);
        }
    }

    private static Path nullablePath(String value) {
        return SecretResolver.resolveRequiredPathReference(value);
    }

    private static String path(Path value) {
        return value == null ? null : value.toString();
    }

    private static String duration(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value.toString();
    }

    private static void map(Map<String, String> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}

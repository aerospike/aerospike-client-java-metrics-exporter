/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
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

public final class OtelMetricsExporterBuilder {
    static final String PREFIX = "aerospike_metrics_exporter.";
    static final String OTEL = PREFIX + "otel.";
    static final String FRESHNESS = PREFIX + "snapshot_stale_timeout";
    private static final String METER_NAME = "com.aerospike.client.metrics";

    private final Map<String, String> explicit = new LinkedHashMap<String, String>();
    private final Map<String, String> resourceAttributes = new LinkedHashMap<String, String>();

    private Path yamlPath;
    private OpenTelemetry openTelemetry;

    public OtelMetricsExporterBuilder fromYaml(Path path) {
        this.yamlPath = path;
        return this;
    }

    public OtelMetricsExporterBuilder openTelemetry(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
        return this;
    }

    public OtelMetricsExporterBuilder serviceName(String value) {
        return put("service_name", value);
    }

    public OtelMetricsExporterBuilder endpoint(String value) {
        return put("endpoint", value);
    }

    public OtelMetricsExporterBuilder protocol(OtelExporterConfig.Protocol value) {
        return put("protocol", value == null ? null : externalName(value));
    }

    public OtelMetricsExporterBuilder header(String name, String value) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("header name must not be empty");
        }
        explicit.put(OTEL + "headers." + name.trim(), value);
        return this;
    }

    public OtelMetricsExporterBuilder timeout(Duration value) {
        return put("timeout", duration(value, "timeout"));
    }

    public OtelMetricsExporterBuilder exportInterval(Duration value) {
        return put("export_interval", duration(value, "exportInterval"));
    }

    public OtelMetricsExporterBuilder compression(OtelExporterConfig.Compression value) {
        return put("compression", value == null ? null : value.name().toLowerCase(Locale.ROOT));
    }

    public OtelMetricsExporterBuilder temporality(OtelExporterConfig.Temporality value) {
        return put("temporality", value == null ? null : value.name().toLowerCase(Locale.ROOT));
    }

    public OtelMetricsExporterBuilder proxy(String value) {
        return put("proxy.url", value);
    }

    public OtelMetricsExporterBuilder trustedCa(Path value) {
        return put("tls.ca_file", path(value));
    }

    public OtelMetricsExporterBuilder clientCertificate(Path value) {
        return put("tls.client_cert_file", path(value));
    }

    public OtelMetricsExporterBuilder clientKey(Path value) {
        return put("tls.client_key_file", path(value));
    }

    public OtelMetricsExporterBuilder clientKeyPassword(String value) {
        return put("tls.client_key_password", value);
    }

    public OtelMetricsExporterBuilder snapshotStaleTimeout(String value) {
        explicit.put(FRESHNESS, value);
        return this;
    }

    public OtelMetricsExporterBuilder resourceAttribute(String name, String value) {
        if (name == null || name.trim().isEmpty() || value == null) {
            throw new IllegalArgumentException("resource attribute name and value are required");
        }
        resourceAttributes.put(name, value);
        return this;
    }

    public OtelMetricsExporter build() {
        OtelExporterConfig config = resolveConfig();
        ClientSnapshotRegistry snapshots = new ClientSnapshotRegistry(
            config.freshness()::createPolicy
        );

        if (openTelemetry != null) {
            Meter meter = openTelemetry.getMeter(METER_NAME);
            return new OtelMetricsExporter(meter, snapshots, null);
        }

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

    public OtelExporterConfig resolveConfig() {
        Map<String, String> yaml = yamlPath == null
            ? Collections.emptyMap()
            : YamlConfigSource.load(yamlPath);
        ConfigValues values = ConfigMerger.merge(
            defaults(),
            yaml,
            environment(System.getenv()),
            systemProperties(System.getProperties()),
            explicit
        );

        OtelExporterConfig.Protocol protocol = parseProtocol(values.getOrDefault(OTEL + "protocol", "grpc"));
        String defaultEndpoint = protocol == OtelExporterConfig.Protocol.GRPC
            ? "http://localhost:4317"
            : "http://localhost:4318/v1/metrics";
        URI endpoint = uri(values.getOrDefault(OTEL + "endpoint", defaultEndpoint), "otel.endpoint");
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
            int port = config.proxyUrl().getPort() >= 0
                ? config.proxyUrl().getPort()
                : 80;
            builder.setProxyOptions(ProxyOptions.create(
                new InetSocketAddress(config.proxyUrl().getHost(), port)
            ));
        }
        applyTls(builder, tls);
        return builder.build();
    }

    private static void applyTls(OtlpGrpcMetricExporterBuilder builder, TlsConfig tls) {
        if (tls.hasCustomTrust()) {
            builder.setTrustedCertificates(PemSupport.read(tls.caFile()));
        }
        if (tls.hasIdentity()) {
            builder.setClientTls(
                PemSupport.read(tls.certificateFile()),
                PemSupport.unencryptedPrivateKeyPem(tls.privateKeyFile(), tls.privateKeyPassword())
            );
        }
    }

    private static void applyTls(OtlpHttpMetricExporterBuilder builder, TlsConfig tls) {
        if (tls.hasCustomTrust()) {
            builder.setTrustedCertificates(PemSupport.read(tls.caFile()));
        }
        if (tls.hasIdentity()) {
            builder.setClientTls(
                PemSupport.read(tls.certificateFile()),
                PemSupport.unencryptedPrivateKeyPem(tls.privateKeyFile(), tls.privateKeyPassword())
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
        map(values, OTEL + "endpoint", environment.get("OTEL_EXPORTER_OTLP_ENDPOINT"));
        map(values, OTEL + "endpoint", environment.get("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT"));
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
        parseHeaders(values, environment.get("OTEL_EXPORTER_OTLP_HEADERS"));
        parseHeaders(values, environment.get("OTEL_EXPORTER_OTLP_METRICS_HEADERS"));
        return values;
    }

    static Map<String, String> systemProperties(Properties properties) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        map(values, OTEL + "service_name", properties.getProperty("otel.service.name"));
        map(values, OTEL + "endpoint", properties.getProperty("otel.exporter.otlp.endpoint"));
        map(values, OTEL + "endpoint", properties.getProperty("otel.exporter.otlp.metrics.endpoint"));
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
        return value == null || value.trim().isEmpty() ? null : Paths.get(value.trim());
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

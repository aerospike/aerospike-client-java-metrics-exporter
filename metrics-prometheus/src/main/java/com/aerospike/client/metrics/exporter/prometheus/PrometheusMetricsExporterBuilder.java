/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import com.aerospike.client.metrics.exporter.common.config.ConfigMerger;
import com.aerospike.client.metrics.exporter.common.config.ConfigValues;
import com.aerospike.client.metrics.exporter.common.config.FreshnessConfig;
import com.aerospike.client.metrics.exporter.common.config.SecretResolver;
import com.aerospike.client.metrics.exporter.common.config.TlsConfig;
import com.aerospike.client.metrics.exporter.common.config.YamlConfigSource;

public final class PrometheusMetricsExporterBuilder {
    static final String PREFIX = "aerospike_metrics_exporter.";
    static final String PROMETHEUS = PREFIX + "prometheus.";
    static final String FRESHNESS = PREFIX + "snapshot_stale_timeout";

    private final Map<String, String> explicit = new LinkedHashMap<String, String>();
    private Path yamlPath;

    public PrometheusMetricsExporterBuilder fromYaml(Path path) {
        this.yamlPath = path;
        return this;
    }

    public PrometheusMetricsExporterBuilder bindAddress(String value) {
        return put("bind_address", value);
    }

    public PrometheusMetricsExporterBuilder port(int value) {
        if (value < 0 || value > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        return put("port", Integer.toString(value));
    }

    public PrometheusMetricsExporterBuilder path(String value) {
        return put("path", value);
    }

    public PrometheusMetricsExporterBuilder httpTimeout(Duration value) {
        if (value == null || value.toMillis() < 1L) {
            throw new IllegalArgumentException("httpTimeout must be at least 1ms");
        }
        return put("http_timeout", value.toString());
    }

    public PrometheusMetricsExporterBuilder basicAuth(String username, String password) {
        put("basic_auth.username", username);
        put("basic_auth.password", password);
        return this;
    }

    public PrometheusMetricsExporterBuilder tlsEnabled(boolean enabled) {
        return put("tls.enabled", Boolean.toString(enabled));
    }

    public PrometheusMetricsExporterBuilder serverCertificate(Path value) {
        return put("tls.cert_file", path(value));
    }

    public PrometheusMetricsExporterBuilder serverKey(Path value) {
        return put("tls.key_file", path(value));
    }

    public PrometheusMetricsExporterBuilder serverKeyPassword(String value) {
        return put("tls.key_password", value);
    }

    public PrometheusMetricsExporterBuilder clientCa(Path value) {
        return put("tls.client_ca_file", path(value));
    }

    public PrometheusMetricsExporterBuilder clientAuth(TlsConfig.ClientAuth value) {
        return put(
            "tls.client_auth",
            value == null ? null : value.name().toLowerCase(Locale.ROOT)
        );
    }

    public PrometheusMetricsExporterBuilder snapshotStaleTimeout(String value) {
        explicit.put(FRESHNESS, value);
        return this;
    }

    public PrometheusMetricsExporter build() {
        return new PrometheusMetricsExporter(resolveConfig());
    }

    public PrometheusExporterConfig resolveConfig() {
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

        String bindAddress = values.getOrDefault(PROMETHEUS + "bind_address", "0.0.0.0");
        int port = values.getInt(PROMETHEUS + "port", 9146);
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("prometheus.port must be between 0 and 65535");
        }
        String endpointPath = values.getOrDefault(PROMETHEUS + "path", "/metrics");
        if (!endpointPath.startsWith("/")) {
            throw new IllegalArgumentException("prometheus.path must start with '/'");
        }

        String username = SecretResolver.resolve(values.get(PROMETHEUS + "basic_auth.username"));
        String password = SecretResolver.resolve(values.get(PROMETHEUS + "basic_auth.password"));
        if ((username == null) != (password == null)) {
            throw new IllegalArgumentException(
                "Prometheus basic-auth username and password must be configured together"
            );
        }

        boolean tlsEnabled = values.getBoolean(PROMETHEUS + "tls.enabled", false);
        Path cert = nullablePath(values.get(PROMETHEUS + "tls.cert_file"));
        Path key = nullablePath(values.get(PROMETHEUS + "tls.key_file"));
        Path clientCa = nullablePath(values.get(PROMETHEUS + "tls.client_ca_file"));
        TlsConfig.ClientAuth clientAuth = parseClientAuth(
            values.getOrDefault(PROMETHEUS + "tls.client_auth", "none")
        );
        TlsConfig tls = new TlsConfig(
            clientCa,
            cert,
            key,
            SecretResolver.resolve(values.get(PROMETHEUS + "tls.key_password")),
            clientAuth
        );
        if (tlsEnabled && !tls.hasIdentity()) {
            throw new IllegalArgumentException(
                "Prometheus HTTPS requires tls.cert_file and tls.key_file"
            );
        }
        if (!tlsEnabled && (tls.hasIdentity() || clientAuth != TlsConfig.ClientAuth.NONE)) {
            throw new IllegalArgumentException("Prometheus TLS settings require tls.enabled=true");
        }
        if (username != null && !tlsEnabled && !isLoopback(bindAddress)) {
            throw new IllegalArgumentException(
                "Prometheus basic authentication requires TLS unless bound to loopback"
            );
        }

        Duration httpTimeout = values.getDuration(
            PROMETHEUS + "http_timeout",
            Duration.ofSeconds(10)
        );
        if (httpTimeout.toMillis() < 1L) {
            throw new IllegalArgumentException("prometheus.http_timeout must be at least 1ms");
        }

        return new PrometheusExporterConfig(
            bindAddress,
            port,
            endpointPath,
            httpTimeout,
            username,
            password,
            tlsEnabled,
            tls,
            new FreshnessConfig(values.getOrDefault(FRESHNESS, FreshnessConfig.AUTO))
        );
    }

    private PrometheusMetricsExporterBuilder put(String suffix, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(suffix + " must not be empty");
        }
        explicit.put(PROMETHEUS + suffix, value);
        return this;
    }

    private static Map<String, String> defaults() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put(PROMETHEUS + "bind_address", "0.0.0.0");
        values.put(PROMETHEUS + "port", "9146");
        values.put(PROMETHEUS + "path", "/metrics");
        values.put(PROMETHEUS + "http_timeout", "10s");
        values.put(PROMETHEUS + "tls.enabled", "false");
        values.put(PROMETHEUS + "tls.client_auth", "none");
        values.put(FRESHNESS, FreshnessConfig.AUTO);
        return values;
    }

    static Map<String, String> environment(Map<String, String> environment) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        map(values, "bind_address", environment.get("AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_BIND_ADDRESS"));
        map(values, "port", environment.get("AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_PORT"));
        map(values, "path", environment.get("AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_PATH"));
        map(values, "http_timeout", environment.get("AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_HTTP_TIMEOUT"));
        map(values, "basic_auth.username", environment.get("AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_BASIC_AUTH_USERNAME"));
        map(values, "basic_auth.password", environment.get("AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_BASIC_AUTH_PASSWORD"));
        map(values, "tls.enabled", environment.get("AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_TLS_ENABLED"));
        map(values, "tls.cert_file", environment.get("AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_TLS_CERT_FILE"));
        map(values, "tls.key_file", environment.get("AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_TLS_KEY_FILE"));
        map(values, "tls.key_password", environment.get("AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_TLS_KEY_PASSWORD"));
        map(values, "tls.client_auth", environment.get("AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_TLS_CLIENT_AUTH"));
        map(values, "tls.client_ca_file", environment.get("AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_TLS_CLIENT_CA_FILE"));
        String stale = environment.get("AEROSPIKE_METRICS_EXPORTER_SNAPSHOT_STALE_TIMEOUT");
        if (stale != null) {
            values.put(FRESHNESS, stale);
        }
        return values;
    }

    static Map<String, String> systemProperties(Properties properties) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        String base = "aerospike.metrics.exporter.prometheus.";
        for (String suffix : new String[] {
            "bind_address", "port", "path", "http_timeout",
            "basic_auth.username", "basic_auth.password", "tls.enabled",
            "tls.cert_file", "tls.key_file", "tls.key_password",
            "tls.client_auth", "tls.client_ca_file"
        }) {
            map(values, suffix, properties.getProperty(base + suffix));
        }
        String stale = properties.getProperty("aerospike.metrics.exporter.snapshot.stale.timeout");
        if (stale != null) {
            values.put(FRESHNESS, stale);
        }
        return values;
    }

    private static void map(Map<String, String> target, String suffix, String value) {
        if (value != null) {
            target.put(PROMETHEUS + suffix, value);
        }
    }

    private static TlsConfig.ClientAuth parseClientAuth(String value) {
        try {
            return TlsConfig.ClientAuth.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "prometheus.tls.client_auth must be none, optional or required",
                e
            );
        }
    }

    private static boolean isLoopback(String address) {
        return "127.0.0.1".equals(address)
            || "::1".equals(address)
            || "localhost".equalsIgnoreCase(address);
    }

    private static Path nullablePath(String value) {
        return value == null || value.trim().isEmpty() ? null : Paths.get(value.trim());
    }

    private static String path(Path value) {
        return value == null ? null : value.toString();
    }
}

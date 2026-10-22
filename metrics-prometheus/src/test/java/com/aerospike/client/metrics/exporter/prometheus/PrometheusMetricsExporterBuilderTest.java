/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aerospike.client.metrics.exporter.common.config.TlsConfig;
import com.aerospike.client.metrics.exporter.common.config.reload.FileWatchConfiguration;

class PrometheusMetricsExporterBuilderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesPrometheusDefaults() {
        PrometheusExporterConfig config = PrometheusMetricsExporter.builder().resolveConfig();

        assertEquals("0.0.0.0", config.bindAddress());
        assertEquals(9146, config.port());
        assertEquals("/metrics", config.path());
        assertEquals(Duration.ofSeconds(10), config.httpTimeout());
        assertFalse(config.hasBasicAuth());
        assertFalse(config.tlsEnabled());
        assertEquals("auto", config.freshness().staleTimeout());
    }

    @Test
    void resolvesExplicitEndpointAuthenticationAndFreshness() {
        PrometheusExporterConfig config = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .port(0)
            .path("/custom")
            .httpTimeout(Duration.ofSeconds(2))
            .basicAuth("metrics", "secret")
            .snapshotStaleTimeout("15s")
            .resolveConfig();
        assertEquals("127.0.0.1", config.bindAddress());
        assertEquals(0, config.port());
        assertEquals("/custom", config.path());
        assertEquals(Duration.ofSeconds(2), config.httpTimeout());
        assertTrue(config.hasBasicAuth());
        assertEquals(Duration.ofSeconds(15), config.freshness().createPolicy().staleTimeout());
    }

    @Test
    void resolvesBuilderThenSystemThenYamlThenDefaults() throws IOException {
        Path yaml = temporaryDirectory.resolve("prometheus-precedence.yml");
        Files.writeString(
            yaml,
            "aerospike_metrics_exporter:\n"
                + "  snapshot_stale_timeout: 4s\n"
                + "  prometheus:\n"
                + "    bind_address: 127.0.0.1\n"
                + "    port: 9123\n"
                + "    path: /yaml\n"
                + "    http_timeout: 3s\n"
        );

        String portProperty = "aerospike.metrics.exporter.prometheus.port";
        String pathProperty = "aerospike.metrics.exporter.prometheus.path";
        String oldPort = System.getProperty(portProperty);
        String oldPath = System.getProperty(pathProperty);
        try {
            System.setProperty(portProperty, "9234");
            System.setProperty(pathProperty, "/system");
            PrometheusExporterConfig config = PrometheusMetricsExporter.builder()
                .fromYaml(yaml)
                .path("/builder")
                .resolveConfig();

            assertEquals("127.0.0.1", config.bindAddress());
            assertEquals(9234, config.port());
            assertEquals("/builder", config.path());
            assertEquals(Duration.ofSeconds(3), config.httpTimeout());
            assertEquals(Duration.ofSeconds(4), config.freshness().createPolicy().staleTimeout());
            assertFalse(config.tlsEnabled());
        }
        finally {
            restoreProperty(portProperty, oldPort);
            restoreProperty(pathProperty, oldPath);
        }
    }

    @Test
    void mapsEnvironmentAndSystemProperties() {
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_BIND_ADDRESS", "127.0.0.1");
        environment.put("AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_PORT", "9999");
        environment.put("AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_PATH", "/environment");
        environment.put("AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_HTTP_TIMEOUT", "7s");
        environment.put(
            "AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_BASIC_AUTH_USERNAME",
            "metrics"
        );
        environment.put(
            "AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_BASIC_AUTH_PASSWORD",
            "secret"
        );
        environment.put("AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_TLS_ENABLED", "true");
        environment.put("AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_TLS_CERT_FILE", "cert.pem");
        environment.put("AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_TLS_KEY_FILE", "key.pem");
        environment.put(
            "AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_TLS_KEY_PASSWORD",
            "key-secret"
        );
        environment.put("AEROSPIKE_METRICS_EXPORTER_SNAPSHOT_STALE_TIMEOUT", "4s");
        environment.put("AEROSPIKE_METRICS_EXPORTER_RELOAD_ENABLED", "false");
        environment.put("AEROSPIKE_METRICS_EXPORTER_RELOAD_INTERVAL", "9s");
        Map<String, String> expectedEnvironment = new LinkedHashMap<String, String>();
        expectedEnvironment.put(PrometheusMetricsExporterBuilder.PROMETHEUS + "bind_address", "127.0.0.1");
        expectedEnvironment.put(PrometheusMetricsExporterBuilder.PROMETHEUS + "port", "9999");
        expectedEnvironment.put(PrometheusMetricsExporterBuilder.PROMETHEUS + "path", "/environment");
        expectedEnvironment.put(PrometheusMetricsExporterBuilder.PROMETHEUS + "http_timeout", "7s");
        expectedEnvironment.put(PrometheusMetricsExporterBuilder.PROMETHEUS + "basic_auth.username", "metrics");
        expectedEnvironment.put(PrometheusMetricsExporterBuilder.PROMETHEUS + "basic_auth.password", "secret");
        expectedEnvironment.put(PrometheusMetricsExporterBuilder.PROMETHEUS + "tls.enabled", "true");
        expectedEnvironment.put(PrometheusMetricsExporterBuilder.PROMETHEUS + "tls.cert_file", "cert.pem");
        expectedEnvironment.put(PrometheusMetricsExporterBuilder.PROMETHEUS + "tls.key_file", "key.pem");
        expectedEnvironment.put(PrometheusMetricsExporterBuilder.PROMETHEUS + "tls.key_password", "key-secret");
        expectedEnvironment.put(PrometheusMetricsExporterBuilder.FRESHNESS, "4s");
        expectedEnvironment.put(PrometheusMetricsExporterBuilder.RELOAD + "enabled", "false");
        expectedEnvironment.put(PrometheusMetricsExporterBuilder.RELOAD + "interval", "9s");
        assertEquals(
            expectedEnvironment,
            PrometheusMetricsExporterBuilder.environment(environment)
        );

        Properties properties = new Properties();
        properties.setProperty("aerospike.metrics.exporter.prometheus.bind_address", "localhost");
        properties.setProperty("aerospike.metrics.exporter.prometheus.port", "9888");
        properties.setProperty("aerospike.metrics.exporter.prometheus.path", "/system");
        properties.setProperty("aerospike.metrics.exporter.prometheus.http_timeout", "8s");
        properties.setProperty(
            "aerospike.metrics.exporter.prometheus.basic_auth.username",
            "system-metrics"
        );
        properties.setProperty(
            "aerospike.metrics.exporter.prometheus.basic_auth.password",
            "system-secret"
        );
        properties.setProperty("aerospike.metrics.exporter.prometheus.tls.enabled", "true");
        properties.setProperty(
            "aerospike.metrics.exporter.prometheus.tls.cert_file",
            "system-cert.pem"
        );
        properties.setProperty(
            "aerospike.metrics.exporter.prometheus.tls.key_file",
            "system-key.pem"
        );
        properties.setProperty(
            "aerospike.metrics.exporter.prometheus.tls.key_password",
            "system-key-secret"
        );
        properties.setProperty("aerospike.metrics.exporter.snapshot.stale.timeout", "5s");
        properties.setProperty("aerospike.metrics.exporter.reload.enabled", "true");
        properties.setProperty("aerospike.metrics.exporter.reload.interval", "8s");
        Map<String, String> expectedProperties = new LinkedHashMap<String, String>();
        expectedProperties.put(PrometheusMetricsExporterBuilder.PROMETHEUS + "bind_address", "localhost");
        expectedProperties.put(PrometheusMetricsExporterBuilder.PROMETHEUS + "port", "9888");
        expectedProperties.put(PrometheusMetricsExporterBuilder.PROMETHEUS + "path", "/system");
        expectedProperties.put(PrometheusMetricsExporterBuilder.PROMETHEUS + "http_timeout", "8s");
        expectedProperties.put(
            PrometheusMetricsExporterBuilder.PROMETHEUS + "basic_auth.username",
            "system-metrics"
        );
        expectedProperties.put(
            PrometheusMetricsExporterBuilder.PROMETHEUS + "basic_auth.password",
            "system-secret"
        );
        expectedProperties.put(PrometheusMetricsExporterBuilder.PROMETHEUS + "tls.enabled", "true");
        expectedProperties.put(
            PrometheusMetricsExporterBuilder.PROMETHEUS + "tls.cert_file",
            "system-cert.pem"
        );
        expectedProperties.put(
            PrometheusMetricsExporterBuilder.PROMETHEUS + "tls.key_file",
            "system-key.pem"
        );
        expectedProperties.put(
            PrometheusMetricsExporterBuilder.PROMETHEUS + "tls.key_password",
            "system-key-secret"
        );
        expectedProperties.put(PrometheusMetricsExporterBuilder.FRESHNESS, "5s");
        expectedProperties.put(PrometheusMetricsExporterBuilder.RELOAD + "enabled", "true");
        expectedProperties.put(PrometheusMetricsExporterBuilder.RELOAD + "interval", "8s");
        assertEquals(
            expectedProperties,
            PrometheusMetricsExporterBuilder.systemProperties(properties)
        );
    }

    @Test
    void rejectsInvalidEndpointAndAuthenticationSettings() {
        assertThrows(
            IllegalArgumentException.class,
            () -> PrometheusMetricsExporter.builder().port(65536)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PrometheusMetricsExporter.builder().path("metrics").resolveConfig()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PrometheusMetricsExporter.builder()
                .bindAddress("0.0.0.0")
                .basicAuth("metrics", "secret")
                .resolveConfig()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PrometheusMetricsExporter.builder().httpTimeout(Duration.ZERO)
        );
    }

    @Test
    void rejectsInvalidResolvedPrometheusConfiguration() throws IOException {
        Path invalidPort = writeYaml(
            "  prometheus:\n"
                + "    port: 70000\n"
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PrometheusMetricsExporter.builder().fromYaml(invalidPort).resolveConfig()
        );

        Path invalidTimeout = writeYaml(
            "  prometheus:\n"
                + "    http_timeout: 0s\n"
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PrometheusMetricsExporter.builder().fromYaml(invalidTimeout).resolveConfig()
        );

        Path incompleteAuth = writeYaml(
            "  prometheus:\n"
                + "    bind_address: 127.0.0.1\n"
                + "    basic_auth:\n"
                + "      username: metrics\n"
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PrometheusMetricsExporter.builder().fromYaml(incompleteAuth).resolveConfig()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> PrometheusMetricsExporter.builder()
                .tlsEnabled(true)
                .resolveConfig()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PrometheusMetricsExporter.builder()
                .serverCertificate(Paths.get("server.pem"))
                .serverKey(Paths.get("server-key.pem"))
                .resolveConfig()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PrometheusMetricsExporter.builder()
                .snapshotStaleTimeout("0s")
                .resolveConfig()
        );

        Path invalidFreshness = writeYaml(
            "  snapshot_stale_timeout: invalid\n"
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PrometheusMetricsExporter.builder()
                .fromYaml(invalidFreshness)
                .resolveConfig()
        );

        Path invalidReload = writeYaml(
            "  reload:\n"
                + "    interval: 500ms\n"
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PrometheusMetricsExporter.builder()
                .fromYaml(invalidReload)
                .resolveConfig()
        );
    }

    @Test
    void resolvesFixedWatchedFilesFromFinalSystemAndBuilderValues() throws IOException {
        Path systemCertificate = Files.writeString(
            temporaryDirectory.resolve("system-cert.pem"),
            "system"
        );
        Path certificate = Files.writeString(
            temporaryDirectory.resolve("builder-cert.pem"),
            "certificate"
        );
        Path key = Files.writeString(
            temporaryDirectory.resolve("builder-key.pem"),
            "key"
        );
        Path keyPassword = Files.writeString(
            temporaryDirectory.resolve("key-password"),
            "key-secret"
        );
        Path clientCa = Files.writeString(
            temporaryDirectory.resolve("client-ca.pem"),
            "ca"
        );
        Path username = Files.writeString(temporaryDirectory.resolve("username"), "metrics");
        Path password = Files.writeString(temporaryDirectory.resolve("password"), "secret");
        String certificateProperty =
            "aerospike.metrics.exporter.prometheus.tls.cert_file";
        String oldCertificate = System.getProperty(certificateProperty);
        try {
            System.setProperty(certificateProperty, systemCertificate.toString());
            PrometheusMetricsExporterBuilder builder = PrometheusMetricsExporter.builder()
                .bindAddress("127.0.0.1")
                .basicAuth("file:" + username, "file:" + password)
                .tlsEnabled(true)
                .serverCertificate(certificate)
                .serverKey(key)
                .serverKeyPassword("file:" + keyPassword)
                .clientCa(clientCa)
                .clientAuth(TlsConfig.ClientAuth.REQUIRED);

            FileWatchConfiguration watched = builder.resolveFileWatchConfiguration();

            assertEquals(6, watched.references().size());
            assertTrue(watched.paths().contains(certificate.toAbsolutePath()));
            assertFalse(watched.paths().contains(systemCertificate.toAbsolutePath()));
            assertTrue(watched.paths().contains(key.toAbsolutePath()));
            assertTrue(watched.paths().contains(keyPassword.toAbsolutePath()));
            assertTrue(watched.paths().contains(clientCa.toAbsolutePath()));
            assertTrue(watched.paths().contains(username.toAbsolutePath()));
            assertTrue(watched.paths().contains(password.toAbsolutePath()));
        }
        finally {
            restoreProperty(certificateProperty, oldCertificate);
        }
    }

    @Test
    void resolvesReloadSettingsWithNormalSourcePrecedence() throws IOException {
        Path yaml = temporaryDirectory.resolve("reload-precedence.yml");
        Files.writeString(
            yaml,
            "aerospike_metrics_exporter:\n"
                + "  reload:\n"
                + "    enabled: false\n"
                + "    interval: 9s\n"
        );
        String intervalProperty = "aerospike.metrics.exporter.reload.interval";
        String oldInterval = System.getProperty(intervalProperty);
        try {
            System.setProperty(intervalProperty, "4s");
            FileWatchConfiguration watched = PrometheusMetricsExporter.builder()
                .fromYaml(yaml)
                .reloadEnabled(true)
                .reloadInterval(Duration.ofSeconds(2))
                .resolveFileWatchConfiguration();

            assertTrue(watched.settings().enabled());
            assertEquals(Duration.ofSeconds(2), watched.settings().interval());
        }
        finally {
            restoreProperty(intervalProperty, oldInterval);
        }
    }

    @Test
    void detectingAFileChangeDoesNotMutateResolvedRuntimeConfig() throws IOException {
        Path username = Files.writeString(temporaryDirectory.resolve("fixed-user"), "metrics");
        Path password = Files.writeString(temporaryDirectory.resolve("fixed-password"), "first");
        PrometheusMetricsExporterBuilder builder = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .basicAuth("file:" + username, "file:" + password);
        PrometheusExporterConfig runtime = builder.resolveConfig();
        FileWatchConfiguration watched = builder.resolveFileWatchConfiguration();

        Files.writeString(password, "second");

        assertEquals("first", runtime.password());
        assertTrue(watched.paths().contains(password.toAbsolutePath()));
    }

    private Path writeYaml(String prometheusContent) throws IOException {
        Path yaml = temporaryDirectory.resolve("invalid-" + System.nanoTime() + ".yml");
        Files.writeString(
            yaml,
            "aerospike_metrics_exporter:\n" + prometheusContent
        );
        return yaml;
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        }
        else {
            System.setProperty(name, value);
        }
    }
}

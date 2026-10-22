/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExporterYamlConfigTest {
    @TempDir
    Path directory;

    @Test
    void loadsEverySupportedFieldIntoTypedAndFlattenedForms() throws IOException {
        Path yaml = write("complete.yml", ""
            + "aerospike_metrics_exporter:\n"
            + "  snapshot_stale_timeout: 30s\n"
            + "  reload:\n"
            + "    enabled: true\n"
            + "    interval: 2s\n"
            + "  prometheus:\n"
            + "    bind_address: localhost\n"
            + "    port: 9146\n"
            + "    path: /custom\n"
            + "    http_timeout: 3s\n"
            + "    basic_auth:\n"
            + "      username: file:/secrets/user\n"
            + "      password: file:/secrets/password\n"
            + "    tls:\n"
            + "      enabled: true\n"
            + "      cert_file: /tls/server.pem\n"
            + "      key_file: /tls/server-key.pem\n"
            + "      key_password: file:/tls/key-password\n"
            + "      client_auth: required\n"
            + "      client_ca_file: /tls/client-ca.pem\n"
            + "  otel:\n"
            + "    service_name: test-service\n"
            + "    endpoint: https://collector:4318/v1/metrics\n"
            + "    protocol: http/protobuf\n"
            + "    headers:\n"
            + "      authorization: file:/secrets/token\n"
            + "      tenant: test\n"
            + "    timeout: 4s\n"
            + "    export_interval: 5s\n"
            + "    compression: gzip\n"
            + "    temporality: delta\n"
            + "    proxy:\n"
            + "      url: http://proxy:8080\n"
            + "    tls:\n"
            + "      ca_file: /tls/otel-ca.pem\n"
            + "      client_cert_file: /tls/otel-client.pem\n"
            + "      client_key_file: /tls/otel-client-key.pem\n"
            + "      client_key_password: file:/tls/otel-key-password\n");

        ExporterYamlConfig document = YamlConfigSource.loadDocument(yaml);
        Map<String, String> values = document.toFlatMap();

        assertEquals(29, values.size());
        assertEquals("30s", document.exporter().snapshotStaleTimeout());
        assertEquals(Boolean.TRUE, document.exporter().reload().enabled());
        assertEquals(9146, document.exporter().prometheus().port());
        assertEquals(
            "file:/secrets/password",
            document.exporter().prometheus().basicAuth().password()
        );
        assertEquals(
            "http://proxy:8080",
            document.exporter().otel().proxy().url()
        );
        assertEquals(
            "/tls/otel-client-key.pem",
            document.exporter().otel().tls().clientKeyFile()
        );
        assertEquals(
            "file:/secrets/token",
            values.get("aerospike_metrics_exporter.otel.headers.authorization")
        );
    }

    @Test
    void acceptsEmptyAndMissingOptionalSections() throws IOException {
        assertTrue(YamlConfigSource.load(write("empty.yml", "")).isEmpty());
        assertTrue(YamlConfigSource.load(
            write("root.yml", "aerospike_metrics_exporter:\n")
        ).isEmpty());
        assertTrue(YamlConfigSource.load(
            write("nested.yml", "aerospike_metrics_exporter:\n  prometheus:\n")
        ).isEmpty());
    }

    @Test
    void rejectsUnknownFieldsAtEveryLevelWithoutEchoingValues() throws IOException {
        for (String content : new String[] {
            "unexpected: secret-value\n",
            "aerospike_metrics_exporter:\n  unexpected: secret-value\n",
            "aerospike_metrics_exporter:\n  prometheus:\n    typo: secret-value\n",
            "aerospike_metrics_exporter:\n  prometheus:\n    tls:\n"
                + "      cert_flie: secret-value\n",
            "aerospike_metrics_exporter:\n  otel:\n    proxy:\n"
                + "      address: secret-value\n"
        }) {
            IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> YamlConfigSource.load(write("unknown-" + content.hashCode() + ".yml", content))
            );
            assertTrue(error.getMessage().contains("Unknown exporter YAML field"));
            assertFalse(error.getMessage().contains("secret-value"));
        }
    }

    @Test
    void rejectsWrongScalarAndSectionTypes() throws IOException {
        for (String content : new String[] {
            "aerospike_metrics_exporter: value\n",
            "aerospike_metrics_exporter:\n  reload:\n    enabled: \"true\"\n",
            "aerospike_metrics_exporter:\n  prometheus:\n    port: \"9146\"\n",
            "aerospike_metrics_exporter:\n  prometheus:\n    tls: value\n",
            "aerospike_metrics_exporter:\n  otel:\n    headers:\n      token: 42\n"
        }) {
            assertThrows(
                IllegalArgumentException.class,
                () -> YamlConfigSource.load(write("type-" + content.hashCode() + ".yml", content))
            );
        }
    }

    @Test
    void rejectsMalformedAndDuplicateYamlWithoutEchoingSecrets() throws IOException {
        for (String content : new String[] {
            "aerospike_metrics_exporter:\n  otel: [secret-value\n",
            "aerospike_metrics_exporter:\n  snapshot_stale_timeout: secret-value\n"
                + "  snapshot_stale_timeout: 5s\n"
        }) {
            IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> YamlConfigSource.load(write("invalid-" + content.hashCode() + ".yml", content))
            );
            assertFalse(error.getMessage().contains("secret-value"));
        }
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(directory.resolve(name), content);
    }
}

/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestPrometheusConfig {
    @TempDir
    Path temporaryDirectory;

    @Test
    void suppliesSafeListenerDefaults() {
        PrometheusConfig config = PrometheusConfig.defaults();

        assertEquals("127.0.0.1", config.getBindHost());
        assertEquals(9464, config.getPort());
    }

    @Test
    void rejectsInvalidListenerConfiguration() {
        assertThrows(
            IllegalArgumentException.class,
            () -> PrometheusConfig.builder().setBindHost(" ").build()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PrometheusConfig.builder().setPort(-1).build()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PrometheusConfig.builder().setPort(65_536).build()
        );
    }

    @Test
    void rejectsMissingTlsFilesAndMtlsWithoutCa() throws Exception {
        Path missingCertificate =
            temporaryDirectory.resolve("missing.crt");
        Path missingKey = temporaryDirectory.resolve("missing.key");

        assertThrows(
            IllegalArgumentException.class,
            () -> TlsConfig.builder()
                .setCertificatePath(missingCertificate)
                .setPrivateKeyPath(missingKey)
                .build()
        );
        Path certificate = Files.write(
            temporaryDirectory.resolve("server.crt"),
            "test".getBytes(StandardCharsets.UTF_8)
        );
        Path key = Files.write(
            temporaryDirectory.resolve("server.key"),
            "test".getBytes(StandardCharsets.UTF_8)
        );
        IllegalArgumentException mtlsFailure = assertThrows(
            IllegalArgumentException.class,
            () -> TlsConfig.builder()
                .setCertificatePath(certificate)
                .setPrivateKeyPath(key)
                .setClientCertificateRequired(true)
                .build()
        );
        assertTrue(mtlsFailure.getMessage().contains("client CA"));
    }

    @Test
    void rejectsInvalidTlsVersionRanges() throws Exception {
        Path certificate = Files.write(
            temporaryDirectory.resolve("version-server.crt"),
            "test".getBytes(StandardCharsets.UTF_8)
        );
        Path key = Files.write(
            temporaryDirectory.resolve("version-server.key"),
            "test".getBytes(StandardCharsets.UTF_8)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> TlsConfig.builder()
                .setCertificatePath(certificate)
                .setPrivateKeyPath(key)
                .setMinimumVersion("SSLv3")
                .build()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> TlsConfig.builder()
                .setCertificatePath(certificate)
                .setPrivateKeyPath(key)
                .setMinimumVersion("TLSv1.3")
                .setMaximumVersion("TLSv1.2")
                .build()
        );
    }
}

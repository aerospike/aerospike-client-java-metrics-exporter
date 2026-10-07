/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config.reload;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExporterConfigWatcherTest {
    @Test
    void findsTlsFilesAndFileBackedSecrets(@TempDir Path directory) throws IOException {
        Path certificate = Files.write(directory.resolve("server.pem"), "cert".getBytes(UTF_8));
        Path key = Files.write(directory.resolve("server-key.pem"), "key".getBytes(UTF_8));
        Path password = Files.write(directory.resolve("password"), "secret".getBytes(UTF_8));
        Path token = Files.write(directory.resolve("token"), "bearer".getBytes(UTF_8));
        Path yaml = yaml(directory, ""
            + "aerospike_metrics_exporter:\n"
            + "  prometheus:\n"
            + "    basic_auth:\n"
            + "      password: file:" + password + "\n"
            + "    tls:\n"
            + "      cert_file: " + certificate + "\n"
            + "      key_file: " + key + "\n"
            + "  otel:\n"
            + "    headers:\n"
            + "      authorization: file:" + token + "\n");

        Map<Path, String> watched = ExporterConfigWatcher.watchedFiles(yaml);

        assertEquals("exporter YAML", watched.get(yaml));
        assertEquals("aerospike_metrics_exporter.prometheus.tls.cert_file", watched.get(certificate));
        assertEquals("aerospike_metrics_exporter.prometheus.tls.key_file", watched.get(key));
        assertEquals(
            "aerospike_metrics_exporter.prometheus.basic_auth.password",
            watched.get(password)
        );
        assertEquals(
            "aerospike_metrics_exporter.otel.headers.authorization",
            watched.get(token)
        );
    }

    @Test
    void ignoresInlineSecretsBecauseTheyCannotRotate(@TempDir Path directory) throws IOException {
        Path yaml = yaml(directory, ""
            + "aerospike_metrics_exporter:\n"
            + "  prometheus:\n"
            + "    basic_auth:\n"
            + "      password: typed-in-place\n"
            + "  otel:\n"
            + "    headers:\n"
            + "      authorization: Bearer inline\n");

        Map<Path, String> watched = ExporterConfigWatcher.watchedFiles(yaml);

        assertEquals(1, watched.size(), "only the YAML itself");
        assertTrue(watched.containsKey(yaml));
    }

    @Test
    void reportsOneLabelPerKeyWhenTwoKeysNameTheSameFile(
            @TempDir Path directory
    ) throws IOException {
        Path shared = Files.write(directory.resolve("bundle.pem"), "pem".getBytes(UTF_8));
        Path yaml = yaml(directory, ""
            + "aerospike_metrics_exporter:\n"
            + "  prometheus:\n"
            + "    tls:\n"
            + "      client_ca_file: " + shared + "\n"
            + "  otel:\n"
            + "    tls:\n"
            + "      ca_file: " + shared + "\n");

        String label = ExporterConfigWatcher.watchedFiles(yaml).get(shared);

        assertTrue(label.contains("prometheus.tls.client_ca_file"), label);
        assertTrue(label.contains("otel.tls.ca_file"), label);
    }

    @Test
    void watchingCanBeTurnedOffInTheYaml(@TempDir Path directory) throws IOException {
        Path password = Files.write(directory.resolve("password"), "secret".getBytes(UTF_8));
        Path yaml = yaml(directory, ""
            + "aerospike_metrics_exporter:\n"
            + "  reload:\n"
            + "    enabled: false\n"
            + "  prometheus:\n"
            + "    basic_auth:\n"
            + "      password: file:" + password + "\n");

        assertNull(ExporterConfigWatcher.watch("prometheus", yaml, changed -> { }));
        assertEquals(0, ConfigFileWatcher.running());
    }

    @Test
    void detectsARotatedSecret(@TempDir Path directory) throws Exception {
        Path password = Files.write(directory.resolve("password"), "first".getBytes(UTF_8));
        Path yaml = yaml(directory, ""
            + "aerospike_metrics_exporter:\n"
            + "  reload:\n"
            + "    interval: 1s\n"
            + "  prometheus:\n"
            + "    basic_auth:\n"
            + "      password: file:" + password + "\n");

        final CountDownLatch detected = new CountDownLatch(1);
        ConfigFileWatcher watcher = ExporterConfigWatcher.watch(
            "prometheus",
            yaml,
            changed -> {
                if (changed.contains(password)) {
                    detected.countDown();
                }
            }
        );
        try {
            Files.write(password, "second".getBytes(UTF_8));

            // One interval to notice it, one more for it to settle.
            assertTrue(detected.await(15L, TimeUnit.SECONDS));
        }
        finally {
            watcher.close();
        }
        assertEquals(0, ConfigFileWatcher.running());
    }

    @Test
    void describesAnEmptyWatchSetWithoutFailing() {
        assertFalse(ExporterConfigWatcher.describe(java.util.Collections.emptyMap()).isEmpty());
    }

    private static Path yaml(Path directory, String content) throws IOException {
        return Files.write(
            directory.resolve("aerospike-metrics-exporter.yml"),
            content.getBytes(UTF_8)
        );
    }
}

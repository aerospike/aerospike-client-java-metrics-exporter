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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aerospike.client.metrics.exporter.common.config.ConfigValues;
import com.aerospike.client.metrics.exporter.common.config.ConfigMerger;
import com.aerospike.client.metrics.exporter.common.config.YamlConfigSource;

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

        FileWatchConfiguration watched = configuration(yaml);

        assertFalse(watched.paths().contains(yaml), "YAML is startup-only");
        assertTrue(fields(watched, certificate).contains("prometheus.tls.cert_file"));
        assertTrue(fields(watched, key).contains("prometheus.tls.key_file"));
        assertTrue(fields(watched, password).contains("prometheus.basic_auth.password"));
        assertTrue(fields(watched, token).contains("otel.headers.authorization"));
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

        FileWatchConfiguration watched = configuration(yaml);

        assertTrue(watched.paths().isEmpty());
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

        Set<String> labels = fields(configuration(yaml), shared);

        assertTrue(labels.contains("prometheus.tls.client_ca_file"), labels.toString());
        assertTrue(labels.contains("otel.tls.ca_file"), labels.toString());
    }

    @Test
    void discoversEverySupportedCredentialFile(@TempDir Path directory) throws IOException {
        Map<String, String> values = new LinkedHashMap<String, String>();
        addPath(values, "prometheus.tls.cert_file", directory, "prom-cert");
        addPath(values, "prometheus.tls.key_file", directory, "prom-key");
        addSecret(values, "prometheus.tls.key_password", directory, "prom-key-password");
        addPath(values, "prometheus.tls.client_ca_file", directory, "prom-ca");
        addSecret(values, "prometheus.basic_auth.username", directory, "prom-user");
        addSecret(values, "prometheus.basic_auth.password", directory, "prom-password");
        addPath(values, "otel.tls.ca_file", directory, "otel-ca");
        addPath(values, "otel.tls.client_cert_file", directory, "otel-cert");
        addPath(values, "otel.tls.client_key_file", directory, "otel-key");
        addSecret(values, "otel.tls.client_key_password", directory, "otel-key-password");
        addSecret(values, "otel.headers.authorization", directory, "otel-token");

        FileWatchConfiguration configuration =
            FileWatchConfiguration.from(new ConfigValues(values));

        assertEquals(11, configuration.references().size());
        assertEquals(11, configuration.paths().size());
        assertEquals(
            6,
            configuration.forBackend(
                ReloadableFileReference.Backend.PROMETHEUS
            ).references().size()
        );
        assertEquals(
            5,
            configuration.forBackend(
                ReloadableFileReference.Backend.OTEL
            ).references().size()
        );
    }

    @Test
    void derivesReferencesOnlyFromFinalWinningValues(@TempDir Path directory) {
        Path overridden = directory.resolve("overridden");
        Path selected = directory.resolve("selected");
        Map<String, String> yaml = Collections.singletonMap(
            "aerospike_metrics_exporter.otel.headers.authorization",
            "file:" + overridden
        );
        Map<String, String> builder = Collections.singletonMap(
            "aerospike_metrics_exporter.otel.headers.authorization",
            "file:" + selected
        );

        FileWatchConfiguration configuration = FileWatchConfiguration.from(
            ConfigMerger.merge(yaml, builder)
        );

        assertEquals(Collections.singleton(selected.toAbsolutePath()), configuration.paths());
        assertFalse(configuration.paths().contains(overridden.toAbsolutePath()));
    }

    @Test
    void keepsFileReferencesContributedByEveryStartupSource(@TempDir Path directory) {
        Path defaultFile = directory.resolve("default-ca");
        Path yamlFile = directory.resolve("yaml-cert");
        Path environmentFile = directory.resolve("environment-key");
        Path systemFile = directory.resolve("system-password");
        Path builderFile = directory.resolve("builder-token");

        FileWatchConfiguration configuration = FileWatchConfiguration.from(
            ConfigMerger.merge(
                Collections.singletonMap(
                    "aerospike_metrics_exporter.otel.tls.ca_file",
                    defaultFile.toString()
                ),
                Collections.singletonMap(
                    "aerospike_metrics_exporter.otel.tls.client_cert_file",
                    yamlFile.toString()
                ),
                Collections.singletonMap(
                    "aerospike_metrics_exporter.otel.tls.client_key_file",
                    environmentFile.toString()
                ),
                Collections.singletonMap(
                    "aerospike_metrics_exporter.otel.tls.client_key_password",
                    "file:" + systemFile
                ),
                Collections.singletonMap(
                    "aerospike_metrics_exporter.otel.headers.authorization",
                    "file:" + builderFile
                )
            )
        );

        assertEquals(
            Set.of(
                defaultFile.toAbsolutePath(),
                yamlFile.toAbsolutePath(),
                environmentFile.toAbsolutePath(),
                systemFile.toAbsolutePath(),
                builderFile.toAbsolutePath()
            ),
            configuration.paths()
        );
    }

    @Test
    void rejectsInvalidWinningFileReferences() {
        assertThrows(
            IllegalArgumentException.class,
            () -> FileWatchConfiguration.from(new ConfigValues(
                Collections.singletonMap(
                    "aerospike_metrics_exporter.otel.headers.authorization",
                    "file: "
                )
            ))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> FileWatchConfiguration.from(new ConfigValues(
                Collections.singletonMap(
                    "aerospike_metrics_exporter.otel.tls.ca_file",
                    "${AEROSPIKE_TEST_VARIABLE_THAT_MUST_NOT_EXIST}"
                )
            ))
        );
    }

    @Test
    void backendFilteringHappensBeforeOptionalReferencesAreParsed() {
        ConfigValues values = new ConfigValues(Collections.singletonMap(
            "aerospike_metrics_exporter.otel.headers.authorization",
            "file:${AEROSPIKE_TEST_VARIABLE_THAT_MUST_NOT_EXIST}"
        ));

        assertTrue(
            FileWatchConfiguration.from(
                values,
                ReloadableFileReference.Backend.PROMETHEUS
            ).paths().isEmpty()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> FileWatchConfiguration.from(
                values,
                ReloadableFileReference.Backend.OTEL
            )
        );
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

        assertNull(ExporterConfigWatcher.watch(
            "prometheus",
            configuration(yaml),
            changed -> { }
        ));
        assertEquals(0, ConfigFileWatcher.running());
    }

    @Test
    void onePhysicalChangeReportsEveryTypedReference(@TempDir Path directory)
            throws IOException {
        Path shared = Files.writeString(directory.resolve("shared.pem"), "first");
        FileWatchConfiguration configuration = new FileWatchConfiguration(
            new ReloadSettings(true, Duration.ofHours(1)),
            java.util.List.of(
                new ReloadableFileReference(
                    ReloadableFileReference.Backend.PROMETHEUS,
                    "prometheus.tls.cert_file",
                    shared
                ),
                new ReloadableFileReference(
                    ReloadableFileReference.Backend.PROMETHEUS,
                    "prometheus.tls.client_ca_file",
                    shared
                )
            )
        );
        java.util.List<Set<ReloadableFileReference>> reported =
            new java.util.ArrayList<Set<ReloadableFileReference>>();
        ConfigFileWatcher watcher = ExporterConfigWatcher.watch(
            "prometheus",
            configuration,
            reported::add
        );
        try {
            Files.writeString(shared, "second");
            watcher.pollOnce();
            watcher.pollOnce();

            assertEquals(1, reported.size());
            assertEquals(2, reported.get(0).size());
        }
        finally {
            watcher.close();
        }
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
            configuration(yaml),
            changed -> {
                if (changed.stream().anyMatch(reference -> reference.path().equals(
                    password.toAbsolutePath().normalize()
                ))) {
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
        FileWatchConfiguration empty = new FileWatchConfiguration(
            new ReloadSettings(true, Duration.ofSeconds(1)),
            Collections.emptyList()
        );
        assertFalse(ExporterConfigWatcher.describe(empty).isEmpty());
    }

    @Test
    void parsesReloadSettingsStrictlyAndPreservesDefaults() {
        ReloadSettings defaults = FileWatchConfiguration.from(
            new ConfigValues(Collections.emptyMap())
        ).settings();
        assertTrue(defaults.enabled());
        assertEquals(Duration.ofSeconds(5), defaults.interval());

        assertThrows(
            IllegalArgumentException.class,
            () -> FileWatchConfiguration.from(
                new ConfigValues(Collections.singletonMap(
                    "aerospike_metrics_exporter.reload.enabled",
                    "yes"
                ))
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> FileWatchConfiguration.from(
                new ConfigValues(Collections.singletonMap(
                    "aerospike_metrics_exporter.reload.interval",
                    "500ms"
                ))
            )
        );

        ReloadSettings minimum = FileWatchConfiguration.from(
            new ConfigValues(Collections.singletonMap(
                "aerospike_metrics_exporter.reload.interval",
                "1s"
            ))
        ).settings();
        assertEquals(Duration.ofSeconds(1), minimum.interval());
    }

    private static FileWatchConfiguration configuration(Path yaml) {
        return FileWatchConfiguration.from(new ConfigValues(YamlConfigSource.load(yaml)));
    }

    private static Set<String> fields(
            FileWatchConfiguration configuration,
            Path path
    ) {
        java.util.LinkedHashSet<String> fields = new java.util.LinkedHashSet<String>();
        for (ReloadableFileReference reference : configuration.referencesFor(path)) {
            fields.add(reference.field());
        }
        return fields;
    }

    private static void addPath(
            Map<String, String> values,
            String suffix,
            Path directory,
            String name
    ) throws IOException {
        Path path = Files.writeString(directory.resolve(name), name);
        values.put("aerospike_metrics_exporter." + suffix, path.toString());
    }

    private static void addSecret(
            Map<String, String> values,
            String suffix,
            Path directory,
            String name
    ) throws IOException {
        Path path = Files.writeString(directory.resolve(name), name);
        values.put("aerospike_metrics_exporter." + suffix, "file:" + path);
    }

    private static Path yaml(Path directory, String content) throws IOException {
        return Files.write(
            directory.resolve("aerospike-metrics-exporter.yml"),
            content.getBytes(UTF_8)
        );
    }
}

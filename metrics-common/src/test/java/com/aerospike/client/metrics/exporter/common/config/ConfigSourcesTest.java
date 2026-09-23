/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigSourcesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void higherPrecedenceSourcesOverrideLowerSources() {
        Map<String, String> defaults = map("endpoint", "default");
        Map<String, String> yaml = map("endpoint", "yaml");
        Map<String, String> environment = map("endpoint", "environment");
        Map<String, String> system = map("endpoint", "system");
        Map<String, String> builder = map("endpoint", "builder");

        ConfigValues values = ConfigMerger.merge(
            defaults,
            yaml,
            environment,
            system,
            builder
        );

        assertEquals("builder", values.get("endpoint"));
    }

    @Test
    void yamlIsFlattenedAndDurationsAreParsed() throws IOException {
        Path yaml = temporaryDirectory.resolve("exporter.yml");
        Files.writeString(
            yaml,
            "aerospike_metrics_exporter:\n"
                + "  snapshot_stale_timeout: 2m\n"
                + "  otel:\n"
                + "    endpoint: https://collector:4317\n"
        );

        ConfigValues values = new ConfigValues(YamlConfigSource.load(yaml));
        assertEquals(
            "https://collector:4317",
            values.get("aerospike_metrics_exporter.otel.endpoint")
        );
        assertEquals(
            Duration.ofMinutes(2),
            values.getDuration(
                "aerospike_metrics_exporter.snapshot_stale_timeout",
                Duration.ofSeconds(1)
            )
        );
    }

    @Test
    void fileBackedSecretsAreTrimmed() throws IOException {
        Path secret = temporaryDirectory.resolve("token");
        Files.writeString(secret, "secret-value\n");
        assertEquals("secret-value", SecretResolver.resolve("file:" + secret));
        assertEquals(
            "Bearer secret-value",
            SecretResolver.resolve(
                "Bearer ${TOKEN}",
                Collections.singletonMap("TOKEN", "secret-value")
            )
        );
    }

    @Test
    void invalidDurationIsRejectedWithoutEchoingSecrets() {
        assertThrows(
            IllegalArgumentException.class,
            () -> DurationParser.parse("not-a-duration", "timeout")
        );
    }

    private static Map<String, String> map(String key, String value) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        result.put(key, value);
        return Collections.unmodifiableMap(result);
    }
}

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigUtilitiesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesEverySupportedDurationForm() {
        assertEquals(Duration.ofMillis(500), DurationParser.parse("500ms", "value"));
        assertEquals(Duration.ofSeconds(10), DurationParser.parse("10s", "value"));
        assertEquals(Duration.ofMinutes(2), DurationParser.parse("2m", "value"));
        assertEquals(Duration.ofHours(1), DurationParser.parse("1h", "value"));
        assertEquals(Duration.ofSeconds(10), DurationParser.parse("PT10S", "value"));
        assertEquals(Duration.ofMillis(250), DurationParser.parse("250", "value"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("0s", "value"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("-1s", "value"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("", "value"));
    }

    @Test
    void readsTypedImmutableConfigValues() {
        Map<String, String> source = new LinkedHashMap<String, String>();
        source.put("prefix.port", " 9146 ");
        source.put("prefix.enabled", "TRUE");
        ConfigValues values = new ConfigValues(source);
        source.clear();

        assertEquals(9146, values.getInt("prefix.port", 1));
        assertTrue(values.getBoolean("prefix.enabled", false));
        assertEquals(" 9146 ", values.withPrefix("prefix.").get("port"));
        assertThrows(
            UnsupportedOperationException.class,
            () -> values.asMap().put("new", "value")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ConfigValues(Collections.singletonMap("x", "no")).getBoolean("x", false)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ConfigValues(Collections.singletonMap("x", "NaN")).getInt("x", 0)
        );
    }

    @Test
    void validatesFreshnessWhenConfigurationIsCreated() {
        assertEquals(
            Duration.ofSeconds(90),
            new FreshnessConfig("auto").createPolicy().staleTimeout()
        );
        assertEquals(
            Duration.ofSeconds(5),
            new FreshnessConfig("5s").createPolicy().staleTimeout()
        );
        assertEquals("auto", new FreshnessConfig(null).staleTimeout());
        assertThrows(IllegalArgumentException.class, () -> new FreshnessConfig(""));
        assertThrows(IllegalArgumentException.class, () -> new FreshnessConfig("invalid"));
        assertThrows(IllegalArgumentException.class, () -> new FreshnessConfig("0s"));
        assertThrows(IllegalArgumentException.class, () -> new FreshnessConfig("-1s"));
    }

    @Test
    void validatesTlsCombinations() {
        Path ca = temporaryDirectory.resolve("ca.pem");
        Path cert = temporaryDirectory.resolve("cert.pem");
        Path key = temporaryDirectory.resolve("key.pem");
        TlsConfig identity = new TlsConfig(
            ca, cert, key, "secret", TlsConfig.ClientAuth.REQUIRED
        );
        assertTrue(identity.hasIdentity());
        assertTrue(identity.hasCustomTrust());
        assertEquals(TlsConfig.ClientAuth.REQUIRED, identity.clientAuth());

        assertThrows(
            IllegalArgumentException.class,
            () -> new TlsConfig(null, cert, null, null, TlsConfig.ClientAuth.NONE)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new TlsConfig(null, cert, key, null, TlsConfig.ClientAuth.REQUIRED)
        );
        assertFalse(
            new TlsConfig(null, null, null, null, null).hasIdentity()
        );
    }

    @Test
    void reportsYamlAndSecretFailuresWithoutLeakingValues() throws Exception {
        Path nonMapping = temporaryDirectory.resolve("list.yml");
        Files.writeString(nonMapping, "- one\n- two\n");
        assertThrows(IllegalArgumentException.class, () -> YamlConfigSource.load(nonMapping));
        assertThrows(
            IllegalArgumentException.class,
            () -> SecretResolver.resolve("${MISSING}", Collections.emptyMap())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> SecretResolver.resolve("file:" + temporaryDirectory.resolve("missing"))
        );
    }
}

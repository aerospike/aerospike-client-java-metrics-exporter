/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PemSupportTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsAndNormalizesUnencryptedPkcs8PrivateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        Path key = temporaryDirectory.resolve("key.pem");
        Files.writeString(key, pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));

        byte[] normalized = PemSupport.unencryptedPrivateKeyPem(key, null);
        assertTrue(
            new String(normalized, StandardCharsets.US_ASCII)
                .startsWith("-----BEGIN PRIVATE KEY-----")
        );
        assertArrayEquals(Files.readAllBytes(key), PemSupport.read(key));
    }

    @Test
    void rejectsMissingMalformedAndPasswordlessEncryptedKeys() throws Exception {
        assertThrows(
            IllegalArgumentException.class,
            () -> PemSupport.read(temporaryDirectory.resolve("missing.pem"))
        );

        Path malformed = temporaryDirectory.resolve("malformed.pem");
        Files.writeString(malformed, "not a key");
        assertThrows(
            IllegalArgumentException.class,
            () -> PemSupport.unencryptedPrivateKeyPem(malformed, null)
        );

        Path encrypted = temporaryDirectory.resolve("encrypted.pem");
        Files.writeString(
            encrypted,
            pem("ENCRYPTED PRIVATE KEY", new byte[] {1, 2, 3, 4})
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PemSupport.unencryptedPrivateKeyPem(encrypted, null)
        );
    }

    @Test
    void createsServerContextFromCertificateAndPkcs8Key() {
        Path fixtures = testFixtures();
        TlsConfig config = new TlsConfig(
            null,
            fixtures.resolve("shared-localhost-test-cert.pem"),
            fixtures.resolve("shared-localhost-test-key.pem"),
            null,
            TlsConfig.ClientAuth.NONE
        );
        assertNotNull(PemSupport.createServerContext(config));
    }

    private static Path testFixtures() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path fixtures = directory.resolve("test-fixtures/tls");
            if (Files.isDirectory(fixtures)) {
                return fixtures;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Unable to locate test-fixtures/tls");
    }

    private static String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes)
            + "\n-----END " + type + "-----\n";
    }
}

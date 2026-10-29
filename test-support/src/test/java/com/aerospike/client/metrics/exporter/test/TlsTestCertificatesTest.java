/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsTestCertificatesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsCaSignedServerAndClientIdentitiesInTemporaryDirectory()
            throws Exception {
        TlsTestCertificates certificates = TlsTestCertificates.create(
            temporaryDirectory.resolve("tls")
        );

        assertTrue(certificates.caCertificate().startsWith(temporaryDirectory));
        assertTrue(certificates.serverCertificate().startsWith(temporaryDirectory));
        assertTrue(certificates.serverKey().startsWith(temporaryDirectory));
        assertTrue(certificates.clientCertificate().startsWith(temporaryDirectory));
        assertTrue(certificates.clientKey().startsWith(temporaryDirectory));

        X509Certificate ca = certificate(certificates.caCertificate());
        X509Certificate server = certificate(certificates.serverCertificate());
        X509Certificate client = certificate(certificates.clientCertificate());
        ca.verify(ca.getPublicKey());
        server.verify(ca.getPublicKey());
        client.verify(ca.getPublicKey());

        assertTrue(
            Files.readString(certificates.serverKey())
                .startsWith("-----BEGIN PRIVATE KEY-----")
        );
        assertTrue(
            Files.readString(certificates.clientKey())
                .startsWith("-----BEGIN PRIVATE KEY-----")
        );
    }

    private static X509Certificate certificate(Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(input);
        }
    }
}

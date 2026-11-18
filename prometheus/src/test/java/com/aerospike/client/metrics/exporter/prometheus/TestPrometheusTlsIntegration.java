/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.opentelemetry.sdk.metrics.SdkMeterProvider;

@Tag("integration")
class TestPrometheusTlsIntegration {
    @TempDir
    static Path temporaryDirectory;

    private static OpenSslCertificates.Material material;

    @BeforeAll
    static void generateCertificates() throws Exception {
        assumeTrue(
            OpenSslCertificates.isAvailable(),
            "OpenSSL is required to run Prometheus TLS integration tests"
        );
        material = OpenSslCertificates.generate(temporaryDirectory);
    }

    @Test
    void servesMetricsOverTls() throws Exception {
        RunningServer running = startServer(
            TlsConfig.builder()
                .setCertificatePath(material.serverCertificate)
                .setPrivateKeyPath(material.serverKey)
                .build()
        );
        try {
            running.provider.get("tls-test")
                .counterBuilder("secure.requests")
                .build()
                .add(2);
            HttpResponse<String> response = request(
                running.endpoint(),
                sslContext(false)
            );
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("secure_requests_total"));
        }
        finally {
            running.close();
        }
    }

    @Test
    void requiresAValidClientCertificateForMtls() throws Exception {
        RunningServer running = startServer(
            TlsConfig.builder()
                .setCertificatePath(material.serverCertificate)
                .setPrivateKeyPath(material.serverKey)
                .setClientCaPath(material.caCertificate)
                .setClientCertificateRequired(true)
                .build()
        );
        try {
            assertThrows(
                IOException.class,
                () -> request(running.endpoint(), sslContext(false))
            );
            assertEquals(
                200,
                request(running.endpoint(), sslContext(true)).statusCode()
            );
        }
        finally {
            running.close();
        }
    }

    @Test
    void rejectsMismatchedCertificateAndKeyBeforeBinding() {
        IllegalArgumentException failure = assertStartRejected(
            TlsConfig.builder()
                .setCertificatePath(material.serverCertificate)
                .setPrivateKeyPath(material.mismatchedServerKey)
                .build()
        );
        assertTrue(failure.getMessage().contains("does not match"));
    }

    @Test
    void rejectsInvalidCertificateKeyAndCaMaterial() throws Exception {
        Path invalidCertificate =
            temporaryDirectory.resolve("invalid-certificate.pem");
        Path invalidKey = temporaryDirectory.resolve("invalid-private.key");
        Files.writeString(invalidCertificate, "not a certificate");
        Files.writeString(invalidKey, "not a private key");

        assertStartRejected(
            TlsConfig.builder()
                .setCertificatePath(invalidCertificate)
                .setPrivateKeyPath(material.serverKey)
                .build()
        );
        assertStartRejected(
            TlsConfig.builder()
                .setCertificatePath(material.serverCertificate)
                .setPrivateKeyPath(invalidKey)
                .build()
        );
        IllegalArgumentException invalidCa = assertStartRejected(
            TlsConfig.builder()
                .setCertificatePath(material.serverCertificate)
                .setPrivateKeyPath(material.serverKey)
                .setClientCaPath(material.serverCertificate)
                .build()
        );
        assertTrue(invalidCa.getMessage().contains("non-CA"));
    }

    private static RunningServer startServer(TlsConfig tls)
            throws Exception {
        CustomPrometheusMetricReader reader =
            CustomPrometheusMetricReader.create(
                PrometheusConfig.builder()
                    .setPort(0)
                    .setTlsConfig(tls)
                    .build()
            );
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        try {
            reader.startHttpServer();
            return new RunningServer(reader, provider);
        }
        catch (Exception e) {
            provider.close();
            throw e;
        }
    }

    private static IllegalArgumentException assertStartRejected(
            TlsConfig tls
    ) {
        CustomPrometheusMetricReader reader =
            CustomPrometheusMetricReader.create(
                PrometheusConfig.builder()
                    .setPort(0)
                    .setTlsConfig(tls)
                    .build()
            );
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        try {
            return assertThrows(
                IllegalArgumentException.class,
                reader::startHttpServer
            );
        }
        finally {
            provider.close();
        }
    }

    private static HttpResponse<String> request(
            String endpoint,
            SSLContext sslContext
    ) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .sslContext(sslContext)
            .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
        return client.send(
            request,
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private static SSLContext sslContext(boolean includeClientCertificate)
            throws Exception {
        KeyStore trustStore = KeyStore.getInstance(
            KeyStore.getDefaultType()
        );
        trustStore.load(null);
        try (InputStream input =
                Files.newInputStream(material.caCertificate)) {
            Certificate ca = CertificateFactory
                .getInstance("X.509")
                .generateCertificate(input);
            trustStore.setCertificateEntry("test-ca", ca);
        }
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        );
        trustManagers.init(trustStore);

        KeyManager[] keyManagers = null;
        if (includeClientCertificate) {
            KeyStore clientStore = KeyStore.getInstance("PKCS12");
            try (InputStream input =
                    Files.newInputStream(material.clientStore)) {
                clientStore.load(
                    input,
                    OpenSslCertificates.STORE_PASSWORD
                );
            }
            KeyManagerFactory factory = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm()
            );
            factory.init(
                clientStore,
                OpenSslCertificates.STORE_PASSWORD
            );
            keyManagers = factory.getKeyManagers();
        }

        SSLContext result = SSLContext.getInstance("TLS");
        result.init(keyManagers, trustManagers.getTrustManagers(), null);
        return result;
    }

    private static final class RunningServer implements AutoCloseable {
        private final CustomPrometheusMetricReader reader;
        private final SdkMeterProvider provider;

        private RunningServer(
                CustomPrometheusMetricReader reader,
                SdkMeterProvider provider
        ) {
            this.reader = reader;
            this.provider = provider;
        }

        private String endpoint() {
            return "https://localhost:"
                + reader.getHttpServerPort()
                + "/metrics";
        }

        @Override
        public void close() {
            provider.close();
        }
    }
}

/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * Loads and validates TLS material before the HTTP listener is bound.
 */
final class TlsSupport {
    private static final char[] KEYSTORE_PASSWORD =
        "aerospike-prometheus".toCharArray();
    private static final Map<String, Integer> TLS_VERSION_ORDER = Map.of(
        "TLSv1", 10,
        "TLSv1.1", 11,
        "TLSv1.2", 12,
        "TLSv1.3", 13
    );

    private TlsSupport() {
    }

    static LoadedTls load(TlsConfig config) {
        try {
            List<X509Certificate> certificateChain =
                loadCertificates(config.getCertificatePath());
            if (certificateChain.isEmpty()) {
                throw new IllegalArgumentException(
                    "server certificate file contains no X.509 certificates"
                );
            }
            for (X509Certificate certificate : certificateChain) {
                certificate.checkValidity();
            }

            PrivateKey privateKey =
                loadPrivateKey(config.getPrivateKeyPath());
            verifyKeyPair(certificateChain.get(0), privateKey);

            KeyStore keyStore = KeyStore.getInstance(
                KeyStore.getDefaultType()
            );
            keyStore.load(null);
            keyStore.setKeyEntry(
                "server",
                privateKey,
                KEYSTORE_PASSWORD,
                certificateChain.toArray(new Certificate[0])
            );
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm()
            );
            keyManagers.init(keyStore, KEYSTORE_PASSWORD);

            TrustManager[] trustManagers = null;
            if (config.getClientCaPath() != null) {
                List<X509Certificate> clientCas =
                    loadCertificates(config.getClientCaPath());
                if (clientCas.isEmpty()) {
                    throw new IllegalArgumentException(
                        "client CA file contains no X.509 certificates"
                    );
                }
                KeyStore trustStore = KeyStore.getInstance(
                    KeyStore.getDefaultType()
                );
                trustStore.load(null);
                for (int index = 0; index < clientCas.size(); index++) {
                    X509Certificate clientCa = clientCas.get(index);
                    clientCa.checkValidity();
                    if (clientCa.getBasicConstraints() < 0) {
                        throw new IllegalArgumentException(
                            "client CA material contains a non-CA certificate"
                        );
                    }
                    trustStore.setCertificateEntry("client-ca-" + index, clientCa);
                }
                TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm()
                    );
                trustManagerFactory.init(trustStore);
                trustManagers = trustManagerFactory.getTrustManagers();
            }

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(
                keyManagers.getKeyManagers(),
                trustManagers,
                null
            );
            String[] protocols = selectProtocols(context, config);
            return new LoadedTls(
                context,
                protocols,
                config.getClientCaPath() != null,
                config.isClientCertificateRequired()
            );
        }
        catch (IllegalArgumentException e) {
            throw e;
        }
        catch (IOException | GeneralSecurityException e) {
            throw new IllegalArgumentException(
                "invalid TLS configuration: " + e.getMessage(),
                e
            );
        }
    }

    private static List<X509Certificate> loadCertificates(Path path)
            throws IOException, CertificateException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        try (InputStream input = Files.newInputStream(path)) {
            Collection<? extends Certificate> certificates =
                factory.generateCertificates(input);
            List<X509Certificate> result =
                new ArrayList<X509Certificate>(certificates.size());
            for (Certificate certificate : certificates) {
                if (!(certificate instanceof X509Certificate)) {
                    throw new CertificateException(
                        "certificate material is not X.509"
                    );
                }
                result.add((X509Certificate) certificate);
            }
            return result;
        }
    }

    private static PrivateKey loadPrivateKey(Path path)
            throws IOException, GeneralSecurityException {
        String pem = Files.readString(path, StandardCharsets.US_ASCII);
        if (pem.contains("BEGIN ENCRYPTED PRIVATE KEY")) {
            throw new IllegalArgumentException(
                "encrypted private keys are not supported"
            );
        }
        byte[] encoded;
        if (pem.contains("-----BEGIN PRIVATE KEY-----")) {
            encoded = decodePem(
                pem,
                "-----BEGIN PRIVATE KEY-----",
                "-----END PRIVATE KEY-----"
            );
        }
        else if (pem.contains("-----BEGIN RSA PRIVATE KEY-----")) {
            encoded = wrapRsaPkcs1InPkcs8(decodePem(
                pem,
                "-----BEGIN RSA PRIVATE KEY-----",
                "-----END RSA PRIVATE KEY-----"
            ));
        }
        else {
            throw new IllegalArgumentException(
                "private key must be an unencrypted PKCS#8 or RSA PKCS#1 PEM key"
            );
        }
        PKCS8EncodedKeySpec specification = new PKCS8EncodedKeySpec(encoded);
        GeneralSecurityException lastFailure = null;
        for (String algorithm : List.of(
                "RSA",
                "EC",
                "DSA",
                "Ed25519",
                "Ed448"
        )) {
            try {
                return KeyFactory
                    .getInstance(algorithm)
                    .generatePrivate(specification);
            }
            catch (GeneralSecurityException e) {
                lastFailure = e;
            }
        }
        throw new GeneralSecurityException(
            "private key must be a supported unencrypted PEM key",
            lastFailure
        );
    }

    private static byte[] decodePem(
            String pem,
            String begin,
            String end
    ) {
        int start = pem.indexOf(begin);
        int finish = pem.indexOf(end);
        if (start < 0 || finish <= start) {
            throw new IllegalArgumentException(
                "private key must contain " + begin
            );
        }
        String base64 = pem.substring(start + begin.length(), finish)
            .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(base64);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "private key contains invalid PEM base64",
                e
            );
        }
    }

    private static byte[] wrapRsaPkcs1InPkcs8(byte[] pkcs1) {
        byte[] version = new byte[] {0x02, 0x01, 0x00};
        byte[] rsaAlgorithmIdentifier = new byte[] {
            0x30, 0x0d,
            0x06, 0x09,
            0x2a, (byte) 0x86, 0x48, (byte) 0x86,
            (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
            0x05, 0x00
        };
        byte[] privateKey = derValue((byte) 0x04, pkcs1);
        return derValue(
            (byte) 0x30,
            concatenate(version, rsaAlgorithmIdentifier, privateKey)
        );
    }

    private static byte[] derValue(byte tag, byte[] value) {
        byte[] length = derLength(value.length);
        byte[] result = new byte[1 + length.length + value.length];
        result[0] = tag;
        System.arraycopy(length, 0, result, 1, length.length);
        System.arraycopy(
            value,
            0,
            result,
            1 + length.length,
            value.length
        );
        return result;
    }

    private static byte[] derLength(int length) {
        if (length < 128) {
            return new byte[] {(byte) length};
        }
        int bytes = 0;
        int remaining = length;
        while (remaining > 0) {
            bytes++;
            remaining >>>= 8;
        }
        byte[] result = new byte[bytes + 1];
        result[0] = (byte) (0x80 | bytes);
        for (int index = bytes; index > 0; index--) {
            result[index] = (byte) length;
            length >>>= 8;
        }
        return result;
    }

    private static byte[] concatenate(byte[]... values) {
        int length = 0;
        for (byte[] value : values) {
            length += value.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    private static void verifyKeyPair(
            X509Certificate certificate,
            PrivateKey privateKey
    ) throws GeneralSecurityException {
        String publicAlgorithm = certificate.getPublicKey().getAlgorithm();
        String privateAlgorithm = privateKey.getAlgorithm();
        if (!publicAlgorithm.equalsIgnoreCase(privateAlgorithm)) {
            throw new IllegalArgumentException(
                "server certificate and private key algorithms do not match"
            );
        }

        String signatureAlgorithm;
        switch (privateAlgorithm.toUpperCase()) {
            case "RSA":
                signatureAlgorithm = "SHA256withRSA";
                break;
            case "EC":
                signatureAlgorithm = "SHA256withECDSA";
                break;
            case "DSA":
                signatureAlgorithm = "SHA256withDSA";
                break;
            case "ED25519":
                signatureAlgorithm = "Ed25519";
                break;
            case "ED448":
                signatureAlgorithm = "Ed448";
                break;
            default:
                throw new IllegalArgumentException(
                    "unsupported private key algorithm: " + privateAlgorithm
                );
        }

        byte[] challenge = (
            "aerospike-prometheus-" + Instant.now().toEpochMilli()
        ).getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance(signatureAlgorithm);
        signer.initSign(privateKey);
        signer.update(challenge);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance(signatureAlgorithm);
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(challenge);
        if (!verifier.verify(signature)) {
            throw new IllegalArgumentException(
                "server certificate does not match the private key"
            );
        }
    }

    private static String[] selectProtocols(
            SSLContext context,
            TlsConfig config
    ) {
        int minimum = TLS_VERSION_ORDER.get(config.getMinimumVersion());
        int maximum = TLS_VERSION_ORDER.get(config.getMaximumVersion());
        Set<String> supported = Set.of(
            context.getSupportedSSLParameters().getProtocols()
        );
        List<String> selected = new ArrayList<String>();
        for (Map.Entry<String, Integer> version
                : TLS_VERSION_ORDER.entrySet()) {
            if (version.getValue() >= minimum
                    && version.getValue() <= maximum
                    && supported.contains(version.getKey())) {
                selected.add(version.getKey());
            }
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException(
                "configured TLS version range is not supported by this JVM"
            );
        }
        return selected.toArray(new String[0]);
    }

    static final class LoadedTls {
        private final SSLContext context;
        private final String[] protocols;
        private final boolean clientCertificateRequested;
        private final boolean clientCertificateRequired;

        private LoadedTls(
                SSLContext context,
                String[] protocols,
                boolean clientCertificateRequested,
                boolean clientCertificateRequired
        ) {
            this.context = context;
            this.protocols = protocols.clone();
            this.clientCertificateRequested = clientCertificateRequested;
            this.clientCertificateRequired = clientCertificateRequired;
        }

        SSLContext getContext() {
            return context;
        }

        String[] getProtocols() {
            return protocols.clone();
        }

        boolean isClientCertificateRequested() {
            return clientCertificateRequested;
        }

        boolean isClientCertificateRequired() {
            return clientCertificateRequired;
        }
    }
}

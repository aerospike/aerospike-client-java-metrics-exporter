/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Minimal PEM support for PKCS#8 RSA/EC/DSA keys and X.509 certificates.
 */
public final class PemSupport {
    private PemSupport() {
    }

    public static byte[] read(Path path) {
        if (path == null) {
            return null;
        }
        try {
            return Files.readAllBytes(path);
        }
        catch (IOException e) {
            throw new IllegalArgumentException("Unable to read TLS file: " + path, e);
        }
    }

    /**
     * Returns an unencrypted PKCS#8 PEM key suitable for OTLP exporter APIs.
     */
    public static byte[] unencryptedPrivateKeyPem(Path path, String password) {
        PrivateKey key = readPrivateKey(path, password);
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded());
        return ("-----BEGIN PRIVATE KEY-----\n" + body
            + "\n-----END PRIVATE KEY-----\n").getBytes(StandardCharsets.US_ASCII);
    }

    public static SSLContext createServerContext(TlsConfig config) {
        if (config == null || !config.hasIdentity()) {
            throw new IllegalArgumentException("HTTPS requires a server certificate and private key");
        }
        try {
            PrivateKey privateKey = readPrivateKey(
                config.privateKeyFile(),
                config.privateKeyPassword()
            );
            List<Certificate> chain = readCertificates(config.certificateFile());

            char[] keyPassword = new char[0];
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setKeyEntry(
                "server",
                privateKey,
                keyPassword,
                chain.toArray(new Certificate[0])
            );

            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm()
            );
            keyManagers.init(keyStore, keyPassword);

            TrustManagerFactory trustManagers = null;
            if (config.hasCustomTrust()) {
                KeyStore trustStore = trustStore(config.caFile());
                trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm()
                );
                trustManagers.init(trustStore);
            }

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(
                keyManagers.getKeyManagers(),
                trustManagers == null ? null : trustManagers.getTrustManagers(),
                new SecureRandom()
            );
            return context;
        }
        catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException("Unable to initialize TLS context", e);
        }
    }

    private static KeyStore trustStore(Path caFile) throws GeneralSecurityException, IOException {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        int index = 0;
        for (Certificate certificate : readCertificates(caFile)) {
            trustStore.setCertificateEntry("ca-" + index++, certificate);
        }
        return trustStore;
    }

    private static List<Certificate> readCertificates(Path path) throws GeneralSecurityException {
        byte[] pem = read(path);
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certificates = factory.generateCertificates(
            new ByteArrayInputStream(pem)
        );
        if (certificates.isEmpty()) {
            throw new IllegalArgumentException("No X.509 certificate found in " + path);
        }
        return new ArrayList<Certificate>(certificates);
    }

    private static PrivateKey readPrivateKey(Path path, String password) {
        byte[] pem = read(path);
        String text = new String(pem, StandardCharsets.US_ASCII);
        boolean encrypted = text.contains("BEGIN ENCRYPTED PRIVATE KEY");
        byte[] der = decodePem(
            text,
            encrypted ? "ENCRYPTED PRIVATE KEY" : "PRIVATE KEY"
        );

        try {
            PKCS8EncodedKeySpec keySpec;
            if (encrypted) {
                if (password == null) {
                    throw new IllegalArgumentException("Encrypted private key requires a password");
                }
                EncryptedPrivateKeyInfo encryptedInfo = new EncryptedPrivateKeyInfo(der);
                SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(
                    encryptedInfo.getAlgName()
                );
                SecretKey secret = secretKeyFactory.generateSecret(
                    new PBEKeySpec(password.toCharArray())
                );
                Cipher cipher = Cipher.getInstance(encryptedInfo.getAlgName());
                cipher.init(Cipher.DECRYPT_MODE, secret, encryptedInfo.getAlgParameters());
                keySpec = encryptedInfo.getKeySpec(cipher);
            }
            else {
                keySpec = new PKCS8EncodedKeySpec(der);
            }

            for (String algorithm : new String[] {"RSA", "EC", "DSA"}) {
                try {
                    return KeyFactory.getInstance(algorithm).generatePrivate(keySpec);
                }
                catch (GeneralSecurityException ignored) {
                    // Try the next common key algorithm.
                }
            }
            throw new IllegalArgumentException("Unsupported PKCS#8 private key algorithm: " + path);
        }
        catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException("Unable to read private key: " + path, e);
        }
    }

    private static byte[] decodePem(String text, String type) {
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int start = text.indexOf(begin);
        int finish = text.indexOf(end);
        if (start < 0 || finish < 0 || finish <= start) {
            throw new IllegalArgumentException(
                "Expected PKCS#8 PEM block " + begin
            );
        }
        String body = text.substring(start + begin.length(), finish).replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}

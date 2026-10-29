/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Creates short-lived TLS identities under a test-owned temporary directory.
 */
public record TlsTestCertificates(
    Path caCertificate,
    Path serverCertificate,
    Path serverKey,
    Path clientCertificate,
    Path clientKey
) {
    private static final BouncyCastleProvider PROVIDER = new BouncyCastleProvider();
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Generate a CA plus separate localhost server and client identities.
     */
    public static TlsTestCertificates create(Path directory) throws Exception {
        Files.createDirectories(directory);

        Instant now = Instant.now();
        Date notBefore = Date.from(now.minus(5, ChronoUnit.MINUTES));
        Date notAfter = Date.from(now.plus(1, ChronoUnit.DAYS));

        KeyPair caKeyPair = keyPair();
        X500Name caName = new X500Name("CN=Aerospike Metrics Test CA");
        X509Certificate ca = certificateAuthority(
            caName,
            caKeyPair,
            notBefore,
            notAfter
        );

        KeyPair serverKeyPair = keyPair();
        X509Certificate server = leafCertificate(
            caName,
            ca,
            caKeyPair.getPrivate(),
            new X500Name("CN=localhost"),
            serverKeyPair,
            KeyPurposeId.id_kp_serverAuth,
            new GeneralNames(new GeneralName[] {
                new GeneralName(GeneralName.dNSName, "localhost"),
                new GeneralName(GeneralName.iPAddress, "127.0.0.1")
            }),
            notBefore,
            notAfter
        );

        KeyPair clientKeyPair = keyPair();
        X509Certificate client = leafCertificate(
            caName,
            ca,
            caKeyPair.getPrivate(),
            new X500Name("CN=aerospike-metrics-test-client"),
            clientKeyPair,
            KeyPurposeId.id_kp_clientAuth,
            null,
            notBefore,
            notAfter
        );

        Path caCertificate = directory.resolve("ca.pem");
        Path serverCertificate = directory.resolve("server-cert.pem");
        Path serverKey = directory.resolve("server-key.pem");
        Path clientCertificate = directory.resolve("client-cert.pem");
        Path clientKey = directory.resolve("client-key.pem");

        writePem(caCertificate, "CERTIFICATE", ca.getEncoded());
        writePem(serverCertificate, "CERTIFICATE", server.getEncoded());
        writePrivateKey(serverKey, serverKeyPair.getPrivate());
        writePem(clientCertificate, "CERTIFICATE", client.getEncoded());
        writePrivateKey(clientKey, clientKeyPair.getPrivate());

        return new TlsTestCertificates(
            caCertificate,
            serverCertificate,
            serverKey,
            clientCertificate,
            clientKey
        );
    }

    private static X509Certificate certificateAuthority(
            X500Name name,
            KeyPair keyPair,
            Date notBefore,
            Date notAfter
    ) throws Exception {
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            name,
            serialNumber(),
            notBefore,
            notAfter,
            name,
            keyPair.getPublic()
        );
        JcaX509ExtensionUtils extensions = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(
            Extension.keyUsage,
            true,
            new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign)
        );
        builder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            extensions.createSubjectKeyIdentifier(keyPair.getPublic())
        );
        return sign(builder, keyPair.getPrivate());
    }

    private static X509Certificate leafCertificate(
            X500Name issuer,
            X509Certificate issuerCertificate,
            PrivateKey issuerKey,
            X500Name subject,
            KeyPair keyPair,
            KeyPurposeId purpose,
            GeneralNames subjectAlternativeNames,
            Date notBefore,
            Date notAfter
    ) throws Exception {
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            issuer,
            serialNumber(),
            notBefore,
            notAfter,
            subject,
            keyPair.getPublic()
        );
        JcaX509ExtensionUtils extensions = new JcaX509ExtensionUtils();
        builder.addExtension(
            Extension.basicConstraints,
            true,
            new BasicConstraints(false)
        );
        builder.addExtension(
            Extension.keyUsage,
            true,
            new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment)
        );
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            new ExtendedKeyUsage(purpose)
        );
        builder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            extensions.createSubjectKeyIdentifier(keyPair.getPublic())
        );
        builder.addExtension(
            Extension.authorityKeyIdentifier,
            false,
            extensions.createAuthorityKeyIdentifier(issuerCertificate)
        );
        if (subjectAlternativeNames != null) {
            builder.addExtension(
                Extension.subjectAlternativeName,
                false,
                subjectAlternativeNames
            );
        }
        return sign(builder, issuerKey);
    }

    private static X509Certificate sign(
            JcaX509v3CertificateBuilder builder,
            PrivateKey signingKey
    ) throws Exception {
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(PROVIDER)
            .build(signingKey);
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate certificate = new JcaX509CertificateConverter()
            .setProvider(PROVIDER)
            .getCertificate(holder);
        certificate.checkValidity();
        return certificate;
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, RANDOM);
        return generator.generateKeyPair();
    }

    private static BigInteger serialNumber() {
        return new BigInteger(159, RANDOM).add(BigInteger.ONE);
    }

    private static void writePrivateKey(Path path, PrivateKey privateKey) throws Exception {
        writePem(path, "PRIVATE KEY", privateKey.getEncoded());
    }

    private static void writePem(Path path, String type, byte[] der) throws Exception {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        Files.writeString(
            path,
            "-----BEGIN " + type + "-----\n" + body
                + "\n-----END " + type + "-----\n",
            StandardCharsets.US_ASCII
        );
    }
}

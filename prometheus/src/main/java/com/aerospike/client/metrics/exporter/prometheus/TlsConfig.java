/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TLS configuration for an exporter-managed Prometheus endpoint.
 *
 * <p>PEM certificate chains and unencrypted PKCS#8 or RSA PKCS#1 private keys
 * are accepted. Client CA material is required when client certificates are
 * mandatory.</p>
 */
public final class TlsConfig {
    public static final String DEFAULT_MINIMUM_VERSION = "TLSv1.2";
    public static final String DEFAULT_MAXIMUM_VERSION = "TLSv1.3";

    private static final Map<String, Integer> VERSION_ORDER =
        versionOrder();

    private final Path certificatePath;
    private final Path privateKeyPath;
    private final Path clientCaPath;
    private final boolean clientCertificateRequired;
    private final String minimumVersion;
    private final String maximumVersion;

    private TlsConfig(Builder builder) {
        certificatePath = builder.certificatePath;
        privateKeyPath = builder.privateKeyPath;
        clientCaPath = builder.clientCaPath;
        clientCertificateRequired = builder.clientCertificateRequired;
        minimumVersion = builder.minimumVersion;
        maximumVersion = builder.maximumVersion;
        validate();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Path getCertificatePath() {
        return certificatePath;
    }

    public Path getPrivateKeyPath() {
        return privateKeyPath;
    }

    public Path getClientCaPath() {
        return clientCaPath;
    }

    public boolean isClientCertificateRequired() {
        return clientCertificateRequired;
    }

    public String getMinimumVersion() {
        return minimumVersion;
    }

    public String getMaximumVersion() {
        return maximumVersion;
    }

    private void validate() {
        validateReadableFile(certificatePath, "server certificate");
        validateReadableFile(privateKeyPath, "server private key");
        if (clientCaPath != null) {
            validateReadableFile(clientCaPath, "client CA");
        }
        if (clientCertificateRequired && clientCaPath == null) {
            throw new IllegalArgumentException(
                "client CA is required when mTLS client authentication is enabled"
            );
        }

        Integer minimum = VERSION_ORDER.get(minimumVersion);
        Integer maximum = VERSION_ORDER.get(maximumVersion);
        if (minimum == null) {
            throw new IllegalArgumentException(
                "unsupported minimum TLS version: " + minimumVersion
            );
        }
        if (maximum == null) {
            throw new IllegalArgumentException(
                "unsupported maximum TLS version: " + maximumVersion
            );
        }
        if (minimum > maximum) {
            throw new IllegalArgumentException(
                "minimum TLS version must not exceed maximum TLS version"
            );
        }
    }

    private static void validateReadableFile(Path path, String description) {
        if (path == null) {
            throw new IllegalArgumentException(
                description + " path must not be null"
            );
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException(
                description + " is not a readable file: " + path
            );
        }
    }

    private static Map<String, Integer> versionOrder() {
        Map<String, Integer> versions = new LinkedHashMap<String, Integer>();
        versions.put("TLSv1", 10);
        versions.put("TLSv1.1", 11);
        versions.put("TLSv1.2", 12);
        versions.put("TLSv1.3", 13);
        return Collections.unmodifiableMap(versions);
    }

    public static final class Builder {
        private Path certificatePath;
        private Path privateKeyPath;
        private Path clientCaPath;
        private boolean clientCertificateRequired;
        private String minimumVersion = DEFAULT_MINIMUM_VERSION;
        private String maximumVersion = DEFAULT_MAXIMUM_VERSION;

        private Builder() {
        }

        public Builder setCertificatePath(Path certificatePath) {
            this.certificatePath = certificatePath;
            return this;
        }

        public Builder setPrivateKeyPath(Path privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
            return this;
        }

        public Builder setClientCaPath(Path clientCaPath) {
            this.clientCaPath = clientCaPath;
            return this;
        }

        public Builder setClientCertificateRequired(
                boolean clientCertificateRequired
        ) {
            this.clientCertificateRequired = clientCertificateRequired;
            return this;
        }

        public Builder setMinimumVersion(String minimumVersion) {
            this.minimumVersion = minimumVersion;
            return this;
        }

        public Builder setMaximumVersion(String maximumVersion) {
            this.maximumVersion = maximumVersion;
            return this;
        }

        public TlsConfig build() {
            return new TlsConfig(this);
        }
    }
}

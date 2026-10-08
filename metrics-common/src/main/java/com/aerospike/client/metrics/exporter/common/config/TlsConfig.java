/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config;

import java.nio.file.Path;

/**
 * Backend-neutral PEM file locations. Files are loaded once at startup in v1.
 */
public final class TlsConfig {
    /**
     * Client-certificate policy for the Prometheus HTTPS endpoint.
     */
    public enum ClientAuth {
        NONE,
        OPTIONAL,
        REQUIRED
    }

    private final Path caFile;
    private final Path certificateFile;
    private final Path privateKeyFile;
    private final String privateKeyPassword;
    private final ClientAuth clientAuth;

    /**
     * Create immutable TLS file configuration.
     *
     * <p>The certificate and private key form one identity and must therefore
     * be configured together. Client authentication additionally requires a
     * CA used to verify presented certificates.</p>
     */
    public TlsConfig(
            Path caFile,
            Path certificateFile,
            Path privateKeyFile,
            String privateKeyPassword,
            ClientAuth clientAuth
    ) {
        if ((certificateFile == null) != (privateKeyFile == null)) {
            throw new IllegalArgumentException("TLS certificate and private key must be configured together");
        }
        if (clientAuth != null && clientAuth != ClientAuth.NONE && caFile == null) {
            throw new IllegalArgumentException("TLS client authentication requires a CA file");
        }
        this.caFile = caFile;
        this.certificateFile = certificateFile;
        this.privateKeyFile = privateKeyFile;
        this.privateKeyPassword = privateKeyPassword;
        this.clientAuth = clientAuth == null ? ClientAuth.NONE : clientAuth;
    }

    public Path caFile() {
        return caFile;
    }

    public Path certificateFile() {
        return certificateFile;
    }

    public Path privateKeyFile() {
        return privateKeyFile;
    }

    public String privateKeyPassword() {
        return privateKeyPassword;
    }

    public ClientAuth clientAuth() {
        return clientAuth;
    }

    /**
     * Return whether a certificate/private-key identity is configured.
     */
    public boolean hasIdentity() {
        return certificateFile != null;
    }

    /**
     * Return whether a custom CA trust source is configured.
     */
    public boolean hasCustomTrust() {
        return caFile != null;
    }
}

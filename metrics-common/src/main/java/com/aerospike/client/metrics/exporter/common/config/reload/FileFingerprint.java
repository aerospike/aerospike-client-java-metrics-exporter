/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config.reload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Immutable identity of one watched file: resolved location, modification
 * time, size and content digest.
 *
 * <p>The digest is what makes credential rotation detectable. Kubernetes
 * replaces a mounted secret by swapping the {@code ..data} symlink rather than
 * editing the file, and some tools rewrite a file without advancing its
 * modification time.</p>
 */
public final class FileFingerprint {
    /** Watched files are credentials and small YAML, so anything larger is a misconfiguration. */
    static final long MAX_BYTES = 1024L * 1024L;

    private final String resolvedPath;
    private final long lastModifiedMillis;
    private final long size;
    private final String digest;

    private FileFingerprint(
            String resolvedPath,
            long lastModifiedMillis,
            long size,
            String digest
    ) {
        this.resolvedPath = resolvedPath;
        this.lastModifiedMillis = lastModifiedMillis;
        this.size = size;
        this.digest = digest;
    }

    public static FileFingerprint of(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }

        // Resolving symlinks is what surfaces a Kubernetes secret rotation.
        Path resolved = path.toRealPath();
        long size = Files.size(resolved);
        if (size > MAX_BYTES) {
            throw new IOException(
                "Watched file is larger than " + MAX_BYTES + " bytes: " + resolved
            );
        }
        return new FileFingerprint(
            resolved.toString(),
            Files.getLastModifiedTime(resolved).toMillis(),
            size,
            digest(Files.readAllBytes(resolved))
        );
    }

    public String resolvedPath() {
        return resolvedPath;
    }

    /**
     * Compares content only. Location and timestamp are carried for diagnostics
     * but deliberately excluded: Kubernetes rewrites a mounted secret on every
     * sync even when nothing changed, and treating that as a change would mean
     * a pointless reload each time.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FileFingerprint)) {
            return false;
        }
        return digest.equals(((FileFingerprint) other).digest);
    }

    @Override
    public int hashCode() {
        return digest.hashCode();
    }

    @Override
    public String toString() {
        return "FileFingerprint[" + resolvedPath + ", " + size + " bytes, sha256="
            + digest.substring(0, 12) + "]";
    }

    private static String digest(byte[] content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder text = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                text.append(Character.forDigit((value >> 4) & 0xf, 16));
                text.append(Character.forDigit(value & 0xf, 16));
            }
            return text.toString();
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }
}

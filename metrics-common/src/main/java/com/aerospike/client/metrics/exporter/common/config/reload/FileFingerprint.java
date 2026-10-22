/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config.reload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Immutable identity of one watched file: resolved location, size and content
 * digest.
 *
 * <p>The digest is what makes credential rotation detectable. Kubernetes
 * replaces a mounted secret by swapping the {@code ..data} symlink rather than
 * editing the file.</p>
 */
public final class FileFingerprint {
    /** Watched files are credentials and small YAML, so anything larger is a misconfiguration. */
    static final long MAX_BYTES = 1024L * 1024L;

    private final String resolvedPath;
    private final long size;
    private final String digest;

    private FileFingerprint(String resolvedPath, long size, String digest) {
        this.resolvedPath = resolvedPath;
        this.size = size;
        this.digest = digest;
    }

    public static FileFingerprint of(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }

        // Resolving symlinks is what surfaces a Kubernetes secret rotation.
        Path resolved = path.toRealPath();
        long reportedSize = Files.size(resolved);
        if (reportedSize > MAX_BYTES) {
            throw new IOException(
                "Watched file is larger than " + MAX_BYTES + " bytes: " + resolved
            );
        }
        DigestResult content = digest(resolved);
        return new FileFingerprint(
            resolved.toString(),
            content.size(),
            content.digest()
        );
    }

    public String resolvedPath() {
        return resolvedPath;
    }

    /**
     * Compares content only. The resolved location is carried for diagnostics
     * but deliberately excluded: Kubernetes can repoint a mounted secret
     * symlink without changing its contents, which should not trigger reload.
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

    private static DigestResult digest(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long bytesRead = 0L;
            byte[] buffer = new byte[8192];
            try (InputStream input = Files.newInputStream(path)) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    bytesRead += count;
                    if (bytesRead > MAX_BYTES) {
                        throw new IOException(
                            "Watched file is larger than " + MAX_BYTES + " bytes: " + path
                        );
                    }
                    digest.update(buffer, 0, count);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder text = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                text.append(Character.forDigit((value >> 4) & 0xf, 16));
                text.append(Character.forDigit(value & 0xf, 16));
            }
            return new DigestResult(bytesRead, text.toString());
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }

    private record DigestResult(long size, String digest) {
    }
}

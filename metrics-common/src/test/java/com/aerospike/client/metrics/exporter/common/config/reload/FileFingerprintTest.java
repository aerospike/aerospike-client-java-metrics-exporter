/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config.reload;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileFingerprintTest {
    @Test
    void unchangedFileKeepsTheSameFingerprint(@TempDir Path directory) throws IOException {
        Path secret = Files.write(directory.resolve("password"), "first".getBytes(UTF_8));

        assertEquals(FileFingerprint.of(secret), FileFingerprint.of(secret));
    }

    @Test
    void detectsContentChangeThatLeavesTheModificationTimeAlone(
            @TempDir Path directory
    ) throws IOException {
        Path secret = Files.write(directory.resolve("password"), "first".getBytes(UTF_8));
        FileFingerprint before = FileFingerprint.of(secret);
        FileTime originalTime = Files.getLastModifiedTime(secret);

        Files.write(secret, "second".getBytes(UTF_8));
        Files.setLastModifiedTime(secret, originalTime);

        // A timestamp comparison alone would miss this; the digest does not.
        assertNotEquals(before, FileFingerprint.of(secret));
    }

    @Test
    void detectsKubernetesStyleSymlinkSwap(@TempDir Path directory) throws IOException {
        // Mirrors how kubelet rotates a mounted Secret: write a new timestamped
        // directory, then atomically repoint the ..data symlink.
        Path firstRevision = Files.createDirectory(directory.resolve("..2026_01_01"));
        Files.write(firstRevision.resolve("tls.crt"), "first".getBytes(UTF_8));
        Path data = Files.createSymbolicLink(directory.resolve("..data"), firstRevision);
        Path mounted = Files.createSymbolicLink(
            directory.resolve("tls.crt"),
            data.resolve("tls.crt")
        );
        FileFingerprint before = FileFingerprint.of(mounted);

        Path secondRevision = Files.createDirectory(directory.resolve("..2026_01_02"));
        Files.write(secondRevision.resolve("tls.crt"), "second".getBytes(UTF_8));
        Files.delete(data);
        Files.createSymbolicLink(data, secondRevision);

        assertNotEquals(before, FileFingerprint.of(mounted));
    }

    @Test
    void rejectsAFileLargerThanTheCap(@TempDir Path directory) throws IOException {
        Path oversized = Files.write(
            directory.resolve("oversized"),
            new byte[(int) FileFingerprint.MAX_BYTES + 1]
        );

        assertThrows(IOException.class, () -> FileFingerprint.of(oversized));
    }

    @Test
    void failsWhenTheFileIsMissing(@TempDir Path directory) {
        assertThrows(
            IOException.class,
            () -> FileFingerprint.of(directory.resolve("absent"))
        );
    }

    @Test
    void followsSymlinkToReportTheResolvedLocation(@TempDir Path directory) throws IOException {
        Path target = Files.write(directory.resolve("real"), "value".getBytes(UTF_8));
        Path link = Files.createSymbolicLink(directory.resolve("link"), target);

        assertEquals(
            target.toRealPath().toString(),
            FileFingerprint.of(link).resolvedPath()
        );
    }

    @Test
    void rewritingIdenticalContentIsNotAChange(@TempDir Path directory) throws IOException {
        Path secret = Files.write(directory.resolve("password"), "same".getBytes(UTF_8));
        Path replacement = Files.write(directory.resolve("staged"), "same".getBytes(UTF_8));
        FileFingerprint before = FileFingerprint.of(secret);

        // Kubernetes rewrites mounted secrets on every sync, which moves the
        // timestamp without changing anything that matters.
        Files.copy(replacement, secret, StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(secret, FileTime.fromMillis(System.currentTimeMillis() + 60_000L));

        assertEquals(before, FileFingerprint.of(secret));
    }
}

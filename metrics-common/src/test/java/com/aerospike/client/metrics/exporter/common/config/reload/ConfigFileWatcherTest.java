/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config.reload;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFileWatcherTest {
    private static final Duration INTERVAL = Duration.ofSeconds(1);
    private static final String THREAD_NAME = "aerospike-metrics-config-watcher";

    @Test
    void reportsAChangeOnlyAfterItHasSettled(@TempDir Path directory) throws IOException {
        Path secret = Files.write(directory.resolve("password"), "first".getBytes(UTF_8));
        List<Set<Path>> reported = new ArrayList<Set<Path>>();
        ConfigFileWatcher watcher = watcher(directory, reported, secret);

        Files.write(secret, "second".getBytes(UTF_8));

        watcher.pollOnce();
        assertTrue(reported.isEmpty(), "a change must not be applied the moment it is first seen");

        watcher.pollOnce();
        assertEquals(1, reported.size());
        assertEquals(Collections.singleton(secret), reported.get(0));

        watcher.pollOnce();
        assertEquals(1, reported.size(), "a settled change must be reported once, not repeatedly");
        watcher.close();
    }

    @Test
    void ignoresAHalfWrittenFileThatIsStillChanging(@TempDir Path directory) throws IOException {
        // A truncated YAML file can still parse, so acting on one would silently
        // drop whole sections such as basic_auth.
        Path config = Files.write(directory.resolve("exporter.yml"), "a: 1\nb: 2\n".getBytes(UTF_8));
        List<Set<Path>> reported = new ArrayList<Set<Path>>();
        ConfigFileWatcher watcher = watcher(directory, reported, config);

        Files.write(config, "a: 1\n".getBytes(UTF_8));
        watcher.pollOnce();
        Files.write(config, "a: 1\nb: 9\n".getBytes(UTF_8));
        watcher.pollOnce();
        assertTrue(reported.isEmpty(), "a file still being written must not be reported");

        watcher.pollOnce();
        assertEquals(1, reported.size());
        watcher.close();
    }

    @Test
    void rewritingACertificateWithIdenticalContentIsNotReported(
            @TempDir Path directory
    ) throws IOException {
        // Vault Agent template renders, Ansible copy and redeploy scripts rewrite
        // files unconditionally. A timestamp check would call every one of those a
        // rotation and rebuild the TLS material for nothing.
        byte[] certificate = "-----BEGIN CERTIFICATE-----\nsame\n".getBytes(UTF_8);
        Path file = Files.write(directory.resolve("server.pem"), certificate);
        List<Set<Path>> reported = new ArrayList<Set<Path>>();
        ConfigFileWatcher watcher = watcher(directory, reported, file);
        FileTime originalTime = Files.getLastModifiedTime(file);

        for (int rewrite = 1; rewrite <= 3; rewrite++) {
            Files.write(file, certificate);
            Files.setLastModifiedTime(
                file,
                FileTime.fromMillis(originalTime.toMillis() + rewrite * 60_000L)
            );
            watcher.pollOnce();
            watcher.pollOnce();
        }

        assertNotEquals(
            originalTime,
            Files.getLastModifiedTime(file),
            "the timestamp really did move, so mtime alone would have fired"
        );
        assertTrue(reported.isEmpty(), "identical content is not a rotation");

        // The watcher is still live: a genuine rotation is still caught.
        Files.write(file, "-----BEGIN CERTIFICATE-----\nrotated\n".getBytes(UTF_8));
        watcher.pollOnce();
        watcher.pollOnce();

        assertEquals(1, reported.size());
        watcher.close();
    }

    @Test
    void reportsNothingWhileNoFileChanges(@TempDir Path directory) throws IOException {
        Path secret = Files.write(directory.resolve("password"), "first".getBytes(UTF_8));
        List<Set<Path>> reported = new ArrayList<Set<Path>>();
        ConfigFileWatcher watcher = watcher(directory, reported, secret);

        watcher.pollOnce();
        watcher.pollOnce();

        assertTrue(reported.isEmpty());
        watcher.close();
    }

    @Test
    void carriesTheLastKnownStateWhileAFileIsMissing(@TempDir Path directory) throws IOException {
        // Some tools rotate by deleting and recreating, so the file briefly vanishes.
        Path secret = Files.write(directory.resolve("password"), "first".getBytes(UTF_8));
        List<Set<Path>> reported = new ArrayList<Set<Path>>();
        ConfigFileWatcher watcher = watcher(directory, reported, secret);

        Files.delete(secret);
        watcher.pollOnce();
        watcher.pollOnce();
        assertTrue(reported.isEmpty(), "a missing file is not ready, not changed");

        Files.write(secret, "second".getBytes(UTF_8));
        watcher.pollOnce();
        watcher.pollOnce();
        assertEquals(1, reported.size(), "the change is reported once the file returns");
        watcher.close();
    }

    @Test
    void reportsOnlyTheFilesThatChanged(@TempDir Path directory) throws IOException {
        Path first = Files.write(directory.resolve("first"), "a".getBytes(UTF_8));
        Path second = Files.write(directory.resolve("second"), "b".getBytes(UTF_8));
        List<Set<Path>> reported = new ArrayList<Set<Path>>();
        ConfigFileWatcher watcher = watcher(directory, reported, first, second);

        Files.write(second, "c".getBytes(UTF_8));
        watcher.pollOnce();
        watcher.pollOnce();

        assertEquals(Collections.singleton(second), reported.get(0));
        watcher.close();
    }

    @Test
    void doesNotRetryUnchangedContentAfterTheListenerFails(
            @TempDir Path directory
    ) throws IOException {
        Path secret = Files.write(directory.resolve("password"), "first".getBytes(UTF_8));
        final AtomicInteger attempts = new AtomicInteger();
        ConfigFileWatcher watcher = new ConfigFileWatcher(
            "test",
            Collections.singletonList(secret),
            INTERVAL,
            changed -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("apply failed");
            }
        );

        Files.write(secret, "broken".getBytes(UTF_8));
        watcher.safePoll();
        watcher.safePoll();
        assertEquals(1, attempts.get(), "a settled change is attempted once");

        watcher.safePoll();
        watcher.safePoll();
        watcher.safePoll();
        assertEquals(1, attempts.get(), "the same bytes must not be attempted again");

        Files.write(secret, "fixed".getBytes(UTF_8));
        watcher.safePoll();
        watcher.safePoll();
        assertEquals(2, attempts.get(), "new content is a new attempt");
        watcher.close();
    }

    @Test
    void aFailingPollDoesNotStopLaterPolls(@TempDir Path directory) throws IOException {
        // A scheduled task that throws is cancelled forever and logs nothing,
        // which would silently disable reload.
        Path secret = Files.write(directory.resolve("password"), "first".getBytes(UTF_8));
        final CountDownLatch calls = new CountDownLatch(2);
        ConfigFileWatcher watcher = new ConfigFileWatcher(
            "test",
            Collections.singletonList(secret),
            INTERVAL,
            changed -> {
                calls.countDown();
                throw new IllegalStateException("listener failure");
            }
        );

        Files.write(secret, "second".getBytes(UTF_8));
        watcher.safePoll();
        watcher.safePoll();

        Files.write(secret, "third".getBytes(UTF_8));
        watcher.safePoll();
        watcher.safePoll();

        assertEquals(0L, calls.getCount(), "the watcher must survive a listener that throws");
        watcher.close();
    }

    @Test
    void closeStopsFurtherPolling(@TempDir Path directory) throws Exception {
        Path secret = Files.write(directory.resolve("password"), "first".getBytes(UTF_8));
        List<Set<Path>> reported = new ArrayList<Set<Path>>();
        ConfigFileWatcher watcher = watcher(directory, reported, secret);
        watcher.start();
        watcher.close();

        Files.write(secret, "second".getBytes(UTF_8));
        watcher.pollOnce();
        watcher.pollOnce();

        assertTrue(reported.isEmpty());
        assertEquals(0, ConfigFileWatcher.running());
        assertTrue(
            noThreadNamed(THREAD_NAME),
            "the shared thread must not outlive the last watcher"
        );
    }

    @Test
    void everyWatcherSharesOneThread(@TempDir Path directory) throws Exception {
        // Exporters are shared across client libraries, so watching must not add
        // a thread per exporter.
        Path first = Files.write(directory.resolve("first"), "a".getBytes(UTF_8));
        Path second = Files.write(directory.resolve("second"), "b".getBytes(UTF_8));
        ConfigFileWatcher prometheus = new ConfigFileWatcher(
            "prometheus",
            Collections.singletonList(first),
            INTERVAL,
            changed -> { }
        );
        ConfigFileWatcher otel = new ConfigFileWatcher(
            "otel",
            Collections.singletonList(second),
            INTERVAL,
            changed -> { }
        );

        prometheus.start();
        otel.start();
        try {
            assertEquals(2, ConfigFileWatcher.running());
            assertEquals(1, threadsNamed(THREAD_NAME), "two watchers, one thread");
        }
        finally {
            prometheus.close();
            assertEquals(1, ConfigFileWatcher.running());
            assertEquals(
                1,
                threadsNamed(THREAD_NAME),
                "the thread must survive until the last watcher closes"
            );
            otel.close();
        }

        assertEquals(0, ConfigFileWatcher.running());
        assertTrue(noThreadNamed(THREAD_NAME));
    }

    @Test
    void detectsAChangeOnItsOwnSchedule(@TempDir Path directory) throws Exception {
        Path secret = Files.write(directory.resolve("password"), "first".getBytes(UTF_8));
        final CountDownLatch detected = new CountDownLatch(1);
        ConfigFileWatcher watcher = new ConfigFileWatcher(
            "test",
            Collections.singletonList(secret),
            INTERVAL,
            changed -> detected.countDown()
        );
        watcher.start();
        try {
            Files.write(secret, "second".getBytes(UTF_8));

            // One interval to see it, one more for it to settle.
            assertTrue(detected.await(15L, TimeUnit.SECONDS));
        }
        finally {
            watcher.close();
        }
    }

    private static ConfigFileWatcher watcher(
            Path directory,
            List<Set<Path>> reported,
            Path... files
    ) {
        return new ConfigFileWatcher(
            directory.getFileName().toString(),
            Arrays.asList(files),
            INTERVAL,
            reported::add
        );
    }

    private static boolean noThreadNamed(String prefix) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (threadsNamed(prefix) == 0) {
                return true;
            }
            Thread.sleep(100L);
        }
        return false;
    }

    private static int threadsNamed(String prefix) {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith(prefix) && thread.isAlive()) {
                count++;
            }
        }
        return count;
    }
}

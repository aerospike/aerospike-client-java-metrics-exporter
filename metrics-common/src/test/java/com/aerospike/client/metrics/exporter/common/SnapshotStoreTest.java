/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.aerospike.client.metrics.MetricsSnapshot;

class SnapshotStoreTest {
    @Test
    void startsAtNinetySecondsAndOmitsStaleData() {
        AtomicLong clock = new AtomicLong(1L);
        SnapshotFreshnessPolicy policy = SnapshotFreshnessPolicy.automatic();
        SnapshotStore store = new SnapshotStore(policy, clock::get);
        MetricsSnapshot snapshot = snapshot();

        store.update(snapshot);
        assertSame(snapshot, store.currentIfFresh());
        assertEquals(Duration.ofSeconds(90), policy.staleTimeout());

        clock.addAndGet(Duration.ofSeconds(91).toNanos());
        assertNull(store.currentIfFresh());
        assertTrue(store.isStale());
    }

    @Test
    void adaptsAfterFirstObservedIntervalAndResumes() {
        AtomicLong clock = new AtomicLong(1L);
        SnapshotFreshnessPolicy policy = SnapshotFreshnessPolicy.automatic();
        SnapshotStore store = new SnapshotStore(policy, clock::get);

        store.update(snapshot());
        clock.addAndGet(Duration.ofSeconds(10).toNanos());
        store.update(snapshot());

        assertEquals(Duration.ofSeconds(30), policy.staleTimeout());

        clock.addAndGet(Duration.ofSeconds(31).toNanos());
        assertNull(store.currentIfFresh());

        store.update(snapshot());
        assertFalse(store.isStale());
        assertEquals(Duration.ofSeconds(90), policy.staleTimeout());
    }

    @Test
    void fixedTimeoutDoesNotAdapt() {
        AtomicLong clock = new AtomicLong(1L);
        SnapshotFreshnessPolicy policy = SnapshotFreshnessPolicy.fixed(Duration.ofSeconds(5));
        SnapshotStore store = new SnapshotStore(policy, clock::get);

        store.update(snapshot());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(4));
        store.update(snapshot());

        assertEquals(Duration.ofSeconds(5), policy.staleTimeout());
    }

    @Test
    void concurrentUpdatesAndReadsRemainConsistent() throws Exception {
        AtomicLong clock = new AtomicLong(1L);
        SnapshotStore store = new SnapshotStore(
            SnapshotFreshnessPolicy.fixed(Duration.ofMinutes(1)),
            clock::get
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Future<?> writer = executor.submit(() -> {
                await(start);
                for (int i = 0; i < 1_000; i++) {
                    clock.incrementAndGet();
                    store.update(snapshot(i));
                }
            });
            java.util.concurrent.Future<?> reader = executor.submit(() -> {
                await(start);
                for (int i = 0; i < 1_000; i++) {
                    MetricsSnapshot current = store.currentIfFresh();
                    if (current != null) {
                        assertTrue(current.retryCount >= 0);
                    }
                }
            });
            start.countDown();
            writer.get(5, TimeUnit.SECONDS);
            reader.get(5, TimeUnit.SECONDS);
            assertEquals(999, store.currentIfFresh().retryCount);
        }
        finally {
            executor.shutdownNow();
        }
    }

    private static MetricsSnapshot snapshot() {
        return snapshot(0);
    }

    private static MetricsSnapshot snapshot(long retryCount) {
        return new MetricsSnapshot(
            Instant.now(),
            false,
            "test",
            "java",
            "10.4.0",
            "app",
            Collections.emptyMap(),
            0,
            0,
            retryCount,
            0,
            0,
            0,
            0,
            0,
            0.0,
            0,
            0,
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            MetricsSnapshot.HistogramType.LOGARITHMIC,
            MetricsSnapshot.LatencyUnit.MILLISECONDS,
            2,
            7
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}

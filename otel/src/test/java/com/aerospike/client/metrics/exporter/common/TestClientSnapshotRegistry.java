/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.aerospike.client.metrics.LatencyUnit;
import com.aerospike.client.metrics.MetricsSnapshot;
import com.aerospike.client.metrics.UsageMetricsSnapshot;

class TestClientSnapshotRegistry {
    @Test
    void validatesClientIdAndLabelsBeforeMutation() {
        ClientSnapshotRegistry registry = new ClientSnapshotRegistry();

        assertThrows(IllegalArgumentException.class, () -> registry.update(
            snapshot(Collections.emptyMap(), true, 1)
        ));
        assertThrows(IllegalArgumentException.class, () -> registry.update(
            snapshot(Map.of("client_id", " "), true, 1)
        ));
        assertThrows(IllegalArgumentException.class, () -> registry.update(
            snapshot(Map.of("client_id", "primary", "bad.label", "value"), true, 1)
        ));

        assertEquals(0, registry.size());
    }

    @Test
    void storesImmutableSnapshotsDirectlyPerClient() {
        ClientSnapshotRegistry registry = new ClientSnapshotRegistry();
        MetricsSnapshot primary = snapshot(labels("primary"), true, 1);

        registry.update(primary);
        registry.update(snapshot(labels("reporting"), true, 2));

        assertEquals(2, registry.size());
        MetricsSnapshot retained = find(registry.currentSnapshots(), "primary");
        assertSame(primary, retained);
        assertEquals(1, retained.commandRetries);
        assertEquals(2, find(registry.currentSnapshots(), "reporting").commandRetries);
    }

    @Test
    void retainsDisabledSnapshot() {
        ClientSnapshotRegistry registry = new ClientSnapshotRegistry();
        MetricsSnapshot disabled = snapshot(labels("primary"), false, 2);
        registry.update(disabled);

        assertEquals(1, registry.size());
        assertSame(disabled, find(registry.currentSnapshots(), "primary"));
    }

    @Test
    void reEnableReplacesDisabledSnapshot() {
        ClientSnapshotRegistry registry = new ClientSnapshotRegistry();
        registry.update(snapshot(labels("primary"), true, 1));
        registry.update(snapshot(labels("primary"), false, 2));
        MetricsSnapshot reEnabled = snapshot(labels("primary"), true, 3);
        registry.update(reEnabled);

        assertEquals(1, registry.size());
        assertSame(reEnabled, find(registry.currentSnapshots(), "primary"));
        assertEquals(3, find(registry.currentSnapshots(), "primary").commandRetries);
    }

    @Test
    void acceptsConcurrentUpdatesFromDifferentClients() throws Exception {
        ClientSnapshotRegistry registry = new ClientSnapshotRegistry();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var primary = executor.submit(() -> updateMany(registry, start, "primary"));
            var reporting = executor.submit(() -> updateMany(registry, start, "reporting"));
            start.countDown();
            primary.get(5, TimeUnit.SECONDS);
            reporting.get(5, TimeUnit.SECONDS);
        }
        finally {
            executor.shutdownNow();
        }

        assertEquals(2, registry.size());
        assertEquals(999, find(registry.currentSnapshots(), "primary").commandRetries);
        assertEquals(999, find(registry.currentSnapshots(), "reporting").commandRetries);
    }

    private static void updateMany(
            ClientSnapshotRegistry registry,
            CountDownLatch start,
            String clientId
    ) {
        await(start);
        for (int index = 0; index < 1_000; index++) {
            registry.update(snapshot(labels(clientId), true, index));
        }
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

    private static Map<String, String> labels(String clientId) {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("client_id", clientId);
        labels.put("service", "orders");
        return labels;
    }

    private static MetricsSnapshot find(
            Collection<MetricsSnapshot> snapshots,
            String clientId
    ) {
        return snapshots.stream()
            .filter(snapshot -> clientId.equals(snapshot.labels.get("client_id")))
            .findFirst()
            .orElseThrow();
    }

    private static MetricsSnapshot snapshot(
            Map<String, String> labels,
            boolean enabled,
            long retries
    ) {
        return new MetricsSnapshot(
            Instant.now(),
            enabled,
            true,
            true,
            "cluster",
            "java",
            "10.4.0",
            "app",
            labels,
            1,
            0,
            0,
            0,
            0,
            0,
            retries,
            0,
            0,
            0,
            0.0,
            0,
            null,
            null,
            null,
            null,
            0,
            0,
            0,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            new UsageMetricsSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            LatencyUnit.MILLISECONDS,
            7,
            1
        );
    }
}

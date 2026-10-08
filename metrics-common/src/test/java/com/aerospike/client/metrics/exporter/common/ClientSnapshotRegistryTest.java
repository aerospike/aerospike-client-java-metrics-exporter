/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.aerospike.client.metrics.MetricsSnapshot;

class ClientSnapshotRegistryTest {
    @Test
    void requiresAStableClientIdLabel() {
        ClientSnapshotRegistry registry = registry(new AtomicLong(1L));

        assertThrows(
            IllegalArgumentException.class,
            () -> registry.update(snapshot(null, 1))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.update(snapshot(Collections.emptyMap(), 1))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.update(snapshot(
                Collections.singletonMap(ClientSnapshotRegistry.CLIENT_ID, " "),
                1
            ))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.update(snapshot(
                Collections.singletonMap(ClientSnapshotRegistry.CLIENT_ID, " primary "),
                1
            ))
        );
        assertEquals(0, registry.size());
    }

    @Test
    void acceptsValidClientWideLabelsAndUnrestrictedValues() {
        ClientSnapshotRegistry registry = registry(new AtomicLong(1L));
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put(ClientSnapshotRegistry.CLIENT_ID, "primary");
        labels.put("deployment_region", "prod.us-east/blue");
        labels.put("_team", "payments-core");

        registry.update(snapshot(labels, 1));

        assertEquals(1, registry.size());
        assertTrue(registry.knownGlobalLabelNames().contains("deployment_region"));
        assertTrue(registry.knownGlobalLabelNames().contains("_team"));
    }

    @Test
    void rejectsInvalidOrNullCustomLabelsBeforeMutation() {
        ClientSnapshotRegistry registry = registry(new AtomicLong(1L));
        Map<String, String> invalidName = labels("primary", "orders");
        invalidName.put("deployment.region", "us-east");
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.update(snapshot(invalidName, 1))
        );

        Map<String, String> leadingNumber = labels("primary", "orders");
        leadingNumber.put("1region", "us-east");
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.update(snapshot(leadingNumber, 1))
        );

        Map<String, String> nullName = labels("primary", "orders");
        nullName.put(null, "us-east");
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.update(snapshot(nullName, 1))
        );

        Map<String, String> nullValue = labels("primary", "orders");
        nullValue.put("region", null);
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.update(snapshot(nullValue, 1))
        );

        assertEquals(0, registry.size());
        assertTrue(registry.knownGlobalLabelNames().isEmpty());
    }

    @Test
    void rejectsExporterOwnedLabelNamesBeforeMutation() {
        ClientSnapshotRegistry registry = registry(new AtomicLong(1L));
        for (String reserved : Arrays.asList(
                "cluster",
                "cluster_name",
                "client_type",
                "client_version",
                "app_id",
                "node",
                "node_address",
                "node_port",
                "conn_type",
                "namespace",
                "le"
        )) {
            Map<String, String> labels = labels("primary", "orders");
            labels.put(reserved, "spoofed");
            assertThrows(
                IllegalArgumentException.class,
                () -> registry.update(snapshot(labels, 1)),
                reserved
            );
        }

        assertEquals(0, registry.size());
        assertTrue(registry.knownGlobalLabelNames().isEmpty());
    }

    @Test
    void storesClientsIndependentlyAndReplacesOnlyMatchingClient() {
        ClientSnapshotRegistry registry = registry(new AtomicLong(1L));
        registry.update(snapshot(labels("primary", "orders"), 1));
        registry.update(snapshot(labels("reporting", "analytics"), 2));
        registry.update(snapshot(labels("primary", "orders"), 3));

        assertEquals(2, registry.size());
        assertEquals(3, retryCount(registry.currentSnapshots(), "primary"));
        assertEquals(2, retryCount(registry.currentSnapshots(), "reporting"));
        assertTrue(registry.knownGlobalLabelNames().contains("service"));
        assertTrue(registry.knownGlobalLabelNames().contains(ClientSnapshotRegistry.CLIENT_ID));
    }

    @Test
    void retainsStaleStoreAndResumesWithSameInstance() {
        AtomicLong clock = new AtomicLong(1L);
        ClientSnapshotRegistry registry = registry(clock);
        registry.update(snapshot(labels("primary", "orders"), 1));
        registry.update(snapshot(labels("reporting", "analytics"), 2));
        SnapshotStore primaryStore = registry.snapshotStore("primary");

        clock.addAndGet(Duration.ofSeconds(6).toNanos());
        assertTrue(registry.currentSnapshots().isEmpty());
        assertEquals(2, registry.size());

        registry.update(snapshot(labels("primary", "orders"), 4));
        assertSame(primaryStore, registry.snapshotStore("primary"));
        assertEquals(1, registry.currentSnapshots().size());
        assertEquals(4, retryCount(registry.currentSnapshots(), "primary"));
        assertEquals(2, registry.size());
    }

    @Test
    void acceptsConcurrentUpdatesFromDifferentClientDispatchers() throws Exception {
        AtomicLong clock = new AtomicLong(1L);
        ClientSnapshotRegistry registry = registry(clock);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Future<?> primary = executor.submit(() -> {
                await(start);
                for (int i = 0; i < 1_000; i++) {
                    clock.incrementAndGet();
                    registry.update(snapshot(labels("primary", "orders"), i));
                }
            });
            java.util.concurrent.Future<?> reporting = executor.submit(() -> {
                await(start);
                for (int i = 0; i < 1_000; i++) {
                    clock.incrementAndGet();
                    registry.update(snapshot(labels("reporting", "analytics"), i));
                }
            });

            start.countDown();
            primary.get(5, TimeUnit.SECONDS);
            reporting.get(5, TimeUnit.SECONDS);

            assertEquals(2, registry.size());
            assertEquals(999, retryCount(registry.currentSnapshots(), "primary"));
            assertEquals(999, retryCount(registry.currentSnapshots(), "reporting"));
        }
        finally {
            executor.shutdownNow();
        }
    }

    private static ClientSnapshotRegistry registry(AtomicLong clock) {
        return new ClientSnapshotRegistry(
            () -> SnapshotFreshnessPolicy.fixed(Duration.ofSeconds(5)),
            clock::get
        );
    }

    private static Map<String, String> labels(String clientId, String service) {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put(ClientSnapshotRegistry.CLIENT_ID, clientId);
        labels.put("service", service);
        return labels;
    }

    private static long retryCount(
            Collection<MetricsSnapshot> snapshots,
            String clientId
    ) {
        for (MetricsSnapshot snapshot : snapshots) {
            if (clientId.equals(snapshot.labels.get(ClientSnapshotRegistry.CLIENT_ID))) {
                return snapshot.retryCount;
            }
        }
        throw new AssertionError("Missing client " + clientId);
    }

    private static MetricsSnapshot snapshot(
            Map<String, String> labels,
            long retryCount
    ) {
        return new MetricsSnapshot(
            Instant.now(),
            false,
            "test",
            "java",
            "10.4.0",
            "app",
            labels,
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

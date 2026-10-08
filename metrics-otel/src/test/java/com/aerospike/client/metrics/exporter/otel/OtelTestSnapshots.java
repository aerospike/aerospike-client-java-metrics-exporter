/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aerospike.client.metrics.LatencyType;
import com.aerospike.client.metrics.MetricsSnapshot;

final class OtelTestSnapshots {
    private OtelTestSnapshots() {
    }

    static MetricsSnapshot complete() {
        return snapshot(
            99L,
            "primary",
            List.of(
                new MetricsSnapshot.EventLoopSnapshot(2, 3),
                new MetricsSnapshot.EventLoopSnapshot(5, 7)
            )
        );
    }

    static MetricsSnapshot withCommandCount(long commandCount) {
        return snapshot(commandCount, "transport-test", Collections.emptyList());
    }

    static MetricsSnapshot withCommandCountAndReadLatency(
            long commandCount,
            long... buckets
    ) {
        Map<LatencyType, MetricsSnapshot.HistogramSnapshot> latency =
            new LinkedHashMap<LatencyType, MetricsSnapshot.HistogramSnapshot>();
        latency.put(LatencyType.READ, histogram(buckets));
        return snapshot(
            commandCount,
            "transport-test",
            Collections.emptyList(),
            latency,
            MetricsSnapshot.HistogramType.LOGARITHMIC,
            MetricsSnapshot.LatencyUnit.MILLISECONDS,
            2,
            buckets.length
        );
    }

    static MetricsSnapshot withReadLatency(long... buckets) {
        Map<LatencyType, MetricsSnapshot.HistogramSnapshot> latency =
            new LinkedHashMap<LatencyType, MetricsSnapshot.HistogramSnapshot>();
        latency.put(LatencyType.READ, histogram(buckets));
        return snapshot(
            99L,
            "latency-test",
            Collections.emptyList(),
            latency,
            MetricsSnapshot.HistogramType.LOGARITHMIC,
            MetricsSnapshot.LatencyUnit.MILLISECONDS,
            2,
            buckets.length
        );
    }

    static MetricsSnapshot linearMicroseconds() {
        Map<LatencyType, MetricsSnapshot.HistogramSnapshot> latency =
            new LinkedHashMap<LatencyType, MetricsSnapshot.HistogramSnapshot>();
        latency.put(LatencyType.READ, histogram(2, 3, 5, 7));
        return snapshot(
            99L,
            "linear-microseconds",
            Collections.emptyList(),
            latency,
            MetricsSnapshot.HistogramType.LINEAR,
            MetricsSnapshot.LatencyUnit.MICROSECONDS,
            3,
            4
        );
    }

    private static MetricsSnapshot snapshot(
            long commandCount,
            String clientId,
            List<MetricsSnapshot.EventLoopSnapshot> eventLoops
    ) {
        return snapshot(
            commandCount,
            clientId,
            eventLoops,
            compatibilityLatencies(),
            MetricsSnapshot.HistogramType.LOGARITHMIC,
            MetricsSnapshot.LatencyUnit.MILLISECONDS,
            2,
            7
        );
    }

    private static MetricsSnapshot snapshot(
            long commandCount,
            String clientId,
            List<MetricsSnapshot.EventLoopSnapshot> eventLoops,
            Map<LatencyType, MetricsSnapshot.HistogramSnapshot> latency,
            MetricsSnapshot.HistogramType histogramType,
            MetricsSnapshot.LatencyUnit latencyUnit,
            int latencyBase,
            int latencyColumns
    ) {
        MetricsSnapshot.ConnectionSnapshot sync =
            new MetricsSnapshot.ConnectionSnapshot(2, 3, 10, 5);
        MetricsSnapshot.ConnectionSnapshot async =
            new MetricsSnapshot.ConnectionSnapshot(1, 1, 4, 2);
        MetricsSnapshot.NamespaceSnapshot namespace = new MetricsSnapshot.NamespaceSnapshot(
            "test",
            7,
            2,
            1,
            100,
            200,
            latency,
            Collections.emptyMap()
        );
        MetricsSnapshot.NodeSnapshot node = new MetricsSnapshot.NodeSnapshot(
            "N1",
            "127.0.0.1",
            3000,
            sync,
            async,
            0, 0, 0, 0, 0,
            0, 0, 0, 0, 7,
            0, 0, 0, 0, 0,
            0, 0, 0, 0, 0,
            Collections.emptyMap(),
            Collections.singletonList(namespace)
        );

        return new MetricsSnapshot(
            Instant.now(),
            true,
            "prod",
            "java",
            "10.4.0",
            "orders",
            Map.of("team", "payments", "client_id", clientId),
            3,
            1,
            8,
            2,
            2,
            7,
            4,
            3,
            12.5,
            1024,
            commandCount,
            eventLoops,
            Collections.singletonList(node),
            node,
            histogramType,
            latencyUnit,
            latencyBase,
            latencyColumns
        );
    }

    private static Map<LatencyType, MetricsSnapshot.HistogramSnapshot>
            compatibilityLatencies() {
        Map<LatencyType, MetricsSnapshot.HistogramSnapshot> latency =
            new LinkedHashMap<LatencyType, MetricsSnapshot.HistogramSnapshot>();
        latency.put(LatencyType.CONN, histogram(2, 0, 1, 0, 0, 0, 0));
        latency.put(LatencyType.READ, histogram(1, 1, 1, 0, 1, 0, 0));
        latency.put(LatencyType.WRITE, histogram(0, 3, 0, 1, 0, 0, 0));
        latency.put(LatencyType.BATCH, histogram(0, 0, 2, 0, 1, 0, 0));
        latency.put(LatencyType.QUERY, histogram(0, 1, 0, 0, 0, 1, 0));
        return latency;
    }

    private static MetricsSnapshot.HistogramSnapshot histogram(long... buckets) {
        long count = 0L;
        for (long bucket : buckets) {
            count += bucket;
        }
        return new MetricsSnapshot.HistogramSnapshot(buckets, 0, 0, 0.0, count);
    }
}

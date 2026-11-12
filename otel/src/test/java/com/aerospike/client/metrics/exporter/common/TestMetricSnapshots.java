/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.aerospike.client.metrics.ConnectionMetricsSnapshot;
import com.aerospike.client.metrics.EventLoopMetricsSnapshot;
import com.aerospike.client.metrics.LatencySnapshot;
import com.aerospike.client.metrics.LatencyType;
import com.aerospike.client.metrics.LatencyUnit;
import com.aerospike.client.metrics.MetricsSnapshot;
import com.aerospike.client.metrics.NamespaceMetricsSnapshot;
import com.aerospike.client.metrics.NodeMetricsSnapshot;
import com.aerospike.client.metrics.UsageMetricsSnapshot;

final class TestMetricSnapshots {
    private TestMetricSnapshots() {
    }

    static MetricsSnapshot create(boolean operational, boolean usage) {
        return create(true, operational, usage);
    }

    static MetricsSnapshot create(
            boolean enabled,
            boolean operational,
            boolean usage
    ) {
        LatencySnapshot read = latency(LatencyType.READ, 1, 2, 3, 4);
        LatencySnapshot write = latency(LatencyType.WRITE, 2, 1, 0, 1);
        LatencySnapshot batch = latency(LatencyType.BATCH, 0, 1, 1, 0);
        LatencySnapshot query = latency(LatencyType.QUERY, 1, 0, 0, 1);
        NamespaceMetricsSnapshot namespace = new NamespaceMetricsSnapshot(
            "test", 7, 2, 3, 1, 4, 5, 100, 200,
            read, write, batch, query
        );
        NamespaceMetricsSnapshot blankNamespace = new NamespaceMetricsSnapshot(
            "", 99, 99, 99, 99, 99, 99, 99, 99,
            read, write, batch, query
        );
        ConnectionMetricsSnapshot sync =
            new ConnectionMetricsSnapshot(2, 3, 10, 5);
        ConnectionMetricsSnapshot async =
            new ConnectionMetricsSnapshot(1, 1, 4, 2);
        NodeMetricsSnapshot active = new NodeMetricsSnapshot(
            "N1", "127.0.0.1", 3000, sync, async, 7,
            latency(LatencyType.CONN, 2, 1, 0, 1),
            2, 1, 1, List.of(namespace, blankNamespace)
        );
        NodeMetricsSnapshot departed = new NodeMetricsSnapshot(
            "D1", "127.0.0.2", 3000, sync, async, 7,
            latency(LatencyType.CONN, 1, 0, 0, 0),
            1, 0, 0, List.of()
        );
        return new MetricsSnapshot(
            Instant.now(),
            enabled,
            operational,
            usage,
            "prod",
            "java",
            "10.4.0",
            "orders",
            Map.of("client_id", "primary", "team", "payments"),
            3,
            1,
            8,
            2,
            7,
            100,
            4,
            1,
            0,
            0,
            12.5,
            1024,
            read,
            write,
            batch,
            query,
            3,
            2,
            1,
            List.of(
                new EventLoopMetricsSnapshot(2, 3),
                new EventLoopMetricsSnapshot(5, 7)
            ),
            List.of(active),
            List.of(departed),
            new UsageMetricsSnapshot(11, 12, 13, 14, 15, 16, 17, 18, 19, 20),
            LatencyUnit.MILLISECONDS,
            4,
            1
        );
    }

    private static LatencySnapshot latency(LatencyType type, long... buckets) {
        long count = 0L;
        for (long bucket : buckets) {
            count += bucket;
        }
        return new LatencySnapshot(type, buckets, null, null, null, count);
    }
}

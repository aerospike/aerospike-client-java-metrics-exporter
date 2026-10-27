/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
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

final class TestSnapshots {
    private TestSnapshots() {
    }

    static MetricsSnapshot complete() {
        return create("primary", true, true, true, 99L, List.of());
    }

    static MetricsSnapshot disabled() {
        return create("primary", false, true, true, 100L, List.of());
    }

    static MetricsSnapshot forClient(String clientId, long commandCount) {
        return create(clientId, true, true, true, commandCount, List.of());
    }

    static MetricsSnapshot withGates(boolean operational, boolean usage) {
        return create("primary", true, operational, usage, 99L, List.of());
    }

    static MetricsSnapshot withDepartedNode() {
        return create("primary", true, true, true, 99L, List.of(node("D1")));
    }

    static MetricsSnapshot updated() {
        return create(
            "primary",
            true,
            true,
            true,
            109L,
            List.of(),
            labels("primary"),
            2L,
            eventLoops()
        );
    }

    static MetricsSnapshot withCollidingLabels() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("client_id", "primary");
        labels.put("cluster", "customer-cluster");
        labels.put("cluster_name", "customer-cluster-name");
        labels.put("client_type", "customer-type");
        labels.put("client_version", "customer-version");
        labels.put("app_id", "customer-app");
        labels.put("node", "customer-node");
        labels.put("node_address", "customer-address");
        labels.put("node_port", "1");
        labels.put("conn_type", "customer-connection");
        labels.put("namespace", "customer-namespace");
        labels.put("latency_type", "customer-latency");
        labels.put("latency_unit", "customer-unit");
        labels.put("le", "customer-bound");
        return create(
            "primary", true, true, true, 99L, List.of(), labels, 1L, eventLoops()
        );
    }

    static MetricsSnapshot withInvalidEventLoop() {
        return create(
            "primary",
            true,
            true,
            true,
            99L,
            List.of(),
            labels("primary"),
            1L,
            Collections.singletonList(null)
        );
    }

    private static MetricsSnapshot create(
            String clientId,
            boolean metricsEnabled,
            boolean operationalEnabled,
            boolean usageEnabled,
            long commandCount,
            List<NodeMetricsSnapshot> departed
    ) {
        return create(
            clientId,
            metricsEnabled,
            operationalEnabled,
            usageEnabled,
            commandCount,
            departed,
            labels(clientId),
            1L,
            eventLoops()
        );
    }

    private static MetricsSnapshot create(
            String clientId,
            boolean metricsEnabled,
            boolean operationalEnabled,
            boolean usageEnabled,
            long commandCount,
            List<NodeMetricsSnapshot> departed,
            Map<String, String> labels,
            long latencyMultiplier,
            List<EventLoopMetricsSnapshot> eventLoops
    ) {
        LatencySnapshot read =
            scaledLatency(LatencyType.READ, latencyMultiplier, 1, 2, 3, 4);
        LatencySnapshot write =
            scaledLatency(LatencyType.WRITE, latencyMultiplier, 2, 1, 0, 1);
        LatencySnapshot batch =
            scaledLatency(LatencyType.BATCH, latencyMultiplier, 0, 1, 1, 0);
        LatencySnapshot query =
            scaledLatency(LatencyType.QUERY, latencyMultiplier, 1, 0, 0, 1);

        NamespaceMetricsSnapshot namespace = new NamespaceMetricsSnapshot(
            "test",
            7,
            2,
            3,
            1,
            4,
            5,
            100,
            200,
            read,
            write,
            batch,
            query
        );
        ConnectionMetricsSnapshot sync =
            new ConnectionMetricsSnapshot(2, 3, 10, 5);
        ConnectionMetricsSnapshot async =
            new ConnectionMetricsSnapshot(1, 1, 4, 2);
        NodeMetricsSnapshot node = new NodeMetricsSnapshot(
            "N1",
            "127.0.0.1",
            3000,
            sync,
            async,
            7,
            scaledLatency(LatencyType.CONN, latencyMultiplier, 2, 1, 0, 1),
            2,
            1,
            1,
            List.of(namespace)
        );

        return new MetricsSnapshot(
            Instant.now(),
            metricsEnabled,
            operationalEnabled,
            usageEnabled,
            "prod",
            "java",
            "10.4.0",
            "orders",
            labels,
            3,
            1,
            8,
            2,
            7,
            commandCount,
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
            eventLoops,
            List.of(node),
            departed,
            new UsageMetricsSnapshot(11, 12, 13, 14, 15, 16, 17, 18, 19, 20),
            LatencyUnit.MILLISECONDS,
            4,
            1
        );
    }

    private static NodeMetricsSnapshot node(String name) {
        ConnectionMetricsSnapshot connections =
            new ConnectionMetricsSnapshot(0, 1, 2, 1);
        return new NodeMetricsSnapshot(
            name,
            "127.0.0.2",
            3000,
            connections,
            connections,
            2,
            latency(LatencyType.CONN, 1, 0, 0, 0),
            0,
            0,
            0,
            List.of()
        );
    }

    private static Map<String, String> labels(String clientId) {
        return Map.of("team", "payments", "client_id", clientId);
    }

    private static List<EventLoopMetricsSnapshot> eventLoops() {
        return List.of(
            new EventLoopMetricsSnapshot(2, 3),
            new EventLoopMetricsSnapshot(5, 7)
        );
    }

    private static LatencySnapshot scaledLatency(
            LatencyType type,
            long multiplier,
            long... buckets
    ) {
        long[] scaled = buckets.clone();
        for (int index = 0; index < scaled.length; index++) {
            scaled[index] *= multiplier;
        }
        return latency(type, scaled);
    }

    private static LatencySnapshot latency(LatencyType type, long... buckets) {
        long count = 0L;
        for (long bucket : buckets) {
            count += bucket;
        }
        return new LatencySnapshot(type, buckets, null, null, null, count);
    }
}

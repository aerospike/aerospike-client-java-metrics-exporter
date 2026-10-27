/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.aerospike.client.metrics.LatencySnapshot;
import com.aerospike.client.metrics.MetricsSnapshot;
import com.aerospike.client.metrics.NamespaceMetricsSnapshot;

/**
 * Maps pre-aggregated latency counts to cumulative bucket points.
 */
final class LatencyMetricMapper {
    boolean supports(MetricDescriptor descriptor) {
        return descriptor == MetricCatalog.CLUSTER_LATENCY_BUCKET
            || descriptor == MetricCatalog.NAMESPACE_LATENCY_BUCKET
            || descriptor == MetricCatalog.NODE_CONNECTION_LATENCY_BUCKET;
    }

    void record(
            MetricDescriptor descriptor,
            MetricsSnapshot snapshot,
            MetricRecorder recorder
    ) {
        if (!snapshot.operationalMetricsEnabled) {
            return;
        }
        if (descriptor == MetricCatalog.CLUSTER_LATENCY_BUCKET) {
            Map<String, String> attributes = SnapshotAttributes.global(snapshot);
            recordBuckets(recorder, descriptor, snapshot, snapshot.readLatency, attributes);
            recordBuckets(recorder, descriptor, snapshot, snapshot.writeLatency, attributes);
            recordBuckets(recorder, descriptor, snapshot, snapshot.batchLatency, attributes);
            recordBuckets(recorder, descriptor, snapshot, snapshot.queryLatency, attributes);
            return;
        }

        SnapshotMetricMapper.forEachNode(snapshot, node -> {
            if (descriptor == MetricCatalog.NODE_CONNECTION_LATENCY_BUCKET) {
                recordBuckets(
                    recorder,
                    descriptor,
                    snapshot,
                    node.connectionLatency,
                    SnapshotAttributes.node(snapshot, node, null)
                );
                return;
            }

            for (NamespaceMetricsSnapshot namespace : node.namespaces) {
                if (!SnapshotMetricMapper.exportable(namespace)) {
                    continue;
                }
                Map<String, String> attributes =
                    SnapshotAttributes.namespace(snapshot, node, namespace);
                recordBuckets(
                    recorder, descriptor, snapshot, namespace.readLatency, attributes
                );
                recordBuckets(
                    recorder, descriptor, snapshot, namespace.writeLatency, attributes
                );
                recordBuckets(
                    recorder, descriptor, snapshot, namespace.batchLatency, attributes
                );
                recordBuckets(
                    recorder, descriptor, snapshot, namespace.queryLatency, attributes
                );
            }
        });
    }

    private static void recordBuckets(
            MetricRecorder recorder,
            MetricDescriptor descriptor,
            MetricsSnapshot snapshot,
            LatencySnapshot latency,
            Map<String, String> baseAttributes
    ) {
        if (latency == null) {
            return;
        }
        long cumulative = 0L;
        long[] buckets = latency.getBucketCounts();
        for (int index = 0; index < buckets.length; index++) {
            cumulative += buckets[index];
            Map<String, String> attributes =
                new LinkedHashMap<String, String>(baseAttributes);
            attributes.put(
                SnapshotAttributes.LATENCY_TYPE,
                latency.type.name().toLowerCase(Locale.ROOT)
            );
            attributes.put(
                SnapshotAttributes.LATENCY_UNIT,
                snapshot.latencyUnit.name().toLowerCase(Locale.ROOT)
            );
            attributes.put(
                SnapshotAttributes.BUCKET_BOUND,
                index == buckets.length - 1
                    ? "+Inf"
                    : formatBound(Math.scalb(1.0, index * snapshot.latencyShift))
            );
            recorder.recordLong(descriptor, cumulative, attributes);
        }
    }

    private static String formatBound(double value) {
        long integral = (long) value;
        return integral == value ? Long.toString(integral) : Double.toString(value);
    }
}

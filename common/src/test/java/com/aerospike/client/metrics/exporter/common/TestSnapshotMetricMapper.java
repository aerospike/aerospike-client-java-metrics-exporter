/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class TestSnapshotMetricMapper {
    private final SnapshotMetricMapper mapper = new SnapshotMetricMapper();

    @Test
    void preservesStandardMetricsAndGatesOperationalMetrics() {
        assertEquals(3L, value(MetricCatalog.TOTAL_NODES, false, true));
        assertTrue(record(MetricCatalog.COMMAND_COUNT, false, true).names().isEmpty());
        assertTrue(record(MetricCatalog.CPU_PERCENT, false, true).names().isEmpty());
        assertEquals(100L, value(MetricCatalog.COMMAND_COUNT, true, true));
        assertEquals(7L, value(MetricCatalog.EVENT_LOOP_PROCESS_SIZE, true, true));
    }

    @Test
    void mapsBothConnectionPoolTypesAndDepartedNodes() {
        TestMetricRecorder recorder =
            record(MetricCatalog.CONNECTIONS_OPENED, true, true);

        assertEquals(4, recorder.points(MetricCatalog.CONNECTIONS_OPENED.name()).size());
        assertTrue(recorder.points(MetricCatalog.CONNECTIONS_OPENED.name()).stream()
            .anyMatch(point ->
                "D1".equals(point.attributes().get(SnapshotAttributes.NODE))
                    && "async".equals(
                        point.attributes().get(SnapshotAttributes.CONNECTION_TYPE)
                    )
            ));
    }

    @Test
    void filtersBlankNamespacesAndHonorsOperationalGate() {
        assertTrue(record(
            MetricCatalog.NAMESPACE_ERRORS, false, true
        ).names().isEmpty());

        TestMetricRecorder recorder = record(
            MetricCatalog.NAMESPACE_ERRORS,
            TestMetricSnapshots.withNamespaceNames(null, "", "   ", "test")
        );
        assertEquals(
            1,
            recorder.points(MetricCatalog.NAMESPACE_ERRORS.name()).size()
        );
        assertEquals(
            "test",
            recorder.points(MetricCatalog.NAMESPACE_ERRORS.name())
                .getFirst().attributes().get(SnapshotAttributes.NAMESPACE)
        );
    }

    @Test
    void activeNodesWinOverDuplicateDepartedNodes() {
        assertEquals(
            List.of("N1", "N1"),
            nodeNames(TestMetricSnapshots.withNodeNames(List.of("N1"), List.of()))
        );
        assertEquals(
            List.of("D1", "D1"),
            nodeNames(TestMetricSnapshots.withNodeNames(List.of(), List.of("D1")))
        );
        assertEquals(
            List.of("N1", "N1"),
            nodeNames(TestMetricSnapshots.withNodeNames(
                List.of("N1"), List.of("N1")
            ))
        );
        assertEquals(
            List.of("N1", "N1", "N2", "N2", "D1", "D1"),
            nodeNames(TestMetricSnapshots.withNodeNames(
                List.of("N1", "N2"), List.of("N1", "D1")
            ))
        );
    }

    @Test
    void emitsCumulativeLatencyBucketsAndHonorsOperationalGate() {
        assertTrue(record(
            MetricCatalog.CLUSTER_LATENCY_BUCKET, false, true
        ).names().isEmpty());

        TestMetricRecorder recorder =
            record(MetricCatalog.CLUSTER_LATENCY_BUCKET, true, true);
        List<TestMetricRecorder.Point> readBuckets =
            recorder.points(MetricCatalog.CLUSTER_LATENCY_BUCKET.name()).stream()
                .filter(point -> "read".equals(
                    point.attributes().get(SnapshotAttributes.LATENCY_TYPE)
                ))
                .toList();

        assertEquals(List.of(1L, 3L, 6L, 10L), readBuckets.stream()
            .map(point -> point.value().longValue())
            .toList());
        assertEquals(List.of("1", "2", "4", "+Inf"), readBuckets.stream()
            .map(point -> point.attributes().get(SnapshotAttributes.BUCKET_BOUND))
            .toList());

        TestMetricRecorder namespaces =
            record(MetricCatalog.NAMESPACE_LATENCY_BUCKET, true, true);
        assertEquals(
            16,
            namespaces.points(MetricCatalog.NAMESPACE_LATENCY_BUCKET.name()).size()
        );
    }

    @Test
    void latencyShiftControlsBucketBounds() {
        TestMetricRecorder recorder = record(
            MetricCatalog.CLUSTER_LATENCY_BUCKET,
            TestMetricSnapshots.withLatencyShift(3)
        );
        List<String> bounds = recorder
            .points(MetricCatalog.CLUSTER_LATENCY_BUCKET.name()).stream()
            .filter(point -> "read".equals(
                point.attributes().get(SnapshotAttributes.LATENCY_TYPE)
            ))
            .map(point -> point.attributes().get(SnapshotAttributes.BUCKET_BOUND))
            .toList();

        assertEquals(List.of("1", "8", "64", "+Inf"), bounds);
    }

    private long value(
            MetricDescriptor descriptor,
            boolean operational,
            boolean usage
    ) {
        return record(descriptor, operational, usage)
            .points(descriptor.name()).getFirst().value().longValue();
    }

    private TestMetricRecorder record(
            MetricDescriptor descriptor,
            boolean operational,
            boolean usage
    ) {
        return record(
            descriptor,
            TestMetricSnapshots.create(operational, usage)
        );
    }

    private TestMetricRecorder record(
            MetricDescriptor descriptor,
            com.aerospike.client.metrics.MetricsSnapshot snapshot
    ) {
        TestMetricRecorder recorder = new TestMetricRecorder();
        mapper.record(
            descriptor,
            List.of(snapshot),
            recorder
        );
        return recorder;
    }

    private List<String> nodeNames(
            com.aerospike.client.metrics.MetricsSnapshot snapshot
    ) {
        return record(MetricCatalog.CONNECTIONS_OPENED, snapshot)
            .points(MetricCatalog.CONNECTIONS_OPENED.name()).stream()
            .map(point -> point.attributes().get(SnapshotAttributes.NODE))
            .toList();
    }
}

/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

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
                .get(0).attributes().get(SnapshotAttributes.NAMESPACE)
        );
    }

    @Test
    void activeNodesWinOverDuplicateDepartedNodes() {
        assertEquals(
            Arrays.asList("N1", "N1"),
            nodeNames(TestMetricSnapshots.withNodeNames(
                Collections.singletonList("N1"),
                Collections.<String>emptyList()
            ))
        );
        assertEquals(
            Arrays.asList("D1", "D1"),
            nodeNames(TestMetricSnapshots.withNodeNames(
                Collections.<String>emptyList(),
                Collections.singletonList("D1")
            ))
        );
        assertEquals(
            Arrays.asList("N1", "N1"),
            nodeNames(TestMetricSnapshots.withNodeNames(
                Collections.singletonList("N1"),
                Collections.singletonList("N1")
            ))
        );
        assertEquals(
            Arrays.asList("N1", "N1", "N2", "N2", "D1", "D1"),
            nodeNames(TestMetricSnapshots.withNodeNames(
                Arrays.asList("N1", "N2"),
                Arrays.asList("N1", "D1")
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
                .collect(Collectors.toList());

        assertEquals(Arrays.asList(1L, 3L, 6L, 10L), readBuckets.stream()
            .map(point -> point.value().longValue())
            .collect(Collectors.toList()));
        assertEquals(Arrays.asList("1", "2", "4", "+Inf"), readBuckets.stream()
            .map(point -> point.attributes().get(SnapshotAttributes.BUCKET_BOUND))
            .collect(Collectors.toList()));

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
            .collect(Collectors.toList());

        assertEquals(Arrays.asList("1", "8", "64", "+Inf"), bounds);
    }

    private long value(
            MetricDescriptor descriptor,
            boolean operational,
            boolean usage
    ) {
        return record(descriptor, operational, usage)
            .points(descriptor.name()).get(0).value().longValue();
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
            Collections.singletonList(snapshot),
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
            .collect(Collectors.toList());
    }
}

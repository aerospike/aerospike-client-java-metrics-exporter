/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aerospike.client.metrics.MetricsSnapshot;

class SnapshotAttributesAndCatalogTest {
    @Test
    void buildsCanonicalIdentityAttributes() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("client_id", "primary");
        labels.put("z", "last");
        MetricsSnapshot snapshot = snapshot(labels);

        Map<String, String> global = SnapshotAttributes.global(snapshot);
        assertEquals("cluster", global.get("cluster"));
        assertEquals("java", global.get("client_type"));
        assertEquals("10.4.0", global.get("client_version"));
        assertEquals("app", global.get("app_id"));
        assertEquals("primary", global.get("client_id"));
    }

    @Test
    void nodeAndNamespaceAttributesAddOnlyTheirDimensions() {
        MetricsSnapshot snapshot = snapshot(Collections.singletonMap("client_id", "primary"));
        MetricsSnapshot.ConnectionSnapshot connections =
            new MetricsSnapshot.ConnectionSnapshot(1, 2, 3, 4);
        MetricsSnapshot.NodeSnapshot node = new MetricsSnapshot.NodeSnapshot(
            "N1", "127.0.0.1", 3000, connections, connections,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            Collections.emptyMap(), Collections.emptyList()
        );
        MetricsSnapshot.NamespaceSnapshot namespace =
            new MetricsSnapshot.NamespaceSnapshot(
                "test", 0, 0, 0, 0, 0, Collections.emptyMap(), Collections.emptyMap()
            );

        Map<String, String> nodeAttributes = SnapshotAttributes.node(snapshot, node, "sync");
        assertEquals("N1", nodeAttributes.get("node"));
        assertEquals("127.0.0.1", nodeAttributes.get("node_address"));
        assertEquals("3000", nodeAttributes.get("node_port"));
        assertEquals("sync", nodeAttributes.get("conn_type"));

        Map<String, String> namespaceAttributes =
            SnapshotAttributes.namespace(snapshot, node, namespace);
        assertEquals("test", namespaceAttributes.get("namespace"));
        assertFalse(namespaceAttributes.containsKey("conn_type"));
    }

    @Test
    void catalogNormalizesCounterAndHistogramNames() {
        assertEquals(
            "aerospike_client_retry_count_total",
            MetricCatalog.RETRIES.prometheusName()
        );
        assertEquals(
            "aerospike_client_total_nodes",
            MetricCatalog.TOTAL_NODES.prometheusName()
        );
        assertEquals(
            "aerospike_client_eventloop_process_size",
            MetricCatalog.EVENT_LOOP_PROCESS_SIZE.name()
        );
        assertEquals(MetricDescriptor.Type.GAUGE, MetricCatalog.EVENT_LOOP_PROCESS_SIZE.type());
        assertFalse(MetricCatalog.EVENT_LOOP_PROCESS_SIZE.extended());
        assertEquals(
            "aerospike_client_eventloop_queue_size",
            MetricCatalog.EVENT_LOOP_QUEUE_SIZE.name()
        );
        assertEquals("{commands}", MetricCatalog.EVENT_LOOP_QUEUE_SIZE.unit());
        MetricDescriptor latency = MetricCatalog.latency("read", "ms");
        assertEquals("aerospike_client_namespace_latency_read", latency.name());
        assertEquals(MetricDescriptor.Type.HISTOGRAM, latency.type());
        assertTrue(latency.extended());

        for (String operation : List.of("conn", "read", "write", "batch", "query")) {
            MetricDescriptor bucket = MetricCatalog.latencyBucket(operation);
            assertEquals(
                "aerospike_client_namespace_latency_" + operation + "_bucket",
                bucket.name()
            );
            assertEquals(MetricDescriptor.Type.COUNTER, bucket.type());
            assertEquals("{operations}", bucket.unit());
            assertTrue(bucket.extended());
        }
    }

    private static MetricsSnapshot snapshot(Map<String, String> labels) {
        return new MetricsSnapshot(
            Instant.now(), false, "cluster", "java", "10.4.0", "app", labels,
            0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0, 0,
            Collections.emptyList(), Collections.emptyList(), null,
            MetricsSnapshot.HistogramType.LOGARITHMIC,
            MetricsSnapshot.LatencyUnit.MILLISECONDS, 2, 7
        );
    }
}

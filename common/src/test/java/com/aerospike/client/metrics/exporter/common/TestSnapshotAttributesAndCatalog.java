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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.aerospike.client.metrics.ConnectionMetricsSnapshot;
import com.aerospike.client.metrics.LatencyUnit;
import com.aerospike.client.metrics.MetricsSnapshot;
import com.aerospike.client.metrics.NamespaceMetricsSnapshot;
import com.aerospike.client.metrics.NodeMetricsSnapshot;
import com.aerospike.client.metrics.UsageMetricsSnapshot;

class TestSnapshotAttributesAndCatalog {
    @Test
    void buildsScopeSpecificAttributes() {
        MetricsSnapshot snapshot = snapshot(map(
            "client_id", "primary",
            "cluster", "customer-cluster",
            "cluster_name", "customer-cluster-name",
            "client_type", "customer-type",
            "client_version", "customer-version",
            "app_id", "customer-app",
            "client_app_ip", "203.0.113.10",
            "team", "payments",
            "feature", "customer-feature",
            "node", "customer-node",
            "node_address", "customer-address",
            "node_port", "1",
            "server_node_id", "customer-server-node",
            "server_node_address", "customer-server-address",
            "server_node_port", "2",
            "conn_type", "customer-connection",
            "namespace", "customer-namespace",
            "ns", "customer-ns",
            "latency_type", "customer-latency",
            "latency_unit", "customer-unit",
            "le", "customer-bound"
        ));
        ConnectionMetricsSnapshot connections =
            new ConnectionMetricsSnapshot(1, 2, 3, 4);
        NamespaceMetricsSnapshot namespace = new NamespaceMetricsSnapshot(
            "test", 0, 0, 0, 0, 0, 0, 0, 0,
            null, null, null, null
        );
        NodeMetricsSnapshot node = new NodeMetricsSnapshot(
            "N1", "127.0.0.1", 3000, connections, connections,
            4, null, 0, 0, 0, Collections.singletonList(namespace)
        );

        Map<String, String> global = SnapshotAttributes.global(snapshot);
        assertEquals("cluster", global.get("cluster"));
        assertEquals("java", global.get("client_type"));
        assertEquals("10.4.0", global.get("client_version"));
        assertEquals("app", global.get("app_id"));
        assertEquals("primary", global.get("client_id"));
        assertEquals("203.0.113.10", global.get("client_app_ip"));
        assertEquals("payments", global.get("team"));
        assertFalse(global.containsKey("feature"));
        assertFalse(global.containsKey("cluster_name"));
        assertFalse(global.containsKey("node"));
        assertFalse(global.containsKey("server_node_id"));
        assertFalse(global.containsKey("namespace"));
        assertFalse(global.containsKey("ns"));
        assertFalse(global.containsKey("le"));

        Map<String, String> nodeAttributes =
            SnapshotAttributes.node(snapshot, node, "sync");
        assertEquals("N1", nodeAttributes.get("server_node_id"));
        assertEquals("127.0.0.1", nodeAttributes.get("server_node_address"));
        assertEquals("3000", nodeAttributes.get("server_node_port"));
        assertEquals("sync", nodeAttributes.get("conn_type"));

        Map<String, String> namespaceAttributes =
            SnapshotAttributes.namespace(snapshot, node, namespace);
        assertEquals("test", namespaceAttributes.get("ns"));
        assertFalse(namespaceAttributes.containsKey("conn_type"));
    }

    @Test
    void absentExporterIdentityDoesNotRestoreCustomerCollision() {
        MetricsSnapshot snapshot = snapshot(
            map(
                "client_id", "primary",
                "cluster", "customer-cluster",
                "client_type", "customer-type",
                "client_version", "customer-version",
                "app_id", "customer-app",
                "server_node_id", "customer-node",
                "ns", "customer-namespace"
            ),
            "",
            "",
            "",
            ""
        );

        Map<String, String> attributes = SnapshotAttributes.global(snapshot);
        assertEquals("primary", attributes.get("client_id"));
        assertFalse(attributes.containsKey("cluster"));
        assertFalse(attributes.containsKey("client_type"));
        assertFalse(attributes.containsKey("client_version"));
        assertFalse(attributes.containsKey("app_id"));
        assertFalse(attributes.containsKey("server_node_id"));
        assertFalse(attributes.containsKey("ns"));
    }

    @Test
    void catalogUsesAuthoritativeNamesAndTiers() {
        assertEquals(
            "aerospike.client.cluster.retry_count",
            MetricCatalog.COMMAND_RETRIES.name()
        );
        assertEquals(
            MetricDescriptor.Tier.OPERATIONAL,
            MetricCatalog.COMMAND_RETRIES.tier()
        );
        assertEquals(
            "aerospike.client.namespace.client_timeouts",
            MetricCatalog.NAMESPACE_CLIENT_TIMEOUTS.name()
        );
        assertEquals(
            "aerospike.client.cluster.latency_bucket",
            MetricCatalog.CLUSTER_LATENCY_BUCKET.name()
        );
        assertEquals(
            "aerospike.client.namespace.latency_bucket",
            MetricCatalog.NAMESPACE_LATENCY_BUCKET.name()
        );
        assertEquals(
            "aerospike.client.node.conn_latency_bucket",
            MetricCatalog.NODE_CONNECTION_LATENCY_BUCKET.name()
        );
        assertEquals("{retry}", MetricCatalog.COMMAND_RETRIES.unit());
        assertEquals("{connection}", MetricCatalog.CONNECTIONS_OPENED.unit());
        assertEquals("{operation}", MetricCatalog.CLUSTER_LATENCY_BUCKET.unit());
        Set<String> published = MetricCatalog.all().stream()
            .map(MetricDescriptor::name)
            .collect(Collectors.toSet());
        assertFalse(published.contains(
            "aerospike.client.cluster.batch_partial_failure"
        ));
        assertFalse(published.contains(
            "aerospike.client.cluster.validation_open_failure"
        ));
        assertFalse(published.contains(
            "aerospike.client.node.connection_open_failure"
        ));
        assertFalse(published.contains(
            "aerospike.client.namespace.server_timeouts"
        ));
        assertFalse(published.contains("aerospike.client.feature.api_blocking"));
    }

    @Test
    void catalogNamesAreUniqueAndTypesAreExplicit() {
        Set<String> names = MetricCatalog.all().stream()
            .map(MetricDescriptor::name)
            .collect(Collectors.toSet());
        assertEquals(MetricCatalog.all().size(), names.size());
        assertTrue(names.stream().allMatch(name -> name.matches(
            "aerospike\\.client\\.(cluster|eventloop|node|namespace)\\."
                + "[a-z][a-z0-9_.]*"
        )));

        Set<String> supportedUnits = new HashSet<String>(Arrays.asList(
            "{command}",
            "{connection}",
            "{error}",
            "{event}",
            "{node}",
            "{operation}",
            "{retry}",
            "{timeout}",
            "%",
            "By"
        ));
        assertTrue(MetricCatalog.all().stream()
            .allMatch(descriptor -> supportedUnits.contains(descriptor.unit())));

        assertEquals(
            MetricDescriptor.ValueType.DOUBLE,
            MetricCatalog.CPU_PERCENT.valueType()
        );
        assertEquals(
            MetricDescriptor.Type.CUMULATIVE_BUCKETS,
            MetricCatalog.CLUSTER_LATENCY_BUCKET.type()
        );
    }

    private static MetricsSnapshot snapshot(Map<String, String> labels) {
        return snapshot(labels, "cluster", "java", "10.4.0", "app");
    }

    private static MetricsSnapshot snapshot(
            Map<String, String> labels,
            String clusterName,
            String clientType,
            String clientVersion,
            String appId
    ) {
        return new MetricsSnapshot(
            Instant.now(), true, true, true,
            clusterName, clientType, clientVersion, appId,
            labels,
            0, 0, 0, 0, 0,
            0, 0, 0, 0, 0,
            0.0, 0,
            null, null, null, null,
            0, 0, 0,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            new UsageMetricsSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            LatencyUnit.MILLISECONDS, 7, 1
        );
    }

    private static Map<String, String> map(String... values) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(values[index], values[index + 1]);
        }
        return result;
    }
}

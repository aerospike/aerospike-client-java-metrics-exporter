/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.aerospike.client.metrics.MetricsSnapshot;
import com.aerospike.client.metrics.NamespaceMetricsSnapshot;
import com.aerospike.client.metrics.NodeMetricsSnapshot;

/**
 * Builds deterministic backend-neutral attributes from a snapshot.
 */
//todo: prefix with as_client
public final class SnapshotAttributes {
    public static final String CLUSTER = "cluster";
    public static final String CLIENT_TYPE = "client_type";
    public static final String CLIENT_VERSION = "client_version";
    public static final String APP_ID = "app_id";
    public static final String NODE = "node";
    public static final String NODE_ADDRESS = "node_address";
    public static final String NODE_PORT = "node_port";
    public static final String CONNECTION_TYPE = "conn_type";
    public static final String NAMESPACE = "namespace";
    public static final String LATENCY_TYPE = "latency_type";
    public static final String LATENCY_UNIT = "latency_unit";
    public static final String BUCKET_BOUND = "le";

    private static final Set<String> EXPORTER_OWNED = Set.of(
        CLUSTER,
        "cluster_name",
        CLIENT_TYPE,
        CLIENT_VERSION,
        APP_ID,
        NODE,
        NODE_ADDRESS,
        NODE_PORT,
        CONNECTION_TYPE,
        NAMESPACE,
        LATENCY_TYPE,
        LATENCY_UNIT,
        BUCKET_BOUND
    );

    private SnapshotAttributes() {
    }

    /**
     * Build client-wide attributes shared by every metric in a snapshot.
     * Exporter-owned identity fields take precedence over customer labels.
     */
    public static Map<String, String> global(MetricsSnapshot snapshot) {
        Map<String, String> attributes = new LinkedHashMap<String, String>();
        if (snapshot.labels != null) {
            attributes.putAll(new TreeMap<String, String>(snapshot.labels));
            attributes.keySet().removeAll(EXPORTER_OWNED);
        }
        putIfPresent(attributes, CLUSTER, snapshot.clusterName);
        putIfPresent(attributes, CLIENT_TYPE, snapshot.clientType);
        putIfPresent(attributes, CLIENT_VERSION, snapshot.clientVersion);
        putIfPresent(attributes, APP_ID, snapshot.appId);
        return attributes;
    }

    /**
     * Build attributes for one node connection-pool series.
     *
     * @param connectionType {@code sync}, {@code async}, or {@code null} when
     *                       the metric is not pool-specific
     */
    public static Map<String, String> node(
            MetricsSnapshot snapshot,
            NodeMetricsSnapshot node,
            String connectionType
    ) {
        Map<String, String> attributes = global(snapshot);
        putIfPresent(attributes, NODE, node.nodeName);
        putIfPresent(attributes, NODE_ADDRESS, node.nodeAddress);
        attributes.put(NODE_PORT, Integer.toString(node.nodePort));
        putIfPresent(attributes, CONNECTION_TYPE, connectionType);
        return attributes;
    }

    /**
     * Build attributes for one namespace on one node.
     */
    public static Map<String, String> namespace(
            MetricsSnapshot snapshot,
            NodeMetricsSnapshot node,
            NamespaceMetricsSnapshot namespace
    ) {
        Map<String, String> attributes = node(snapshot, node, null);
        putIfPresent(attributes, NAMESPACE, namespace.namespace);
        return attributes;
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isEmpty()) {
            target.put(key, value);
        }
    }
}

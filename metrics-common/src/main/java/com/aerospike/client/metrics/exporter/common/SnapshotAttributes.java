/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import com.aerospike.client.metrics.MetricsSnapshot;

/**
 * Builds deterministic backend-neutral attributes from a snapshot.
 */
public final class SnapshotAttributes {
    public static final String CLUSTER = "cluster_name";
    public static final String CLIENT_TYPE = "client_type";
    public static final String CLIENT_VERSION = "client_version";
    public static final String APP_ID = "app_id";
    public static final String NODE = "node";
    public static final String NODE_ADDRESS = "node_address";
    public static final String NODE_PORT = "node_port";
    public static final String CONNECTION_TYPE = "conn_type";
    public static final String NAMESPACE = "namespace";

    private SnapshotAttributes() {
    }

    public static Map<String, String> global(MetricsSnapshot snapshot) {
        Map<String, String> attributes = new LinkedHashMap<String, String>();
        if (snapshot.labels != null) {
            attributes.putAll(new TreeMap<String, String>(snapshot.labels));
        }
        // Reserved identity attributes always win over user-provided labels.
        putIfPresent(attributes, CLUSTER, snapshot.clusterName);
        putIfPresent(attributes, CLIENT_TYPE, snapshot.clientType);
        putIfPresent(attributes, CLIENT_VERSION, snapshot.clientVersion);
        putIfPresent(attributes, APP_ID, snapshot.appId);
        return attributes;
    }

    public static Map<String, String> node(
            MetricsSnapshot snapshot,
            MetricsSnapshot.NodeSnapshot node,
            String connectionType
    ) {
        Map<String, String> attributes = global(snapshot);
        putIfPresent(attributes, NODE, node.nodeName);
        putIfPresent(attributes, NODE_ADDRESS, node.nodeAddress);
        attributes.put(NODE_PORT, Integer.toString(node.nodePort));
        putIfPresent(attributes, CONNECTION_TYPE, connectionType);
        return attributes;
    }

    public static Map<String, String> namespace(
            MetricsSnapshot snapshot,
            MetricsSnapshot.NodeSnapshot node,
            MetricsSnapshot.NamespaceSnapshot namespace
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

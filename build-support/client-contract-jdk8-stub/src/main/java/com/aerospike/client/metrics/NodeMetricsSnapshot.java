/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Temporary Java 8 compile-time copy of the client-owned node snapshot.
 *
 * <p>TODO(OM-386): Remove when aerospike-client-jdk8 provides this type.</p>
 */
public final class NodeMetricsSnapshot {
    public final String nodeName;
    public final String nodeAddress;
    public final int nodePort;
    public final ConnectionMetricsSnapshot syncConnections;
    public final ConnectionMetricsSnapshot asyncConnections;
    public final long openConnections;
    public final LatencySnapshot connectionLatency;
    public final long connectionOpenFailure;
    public final long connectionTlsFailure;
    public final long connectionAuthFailure;
    public final List<NamespaceMetricsSnapshot> namespaces;

    public NodeMetricsSnapshot(
            String nodeName,
            String nodeAddress,
            int nodePort,
            ConnectionMetricsSnapshot syncConnections,
            ConnectionMetricsSnapshot asyncConnections,
            long openConnections,
            LatencySnapshot connectionLatency,
            long connectionOpenFailure,
            long connectionTlsFailure,
            long connectionAuthFailure,
            List<NamespaceMetricsSnapshot> namespaces
    ) {
        this.nodeName = nodeName;
        this.nodeAddress = nodeAddress;
        this.nodePort = nodePort;
        this.syncConnections = syncConnections;
        this.asyncConnections = asyncConnections;
        this.openConnections = openConnections;
        this.connectionLatency = connectionLatency;
        this.connectionOpenFailure = connectionOpenFailure;
        this.connectionTlsFailure = connectionTlsFailure;
        this.connectionAuthFailure = connectionAuthFailure;
        this.namespaces = Collections.unmodifiableList(
            new ArrayList<NamespaceMetricsSnapshot>(namespaces)
        );
    }

    @Override
    public String toString() {
        return "NodeMetricsSnapshot{"
            + "nodeName='" + nodeName + '\''
            + ", nodeAddress='" + nodeAddress + '\''
            + ", nodePort=" + nodePort
            + ", syncConnections=" + syncConnections
            + ", asyncConnections=" + asyncConnections
            + ", openConnections=" + openConnections
            + ", connectionLatency=" + connectionLatency
            + ", connectionOpenFailure=" + connectionOpenFailure
            + ", connectionTlsFailure=" + connectionTlsFailure
            + ", connectionAuthFailure=" + connectionAuthFailure
            + ", namespaces=" + namespaces
            + '}';
    }
}

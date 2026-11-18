/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aerospike.client.metrics.ConnectionMetricsSnapshot;
import com.aerospike.client.metrics.EventLoopMetricsSnapshot;
import com.aerospike.client.metrics.LatencySnapshot;
import com.aerospike.client.metrics.MetricsSnapshot;
import com.aerospike.client.metrics.NamespaceMetricsSnapshot;
import com.aerospike.client.metrics.NodeMetricsSnapshot;
import com.aerospike.client.metrics.UsageMetricsSnapshot;

/**
 * Creates exporter-owned snapshots while a client-owned snapshot is valid.
 */
final class MetricsSnapshotCopier {
    private MetricsSnapshotCopier() {
    }

    static MetricsSnapshot copy(MetricsSnapshot snapshot) {
        return new MetricsSnapshot(
            snapshot.timestamp,
            snapshot.metricsEnabled,
            snapshot.operationalMetricsEnabled,
            snapshot.usageMetricsEnabled,
            snapshot.clusterName,
            snapshot.clientType,
            snapshot.clientVersion,
            snapshot.appId,
            copyMap(snapshot.labels),
            snapshot.totalNodes,
            snapshot.recoverQueueSize,
            snapshot.invalidNodeCount,
            snapshot.delayQueueTimeoutCount,
            snapshot.openConnections,
            snapshot.commandCount,
            snapshot.commandRetries,
            snapshot.batchPartialFailure,
            snapshot.exceededMaxRetries,
            snapshot.exceededTotalTimeout,
            snapshot.cpuPercent,
            snapshot.memoryBytes,
            copy(snapshot.readLatency),
            copy(snapshot.writeLatency),
            copy(snapshot.batchLatency),
            copy(snapshot.queryLatency),
            snapshot.validationOpenFailure,
            snapshot.validationTlsFailure,
            snapshot.validationAuthFailure,
            copyEventLoops(snapshot.eventLoops),
            copyNodes(snapshot.nodes),
            copyNodes(snapshot.nodesDeparted),
            copy(snapshot.usage),
            snapshot.latencyUnit,
            snapshot.latencyColumns,
            snapshot.latencyShift
        );
    }

    private static Map<String, String> copyMap(Map<String, String> source) {
        return Collections.unmodifiableMap(
            new LinkedHashMap<String, String>(source)
        );
    }

    private static List<EventLoopMetricsSnapshot> copyEventLoops(
            List<EventLoopMetricsSnapshot> source
    ) {
        List<EventLoopMetricsSnapshot> result =
            new ArrayList<EventLoopMetricsSnapshot>(source.size());
        for (EventLoopMetricsSnapshot eventLoop : source) {
            result.add(eventLoop == null
                ? null
                : new EventLoopMetricsSnapshot(
                    eventLoop.processSize,
                    eventLoop.queueSize
                )
            );
        }
        return Collections.unmodifiableList(result);
    }

    private static List<NodeMetricsSnapshot> copyNodes(
            List<NodeMetricsSnapshot> source
    ) {
        List<NodeMetricsSnapshot> result =
            new ArrayList<NodeMetricsSnapshot>(source.size());
        for (NodeMetricsSnapshot node : source) {
            if (node == null) {
                result.add(null);
                continue;
            }
            result.add(new NodeMetricsSnapshot(
                node.nodeName,
                node.nodeAddress,
                node.nodePort,
                copy(node.syncConnections),
                copy(node.asyncConnections),
                node.openConnections,
                copy(node.connectionLatency),
                node.connectionOpenFailure,
                node.connectionTlsFailure,
                node.connectionAuthFailure,
                copyNamespaces(node.namespaces)
            ));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<NamespaceMetricsSnapshot> copyNamespaces(
            List<NamespaceMetricsSnapshot> source
    ) {
        List<NamespaceMetricsSnapshot> result =
            new ArrayList<NamespaceMetricsSnapshot>(source.size());
        for (NamespaceMetricsSnapshot namespace : source) {
            if (namespace == null) {
                result.add(null);
                continue;
            }
            result.add(new NamespaceMetricsSnapshot(
                namespace.namespace,
                namespace.errors,
                namespace.clientTimeouts,
                namespace.serverTimeouts,
                namespace.keyBusy,
                namespace.recordTooBig,
                namespace.deviceOverload,
                namespace.bytesIn,
                namespace.bytesOut,
                copy(namespace.readLatency),
                copy(namespace.writeLatency),
                copy(namespace.batchLatency),
                copy(namespace.queryLatency)
            ));
        }
        return Collections.unmodifiableList(result);
    }

    private static ConnectionMetricsSnapshot copy(
            ConnectionMetricsSnapshot source
    ) {
        if (source == null) {
            return null;
        }
        return new ConnectionMetricsSnapshot(
            source.inUse,
            source.inPool,
            source.opened,
            source.closed
        );
    }

    private static LatencySnapshot copy(LatencySnapshot source) {
        if (source == null) {
            return null;
        }
        return new LatencySnapshot(
            source.type,
            source.getBucketCounts().clone(),
            source.min,
            source.max,
            source.sum,
            source.count
        );
    }

    private static UsageMetricsSnapshot copy(UsageMetricsSnapshot source) {
        if (source == null) {
            return null;
        }
        return new UsageMetricsSnapshot(
            source.blocking,
            source.deferred,
            source.background,
            source.shapePoint,
            source.shapeBatch,
            source.shapeQuery,
            source.objectMappingRead,
            source.objectMappingWrite,
            source.filterAel,
            source.filterExp
        );
    }
}

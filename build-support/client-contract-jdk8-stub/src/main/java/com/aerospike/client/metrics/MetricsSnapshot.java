/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Temporary Java 8 compile-time copy of the client-owned metrics snapshot.
 *
 * <p>TODO(OM-386): Remove when aerospike-client-jdk8 provides this type.</p>
 */
public final class MetricsSnapshot {
    public final Instant timestamp;
    public final boolean metricsEnabled;
    public final boolean operationalMetricsEnabled;
    public final boolean usageMetricsEnabled;
    public final String clusterName;
    public final String clientType;
    public final String clientVersion;
    public final String appId;
    public final Map<String, String> labels;
    public final int totalNodes;
    public final int recoverQueueSize;
    public final int invalidNodeCount;
    public final long delayQueueTimeoutCount;
    public final long openConnections;
    public final long commandCount;
    public final long commandRetries;
    public final long retryCount;
    public final long batchPartialFailure;
    public final long exceededMaxRetries;
    public final long exceededTotalTimeout;
    public final double cpuPercent;
    public final long memoryBytes;
    public final LatencySnapshot readLatency;
    public final LatencySnapshot writeLatency;
    public final LatencySnapshot batchLatency;
    public final LatencySnapshot queryLatency;
    public final long validationOpenFailure;
    public final long validationTlsFailure;
    public final long validationAuthFailure;
    public final List<EventLoopMetricsSnapshot> eventLoops;
    public final List<NodeMetricsSnapshot> nodes;
    public final List<NodeMetricsSnapshot> nodesDeparted;
    public final UsageMetricsSnapshot usage;
    public final LatencyUnit latencyUnit;
    public final int latencyColumns;
    public final int latencyShift;

    public MetricsSnapshot(
            Instant timestamp,
            boolean metricsEnabled,
            boolean operationalMetricsEnabled,
            boolean usageMetricsEnabled,
            String clusterName,
            String clientType,
            String clientVersion,
            String appId,
            Map<String, String> labels,
            int totalNodes,
            int recoverQueueSize,
            int invalidNodeCount,
            long delayQueueTimeoutCount,
            long openConnections,
            long commandCount,
            long commandRetries,
            long batchPartialFailure,
            long exceededMaxRetries,
            long exceededTotalTimeout,
            double cpuPercent,
            long memoryBytes,
            LatencySnapshot readLatency,
            LatencySnapshot writeLatency,
            LatencySnapshot batchLatency,
            LatencySnapshot queryLatency,
            long validationOpenFailure,
            long validationTlsFailure,
            long validationAuthFailure,
            List<EventLoopMetricsSnapshot> eventLoops,
            List<NodeMetricsSnapshot> nodes,
            List<NodeMetricsSnapshot> nodesDeparted,
            UsageMetricsSnapshot usage,
            LatencyUnit latencyUnit,
            int latencyColumns,
            int latencyShift
    ) {
        this.timestamp = timestamp;
        this.metricsEnabled = metricsEnabled;
        this.operationalMetricsEnabled = operationalMetricsEnabled;
        this.usageMetricsEnabled = usageMetricsEnabled;
        this.clusterName = clusterName;
        this.clientType = clientType;
        this.clientVersion = clientVersion;
        this.appId = appId;
        this.labels = labels == null
            ? Collections.<String, String>emptyMap()
            : Collections.unmodifiableMap(
                new HashMap<String, String>(labels)
            );
        this.totalNodes = totalNodes;
        this.recoverQueueSize = recoverQueueSize;
        this.invalidNodeCount = invalidNodeCount;
        this.delayQueueTimeoutCount = delayQueueTimeoutCount;
        this.openConnections = openConnections;
        this.commandCount = commandCount;
        this.commandRetries = commandRetries;
        this.retryCount = commandRetries;
        this.batchPartialFailure = batchPartialFailure;
        this.exceededMaxRetries = exceededMaxRetries;
        this.exceededTotalTimeout = exceededTotalTimeout;
        this.cpuPercent = cpuPercent;
        this.memoryBytes = memoryBytes;
        this.readLatency = readLatency;
        this.writeLatency = writeLatency;
        this.batchLatency = batchLatency;
        this.queryLatency = queryLatency;
        this.validationOpenFailure = validationOpenFailure;
        this.validationTlsFailure = validationTlsFailure;
        this.validationAuthFailure = validationAuthFailure;
        this.eventLoops = Collections.unmodifiableList(
            new ArrayList<EventLoopMetricsSnapshot>(eventLoops)
        );
        this.nodes = Collections.unmodifiableList(
            new ArrayList<NodeMetricsSnapshot>(nodes)
        );
        this.nodesDeparted = Collections.unmodifiableList(
            new ArrayList<NodeMetricsSnapshot>(nodesDeparted)
        );
        this.usage = usage;
        this.latencyUnit = latencyUnit;
        this.latencyColumns = latencyColumns;
        this.latencyShift = latencyShift;
    }

    @Override
    public String toString() {
        return "MetricsSnapshot{"
            + "timestamp=" + timestamp
            + ", metricsEnabled=" + metricsEnabled
            + ", operationalMetricsEnabled=" + operationalMetricsEnabled
            + ", usageMetricsEnabled=" + usageMetricsEnabled
            + ", clusterName='" + clusterName + '\''
            + ", clientType='" + clientType + '\''
            + ", clientVersion='" + clientVersion + '\''
            + ", appId='" + appId + '\''
            + ", labels=" + labels
            + ", totalNodes=" + totalNodes
            + ", recoverQueueSize=" + recoverQueueSize
            + ", invalidNodeCount=" + invalidNodeCount
            + ", commandCount=" + commandCount
            + ", nodes=" + nodes
            + ", nodesDeparted=" + nodesDeparted
            + '}';
    }
}

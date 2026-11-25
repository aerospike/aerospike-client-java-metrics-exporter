/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import java.util.Collection;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

import com.aerospike.client.metrics.ConnectionMetricsSnapshot;
import com.aerospike.client.metrics.EventLoopMetricsSnapshot;
import com.aerospike.client.metrics.MetricsSnapshot;
import com.aerospike.client.metrics.NamespaceMetricsSnapshot;
import com.aerospike.client.metrics.NodeMetricsSnapshot;

/**
 * Converts typed client snapshots into backend-neutral metric points.
 */
public final class SnapshotMetricMapper {
    private static final String SYNC = "sync";
    private static final String ASYNC = "async";

    private static final List<LongBinding<MetricsSnapshot>> CLIENT_LONG = Arrays.asList(
        new LongBinding<>(MetricCatalog.RECOVER_QUEUE_SIZE, value -> value.recoverQueueSize),
        new LongBinding<>(MetricCatalog.TOTAL_NODES, value -> value.totalNodes),
        new LongBinding<>(MetricCatalog.OPEN_CONNECTIONS, value -> value.openConnections),
        new LongBinding<>(MetricCatalog.INVALID_NODES, value -> value.invalidNodeCount),
        new LongBinding<>(
            MetricCatalog.DELAY_QUEUE_TIMEOUTS,
            value -> value.delayQueueTimeoutCount
        ),
        new LongBinding<>(MetricCatalog.COMMAND_RETRIES, value -> value.commandRetries),
        new LongBinding<>(MetricCatalog.COMMAND_COUNT, value -> value.commandCount),
        new LongBinding<>(MetricCatalog.MEMORY_BYTES, value -> value.memoryBytes)
    );
    private static final DoubleBinding<MetricsSnapshot> CLIENT_CPU =
        new DoubleBinding<>(MetricCatalog.CPU_PERCENT, value -> value.cpuPercent);
    private static final List<LongBinding<ConnectionMetricsSnapshot>> CONNECTIONS =
        Arrays.asList(
            new LongBinding<>(MetricCatalog.CONNECTIONS_IN_USE, value -> value.inUse),
            new LongBinding<>(MetricCatalog.CONNECTIONS_IN_POOL, value -> value.inPool),
            new LongBinding<>(MetricCatalog.CONNECTIONS_OPENED, value -> value.opened),
            new LongBinding<>(MetricCatalog.CONNECTIONS_CLOSED, value -> value.closed)
        );
    private static final List<LongBinding<NamespaceMetricsSnapshot>> NAMESPACES =
        Arrays.asList(
            new LongBinding<>(MetricCatalog.NAMESPACE_ERRORS, value -> value.errors),
            new LongBinding<>(
                MetricCatalog.NAMESPACE_CLIENT_TIMEOUTS,
                value -> value.clientTimeouts
            ),
            new LongBinding<>(MetricCatalog.NAMESPACE_KEY_BUSY, value -> value.keyBusy),
            new LongBinding<>(MetricCatalog.NAMESPACE_BYTES_IN, value -> value.bytesIn),
            new LongBinding<>(MetricCatalog.NAMESPACE_BYTES_OUT, value -> value.bytesOut)
        );

    private final LatencyMetricMapper latency = new LatencyMetricMapper();

    /**
     * Record one descriptor across the supplied client snapshots.
     */
    public void record(
            MetricDescriptor descriptor,
            Collection<MetricsSnapshot> snapshots,
            MetricRecorder recorder
    ) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(recorder, "recorder");
        if (!supports(descriptor)) {
            throw new IllegalArgumentException(
                "Unsupported metric descriptor: " + descriptor.name()
            );
        }

        for (MetricsSnapshot snapshot : snapshots) {
            if (recordClient(descriptor, snapshot, recorder)
                    || recordConnections(descriptor, snapshot, recorder)
                    || recordNamespaces(descriptor, snapshot, recorder)) {
                continue;
            }
            latency.record(descriptor, snapshot, recorder);
        }
    }

    private static boolean recordClient(
            MetricDescriptor descriptor,
            MetricsSnapshot snapshot,
            MetricRecorder recorder
    ) {
        LongBinding<MetricsSnapshot> binding = find(CLIENT_LONG, descriptor);
        if (binding != null) {
            if (enabled(snapshot, descriptor)) {
                binding.record(
                    snapshot,
                    snapshot,
                    SnapshotAttributes.global(snapshot),
                    recorder
                );
            }
            return true;
        }
        if (CLIENT_CPU.matches(descriptor)) {
            if (enabled(snapshot, descriptor)) {
                CLIENT_CPU.record(
                    snapshot,
                    snapshot,
                    SnapshotAttributes.global(snapshot),
                    recorder
                );
            }
            return true;
        }
        if (descriptor == MetricCatalog.EVENT_LOOP_PROCESS_SIZE
                || descriptor == MetricCatalog.EVENT_LOOP_QUEUE_SIZE) {
            long total = 0L;
            for (EventLoopMetricsSnapshot eventLoop : snapshot.eventLoops) {
                total += descriptor == MetricCatalog.EVENT_LOOP_PROCESS_SIZE
                    ? eventLoop.processSize
                    : eventLoop.queueSize;
            }
            recorder.recordLong(
                descriptor,
                total,
                SnapshotAttributes.global(snapshot)
            );
            return true;
        }
        return false;
    }

    private static boolean recordConnections(
            MetricDescriptor descriptor,
            MetricsSnapshot snapshot,
            MetricRecorder recorder
    ) {
        LongBinding<ConnectionMetricsSnapshot> pool = find(CONNECTIONS, descriptor);
        if (pool != null) {
            forEachNode(snapshot, node -> {
                recordConnection(snapshot, node, SYNC, node.syncConnections, pool, recorder);
                recordConnection(snapshot, node, ASYNC, node.asyncConnections, pool, recorder);
            });
            return true;
        }

        return false;
    }

    private static void recordConnection(
            MetricsSnapshot snapshot,
            NodeMetricsSnapshot node,
            String connectionType,
            ConnectionMetricsSnapshot connection,
            LongBinding<ConnectionMetricsSnapshot> binding,
            MetricRecorder recorder
    ) {
        if (connection != null) {
            binding.record(
                snapshot,
                connection,
                SnapshotAttributes.node(snapshot, node, connectionType),
                recorder
            );
        }
    }

    private static boolean recordNamespaces(
            MetricDescriptor descriptor,
            MetricsSnapshot snapshot,
            MetricRecorder recorder
    ) {
        LongBinding<NamespaceMetricsSnapshot> binding = find(NAMESPACES, descriptor);
        if (binding == null) {
            return false;
        }
        if (!enabled(snapshot, descriptor)) {
            return true;
        }
        forEachNode(snapshot, node -> {
            for (NamespaceMetricsSnapshot namespace : node.namespaces) {
                if (exportable(namespace)) {
                    binding.record(
                        snapshot,
                        namespace,
                        SnapshotAttributes.namespace(snapshot, node, namespace),
                        recorder
                    );
                }
            }
        });
        return true;
    }

    private boolean supports(MetricDescriptor descriptor) {
        return find(CLIENT_LONG, descriptor) != null
            || CLIENT_CPU.matches(descriptor)
            || descriptor == MetricCatalog.EVENT_LOOP_PROCESS_SIZE
            || descriptor == MetricCatalog.EVENT_LOOP_QUEUE_SIZE
            || find(CONNECTIONS, descriptor) != null
            || find(NAMESPACES, descriptor) != null
            || latency.supports(descriptor);
    }

    private static boolean enabled(
            MetricsSnapshot snapshot,
            MetricDescriptor descriptor
    ) {
        switch (descriptor.tier()) {
            case STANDARD:
                return true;
            case OPERATIONAL:
                return snapshot.operationalMetricsEnabled;
            case USAGE:
                return snapshot.usageMetricsEnabled;
            default:
                throw new IllegalArgumentException(
                    "Unsupported metric tier: " + descriptor.tier()
                );
        }
    }

    private static <T> LongBinding<T> find(
            List<LongBinding<T>> bindings,
            MetricDescriptor descriptor
    ) {
        for (LongBinding<T> binding : bindings) {
            if (binding.matches(descriptor)) {
                return binding;
            }
        }
        return null;
    }

    static void forEachNode(
            MetricsSnapshot snapshot,
            Consumer<NodeMetricsSnapshot> consumer
    ) {
        Set<String> activeNodeNames = new HashSet<String>();
        for (NodeMetricsSnapshot node : snapshot.nodes) {
            if (!isBlank(node.nodeName)) {
                activeNodeNames.add(node.nodeName);
            }
            consumer.accept(node);
        }
        for (NodeMetricsSnapshot node : snapshot.nodesDeparted) {
            // Client-java should produce disjoint active/departed lists. This
            // fallback prevents duplicate OTel series from malformed snapshots
            // and older clients; the active node is authoritative.
            if (isBlank(node.nodeName)
                    || !activeNodeNames.contains(node.nodeName)) {
                consumer.accept(node);
            }
        }
    }

    static boolean exportable(NamespaceMetricsSnapshot namespace) {
        return !isBlank(namespace.namespace);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class LongBinding<T> {
        private final MetricDescriptor descriptor;
        private final ToLongFunction<T> value;

        private LongBinding(
                MetricDescriptor descriptor,
                ToLongFunction<T> value
        ) {
            this.descriptor = descriptor;
            this.value = value;
        }

        boolean matches(MetricDescriptor candidate) {
            return descriptor == candidate;
        }

        void record(
                MetricsSnapshot snapshot,
                T source,
                Map<String, String> attributes,
                MetricRecorder recorder
        ) {
            if (enabled(snapshot, descriptor)) {
                recorder.recordLong(descriptor, value.applyAsLong(source), attributes);
            }
        }
    }

    private static final class DoubleBinding<T> {
        private final MetricDescriptor descriptor;
        private final ToDoubleFunction<T> value;

        private DoubleBinding(
                MetricDescriptor descriptor,
                ToDoubleFunction<T> value
        ) {
            this.descriptor = descriptor;
            this.value = value;
        }

        boolean matches(MetricDescriptor candidate) {
            return descriptor == candidate;
        }

        void record(
                MetricsSnapshot snapshot,
                T source,
                Map<String, String> attributes,
                MetricRecorder recorder
        ) {
            if (enabled(snapshot, descriptor)) {
                recorder.recordDouble(descriptor, value.applyAsDouble(source), attributes);
            }
        }
    }
}

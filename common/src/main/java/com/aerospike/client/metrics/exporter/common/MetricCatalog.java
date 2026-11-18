/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import static com.aerospike.client.metrics.exporter.common.MetricDescriptor.Type.COUNTER;
import static com.aerospike.client.metrics.exporter.common.MetricDescriptor.Type.CUMULATIVE_BUCKETS;
import static com.aerospike.client.metrics.exporter.common.MetricDescriptor.Type.GAUGE;
import static com.aerospike.client.metrics.exporter.common.MetricDescriptor.ValueType.DOUBLE;
import static com.aerospike.client.metrics.exporter.common.MetricDescriptor.ValueType.LONG;
import static com.aerospike.client.metrics.exporter.common.MetricDescriptor.Tier.OPERATIONAL;
import static com.aerospike.client.metrics.exporter.common.MetricDescriptor.Tier.STANDARD;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Metrics currently populated by the local Java client snapshot.
 *
 * <p>This is intentionally the only place that owns metric names so future
 * schema revisions do not affect transport and lifecycle code.</p>
 */
public final class MetricCatalog {
    // TODO: Reconcile these OTel singular UCUM annotations with the
    // Aerospike tech spec, which currently uses unbraced units.
    // https://opentelemetry.io/docs/specs/semconv/general/metrics/#instrument-units
    private MetricCatalog() {
    }

    public static final MetricDescriptor RECOVER_QUEUE_SIZE = gauge(
        "aerospike.client.cluster.recover_queue_size",
        "Connections waiting in the recovery queue", "{connection}", STANDARD);
    public static final MetricDescriptor INVALID_NODES = counter(
        "aerospike.client.cluster.invalid_node_count",
        "Invalid node events", "{event}", STANDARD);
    public static final MetricDescriptor COMMAND_RETRIES = counter(
        "aerospike.client.cluster.retry_count",
        "Command retries", "{retry}", OPERATIONAL);
    public static final MetricDescriptor DELAY_QUEUE_TIMEOUTS = counter(
        "aerospike.client.cluster.delay_queue_timeout_count",
        "Delay queue timeouts", "{timeout}", STANDARD);
    public static final MetricDescriptor TOTAL_NODES = gauge(
        "aerospike.client.cluster.total_nodes",
        "Current cluster node count", "{node}", STANDARD);
    public static final MetricDescriptor OPEN_CONNECTIONS = gauge(
        "aerospike.client.cluster.open_connections",
        "Current open connection count", "{connection}", STANDARD);
    public static final MetricDescriptor EVENT_LOOP_PROCESS_SIZE = gauge(
        "aerospike.client.eventloop.process_size",
        "Commands processing on event loops", "{command}", STANDARD);
    public static final MetricDescriptor EVENT_LOOP_QUEUE_SIZE = gauge(
        "aerospike.client.eventloop.queue_size",
        "Commands queued on event loops", "{command}", STANDARD);

    public static final MetricDescriptor CPU_PERCENT = doubleGauge(
        "aerospike.client.cluster.cpu_percent",
        "Client process CPU utilization", "%", OPERATIONAL);
    public static final MetricDescriptor MEMORY_BYTES = gauge(
        "aerospike.client.cluster.memory_bytes",
        "Client process memory usage", "By", OPERATIONAL);
    public static final MetricDescriptor COMMAND_COUNT = counter(
        "aerospike.client.cluster.command_count",
        "Commands issued by the client", "{command}", OPERATIONAL);

    public static final MetricDescriptor CONNECTIONS_IN_USE = gauge(
        "aerospike.client.node.connections_in_use",
        "Connections currently in use", "{connection}", STANDARD);
    public static final MetricDescriptor CONNECTIONS_IN_POOL = gauge(
        "aerospike.client.node.connections_in_pool",
        "Idle connections in the pool", "{connection}", STANDARD);
    public static final MetricDescriptor CONNECTIONS_OPENED = counter(
        "aerospike.client.node.connections_opened",
        "Connections opened", "{connection}", STANDARD);
    public static final MetricDescriptor CONNECTIONS_CLOSED = counter(
        "aerospike.client.node.connections_closed",
        "Connections closed", "{connection}", STANDARD);
    public static final MetricDescriptor NAMESPACE_ERRORS = counter(
        "aerospike.client.namespace.errors",
        "Namespace operation errors", "{error}", OPERATIONAL);
    public static final MetricDescriptor NAMESPACE_CLIENT_TIMEOUTS = counter(
        "aerospike.client.namespace.client_timeouts",
        "Namespace client-side timeouts", "{timeout}", OPERATIONAL);
    public static final MetricDescriptor NAMESPACE_KEY_BUSY = counter(
        "aerospike.client.namespace.key_busy",
        "Namespace key-busy errors", "{error}", OPERATIONAL);
    public static final MetricDescriptor NAMESPACE_BYTES_IN = counter(
        "aerospike.client.namespace.bytes_in",
        "Bytes received for namespace operations", "By", OPERATIONAL);
    public static final MetricDescriptor NAMESPACE_BYTES_OUT = counter(
        "aerospike.client.namespace.bytes_out",
        "Bytes sent for namespace operations", "By", OPERATIONAL);

    public static final MetricDescriptor CLUSTER_LATENCY_BUCKET = cumulativeBuckets(
        "aerospike.client.cluster.latency_bucket",
        "Whole-call latency observations by upper bound", "{operation}");
    public static final MetricDescriptor NAMESPACE_LATENCY_BUCKET = cumulativeBuckets(
        "aerospike.client.namespace.latency_bucket",
        "Namespace RPC latency observations by upper bound", "{operation}");
    public static final MetricDescriptor NODE_CONNECTION_LATENCY_BUCKET = cumulativeBuckets(
        "aerospike.client.node.conn_latency_bucket",
        "Node connection latency observations by upper bound", "{operation}");

    private static final List<MetricDescriptor> ALL = Collections.unmodifiableList(Arrays.asList(
        RECOVER_QUEUE_SIZE,
        INVALID_NODES,
        COMMAND_RETRIES,
        DELAY_QUEUE_TIMEOUTS,
        TOTAL_NODES,
        OPEN_CONNECTIONS,
        EVENT_LOOP_PROCESS_SIZE,
        EVENT_LOOP_QUEUE_SIZE,
        CPU_PERCENT,
        MEMORY_BYTES,
        COMMAND_COUNT,
        CONNECTIONS_IN_USE,
        CONNECTIONS_IN_POOL,
        CONNECTIONS_OPENED,
        CONNECTIONS_CLOSED,
        NAMESPACE_ERRORS,
        NAMESPACE_CLIENT_TIMEOUTS,
        NAMESPACE_KEY_BUSY,
        NAMESPACE_BYTES_IN,
        NAMESPACE_BYTES_OUT,
        CLUSTER_LATENCY_BUCKET,
        NAMESPACE_LATENCY_BUCKET,
        NODE_CONNECTION_LATENCY_BUCKET
    ));

    /**
     * Return every supported descriptor in deterministic registration order.
     */
    public static List<MetricDescriptor> all() {
        return ALL;
    }

    private static MetricDescriptor gauge(
            String name,
            String description,
            String unit,
            MetricDescriptor.Tier tier
    ) {
        return new MetricDescriptor(name, description, unit, GAUGE, LONG, tier);
    }

    private static MetricDescriptor doubleGauge(
            String name,
            String description,
            String unit,
            MetricDescriptor.Tier tier
    ) {
        return new MetricDescriptor(name, description, unit, GAUGE, DOUBLE, tier);
    }

    private static MetricDescriptor counter(
            String name,
            String description,
            String unit,
            MetricDescriptor.Tier tier
    ) {
        return new MetricDescriptor(name, description, unit, COUNTER, LONG, tier);
    }

    private static MetricDescriptor cumulativeBuckets(
            String name,
            String description,
            String unit
    ) {
        return new MetricDescriptor(
            name, description, unit, CUMULATIVE_BUCKETS, LONG, OPERATIONAL
        );
    }

}

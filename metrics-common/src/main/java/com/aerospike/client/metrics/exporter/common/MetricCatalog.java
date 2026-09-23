/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import static com.aerospike.client.metrics.exporter.common.MetricDescriptor.Type.COUNTER;
import static com.aerospike.client.metrics.exporter.common.MetricDescriptor.Type.GAUGE;
import static com.aerospike.client.metrics.exporter.common.MetricDescriptor.Type.HISTOGRAM;

/**
 * Metrics currently populated by the local Java client snapshot.
 *
 * <p>This is intentionally the only place that owns metric names so future
 * schema revisions do not affect transport and lifecycle code.</p>
 */
public final class MetricCatalog {
    private MetricCatalog() {
    }

    //todo: verify no missing metrics
    public static final MetricDescriptor RECOVER_QUEUE_SIZE = gauge(
        "aerospike_client_recover_queue_size", "Connections waiting in the recovery queue", "{connections}", false);
    public static final MetricDescriptor INVALID_NODES = counter(
        "aerospike_client_invalid_node_count", "Invalid node events", "{events}", false);
    public static final MetricDescriptor RETRIES = counter(
        "aerospike_client_retry_count", "Transaction retries", "{retries}", false);
    public static final MetricDescriptor DELAY_QUEUE_TIMEOUTS = counter(
        "aerospike_client_delay_queue_timeout_count", "Delay queue timeouts", "{timeouts}", false);
    public static final MetricDescriptor TOTAL_NODES = gauge(
        "aerospike_client_total_nodes", "Current cluster node count", "{nodes}", false);
    public static final MetricDescriptor OPEN_CONNECTIONS = gauge(
        "aerospike_client_open_connections", "Current open connection count", "{connections}", false);
    public static final MetricDescriptor EXCEEDED_MAX_RETRIES = counter(
        "aerospike_client_exceeded_max_retries", "Commands that exceeded max retries", "{commands}", false);
    public static final MetricDescriptor EXCEEDED_TOTAL_TIMEOUT = counter(
        "aerospike_client_exceeded_total_timeout", "Commands that exceeded total timeout", "{commands}", false);

    public static final MetricDescriptor CPU_PERCENT = gauge(
        "aerospike_client_cpu_percent", "Client process CPU utilization", "%", true);
    public static final MetricDescriptor MEMORY_BYTES = gauge(
        "aerospike_client_memory_bytes", "Client process memory usage", "By", true);
    public static final MetricDescriptor COMMAND_COUNT = counter(
        "aerospike_client_command_count", "Commands issued by the client", "{commands}", true);

    public static final MetricDescriptor CONNECTIONS_IN_USE = gauge(
        "aerospike_client_node_connections_in_use", "Connections currently in use", "{connections}", false);
    public static final MetricDescriptor CONNECTIONS_IN_POOL = gauge(
        "aerospike_client_node_connections_in_pool", "Idle connections in the pool", "{connections}", false);
    public static final MetricDescriptor CONNECTIONS_OPENED = counter(
        "aerospike_client_node_connections_opened", "Connections opened", "{connections}", false);
    public static final MetricDescriptor CONNECTIONS_CLOSED = counter(
        "aerospike_client_node_connections_closed", "Connections closed", "{connections}", false);

    public static final MetricDescriptor NAMESPACE_ERRORS = counter(
        "aerospike_client_namespace_errors", "Namespace operation errors", "{errors}", true);
    public static final MetricDescriptor NAMESPACE_TIMEOUTS = counter(
        "aerospike_client_namespace_timeouts", "Namespace operation timeouts", "{timeouts}", true);
    public static final MetricDescriptor NAMESPACE_KEY_BUSY = counter(
        "aerospike_client_namespace_key_busy", "Namespace key-busy errors", "{errors}", true);
    public static final MetricDescriptor NAMESPACE_BYTES_IN = counter(
        "aerospike_client_namespace_bytes_in", "Bytes received for namespace operations", "By", true);
    public static final MetricDescriptor NAMESPACE_BYTES_OUT = counter(
        "aerospike_client_namespace_bytes_out", "Bytes sent for namespace operations", "By", true);

    public static MetricDescriptor latency(String operation, String unit) {
        return new MetricDescriptor(
            "aerospike_client_namespace_latency_" + operation,
            "Client-side " + operation + " latency",
            unit,
            HISTOGRAM,
            true
        );
    }

    private static MetricDescriptor gauge(
            String name, String description, String unit, boolean extended) {
        return new MetricDescriptor(name, description, unit, GAUGE, extended);
    }

    private static MetricDescriptor counter(
            String name, String description, String unit, boolean extended) {
        return new MetricDescriptor(name, description, unit, COUNTER, extended);
    }
}

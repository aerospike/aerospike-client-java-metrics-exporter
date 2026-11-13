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
    private MetricCatalog() {
    }

    public static final MetricDescriptor RECOVER_QUEUE_SIZE = gauge(
        "aerospike_client_recover_queue_size", "Connections waiting in the recovery queue",
        "{connections}", STANDARD);
    public static final MetricDescriptor INVALID_NODES = counter(
        "aerospike_client_invalid_node_count", "Invalid node events", "{events}", STANDARD);
    public static final MetricDescriptor COMMAND_RETRIES = counter(
        "aerospike_client_retry_count", "Command retries", "{retries}", OPERATIONAL);
    public static final MetricDescriptor DELAY_QUEUE_TIMEOUTS = counter(
        "aerospike_client_delay_queue_timeout_count", "Delay queue timeouts",
        "{timeouts}", STANDARD);
    public static final MetricDescriptor TOTAL_NODES = gauge(
        "aerospike_client_total_nodes", "Current cluster node count", "{nodes}", STANDARD);
    public static final MetricDescriptor OPEN_CONNECTIONS = gauge(
        "aerospike_client_open_connections", "Current open connection count",
        "{connections}", STANDARD);
    public static final MetricDescriptor BATCH_PARTIAL_FAILURE = counter(
        "aerospike_client_batch_partial_failure", "Batch calls with partial failures",
        "{calls}", OPERATIONAL);

    public static final MetricDescriptor EVENT_LOOP_PROCESS_SIZE = gauge(
        "aerospike_client_eventloop_process_size", "Commands processing on event loops",
        "{commands}", STANDARD);
    public static final MetricDescriptor EVENT_LOOP_QUEUE_SIZE = gauge(
        "aerospike_client_eventloop_queue_size", "Commands queued on event loops",
        "{commands}", STANDARD);

    public static final MetricDescriptor CPU_PERCENT = doubleGauge(
        "aerospike_client_cpu_percent", "Client process CPU utilization", "%", OPERATIONAL);
    public static final MetricDescriptor MEMORY_BYTES = gauge(
        "aerospike_client_memory_bytes", "Client process memory usage", "By", OPERATIONAL);
    public static final MetricDescriptor COMMAND_COUNT = counter(
        "aerospike_client_command_count", "Commands issued by the client",
        "{commands}", OPERATIONAL);

    public static final MetricDescriptor VALIDATION_OPEN_FAILURE = counter(
        "aerospike_client_validation_open_failure", "Pre-node connection validation failures",
        "{errors}", OPERATIONAL);
    public static final MetricDescriptor VALIDATION_TLS_FAILURE = counter(
        "aerospike_client_validation_tls_handshake_failure", "Pre-node TLS validation failures",
        "{errors}", OPERATIONAL);
    public static final MetricDescriptor VALIDATION_AUTH_FAILURE = counter(
        "aerospike_client_validation_auth_failure", "Pre-node authentication validation failures",
        "{errors}", OPERATIONAL);

    public static final MetricDescriptor CONNECTIONS_IN_USE = gauge(
        "aerospike_client_node_connections_in_use", "Connections currently in use",
        "{connections}", STANDARD);
    public static final MetricDescriptor CONNECTIONS_IN_POOL = gauge(
        "aerospike_client_node_connections_in_pool", "Idle connections in the pool",
        "{connections}", STANDARD);
    public static final MetricDescriptor CONNECTIONS_OPENED = counter(
        "aerospike_client_node_connections_opened", "Connections opened",
        "{connections}", STANDARD);
    public static final MetricDescriptor CONNECTIONS_CLOSED = counter(
        "aerospike_client_node_connections_closed", "Connections closed",
        "{connections}", STANDARD);
    public static final MetricDescriptor CONNECTION_OPEN_FAILURE = counter(
        "aerospike_client_node_connection_open_failure", "Node connection-open failures",
        "{errors}", OPERATIONAL);
    public static final MetricDescriptor CONNECTION_TLS_FAILURE = counter(
        "aerospike_client_node_connection_tls_handshake_failure", "Node TLS handshake failures",
        "{errors}", OPERATIONAL);
    public static final MetricDescriptor CONNECTION_AUTH_FAILURE = counter(
        "aerospike_client_node_connection_auth_failure", "Node authentication failures",
        "{errors}", OPERATIONAL);

    public static final MetricDescriptor NAMESPACE_ERRORS = counter(
        "aerospike_client_namespace_errors", "Namespace operation errors",
        "{errors}", OPERATIONAL);
    public static final MetricDescriptor NAMESPACE_CLIENT_TIMEOUTS = counter(
        "aerospike_client_namespace_client_timeouts", "Namespace client-side timeouts",
        "{timeouts}", OPERATIONAL);
    public static final MetricDescriptor NAMESPACE_SERVER_TIMEOUTS = counter(
        "aerospike_client_namespace_server_timeouts", "Namespace server-side timeouts",
        "{timeouts}", OPERATIONAL);
    public static final MetricDescriptor NAMESPACE_KEY_BUSY = counter(
        "aerospike_client_namespace_key_busy", "Namespace key-busy errors",
        "{errors}", OPERATIONAL);
    public static final MetricDescriptor NAMESPACE_RECORD_TOO_BIG = counter(
        "aerospike_client_namespace_record_too_big", "Namespace record-too-big errors",
        "{errors}", OPERATIONAL);
    public static final MetricDescriptor NAMESPACE_DEVICE_OVERLOAD = counter(
        "aerospike_client_namespace_device_overload", "Namespace device-overload errors",
        "{errors}", OPERATIONAL);
    public static final MetricDescriptor NAMESPACE_BYTES_IN = counter(
        "aerospike_client_namespace_bytes_in", "Bytes received for namespace operations",
        "By", OPERATIONAL);
    public static final MetricDescriptor NAMESPACE_BYTES_OUT = counter(
        "aerospike_client_namespace_bytes_out", "Bytes sent for namespace operations",
        "By", OPERATIONAL);

    public static final MetricDescriptor CLUSTER_LATENCY_BUCKET = cumulativeBuckets(
        "aerospike_client_latency_bucket", "Whole-call latency observations by upper bound",
        "{operations}");
    public static final MetricDescriptor NAMESPACE_LATENCY_BUCKET = cumulativeBuckets(
        "aerospike_client_namespace_latency_bucket",
        "Namespace RPC latency observations by upper bound", "{operations}");
    public static final MetricDescriptor NODE_CONNECTION_LATENCY_BUCKET = cumulativeBuckets(
        "aerospike_client_node_conn_latency_bucket",
        "Node connection latency observations by upper bound", "{connections}");

    public static final MetricDescriptor FEATURE_API_BLOCKING = usage(
        "aerospike_client_feature_api_blocking", "Blocking API calls");
    public static final MetricDescriptor FEATURE_API_DEFERRED = usage(
        "aerospike_client_feature_api_deferred", "Deferred API calls");
    public static final MetricDescriptor FEATURE_API_BACKGROUND = usage(
        "aerospike_client_feature_api_background", "Background API calls");
    public static final MetricDescriptor FEATURE_SHAPE_POINT = usage(
        "aerospike_client_feature_shape_point", "Point-operation API calls");
    public static final MetricDescriptor FEATURE_SHAPE_BATCH = usage(
        "aerospike_client_feature_shape_batch", "Batch API calls");
    public static final MetricDescriptor FEATURE_SHAPE_QUERY = usage(
        "aerospike_client_feature_shape_query", "Query and scan API calls");
    public static final MetricDescriptor FEATURE_OBJECT_MAPPING_READ = usage(
        "aerospike_client_feature_object_mapping_read", "Object-mapping read calls");
    public static final MetricDescriptor FEATURE_OBJECT_MAPPING_WRITE = usage(
        "aerospike_client_feature_object_mapping_write", "Object-mapping write calls");
    public static final MetricDescriptor FEATURE_FILTER_AEL = usage(
        "aerospike_client_feature_filter_ael", "Calls using an AEL record filter");
    public static final MetricDescriptor FEATURE_FILTER_EXP = usage(
        "aerospike_client_feature_filter_exp", "Calls using a legacy expression record filter");

    private static final List<MetricDescriptor> ALL = Collections.unmodifiableList(Arrays.asList(
        RECOVER_QUEUE_SIZE,
        INVALID_NODES,
        COMMAND_RETRIES,
        DELAY_QUEUE_TIMEOUTS,
        TOTAL_NODES,
        OPEN_CONNECTIONS,
        BATCH_PARTIAL_FAILURE,
        EVENT_LOOP_PROCESS_SIZE,
        EVENT_LOOP_QUEUE_SIZE,
        CPU_PERCENT,
        MEMORY_BYTES,
        COMMAND_COUNT,
        VALIDATION_OPEN_FAILURE,
        VALIDATION_TLS_FAILURE,
        VALIDATION_AUTH_FAILURE,
        CONNECTIONS_IN_USE,
        CONNECTIONS_IN_POOL,
        CONNECTIONS_OPENED,
        CONNECTIONS_CLOSED,
        CONNECTION_OPEN_FAILURE,
        CONNECTION_TLS_FAILURE,
        CONNECTION_AUTH_FAILURE,
        NAMESPACE_ERRORS,
        NAMESPACE_CLIENT_TIMEOUTS,
        NAMESPACE_SERVER_TIMEOUTS,
        NAMESPACE_KEY_BUSY,
        NAMESPACE_RECORD_TOO_BIG,
        NAMESPACE_DEVICE_OVERLOAD,
        NAMESPACE_BYTES_IN,
        NAMESPACE_BYTES_OUT,
        CLUSTER_LATENCY_BUCKET,
        NAMESPACE_LATENCY_BUCKET,
        NODE_CONNECTION_LATENCY_BUCKET,
        FEATURE_API_BLOCKING,
        FEATURE_API_DEFERRED,
        FEATURE_API_BACKGROUND,
        FEATURE_SHAPE_POINT,
        FEATURE_SHAPE_BATCH,
        FEATURE_SHAPE_QUERY,
        FEATURE_OBJECT_MAPPING_READ,
        FEATURE_OBJECT_MAPPING_WRITE,
        FEATURE_FILTER_AEL,
        FEATURE_FILTER_EXP
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

    private static MetricDescriptor usage(String name, String description) {
        return counter(
            name,
            description,
            "{calls}",
            MetricDescriptor.Tier.USAGE
        );
    }
}

/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import java.util.Map;

/**
 * Records backend-neutral metric values produced from client snapshots.
 *
 * <p>Backend modules translate canonical descriptors, values, and attributes
 * to their native metric representation. Implementations must consume the
 * supplied attributes during the call and must not mutate them.</p>
 */
public interface MetricRecorder {
    void recordLong(
        MetricDescriptor descriptor,
        long value,
        Map<String, String> attributes
    );

    void recordDouble(
        MetricDescriptor descriptor,
        double value,
        Map<String, String> attributes
    );
}

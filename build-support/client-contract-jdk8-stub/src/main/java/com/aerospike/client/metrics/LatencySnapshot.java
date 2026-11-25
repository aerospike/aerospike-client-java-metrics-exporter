/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics;

import java.util.Arrays;

/**
 * Temporary Java 8 compile-time copy of the client-owned latency snapshot.
 *
 * <p>TODO(OM-386): Remove when aerospike-client-jdk8 provides this type.</p>
 */
public final class LatencySnapshot {
    public final LatencyType type;
    private final long[] bucketCounts;
    public final Long min;
    public final Long max;
    public final Double sum;
    public final long count;

    public LatencySnapshot(
            LatencyType type,
            long[] bucketCounts,
            Long min,
            Long max,
            Double sum,
            long count
    ) {
        this.type = type;
        this.bucketCounts =
            bucketCounts == null ? new long[0] : bucketCounts.clone();
        this.min = min;
        this.max = max;
        this.sum = sum;
        this.count = count;
    }

    public long[] getBucketCounts() {
        return bucketCounts.clone();
    }

    @Override
    public String toString() {
        return "LatencySnapshot{"
            + "type=" + type
            + ", bucketCounts=" + Arrays.toString(bucketCounts)
            + ", min=" + min
            + ", max=" + max
            + ", sum=" + sum
            + ", count=" + count
            + '}';
    }
}

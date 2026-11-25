/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics;

/**
 * Temporary Java 8 compile-time copy of the client-owned latency unit.
 *
 * <p>TODO(OM-386): Remove when aerospike-client-jdk8 provides this type.</p>
 */
public enum LatencyUnit {
    MILLISECONDS,
    MICROSECONDS;

    public static LatencyUnit parse(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase());
    }
}

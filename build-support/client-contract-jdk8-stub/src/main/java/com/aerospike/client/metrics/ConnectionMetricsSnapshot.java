/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics;

/**
 * Temporary Java 8 compile-time copy of the client-owned connection snapshot.
 *
 * <p>TODO(OM-386): Remove when aerospike-client-jdk8 provides this type.</p>
 */
public final class ConnectionMetricsSnapshot {
    public final int inUse;
    public final int inPool;
    public final int opened;
    public final int closed;

    public ConnectionMetricsSnapshot(
            int inUse,
            int inPool,
            int opened,
            int closed
    ) {
        this.inUse = inUse;
        this.inPool = inPool;
        this.opened = opened;
        this.closed = closed;
    }

    @Override
    public String toString() {
        return "ConnectionMetricsSnapshot{"
            + "inUse=" + inUse
            + ", inPool=" + inPool
            + ", opened=" + opened
            + ", closed=" + closed
            + '}';
    }
}

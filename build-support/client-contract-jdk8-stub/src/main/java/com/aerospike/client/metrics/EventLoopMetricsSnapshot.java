/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics;

/**
 * Temporary Java 8 compile-time copy of the client-owned event-loop snapshot.
 *
 * <p>TODO(OM-386): Remove when aerospike-client-jdk8 provides this type.</p>
 */
public final class EventLoopMetricsSnapshot {
    public final int processSize;
    public final int queueSize;

    public EventLoopMetricsSnapshot(int processSize, int queueSize) {
        this.processSize = processSize;
        this.queueSize = queueSize;
    }

    @Override
    public String toString() {
        return "EventLoopMetricsSnapshot{"
            + "processSize=" + processSize
            + ", queueSize=" + queueSize
            + '}';
    }
}

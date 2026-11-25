/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics;

/**
 * Temporary Java 8 compile-time copy of the client-owned namespace snapshot.
 *
 * <p>TODO(OM-386): Remove when aerospike-client-jdk8 provides this type.</p>
 */
public final class NamespaceMetricsSnapshot {
    public final String namespace;
    public final long errors;
    public final long clientTimeouts;
    public final long serverTimeouts;
    public final long keyBusy;
    public final long recordTooBig;
    public final long deviceOverload;
    public final long bytesIn;
    public final long bytesOut;
    public final LatencySnapshot readLatency;
    public final LatencySnapshot writeLatency;
    public final LatencySnapshot batchLatency;
    public final LatencySnapshot queryLatency;

    public NamespaceMetricsSnapshot(
            String namespace,
            long errors,
            long clientTimeouts,
            long serverTimeouts,
            long keyBusy,
            long recordTooBig,
            long deviceOverload,
            long bytesIn,
            long bytesOut,
            LatencySnapshot readLatency,
            LatencySnapshot writeLatency,
            LatencySnapshot batchLatency,
            LatencySnapshot queryLatency
    ) {
        this.namespace = namespace;
        this.errors = errors;
        this.clientTimeouts = clientTimeouts;
        this.serverTimeouts = serverTimeouts;
        this.keyBusy = keyBusy;
        this.recordTooBig = recordTooBig;
        this.deviceOverload = deviceOverload;
        this.bytesIn = bytesIn;
        this.bytesOut = bytesOut;
        this.readLatency = readLatency;
        this.writeLatency = writeLatency;
        this.batchLatency = batchLatency;
        this.queryLatency = queryLatency;
    }

    @Override
    public String toString() {
        return "NamespaceMetricsSnapshot{"
            + "namespace='" + namespace + '\''
            + ", errors=" + errors
            + ", clientTimeouts=" + clientTimeouts
            + ", serverTimeouts=" + serverTimeouts
            + ", keyBusy=" + keyBusy
            + ", recordTooBig=" + recordTooBig
            + ", deviceOverload=" + deviceOverload
            + ", bytesIn=" + bytesIn
            + ", bytesOut=" + bytesOut
            + ", readLatency=" + readLatency
            + ", writeLatency=" + writeLatency
            + ", batchLatency=" + batchLatency
            + ", queryLatency=" + queryLatency
            + '}';
    }
}

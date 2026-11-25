/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics;

/**
 * Temporary Java 8 compile-time copy of the client-owned usage snapshot.
 *
 * <p>TODO(OM-386): Remove when aerospike-client-jdk8 provides this type.</p>
 */
public final class UsageMetricsSnapshot {
    public final long blocking;
    public final long deferred;
    public final long background;
    public final long shapePoint;
    public final long shapeBatch;
    public final long shapeQuery;
    public final long objectMappingRead;
    public final long objectMappingWrite;
    public final long filterAel;
    public final long filterExp;

    public UsageMetricsSnapshot(
            long blocking,
            long deferred,
            long background,
            long shapePoint,
            long shapeBatch,
            long shapeQuery,
            long objectMappingRead,
            long objectMappingWrite,
            long filterAel,
            long filterExp
    ) {
        this.blocking = blocking;
        this.deferred = deferred;
        this.background = background;
        this.shapePoint = shapePoint;
        this.shapeBatch = shapeBatch;
        this.shapeQuery = shapeQuery;
        this.objectMappingRead = objectMappingRead;
        this.objectMappingWrite = objectMappingWrite;
        this.filterAel = filterAel;
        this.filterExp = filterExp;
    }

    @Override
    public String toString() {
        return "UsageMetricsSnapshot{"
            + "blocking=" + blocking
            + ", deferred=" + deferred
            + ", background=" + background
            + ", shapePoint=" + shapePoint
            + ", shapeBatch=" + shapeBatch
            + ", shapeQuery=" + shapeQuery
            + ", objectMappingRead=" + objectMappingRead
            + ", objectMappingWrite=" + objectMappingWrite
            + ", filterAel=" + filterAel
            + ", filterExp=" + filterExp
            + '}';
    }
}

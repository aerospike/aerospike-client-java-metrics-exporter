/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricReader;

final class TestMetricReader implements MetricReader {
    private volatile CollectionRegistration registration =
        CollectionRegistration.noop();
    private final AggregationTemporality temporality;
    private final AtomicInteger flushes = new AtomicInteger();
    private final AtomicInteger shutdowns = new AtomicInteger();

    TestMetricReader() {
        this(AggregationTemporality.CUMULATIVE);
    }

    TestMetricReader(AggregationTemporality temporality) {
        this.temporality = temporality;
    }

    @Override
    public void register(CollectionRegistration registration) {
        this.registration = registration;
    }

    Collection<MetricData> collect() {
        return registration.collectAllMetrics();
    }

    int flushCount() {
        return flushes.get();
    }

    int shutdownCount() {
        return shutdowns.get();
    }

    @Override
    public AggregationTemporality getAggregationTemporality(
            InstrumentType instrumentType
    ) {
        return temporality;
    }

    @Override
    public CompletableResultCode forceFlush() {
        flushes.incrementAndGet();
        collect();
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        shutdowns.incrementAndGet();
        return CompletableResultCode.ofSuccess();
    }
}

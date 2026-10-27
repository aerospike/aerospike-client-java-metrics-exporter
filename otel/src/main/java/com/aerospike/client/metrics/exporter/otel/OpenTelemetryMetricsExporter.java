/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import java.util.concurrent.atomic.AtomicBoolean;

import com.aerospike.client.metrics.IMetricsExporter;
import com.aerospike.client.metrics.MetricsSnapshot;
import com.aerospike.client.metrics.exporter.common.ClientSnapshotRegistry;
import com.aerospike.client.metrics.exporter.common.SnapshotMetricMapper;

import io.opentelemetry.sdk.metrics.SdkMeterProvider;

/**
 * Adapts Aerospike client metric snapshots to a customer-owned OpenTelemetry
 * meter provider.
 */
public final class OpenTelemetryMetricsExporter implements IMetricsExporter, AutoCloseable {
    private static final String METER_NAME = "aerospike-client";

    private final ClientSnapshotRegistry snapshots;
    private final OtelInstrumentSet instruments;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    /**
     * Register Aerospike instruments with a customer-owned provider.
     *
     * @param meterProvider provider configured and owned by the application
     */
    public OpenTelemetryMetricsExporter(SdkMeterProvider meterProvider) {
        this(meterProvider, new ClientSnapshotRegistry());
    }

    OpenTelemetryMetricsExporter(
            SdkMeterProvider meterProvider,
            ClientSnapshotRegistry snapshots
    ) {
        this(
            meterProvider,
            snapshots,
            new SnapshotMetricMapper()
        );
    }

    OpenTelemetryMetricsExporter(
            SdkMeterProvider meterProvider,
            ClientSnapshotRegistry snapshots,
            SnapshotMetricMapper mapper
    ) {
        if (meterProvider == null) {
            throw new IllegalArgumentException("meterProvider must not be null");
        }
        if (snapshots == null) {
            throw new IllegalArgumentException("snapshots must not be null");
        }
        if (mapper == null) {
            throw new IllegalArgumentException("mapper must not be null");
        }
        this.snapshots = snapshots;
        this.instruments = new OtelInstrumentSet(
            meterProvider.get(METER_NAME),
            snapshots,
            mapper
        );
    }

    @Override
    public void export(MetricsSnapshot snapshot) {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("OpenTelemetryMetricsExporter is closed");
            }
            // TODO: Revisit disabled-snapshot removal and optional final
            // flushing. For now the final values remain observable until a
            // re-enabled snapshot replaces them or this adapter closes.
            snapshots.update(snapshot);
        }
    }

    /**
     * Package-private diagnostics for focused lifecycle tests.
     */
    ClientSnapshotRegistry snapshotRegistry() {
        return snapshots;
    }

    /**
     * Unregister adapter callbacks without closing customer-owned OTel objects.
     */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
        }

        instruments.close();
        snapshots.clear();
    }
}

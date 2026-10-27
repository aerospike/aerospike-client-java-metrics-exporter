/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.aerospike.client.metrics.exporter.common.ClientSnapshotRegistry;
import com.aerospike.client.metrics.exporter.common.MetricCatalog;
import com.aerospike.client.metrics.exporter.common.MetricDescriptor;
import com.aerospike.client.metrics.exporter.common.SnapshotMetricMapper;

import io.opentelemetry.api.metrics.Meter;

/**
 * Owns only the adapter's observable instruments and their callbacks.
 */
final class OtelInstrumentSet implements AutoCloseable {
    private static final Logger LOGGER =
        Logger.getLogger(OtelInstrumentSet.class.getName());

    private final List<AutoCloseable> instruments = new ArrayList<AutoCloseable>();
    private final AtomicBoolean closed = new AtomicBoolean();

    OtelInstrumentSet(
            Meter meter,
            ClientSnapshotRegistry snapshots,
            SnapshotMetricMapper mapper
    ) {
        try {
            // TODO: Revisit Meter.batchCallback() if one consistent snapshot
            // traversal becomes preferable to per-instrument failure isolation.
            for (MetricDescriptor descriptor : MetricCatalog.all()) {
                instruments.add(register(meter, descriptor, snapshots, mapper));
            }
        }
        catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    int instrumentCount() {
        return instruments.size();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (AutoCloseable instrument : instruments) {
            try {
                instrument.close();
            }
            catch (Exception failure) {
                LOGGER.log(
                    Level.WARNING,
                    "Unable to close an OTel observable instrument",
                    failure
                );
            }
        }
    }

    private static AutoCloseable register(
            Meter meter,
            MetricDescriptor descriptor,
            ClientSnapshotRegistry snapshots,
            SnapshotMetricMapper mapper
    ) {
        return switch (descriptor.valueType()) {
            case LONG -> registerLong(meter, descriptor, snapshots, mapper);
            case DOUBLE -> registerDouble(meter, descriptor, snapshots, mapper);
        };
    }

    private static AutoCloseable registerLong(
            Meter meter,
            MetricDescriptor descriptor,
            ClientSnapshotRegistry snapshots,
            SnapshotMetricMapper mapper
    ) {
        return switch (descriptor.type()) {
            case GAUGE -> meter.gaugeBuilder(descriptor.name())
                .ofLongs()
                .setDescription(descriptor.description())
                .setUnit(descriptor.unit())
                .buildWithCallback(measurement -> mapper.record(
                    descriptor,
                    snapshots.currentSnapshots(),
                    new OtelMetricRecorder(descriptor, measurement)
                ));
            case COUNTER, CUMULATIVE_BUCKETS -> meter.counterBuilder(descriptor.name())
                .setDescription(descriptor.description())
                .setUnit(descriptor.unit())
                .buildWithCallback(measurement -> mapper.record(
                    descriptor,
                    snapshots.currentSnapshots(),
                    new OtelMetricRecorder(descriptor, measurement)
                ));
        };
    }

    private static AutoCloseable registerDouble(
            Meter meter,
            MetricDescriptor descriptor,
            ClientSnapshotRegistry snapshots,
            SnapshotMetricMapper mapper
    ) {
        return switch (descriptor.type()) {
            case GAUGE -> meter.gaugeBuilder(descriptor.name())
                .setDescription(descriptor.description())
                .setUnit(descriptor.unit())
                .buildWithCallback(measurement -> mapper.record(
                    descriptor,
                    snapshots.currentSnapshots(),
                    new OtelMetricRecorder(descriptor, measurement)
                ));
            case COUNTER -> meter.counterBuilder(descriptor.name())
                .ofDoubles()
                .setDescription(descriptor.description())
                .setUnit(descriptor.unit())
                .buildWithCallback(measurement -> mapper.record(
                    descriptor,
                    snapshots.currentSnapshots(),
                    new OtelMetricRecorder(descriptor, measurement)
                ));
            case CUMULATIVE_BUCKETS -> throw new IllegalStateException(
                "Cumulative buckets must use long values: " + descriptor.name()
            );
        };
    }
}

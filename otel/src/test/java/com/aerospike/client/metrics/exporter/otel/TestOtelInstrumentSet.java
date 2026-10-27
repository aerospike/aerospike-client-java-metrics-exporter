/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.aerospike.client.metrics.exporter.common.ClientSnapshotRegistry;
import com.aerospike.client.metrics.exporter.common.MetricCatalog;
import com.aerospike.client.metrics.exporter.common.SnapshotMetricMapper;

import io.opentelemetry.sdk.metrics.SdkMeterProvider;

class TestOtelInstrumentSet {
    @Test
    void registersOneObservableCallbackPerDescriptor() {
        TestMetricReader reader = new TestMetricReader();
        SdkMeterProvider provider =
            SdkMeterProvider.builder().registerMetricReader(reader).build();
        ClientSnapshotRegistry snapshots = new ClientSnapshotRegistry();
        OtelInstrumentSet instruments = new OtelInstrumentSet(
            provider.get("test"),
            snapshots,
            new SnapshotMetricMapper()
        );
        try {
            snapshots.update(TestSnapshots.complete());
            Set<String> actual = reader.collect().stream()
                .map(metric -> metric.getName())
                .collect(Collectors.toSet());
            Set<String> expected = MetricCatalog.all().stream()
                .map(descriptor -> descriptor.name())
                .collect(Collectors.toSet());

            assertEquals(MetricCatalog.all().size(), instruments.instrumentCount());
            assertEquals(expected, actual);

            instruments.close();
            assertTrue(reader.collect().isEmpty());
        }
        finally {
            instruments.close();
            provider.close();
        }
    }

}

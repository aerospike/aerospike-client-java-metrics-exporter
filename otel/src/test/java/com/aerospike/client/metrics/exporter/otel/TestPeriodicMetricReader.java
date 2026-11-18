/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;

class TestPeriodicMetricReader {
    private static final String COMMANDS =
        "aerospike.client.cluster.command_count";
    private static final String NODES =
        "aerospike.client.cluster.total_nodes";
    private static final String CONNECTIONS_OPENED =
        "aerospike.client.node.connections_opened";

    @Test
    void fasterReaderRepeatsAbsoluteValuesUntilSnapshotReplacement()
            throws InterruptedException {
        RecordingExporter transport = new RecordingExporter();
        PeriodicMetricReader reader = PeriodicMetricReader.builder(transport)
            .setInterval(Duration.ofMillis(25))
            .build();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        try {
            exporter.export(TestSnapshots.complete());

            List<MetricData> first = transport.await(
                metrics -> longValue(metrics, COMMANDS) == 99L
            );
            List<MetricData> repeated = transport.await(
                metrics -> longValue(metrics, COMMANDS) == 99L
            );
            assertEquals(3L, longValue(first, NODES));
            assertEquals(3L, longValue(repeated, NODES));

            exporter.export(TestSnapshots.updated());
            List<MetricData> replaced = transport.await(
                metrics -> longValue(metrics, COMMANDS) == 109L
            );
            assertEquals(3L, longValue(replaced, NODES));
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void slowerReaderObservesLatestSnapshotAndCanMissTransientSeries()
            throws InterruptedException {
        RecordingExporter transport = new RecordingExporter();
        PeriodicMetricReader reader = PeriodicMetricReader.builder(transport)
            .setInterval(Duration.ofMillis(250))
            .build();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        try {
            exporter.export(TestSnapshots.withDepartedNode());
            exporter.export(TestSnapshots.updated());

            List<MetricData> latest = transport.await(
                metrics -> longValue(metrics, COMMANDS) == 109L
            );
            MetricData connections = metric(latest, CONNECTIONS_OPENED);
            assertFalse(connections.getLongSumData().getPoints().stream().anyMatch(
                point -> "D1".equals(point.getAttributes().get(
                    AttributeKey.stringKey("server_node_id")
                ))
            ));
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    private static long longValue(Collection<MetricData> metrics, String name) {
        MetricData metric = metrics.stream()
            .filter(candidate -> name.equals(candidate.getName()))
            .findFirst()
            .orElse(null);
        if (metric == null) {
            return Long.MIN_VALUE;
        }
        if (!metric.getLongSumData().getPoints().isEmpty()) {
            return metric.getLongSumData().getPoints().iterator().next().getValue();
        }
        if (!metric.getLongGaugeData().getPoints().isEmpty()) {
            return metric.getLongGaugeData().getPoints().iterator().next().getValue();
        }
        return Long.MIN_VALUE;
    }

    private static MetricData metric(Collection<MetricData> metrics, String name) {
        return metrics.stream()
            .filter(candidate -> name.equals(candidate.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing metric " + name));
    }

    private static final class RecordingExporter implements MetricExporter {
        private final BlockingQueue<List<MetricData>> exports =
            new LinkedBlockingQueue<List<MetricData>>();

        @Override
        public CompletableResultCode export(Collection<MetricData> metrics) {
            exports.add(List.copyOf(metrics));
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public AggregationTemporality getAggregationTemporality(
                InstrumentType instrumentType
        ) {
            return AggregationTemporality.CUMULATIVE;
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        List<MetricData> await(Predicate<List<MetricData>> predicate)
                throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                List<MetricData> metrics = exports.poll(100, TimeUnit.MILLISECONDS);
                if (metrics != null && predicate.test(metrics)) {
                    return metrics;
                }
            }
            throw new AssertionError("Timed out waiting for periodic metric export");
        }
    }
}

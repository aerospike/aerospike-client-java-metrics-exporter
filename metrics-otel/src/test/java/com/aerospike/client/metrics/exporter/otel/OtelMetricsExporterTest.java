/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aerospike.client.metrics.MetricsSnapshot;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

class OtelMetricsExporterTest {
    @Test
    void exportsCurrentSnapshotUsingObservableInstruments() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setMeterProvider(provider)
            .build();

        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .openTelemetry(openTelemetry)
            .snapshotStaleTimeout("5m")
            .build();
        try {
            exporter.export(snapshot(true));
            Collection<MetricData> metrics = reader.collectAllMetrics();

            MetricData nodes = metric(metrics, "aerospike_client_total_nodes");
            LongPointData point = nodes.getLongGaugeData().getPoints().iterator().next();
            assertEquals(2L, point.getValue());
            assertEquals(
                "prod",
                point.getAttributes().get(AttributeKey.stringKey("cluster_name"))
            );

            assertTrue(hasMetric(metrics, "aerospike_client_command_count"));
            assertTrue(hasMetric(metrics, "aerospike_client_namespace_errors"));
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void suppressesExtendedMetricsWhenClientDidNotCollectThem() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setMeterProvider(provider)
            .build();

        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .openTelemetry(openTelemetry)
            .build();
        try {
            exporter.export(snapshot(false));
            Collection<MetricData> metrics = reader.collectAllMetrics();
            assertFalse(hasMetric(metrics, "aerospike_client_command_count"));
            assertFalse(hasMetric(metrics, "aerospike_client_namespace_errors"));
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void resolvesDefaultsAndRejectsGrpcProxy() {
        OtelExporterConfig config = OtelMetricsExporter.builder().resolveConfig();
        assertEquals(OtelExporterConfig.Protocol.GRPC, config.protocol());
        assertEquals(Duration.ofSeconds(60), config.exportInterval());

        assertThrows(
            IllegalArgumentException.class,
            () -> OtelMetricsExporter.builder().proxy("http://proxy:8080").resolveConfig()
        );
    }

    @Test
    void validatesMtlsPairAndClosesOnlyManagedProvider() {
        assertThrows(
            IllegalArgumentException.class,
            () -> OtelMetricsExporter.builder()
                .clientCertificate(Paths.get("client.pem"))
                .resolveConfig()
        );

        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setMeterProvider(provider)
            .build();
        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .openTelemetry(openTelemetry)
            .build();

        exporter.close();
        assertTrue(
            provider.forceFlush()
                .join(1, java.util.concurrent.TimeUnit.SECONDS)
                .isSuccess()
        );
        provider.close();
    }

    private static boolean hasMetric(Collection<MetricData> metrics, String name) {
        for (MetricData metric : metrics) {
            if (name.equals(metric.getName())) {
                return true;
            }
        }
        return false;
    }

    private static MetricData metric(Collection<MetricData> metrics, String name) {
        for (MetricData metric : metrics) {
            if (name.equals(metric.getName())) {
                return metric;
            }
        }
        throw new AssertionError("Missing metric " + name);
    }

    private static MetricsSnapshot snapshot(boolean extended) {
        MetricsSnapshot.ConnectionSnapshot sync =
            new MetricsSnapshot.ConnectionSnapshot(2, 3, 10, 5);
        MetricsSnapshot.ConnectionSnapshot async =
            new MetricsSnapshot.ConnectionSnapshot(1, 1, 4, 2);
        Map<com.aerospike.client.metrics.LatencyType, MetricsSnapshot.HistogramSnapshot> latency =
            new LinkedHashMap<>();
        MetricsSnapshot.NamespaceSnapshot namespace = new MetricsSnapshot.NamespaceSnapshot(
            "test",
            7,
            2,
            1,
            100,
            200,
            latency,
            Collections.emptyMap()
        );
        MetricsSnapshot.NodeSnapshot node = new MetricsSnapshot.NodeSnapshot(
            "N1",
            "127.0.0.1",
            3000,
            sync,
            async,
            0, 0, 0, 0, 0,
            0, 0, 0, 0, 7,
            0, 0, 0, 0, 0,
            0, 0, 0, 0, 0,
            Collections.emptyMap(),
            extended ? Collections.singletonList(namespace) : Collections.emptyList()
        );

        return new MetricsSnapshot(
            Instant.now(),
            extended,
            "prod",
            "java",
            "10.4.0",
            "orders",
            Collections.singletonMap("team", "payments"),
            3,
            1,
            8,
            2,
            2,
            7,
            4,
            3,
            12.5,
            1024,
            99,
            Collections.emptyList(),
            Collections.singletonList(node),
            node,
            MetricsSnapshot.HistogramType.LOGARITHMIC,
            MetricsSnapshot.LatencyUnit.MILLISECONDS,
            2,
            7
        );
    }
}

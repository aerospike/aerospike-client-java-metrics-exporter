/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.aerospike.client.metrics.LatencySnapshot;
import com.aerospike.client.metrics.LatencyType;
import com.aerospike.client.metrics.LatencyUnit;
import com.aerospike.client.metrics.MetricsSnapshot;
import com.aerospike.client.metrics.exporter.otel.OpenTelemetryMetricsExporter;

import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.HistogramSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;

@Tag("integration")
class TestAerospikePrometheusIntegration {
    @Test
    void exposesAerospikeMetricsOnRealHttpEndpoint() throws Exception {
        CustomPrometheusMetricReader reader =
            CustomPrometheusMetricReader.create(
                PrometheusConfig.builder().setPort(0).build()
            );
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        try {
            exporter.export(snapshot("primary", 42));
            reader.startHttpServer();

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:"
                        + reader.getHttpServerPort()
                        + "/metrics"
                )).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains(
                "aerospike_client_cluster_command_count_total"
            ));
            assertTrue(response.body().contains("client_id=\"primary\""));
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void exposesAerospikeAndApplicationMetricsInOneScrape() {
        CustomPrometheusMetricReader reader =
            CustomPrometheusMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        try {
            provider.get("application")
                .counterBuilder("application.jobs")
                .build()
                .add(9);
            exporter.export(snapshot("primary", 42));

            MetricSnapshots scrape = reader.getRegistry().scrape();
            assertTrue(hasMetric(
                scrape,
                "aerospike_client_cluster_command_count"
            ));
            assertTrue(hasMetric(scrape, "application_jobs"));
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void supportsPrometheusAndPeriodicReadersOnOneProvider() {
        CustomPrometheusMetricReader prometheus =
            CustomPrometheusMetricReader.create();
        InMemoryMetricExporter memory = InMemoryMetricExporter.create();
        PeriodicMetricReader periodic = PeriodicMetricReader.builder(memory)
            .setInterval(Duration.ofDays(1))
            .build();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(prometheus)
            .registerMetricReader(periodic)
            .build();
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        try {
            exporter.export(snapshot("primary", 42));
            assertTrue(hasMetric(
                prometheus.getRegistry().scrape(),
                "aerospike_client_cluster_command_count"
            ));

            assertTrue(provider.forceFlush()
                .join(5, TimeUnit.SECONDS)
                .isSuccess());
            assertTrue(memory.getFinishedMetricItems().stream().anyMatch(
                metric -> metric.getName().equals(
                    "aerospike.client.cluster.command_count"
                )
            ));
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void keepsMultipleAerospikeClientsIsolated() {
        CustomPrometheusMetricReader reader =
            CustomPrometheusMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        try {
            exporter.export(snapshot("primary", 42));
            exporter.export(snapshot("reporting", 7));

            CounterSnapshot commands = assertInstanceOf(
                CounterSnapshot.class,
                find(
                    reader.getRegistry().scrape(),
                    "aerospike_client_cluster_command_count"
                )
            );
            assertEquals(2, commands.getDataPoints().size());
            assertTrue(commands.getDataPoints().stream().anyMatch(point ->
                "primary".equals(point.getLabels().get("client_id"))
                    && point.getValue() == 42.0
            ));
            assertTrue(commands.getDataPoints().stream().anyMatch(point ->
                "reporting".equals(point.getLabels().get("client_id"))
                    && point.getValue() == 7.0
            ));
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void exposesAerospikeLatencyAsBucketCountersNotHistogram() {
        CustomPrometheusMetricReader reader =
            CustomPrometheusMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        try {
            exporter.export(snapshot("primary", 42));

            MetricSnapshots scrape = reader.getRegistry().scrape();
            CounterSnapshot latency = assertInstanceOf(
                CounterSnapshot.class,
                find(
                    scrape,
                    "aerospike_client_cluster_latency"
                )
            );
            assertTrue(latency.getDataPoints().stream().anyMatch(point ->
                "+Inf".equals(point.getLabels().get("le"))
            ));
            assertFalse(scrape.stream().anyMatch(metric ->
                metric instanceof HistogramSnapshot
                    && metric.getMetadata().getName().contains("latency")
            ));
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    private static MetricsSnapshot snapshot(
            String clientId,
            long commandCount
    ) {
        LatencySnapshot latency = new LatencySnapshot(
            LatencyType.READ,
            new long[] {2, 3},
            1L,
            10L,
            15.0,
            5
        );
        return new MetricsSnapshot(
            Instant.now(),
            true,
            true,
            false,
            "test-cluster",
            "java",
            "10.4.0",
            "integration-test",
            Map.of("client_id", clientId),
            1,
            0,
            0,
            0,
            0,
            commandCount,
            0,
            0,
            0,
            0,
            0.0,
            0,
            latency,
            null,
            null,
            null,
            0,
            0,
            0,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            LatencyUnit.MILLISECONDS,
            2,
            1
        );
    }

    private static boolean hasMetric(
            MetricSnapshots metrics,
            String name
    ) {
        return metrics.stream().anyMatch(metric ->
            name.equals(metric.getMetadata().getName())
        );
    }

    private static MetricSnapshot find(
            MetricSnapshots metrics,
            String name
    ) {
        return metrics.stream()
            .filter(metric -> name.equals(metric.getMetadata().getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "metric not found: "
                    + name
                    + "; available="
                    + metrics.stream()
                        .map(metric -> metric.getMetadata().getName())
                        .sorted()
                        .toList()
            ));
    }
}

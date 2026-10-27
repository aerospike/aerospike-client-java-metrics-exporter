/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.metrics.MetricsExporterType;
import com.aerospike.client.metrics.MetricsPolicy;

import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

@Tag("integration")
class TestOpenTelemetryMetricsExporterIntegration {
    @Test
    void exportsLiveClientSnapshotThroughCustomerProvider() throws Exception {
        TestAerospikeIntegrationSupport aerospike =
            new TestAerospikeIntegrationSupport();
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider =
            SdkMeterProvider.builder().registerMetricReader(reader).build();
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        AerospikeClient client = null;
        try {
            client = aerospike.newClient("metrics-otel-integration");
            MetricsPolicy policy = new MetricsPolicy();
            policy.exporter = MetricsExporterType.CUSTOM;
            policy.exportInterval = Duration.ofMillis(250);
            policy.enableOperationalMetrics = true;
            policy.labels = Map.of("client_id", "integration");
            policy.addExporter(exporter);
            client.enableMetrics(policy);

            aerospike.runWorkload(client, client, Duration.ofSeconds(1));
            Thread.sleep(500);

            assertTrue(reader.collectAllMetrics().stream().anyMatch(
                metric -> "aerospike_client_total_nodes".equals(metric.getName())
            ));

            client.disableMetrics();
            assertTrue(exporter.snapshotRegistry().currentSnapshots().stream()
                .anyMatch(snapshot -> !snapshot.metricsEnabled));
        }
        finally {
            if (client != null) {
                client.disableMetrics();
                client.close();
            }
            exporter.close();
            provider.close();
        }
    }
}

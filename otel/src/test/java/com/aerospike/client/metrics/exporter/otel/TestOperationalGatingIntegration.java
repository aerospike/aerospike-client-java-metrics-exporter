/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.metrics.MetricsPolicy;
import com.aerospike.client.metrics.MetricsSnapshot;

import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

@Tag("integration")
class TestOperationalGatingIntegration {
    @Test
    void livePolicyTransitionsGateOperationalMetrics() throws Exception {
        TestAerospikeIntegrationSupport aerospike =
            new TestAerospikeIntegrationSupport();
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider =
            SdkMeterProvider.builder().registerMetricReader(reader).build();
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        AerospikeClient client = null;
        try {
            client = aerospike.newClient("gating-app");
            MetricsPolicy standard =
                aerospike.metricsPolicy(exporter, "gating", false);
            standard.enableUsageMetrics = true;
            client.enableMetrics(standard);
            MetricsSnapshot standardSnapshot = aerospike.awaitSnapshot(
                exporter,
                snapshot -> snapshot.metricsEnabled
                    && !snapshot.operationalMetricsEnabled
            );

            Collection<MetricData> standardMetrics = reader.collectAllMetrics();
            assertTrue(TestOtelIntegrationSupport.contains(
                standardMetrics, "aerospike.client.cluster.total_nodes"
            ));
            assertFalse(TestOtelIntegrationSupport.contains(
                standardMetrics, "aerospike.client.cluster.command_count"
            ));
            assertFalse(TestOtelIntegrationSupport.contains(
                standardMetrics, "aerospike.client.feature.api_blocking"
            ));

            MetricsPolicy operational =
                aerospike.metricsPolicy(exporter, "gating", true);
            operational.enableUsageMetrics = true;
            client.enableMetrics(operational);
            aerospike.runOperations(client, client, 2);
            aerospike.awaitSnapshot(
                exporter,
                snapshot -> snapshot.metricsEnabled
                    && snapshot.operationalMetricsEnabled
                    && snapshot.timestamp.isAfter(standardSnapshot.timestamp)
                    && snapshot.commandCount > 0
            );

            Collection<MetricData> operationalMetrics =
                reader.collectAllMetrics();
            assertTrue(TestOtelIntegrationSupport.contains(
                operationalMetrics, "aerospike.client.cluster.command_count"
            ));
            assertFalse(TestOtelIntegrationSupport.contains(
                operationalMetrics, "aerospike.client.feature.api_blocking"
            ));

            MetricsSnapshot operationalSnapshot = aerospike.awaitSnapshot(
                exporter,
                snapshot -> snapshot.metricsEnabled
                    && snapshot.operationalMetricsEnabled
                    && snapshot.timestamp.isAfter(standardSnapshot.timestamp)
            );
            client.enableMetrics(standard);
            aerospike.awaitSnapshot(
                exporter,
                snapshot -> snapshot.metricsEnabled
                    && !snapshot.operationalMetricsEnabled
                    && snapshot.timestamp.isAfter(operationalSnapshot.timestamp)
            );
            Collection<MetricData> standardAgain = reader.collectAllMetrics();
            assertTrue(TestOtelIntegrationSupport.contains(
                standardAgain, "aerospike.client.cluster.total_nodes"
            ));
            assertFalse(TestOtelIntegrationSupport.contains(
                standardAgain, "aerospike.client.cluster.command_count"
            ));
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

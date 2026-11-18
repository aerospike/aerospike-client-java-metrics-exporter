/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.metrics.MetricsPolicy;
import com.aerospike.client.metrics.MetricsSnapshot;

import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

@Tag("integration")
class TestExporterLifecycleIntegration {
    @Test
    void providerAndApplicationMetricsSurviveDisableReEnableAndAdapterClose()
            throws Exception {
        TestAerospikeIntegrationSupport aerospike =
            new TestAerospikeIntegrationSupport();
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider =
            SdkMeterProvider.builder().registerMetricReader(reader).build();
        LongCounter applicationJobs = provider.get("application")
            .counterBuilder("application.jobs")
            .build();
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        AerospikeClient client = null;
        try {
            client = aerospike.newClient("lifecycle-app");
            MetricsPolicy policy =
                aerospike.metricsPolicy(exporter, "lifecycle", true);
            client.enableMetrics(policy);
            aerospike.runOperations(client, client, 2);

            MetricsSnapshot enabled = aerospike.awaitSnapshot(
                exporter,
                snapshot -> snapshot.metricsEnabled
                    && "lifecycle".equals(snapshot.labels.get("client_id"))
            );

            client.disableMetrics();
            MetricsSnapshot disabled = aerospike.awaitSnapshot(
                exporter,
                snapshot -> !snapshot.metricsEnabled
                    && "lifecycle".equals(snapshot.labels.get("client_id"))
            );
            assertFalse(disabled.timestamp.isBefore(enabled.timestamp));

            applicationJobs.add(1L);
            Collection<MetricData> whileDisabled = reader.collectAllMetrics();
            assertTrue(TestOtelIntegrationSupport.contains(
                whileDisabled, "aerospike.client.cluster.total_nodes"
            ));
            assertEquals(
                1L,
                TestOtelIntegrationSupport.firstLongValue(
                    TestOtelIntegrationSupport.metric(
                        whileDisabled, "application.jobs"
                    )
                )
            );

            client.enableMetrics(policy);
            MetricsSnapshot reenabled = aerospike.awaitSnapshot(
                exporter,
                snapshot -> snapshot.metricsEnabled
                    && snapshot.timestamp.isAfter(disabled.timestamp)
            );
            assertTrue(reenabled.metricsEnabled);
            client.disableMetrics();

            exporter.close();
            applicationJobs.add(2L);
            Collection<MetricData> afterAdapterClose = reader.collectAllMetrics();
            assertFalse(TestOtelIntegrationSupport.contains(
                afterAdapterClose, "aerospike.client.cluster.total_nodes"
            ));
            assertEquals(
                3L,
                TestOtelIntegrationSupport.firstLongValue(
                    TestOtelIntegrationSupport.metric(
                        afterAdapterClose, "application.jobs"
                    )
                )
            );
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

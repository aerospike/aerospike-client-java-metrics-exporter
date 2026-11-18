/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.metrics.MetricsPolicy;
import com.aerospike.client.metrics.MetricsSnapshot;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

@Tag("integration")
class TestLiveMetricValuesIntegration {
    @Test
    void knownWorkloadAdvancesCommandsNamespaceBytesAndLatency() throws Exception {
        TestAerospikeIntegrationSupport aerospike =
            new TestAerospikeIntegrationSupport();
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider =
            SdkMeterProvider.builder().registerMetricReader(reader).build();
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        AerospikeClient client = null;
        try {
            client = aerospike.newClient("values-app");
            MetricsPolicy policy =
                aerospike.metricsPolicy(exporter, "values", true);
            client.enableMetrics(policy);
            MetricsSnapshot baseline = aerospike.awaitSnapshot(
                exporter,
                snapshot -> snapshot.metricsEnabled
                    && "values".equals(snapshot.labels.get("client_id"))
            );

            int iterations = 4;
            aerospike.runOperations(client, client, iterations);
            MetricsSnapshot updated = aerospike.awaitSnapshot(
                exporter,
                snapshot -> snapshot.metricsEnabled
                    && snapshot.timestamp.isAfter(baseline.timestamp)
                    && snapshot.commandCount
                        >= baseline.commandCount + iterations * 3L
            );
            assertEquals(
                baseline.commandCount + iterations * 3L,
                updated.commandCount
            );

            Collection<MetricData> metrics = reader.collectAllMetrics();
            assertEquals(updated.commandCount, TestOtelIntegrationSupport.firstLongValue(
                TestOtelIntegrationSupport.metric(
                    metrics, "aerospike.client.cluster.command_count"
                )
            ));
            assertTrue(TestOtelIntegrationSupport.firstLongValue(
                TestOtelIntegrationSupport.metric(
                    metrics, "aerospike.client.namespace.bytes_in"
                )
            ) > 0L);
            assertTrue(TestOtelIntegrationSupport.firstLongValue(
                TestOtelIntegrationSupport.metric(
                    metrics, "aerospike.client.namespace.bytes_out"
                )
            ) > 0L);

            MetricData latency = TestOtelIntegrationSupport.metric(
                metrics, "aerospike.client.cluster.latency_bucket"
            );
            assertTrue(latency.getLongSumData().getPoints().stream().anyMatch(
                point -> "+Inf".equals(point.getAttributes().get(
                    AttributeKey.stringKey("le")
                )) && point.getValue() > 0L
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

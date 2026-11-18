/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
class TestMultipleClientsIntegration {
    @Test
    void oneAdapterKeepsTwoLiveClientsIndependent() throws Exception {
        TestAerospikeIntegrationSupport aerospike =
            new TestAerospikeIntegrationSupport();
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider =
            SdkMeterProvider.builder().registerMetricReader(reader).build();
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        AerospikeClient orders = null;
        AerospikeClient billing = null;
        try {
            orders = aerospike.newClient("orders-app");
            billing = aerospike.newClient("billing-app");
            MetricsPolicy ordersPolicy =
                aerospike.metricsPolicy(exporter, "orders", true);
            MetricsPolicy billingPolicy =
                aerospike.metricsPolicy(exporter, "billing", true);
            orders.enableMetrics(ordersPolicy);
            billing.enableMetrics(billingPolicy);

            aerospike.runOperations(orders, billing, 3);
            MetricsSnapshot ordersSnapshot = aerospike.awaitSnapshot(
                exporter,
                snapshot -> snapshot.metricsEnabled
                    && "orders".equals(snapshot.labels.get("client_id"))
                    && snapshot.commandCount > 0
            );
            MetricsSnapshot billingSnapshot = aerospike.awaitSnapshot(
                exporter,
                snapshot -> snapshot.metricsEnabled
                    && "billing".equals(snapshot.labels.get("client_id"))
                    && snapshot.commandCount > 0
            );

            Collection<MetricData> metrics = reader.collectAllMetrics();
            Map<String, Long> commands = TestOtelIntegrationSupport.metric(
                metrics,
                "aerospike.client.cluster.command_count"
            ).getLongSumData().getPoints().stream().collect(Collectors.toMap(
                point -> point.getAttributes().get(
                    AttributeKey.stringKey("client_id")
                ),
                point -> point.getValue()
            ));
            assertEquals(2, commands.size());
            assertEquals(ordersSnapshot.commandCount, commands.get("orders"));
            assertEquals(billingSnapshot.commandCount, commands.get("billing"));
            TestOtelIntegrationSupport.metric(
                metrics, "aerospike.client.cluster.command_count"
            ).getLongSumData().getPoints().forEach(point -> {
                String clientId = point.getAttributes().get(
                    AttributeKey.stringKey("client_id")
                );
                MetricsSnapshot snapshot = "orders".equals(clientId)
                    ? ordersSnapshot
                    : billingSnapshot;
                assertEquals(
                    snapshot.appId,
                    point.getAttributes().get(AttributeKey.stringKey("app_id"))
                );
                String cluster = point.getAttributes().get(
                    AttributeKey.stringKey("cluster")
                );
                if (snapshot.clusterName == null
                        || snapshot.clusterName.isEmpty()) {
                    assertNull(cluster);
                }
                else {
                    assertEquals(snapshot.clusterName, cluster);
                }
            });

            orders.disableMetrics();
            aerospike.awaitSnapshot(
                exporter,
                snapshot -> !snapshot.metricsEnabled
                    && "orders".equals(snapshot.labels.get("client_id"))
            );
            long billingBefore = billingSnapshot.commandCount;
            aerospike.runOperations(billing, billing, 2);
            aerospike.awaitSnapshot(
                exporter,
                snapshot -> snapshot.metricsEnabled
                    && "billing".equals(snapshot.labels.get("client_id"))
                    && snapshot.commandCount > billingBefore
            );

            Map<String, MetricsSnapshot> snapshots =
                exporter.snapshotRegistry().currentSnapshots().stream()
                    .collect(Collectors.toMap(
                        snapshot -> snapshot.labels.get("client_id"),
                        Function.identity()
                    ));
            assertEquals(2, snapshots.size());
            assertFalse(snapshots.get("orders").metricsEnabled);
            assertTrue(snapshots.get("billing").metricsEnabled);
        }
        finally {
            if (orders != null) {
                orders.disableMetrics();
                orders.close();
            }
            if (billing != null) {
                billing.disableMetrics();
                billing.close();
            }
            exporter.close();
            provider.close();
        }
    }
}

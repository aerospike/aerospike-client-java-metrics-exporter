/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.async.NioEventLoops;
import com.aerospike.client.metrics.MetricsPolicy;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

@Tag("integration")
class TestAsyncMetricsIntegration {
    @Test
    void publishesEventLoopAndSyncAsyncConnectionMetrics() throws Exception {
        TestAerospikeIntegrationSupport aerospike =
            new TestAerospikeIntegrationSupport();
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider =
            SdkMeterProvider.builder().registerMetricReader(reader).build();
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        NioEventLoops eventLoops = new NioEventLoops(1);
        AerospikeClient client = null;
        try {
            client = aerospike.newAsyncClient("async-app", eventLoops);
            MetricsPolicy policy =
                aerospike.metricsPolicy(exporter, "async", true);
            client.enableMetrics(policy);
            aerospike.runAsyncWorkload(
                client, eventLoops, Duration.ofMillis(500)
            );
            aerospike.awaitSnapshot(
                exporter,
                snapshot -> snapshot.metricsEnabled
                    && snapshot.commandCount > 0
                    && "async".equals(snapshot.labels.get("client_id"))
            );

            Collection<MetricData> metrics = reader.collectAllMetrics();
            assertTrue(TestOtelIntegrationSupport.contains(
                metrics, "aerospike.client.eventloop.process_size"
            ));
            assertTrue(TestOtelIntegrationSupport.contains(
                metrics, "aerospike.client.eventloop.queue_size"
            ));

            Set<String> connectionTypes = TestOtelIntegrationSupport.metric(
                metrics,
                "aerospike.client.node.connections_opened"
            ).getLongSumData().getPoints().stream()
                .map(point -> point.getAttributes().get(
                    AttributeKey.stringKey("conn_type")
                ))
                .collect(Collectors.toSet());
            assertEquals(Set.of("sync", "async"), connectionTypes);
        }
        finally {
            if (client != null) {
                client.disableMetrics();
                client.close();
            }
            eventLoops.close();
            exporter.close();
            provider.close();
        }
    }
}

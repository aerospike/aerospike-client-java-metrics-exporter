/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.metrics.MetricsPolicy;
import com.aerospike.client.metrics.MetricsSnapshot;
import com.aerospike.client.policy.ClientPolicy;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class TestServerDownIntegration {
    @Test
    void metricsContinueAfterTheOnlyServerStops() throws Exception {
        runServerLossScenario(false);
    }

    @Disabled("CM-28: client-java does not populate MetricsSnapshot.nodesDeparted")
    @Test
    void departedNodeSeriesAppearAfterServerLoss() throws Exception {
        runServerLossScenario(true);
    }

    private static void runServerLossScenario(boolean requireDeparted)
            throws Exception {
        GenericContainer<?> server = new GenericContainer<>(
            DockerImageName.parse("aerospike/aerospike-server:latest")
        )
            .withExposedPorts(3000)
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofMinutes(2));

        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider =
            SdkMeterProvider.builder().registerMetricReader(reader).build();
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        AerospikeClient client = null;
        try {
            server.start();
            TestAerospikeIntegrationSupport aerospike =
                new TestAerospikeIntegrationSupport(
                    server.getHost(),
                    server.getMappedPort(3000),
                    "test"
                );
            client = awaitClientReady(aerospike);
            MetricsPolicy metricsPolicy =
                aerospike.metricsPolicy(exporter, "server-down", true);
            client.enableMetrics(metricsPolicy);
            aerospike.runOperations(client, client, 1);
            MetricsSnapshot before = aerospike.awaitSnapshot(
                exporter,
                snapshot -> snapshot.metricsEnabled
                    && snapshot.commandCount > 0
            );
            assertFalse(before.nodes.isEmpty());
            String stoppedNodeId = before.nodes.get(0).nodeName;

            server.stop();
            MetricsSnapshot after = aerospike.awaitSnapshot(
                exporter,
                snapshot -> snapshot.metricsEnabled
                    && snapshot.timestamp.isAfter(before.timestamp)
            );
            Collection<MetricData> metrics = reader.collectAllMetrics();
            assertTrue(TestOtelIntegrationSupport.contains(
                metrics, "aerospike.client.cluster.total_nodes"
            ));

            if (requireDeparted) {
                assertTrue(after.nodesDeparted.stream().anyMatch(
                    node -> stoppedNodeId.equals(node.nodeName)
                ));
                assertFalse(after.nodes.stream().anyMatch(
                    node -> stoppedNodeId.equals(node.nodeName)
                ));
                MetricData departed = TestOtelIntegrationSupport.metric(
                    metrics, "aerospike.client.node.connections_opened"
                );
                assertTrue(departed.getLongSumData().getPoints().stream().anyMatch(
                    point -> stoppedNodeId.equals(point.getAttributes().get(
                        AttributeKey.stringKey("server_node_id")
                    ))
                ));
            }
        }
        finally {
            if (client != null) {
                client.disableMetrics();
                client.close();
            }
            if (server.isRunning()) {
                server.stop();
            }
            exporter.close();
            provider.close();
        }
    }

    private static AerospikeClient awaitClientReady(
            TestAerospikeIntegrationSupport aerospike
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        IllegalStateException latest = null;
        while (System.nanoTime() < deadline) {
            ClientPolicy policy = aerospike.clientPolicy("server-down-app");
            policy.forceSingleNode = true;
            policy.tendInterval = 250;
            try {
                return aerospike.newClient(policy);
            }
            catch (IllegalStateException e) {
                latest = e;
                TimeUnit.MILLISECONDS.sleep(100);
            }
        }
        throw new AssertionError(
            "Aerospike container did not become client-ready", latest
        );
    }
}

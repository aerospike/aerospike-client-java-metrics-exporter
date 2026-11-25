/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.metrics.MetricsPolicy;

import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class TestOtlpCollectorIntegration {
    private static final int GRPC_PORT = 4317;
    private static final int HTTP_PORT = 4318;
    private static final int PROMETHEUS_PORT = 9464;

    @Container
    private static final GenericContainer<?> COLLECTOR =
        new GenericContainer<>(
            DockerImageName.parse(
                "otel/opentelemetry-collector-contrib:0.161.0"
            )
        )
            .withExposedPorts(GRPC_PORT, HTTP_PORT, PROMETHEUS_PORT)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource(
                    "otel-collector-integration.yml"
                ),
                "/etc/otelcol-contrib/config.yaml"
            )
            .withCommand("--config=/etc/otelcol-contrib/config.yaml")
            .waitingFor(
                Wait.forHttp("/metrics")
                    .forPort(PROMETHEUS_PORT)
                    .forStatusCode(200)
            );

    @ParameterizedTest(name = "OTLP {0}")
    @ValueSource(strings = {"grpc", "http"})
    void exportsDottedSchemaAndCanonicalLabelsThroughCollector(String protocol)
            throws Exception {
        TestAerospikeIntegrationSupport aerospike =
            new TestAerospikeIntegrationSupport();
        MetricExporter transport = transport(protocol);
        PeriodicMetricReader reader = PeriodicMetricReader.builder(transport)
            .setInterval(Duration.ofMillis(50))
            .build();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        AerospikeClient firstClient = null;
        AerospikeClient secondClient = null;
        String firstClientId = "collector-" + protocol + "-first";
        String secondClientId = "collector-" + protocol + "-second";
        try {
            firstClient = aerospike.newClient(firstClientId + "-app");
            secondClient = aerospike.newClient(secondClientId + "-app");
            MetricsPolicy firstPolicy =
                aerospike.metricsPolicy(exporter, firstClientId, true);
            MetricsPolicy secondPolicy =
                aerospike.metricsPolicy(exporter, secondClientId, true);
            firstClient.enableMetrics(firstPolicy);
            secondClient.enableMetrics(secondPolicy);
            aerospike.runOperations(firstClient, firstClient, 2);
            aerospike.runOperations(secondClient, secondClient, 2);
            aerospike.awaitSnapshot(
                exporter,
                snapshot -> snapshot.metricsEnabled
                    && snapshot.commandCount > 0
                    && firstClientId.equals(snapshot.labels.get("client_id"))
            );
            aerospike.awaitSnapshot(
                exporter,
                snapshot -> snapshot.metricsEnabled
                    && snapshot.commandCount > 0
                    && secondClientId.equals(snapshot.labels.get("client_id"))
            );

            String scrape = awaitScrape(firstClientId, secondClientId);
            assertMetricLine(
                scrape, "aerospike_client_cluster_total_nodes", firstClientId
            );
            assertMetricLine(
                scrape, "aerospike_client_cluster_total_nodes", secondClientId
            );
            assertMetricLine(
                scrape,
                "aerospike_client_node_connections_opened",
                firstClientId,
                "server_node_id=",
                "server_node_address=",
                "server_node_port="
            );
            assertMetricLine(
                scrape,
                "aerospike_client_namespace_bytes_in",
                firstClientId,
                "ns=\"" + aerospike.namespace() + "\""
            );
            assertMetricLine(
                scrape,
                "aerospike_client_cluster_latency_bucket",
                firstClientId,
                "latency_type=",
                "latency_unit=",
                "le="
            );
        }
        finally {
            if (firstClient != null) {
                firstClient.disableMetrics();
                firstClient.close();
            }
            if (secondClient != null) {
                secondClient.disableMetrics();
                secondClient.close();
            }
            exporter.close();
            provider.close();
        }
    }

    private static MetricExporter transport(String protocol) {
        String host = COLLECTOR.getHost();
        if ("grpc".equals(protocol)) {
            return OtlpGrpcMetricExporter.builder()
                .setEndpoint(
                    "http://" + host + ":" + COLLECTOR.getMappedPort(GRPC_PORT)
                )
                .build();
        }
        return OtlpHttpMetricExporter.builder()
            .setEndpoint(
                "http://" + host + ":" + COLLECTOR.getMappedPort(HTTP_PORT)
                    + "/v1/metrics"
            )
            .build();
    }

    private static String awaitScrape(String... clientIds) throws Exception {
        String endpoint =
            "http://" + COLLECTOR.getHost() + ":"
                + COLLECTOR.getMappedPort(PROMETHEUS_PORT) + "/metrics";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        String latest = "";
        while (System.nanoTime() < deadline) {
            String body = get(endpoint);
            latest = body;
            boolean allClientsPresent = java.util.Arrays.stream(clientIds)
                .allMatch(clientId -> body.contains(
                    "client_id=\"" + clientId + "\""
                ));
            if (allClientsPresent
                    && body.contains("aerospike_client_cluster_total_nodes")) {
                return body;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError(
            "Collector did not expose metrics for "
                + java.util.Arrays.toString(clientIds) + ": " + latest
        );
    }

    private static void assertMetricLine(
            String scrape,
            String metricName,
            String clientId,
            String... expectedFragments
    ) {
        boolean found = Arrays.stream(scrape.split("\\R"))
            .filter(line -> line.startsWith(metricName))
            .filter(line -> line.contains("client_id=\"" + clientId + "\""))
            .anyMatch(line -> java.util.Arrays.stream(expectedFragments)
                .allMatch(line::contains));
        assertTrue(
            found,
            () -> "Missing " + metricName + " line for " + clientId
        );
    }

    private static String get(String endpoint) throws Exception {
        HttpURLConnection connection =
            (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(5_000);
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4_096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
        finally {
            connection.disconnect();
        }
    }
}

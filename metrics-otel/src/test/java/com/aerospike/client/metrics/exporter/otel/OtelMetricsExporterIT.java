/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.async.NioEventLoops;
import com.aerospike.client.metrics.MetricsPolicy;
import com.aerospike.client.metrics.exporter.test.TlsTestCertificates;

class OtelMetricsExporterIT {
    private static final DockerImageName COLLECTOR_IMAGE = DockerImageName.parse(
        "otel/opentelemetry-collector-contrib:0.161.0"
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void exportsSharedClientsOverGrpcAndHttpProtobuf() throws Exception {
        try (GenericContainer<?> collector = collector()) {
            collector.start();
            verifyProtocol(collector, OtelExporterConfig.Protocol.GRPC, 4317, "grpc");
            verifyProtocol(
                collector,
                OtelExporterConfig.Protocol.HTTP_PROTOBUF,
                4318,
                "http"
            );
        }
    }

    @Test
    void exportsThroughMutuallyAuthenticatedTlsCollector() throws Exception {
        TlsTestCertificates certificates = TlsTestCertificates.create(
            temporaryDirectory.resolve("collector-mtls")
        );
        try (GenericContainer<?> collector = mutualTlsCollector(certificates)) {
            collector.start();
            OtelMetricsExporter exporter = OtelMetricsExporter.builder()
                .endpoint(
                    "https://" + collector.getHost() + ":"
                        + collector.getMappedPort(4317)
                )
                .protocol(OtelExporterConfig.Protocol.GRPC)
                .trustedCa(certificates.caCertificate())
                .clientCertificate(certificates.clientCertificate())
                .clientKey(certificates.clientKey())
                .exportInterval(Duration.ofSeconds(1))
                .build();
            AerospikeClient client = null;
            try {
                AerospikeIntegrationSupport aerospike = new AerospikeIntegrationSupport();
                client = aerospike.newClient("metrics-otel-mtls");
                client.enableMetrics(policy("mtls-client", exporter));
                aerospike.runWorkload(client, client, Duration.ofSeconds(2));
                awaitCollectorMetric(
                    collector,
                    "client_id=\"mtls-client\"",
                    Duration.ofSeconds(12)
                );
            }
            finally {
                closeClient(client);
                exporter.close();
            }
        }
    }

    @Test
    void omitsStaleSharedClientAndResumesWithRetainedStore() throws Exception {
        try (GenericContainer<?> collector = freshnessCollector()) {
            collector.start();
            OtelMetricsExporter exporter = OtelMetricsExporter.builder()
                .endpoint(
                    "http://" + collector.getHost() + ":"
                        + collector.getMappedPort(4318) + "/v1/metrics"
                )
                .protocol(OtelExporterConfig.Protocol.HTTP_PROTOBUF)
                .exportInterval(Duration.ofSeconds(1))
                .snapshotStaleTimeout("3s")
                .build();
            AerospikeIntegrationSupport aerospike = new AerospikeIntegrationSupport();
            AerospikeClient primary = null;
            AerospikeClient reporting = null;
            try {
                primary = aerospike.newClient("metrics-otel-stale-primary");
                reporting = aerospike.newClient("metrics-otel-stale-reporting");
                MetricsPolicy primaryPolicy = policy("stale-primary", exporter);
                primary.enableMetrics(primaryPolicy);
                reporting.enableMetrics(policy("stale-reporting", exporter));
                aerospike.runWorkload(primary, reporting, Duration.ofSeconds(2));
                awaitCollectorMetric(
                    collector,
                    "client_id=\"stale-primary\"",
                    Duration.ofSeconds(12)
                );
                Object primaryStore =
                    exporter.snapshotRegistry().snapshotStore("stale-primary");

                primary.disableMetrics();
                aerospike.runWorkload(reporting, reporting, Duration.ofSeconds(6));
                String stale = awaitCollectorWithoutClient(
                    collector,
                    "stale-primary",
                    "stale-reporting",
                    Duration.ofSeconds(12)
                );
                assertFalse(stale.contains("client_id=\"stale-primary\""));
                assertTrue(stale.contains("client_id=\"stale-reporting\""));
                assertFalse(exporter.snapshotRegistry().currentSnapshots().stream().anyMatch(
                    snapshot -> "stale-primary".equals(snapshot.labels.get("client_id"))
                ));

                primary.enableMetrics(primaryPolicy);
                aerospike.runWorkload(primary, reporting, Duration.ofSeconds(2));
                awaitCollectorMetric(
                    collector,
                    "client_id=\"stale-primary\"",
                    Duration.ofSeconds(12)
                );
                assertSame(
                    primaryStore,
                    exporter.snapshotRegistry().snapshotStore("stale-primary")
                );
            }
            finally {
                closeClient(primary);
                closeClient(reporting);
                exporter.close();
            }
        }
    }

    @Test
    void exportsLiveSyncAsyncNamespaceEventLoopAndExtendedMetrics() throws Exception {
        try (GenericContainer<?> collector = collector()) {
            collector.start();
            OtelMetricsExporter exporter = OtelMetricsExporter.builder()
                .endpoint(
                    "http://" + collector.getHost() + ":"
                        + collector.getMappedPort(4318) + "/v1/metrics"
                )
                .protocol(OtelExporterConfig.Protocol.HTTP_PROTOBUF)
                .exportInterval(Duration.ofSeconds(1))
                .snapshotStaleTimeout("5m")
                .build();
            AerospikeIntegrationSupport aerospike = new AerospikeIntegrationSupport();
            NioEventLoops eventLoops = new NioEventLoops(1);
            AerospikeClient client = null;
            try {
                client = aerospike.newAsyncClient("metrics-otel-async", eventLoops);
                client.enableMetrics(policy("async-live", exporter));
                aerospike.runWorkload(client, client, Duration.ofSeconds(1));
                aerospike.runAsyncWorkload(client, eventLoops, Duration.ofSeconds(2));
                String body = awaitCollectorMetric(
                    collector,
                    "aerospike_client_eventloop_process_size",
                    Duration.ofSeconds(12)
                );
                assertTrue(body.contains("client_id=\"async-live\""));
                assertTrue(body.contains("aerospike_client_node_connections_in_use"));
                assertTrue(body.contains("conn_type=\"sync\""));
                assertTrue(body.contains("conn_type=\"async\""));
                assertTrue(body.contains("aerospike_client_namespace_bytes_in"));
                assertTrue(body.contains("namespace=\"" + aerospike.namespace() + "\""));
                assertTrue(body.contains("aerospike_client_eventloop_queue_size"));
                assertTrue(body.contains("aerospike_client_cpu_percent"));
                assertTrue(body.contains("aerospike_client_memory_bytes"));
                assertTrue(body.contains("aerospike_client_command_count"));
                assertTrue(body.contains(
                    "aerospike_client_namespace_latency_read_bucket"
                ));
                assertTrue(body.contains("le="));
            }
            finally {
                closeClient(client);
                eventLoops.close();
                exporter.close();
            }
        }
    }

    private static void verifyProtocol(
            GenericContainer<?> collector,
            OtelExporterConfig.Protocol protocol,
            int containerPort,
            String idPrefix
    ) throws Exception {
        AerospikeIntegrationSupport aerospike = new AerospikeIntegrationSupport();
        String endpoint = "http://" + collector.getHost() + ":"
            + collector.getMappedPort(containerPort);
        if (protocol == OtelExporterConfig.Protocol.HTTP_PROTOBUF) {
            endpoint += "/v1/metrics";
        }
        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .endpoint(endpoint)
            .protocol(protocol)
            .serviceName("aerospike-metrics-" + idPrefix + "-it")
            .exportInterval(Duration.ofSeconds(1))
            .snapshotStaleTimeout("3s")
            .build();
        AerospikeClient primary = null;
        AerospikeClient reporting = null;
        try {
            String primaryId = idPrefix + "-primary";
            String reportingId = idPrefix + "-reporting";
            primary = aerospike.newClient("metrics-otel-" + primaryId);
            reporting = aerospike.newClient("metrics-otel-" + reportingId);
            primary.enableMetrics(policy(primaryId, exporter));
            reporting.enableMetrics(policy(reportingId, exporter));

            collector.getDockerClient().pauseContainerCmd(collector.getContainerId()).exec();
            try {
                aerospike.runWorkload(primary, reporting, Duration.ofSeconds(2));
            }
            finally {
                collector.getDockerClient()
                    .unpauseContainerCmd(collector.getContainerId())
                    .exec();
            }
            aerospike.runWorkload(primary, reporting, Duration.ofSeconds(2));

            String body = awaitCollectorMetric(
                collector,
                "client_id=\"" + primaryId + "\"",
                Duration.ofSeconds(12)
            );
            assertTrue(body.contains("client_id=\"" + reportingId + "\""));
            assertTrue(body.contains("aerospike_client_total_nodes"));
            assertTrue(body.contains("aerospike_client_command_count"));
            assertTrue(body.contains("aerospike_client_namespace_latency_read_bucket"));
            assertTrue(body.contains("le="));
        }
        finally {
            closeClient(primary);
            closeClient(reporting);
            exporter.close();
        }
    }

    private static MetricsPolicy policy(String clientId, OtelMetricsExporter exporter) {
        MetricsPolicy policy = new MetricsPolicy();
        policy.interval = 1;
        policy.enableExtendedMetrics = true;
        policy.labels = Collections.singletonMap("client_id", clientId);
        policy.addExporter(exporter);
        return policy;
    }

    @SuppressWarnings("resource") // The caller owns the returned container.
    private static GenericContainer<?> collector() {
        return new GenericContainer<>(COLLECTOR_IMAGE)
            .withCopyToContainer(
                MountableFile.forClasspathResource("otel-collector-it.yml"),
                "/etc/otelcol-contrib/config.yaml"
            )
            .withCommand("--config=/etc/otelcol-contrib/config.yaml")
            .withExposedPorts(4317, 4318, 9464)
            .waitingFor(Wait.forHttp("/metrics").forPort(9464));
    }

    @SuppressWarnings("resource") // The caller owns the returned container.
    private static GenericContainer<?> mutualTlsCollector(
            TlsTestCertificates certificates
    ) {
        return new GenericContainer<>(COLLECTOR_IMAGE)
            .withCopyToContainer(
                MountableFile.forClasspathResource("otel-collector-mtls-it.yml"),
                "/etc/otelcol-contrib/config.yaml"
            )
            .withCopyToContainer(
                MountableFile.forHostPath(certificates.caCertificate()),
                "/etc/otelcol-contrib/tls/ca.pem"
            )
            .withCopyToContainer(
                MountableFile.forHostPath(certificates.serverCertificate()),
                "/etc/otelcol-contrib/tls/server-cert.pem"
            )
            .withCopyToContainer(
                MountableFile.forHostPath(certificates.serverKey()),
                "/etc/otelcol-contrib/tls/server-key.pem"
            )
            .withCommand("--config=/etc/otelcol-contrib/config.yaml")
            .withExposedPorts(4317, 9464)
            .waitingFor(Wait.forHttp("/metrics").forPort(9464));
    }

    @SuppressWarnings("resource") // The caller owns the returned container.
    private static GenericContainer<?> freshnessCollector() {
        return new GenericContainer<>(COLLECTOR_IMAGE)
            .withCopyToContainer(
                MountableFile.forClasspathResource("otel-collector-freshness-it.yml"),
                "/etc/otelcol-contrib/config.yaml"
            )
            .withCommand("--config=/etc/otelcol-contrib/config.yaml")
            .withExposedPorts(4318, 9464)
            .waitingFor(Wait.forHttp("/metrics").forPort(9464));
    }

    private static String awaitCollectorMetric(
            GenericContainer<?> collector,
            String expected,
            Duration timeout
    ) throws Exception {
        URI endpoint = URI.create(
            "http://" + collector.getHost() + ":"
                + collector.getMappedPort(9464) + "/metrics"
        );
        long deadline = System.nanoTime() + timeout.toNanos();
        String body = "";
        while (System.nanoTime() < deadline) {
            body = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            ).body();
            if (body.contains(expected)) {
                return body;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for " + expected + "\n" + body);
    }

    private static String awaitCollectorWithoutClient(
            GenericContainer<?> collector,
            String absentClientId,
            String presentClientId,
            Duration timeout
    ) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String body = "";
        String absent = "client_id=\"" + absentClientId + "\"";
        String present = "client_id=\"" + presentClientId + "\"";
        while (System.nanoTime() < deadline) {
            body = collectorMetrics(collector);
            if (!body.contains(absent) && body.contains(present)) {
                return body;
            }
            Thread.sleep(200);
        }
        throw new AssertionError(
            "Timed out waiting for " + absentClientId + " to disappear\n" + body
        );
    }

    private static String collectorMetrics(GenericContainer<?> collector) throws Exception {
        URI endpoint = URI.create(
            "http://" + collector.getHost() + ":"
                + collector.getMappedPort(9464) + "/metrics"
        );
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        ).body();
    }

    private static void closeClient(AerospikeClient client) {
        if (client != null) {
            client.close();
        }
    }
}

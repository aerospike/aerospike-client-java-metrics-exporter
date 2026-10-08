/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.async.NioEventLoops;
import com.aerospike.client.metrics.MetricsPolicy;

class PrometheusMetricsExporterIT {
    @Test
    void sharesOneEndpointAcrossClientDisableAndReenable() throws Exception {
        AerospikeIntegrationSupport aerospike = new AerospikeIntegrationSupport();
        PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .port(0)
            .snapshotStaleTimeout("3s")
            .build();
        AerospikeClient primary = null;
        AerospikeClient reporting = null;
        try {
            primary = aerospike.newClient("metrics-prometheus-primary");
            reporting = aerospike.newClient("metrics-prometheus-reporting");
            MetricsPolicy primaryPolicy = policy("primary", exporter);
            MetricsPolicy reportingPolicy = policy("reporting", exporter);
            primary.enableMetrics(primaryPolicy);
            reporting.enableMetrics(reportingPolicy);

            aerospike.runWorkload(primary, reporting, Duration.ofSeconds(2));
            String initial = awaitMetrics(exporter, "client_id=\"primary\"", Duration.ofSeconds(8));
            assertTrue(initial.contains("client_id=\"reporting\""));
            assertTrue(initial.contains("aerospike_client_total_nodes"));
            assertTrue(initial.contains("aerospike_client_command_count_total"));
            assertTrue(initial.contains("aerospike_client_namespace_latency_"));
            Object primaryStore = exporter.snapshotRegistry().snapshotStore("primary");

            primary.disableMetrics();
            aerospike.runWorkload(reporting, reporting, Duration.ofSeconds(4));
            String stale = scrape(exporter);
            assertFalse(stale.contains("client_id=\"primary\""));
            assertTrue(stale.contains("client_id=\"reporting\""));

            primary.enableMetrics(primaryPolicy);
            aerospike.runWorkload(primary, reporting, Duration.ofSeconds(2));
            awaitMetrics(exporter, "client_id=\"primary\"", Duration.ofSeconds(8));
            assertSame(primaryStore, exporter.snapshotRegistry().snapshotStore("primary"));
        }
        finally {
            closeClient(primary);
            closeClient(reporting);
            exporter.close();
        }
    }

    @Test
    void exportsCoreOnlyWhenExtendedMetricsAreDisabled() throws Exception {
        AerospikeIntegrationSupport aerospike = new AerospikeIntegrationSupport();
        PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .port(0)
            .snapshotStaleTimeout("5m")
            .build();
        AerospikeClient client = null;
        try {
            client = aerospike.newClient("metrics-prometheus-core-only");
            client.enableMetrics(policy("core-only", exporter, false));
            aerospike.runWorkload(client, client, Duration.ofSeconds(2));
            String body = awaitMetrics(
                exporter,
                "aerospike_client_total_nodes",
                Duration.ofSeconds(8)
            );
            assertTrue(body.contains("aerospike_client_open_connections"));
            assertTrue(body.contains("aerospike_client_retry_count_total"));
            assertTrue(body.contains("aerospike_client_node_connections_in_use"));
            assertTrue(body.contains("conn_type=\"sync\""));
            assertTrue(body.contains("conn_type=\"async\""));

            assertFalse(body.contains("aerospike_client_command_count_total"));
            assertFalse(body.contains("aerospike_client_cpu_percent"));
            assertFalse(body.contains("aerospike_client_memory_bytes"));
            assertFalse(body.contains("aerospike_client_namespace_"));
        }
        finally {
            closeClient(client);
            exporter.close();
        }
    }

    @Test
    void exportsAsyncEventLoopsNodeNamespaceAndHistogramMetrics() throws Exception {
        AerospikeIntegrationSupport aerospike = new AerospikeIntegrationSupport();
        PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .port(0)
            .snapshotStaleTimeout("5m")
            .build();
        NioEventLoops eventLoops = new NioEventLoops(1);
        AerospikeClient client = null;
        try {
            client = aerospike.newAsyncClient("metrics-prometheus-async", eventLoops);
            client.enableMetrics(policy("async-live", exporter, true));
            aerospike.runWorkload(client, client, Duration.ofSeconds(1));
            aerospike.runAsyncWorkload(client, eventLoops, Duration.ofSeconds(2));
            String body = awaitMetrics(
                exporter,
                "aerospike_client_eventloop_process_size",
                Duration.ofSeconds(8)
            );
            assertTrue(body.contains("aerospike_client_eventloop_queue_size"));
            assertTrue(body.contains("aerospike_client_node_connections_in_use"));
            assertTrue(body.contains("conn_type=\"sync\""));
            assertTrue(body.contains("conn_type=\"async\""));
            assertTrue(body.contains("aerospike_client_namespace_bytes_in_total"));
            assertTrue(body.contains("aerospike_client_namespace_errors_total"));
            assertTrue(body.contains("namespace=\"" + aerospike.namespace() + "\""));
            assertLiveHistogram(body, "aerospike_client_namespace_latency_read");
            assertLiveHistogram(body, "aerospike_client_namespace_latency_write");
        }
        finally {
            closeClient(client);
            eventLoops.close();
            exporter.close();
        }
    }

    @Test
    void servesConfiguredCustomMetricsPath() throws Exception {
        AerospikeIntegrationSupport aerospike = new AerospikeIntegrationSupport();
        PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .port(0)
            .path("/client-metrics")
            .snapshotStaleTimeout("5m")
            .build();
        AerospikeClient client = null;
        try {
            client = aerospike.newClient("metrics-prometheus-custom-path");
            client.enableMetrics(policy("custom-path", exporter, false));
            aerospike.runWorkload(client, client, Duration.ofSeconds(2));
            String body = awaitMetrics(
                exporter,
                "/client-metrics",
                "aerospike_client_total_nodes",
                Duration.ofSeconds(8)
            );
            assertTrue(body.contains("client_id=\"custom-path\""));
            assertEquals(404, status(exporter, "/metrics"));
        }
        finally {
            closeClient(client);
            exporter.close();
        }
    }

    private static MetricsPolicy policy(
            String clientId,
            PrometheusMetricsExporter exporter
    ) {
        return policy(clientId, exporter, true);
    }

    private static MetricsPolicy policy(
            String clientId,
            PrometheusMetricsExporter exporter,
            boolean extended
    ) {
        MetricsPolicy policy = new MetricsPolicy();
        policy.interval = 1;
        policy.latencyColumns = 7;
        policy.latencyShift = 1;
        policy.enableExtendedMetrics = extended;
        policy.labels = Collections.singletonMap("client_id", clientId);
        policy.addExporter(exporter);
        return policy;
    }

    private static String awaitMetrics(
            PrometheusMetricsExporter exporter,
            String expected,
            Duration timeout
    ) throws Exception {
        return awaitMetrics(exporter, "/metrics", expected, timeout);
    }

    private static String awaitMetrics(
            PrometheusMetricsExporter exporter,
            String path,
            String expected,
            Duration timeout
    ) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String body = "";
        while (System.nanoTime() < deadline) {
            body = scrape(exporter, path);
            if (body.contains(expected)) {
                return body;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + expected + "\n" + body);
    }

    private static String scrape(PrometheusMetricsExporter exporter) throws Exception {
        return scrape(exporter, "/metrics");
    }

    private static String scrape(
            PrometheusMetricsExporter exporter,
            String path
    ) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + exporter.port() + path)
        ).timeout(Duration.ofSeconds(2)).GET().build();
        return HttpClient.newHttpClient()
            .send(request, HttpResponse.BodyHandlers.ofString())
            .body();
    }

    private static int status(
            PrometheusMetricsExporter exporter,
            String path
    ) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + exporter.port() + path)
        ).timeout(Duration.ofSeconds(2)).GET().build();
        return HttpClient.newHttpClient()
            .send(request, HttpResponse.BodyHandlers.discarding())
            .statusCode();
    }

    private static void assertLiveHistogram(String body, String baseName) {
        assertTrue(body.contains(baseName + "_bucket{"));
        assertTrue(body.contains("le=\"1.0\"") || body.contains("le=\"1\""));
        assertTrue(body.contains("le=\"+Inf\""));
        assertTrue(body.contains(baseName + "_count{"));
        assertFalse(body.contains(baseName + "_sum"));
    }

    private static void closeClient(AerospikeClient client) {
        if (client != null) {
            client.close();
        }
    }
}

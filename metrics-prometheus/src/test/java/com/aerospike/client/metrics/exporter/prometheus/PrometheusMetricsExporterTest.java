/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.aerospike.client.metrics.LatencyType;
import com.aerospike.client.metrics.MetricsSnapshot;

class PrometheusMetricsExporterTest {
    @Test
    void servesCountersGaugesAndCumulativeHistogramBuckets() throws Exception {
        PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .port(0)
            .snapshotStaleTimeout("5m")
            .build();
        try {
            exporter.export(snapshot(true));
            HttpURLConnection connection = connection(exporter, null);
            assertEquals(200, connection.getResponseCode());
            String body = body(connection);

            assertTrue(body.contains("aerospike_client_total_nodes"));
            assertTrue(body.contains("aerospike_client_namespace_errors_total"));
            assertTrue(body.contains(
                "aerospike_client_namespace_latency_read_bucket"
            ));
            assertTrue(body.contains("le=\"2.0\"") || body.contains("le=\"2\""));
            assertTrue(body.contains(
                "aerospike_client_namespace_latency_read_count"
            ));
            assertFalse(body.contains(
                "aerospike_client_namespace_latency_read_sum"
            ));
        }
        finally {
            exporter.close();
        }
    }

    @Test
    void servesMultipleClientsAndNormalizesOptionalLabels() throws Exception {
        PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .port(0)
            .snapshotStaleTimeout("5m")
            .build();
        try {
            exporter.export(snapshot(true, "primary", 2, true));
            exporter.export(snapshot(true, "reporting", 4, false));

            String body = body(connection(exporter, null));
            assertTrue(body.contains("client_id=\"primary\""));
            assertTrue(body.contains("client_id=\"reporting\""));
            assertTrue(body.contains("region=\"us-east\""));
            assertTrue(body.contains("region=\"\""));
            assertTrue(body.contains(" 2.0") || body.contains(" 2"));
            assertTrue(body.contains(" 4.0") || body.contains(" 4"));
            assertEquals(2, exporter.snapshotRegistry().size());
        }
        finally {
            exporter.close();
        }
    }

    @Test
    void rejectsSnapshotsWithoutClientId() {
        PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .port(0)
            .build();
        try {
            assertThrows(
                IllegalArgumentException.class,
                () -> exporter.export(snapshot(false, null, 2, false))
            );
        }
        finally {
            exporter.close();
        }
    }

    @Test
    void requiresValidBasicAuthentication() throws Exception {
        PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .port(0)
            .basicAuth("metrics", "secret")
            .build();
        try {
            exporter.export(snapshot(false));
            assertEquals(401, connection(exporter, null).getResponseCode());

            String header = "Basic " + Base64.getEncoder().encodeToString(
                "metrics:secret".getBytes(StandardCharsets.UTF_8)
            );
            assertEquals(200, connection(exporter, header).getResponseCode());
        }
        finally {
            exporter.close();
        }
    }

    @Test
    void suppressesExtendedFamiliesWhenDisabledInSnapshot() throws Exception {
        PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .port(0)
            .build();
        try {
            exporter.export(snapshot(false));
            String body = body(connection(exporter, null));
            assertFalse(body.contains("aerospike_client_command_count_total"));
            assertFalse(body.contains("aerospike_client_namespace_errors_total"));
        }
        finally {
            exporter.close();
        }
    }

    @Test
    void validatesAuthenticationAndTlsCombinations() {
        assertThrows(
            IllegalArgumentException.class,
            () -> PrometheusMetricsExporter.builder()
                .basicAuth("metrics", "secret")
                .resolveConfig()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PrometheusMetricsExporter.builder()
                .tlsEnabled(true)
                .serverCertificate(Paths.get("server.pem"))
                .resolveConfig()
        );
    }

    private static HttpURLConnection connection(
            PrometheusMetricsExporter exporter,
            String authorization
    ) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(
            "http://127.0.0.1:" + exporter.port() + "/metrics"
        ).toURL().openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(2_000);
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization);
        }
        return connection;
    }

    private static String body(HttpURLConnection connection) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private static MetricsSnapshot snapshot(boolean extended) {
        return snapshot(extended, "primary", 2, false);
    }

    private static MetricsSnapshot snapshot(
            boolean extended,
            String clientId,
            int totalNodes,
            boolean includeRegion
    ) {
        MetricsSnapshot.ConnectionSnapshot sync =
            new MetricsSnapshot.ConnectionSnapshot(2, 3, 10, 5);
        MetricsSnapshot.ConnectionSnapshot async =
            new MetricsSnapshot.ConnectionSnapshot(1, 1, 4, 2);

        Map<LatencyType, MetricsSnapshot.HistogramSnapshot> latency =
            new EnumMap<LatencyType, MetricsSnapshot.HistogramSnapshot>(LatencyType.class);
        latency.put(
            LatencyType.READ,
            new MetricsSnapshot.HistogramSnapshot(
                new long[] {1, 1, 1, 0, 1, 0, 0},
                1,
                10,
                15.3,
                4
            )
        );
        MetricsSnapshot.NamespaceSnapshot namespace = new MetricsSnapshot.NamespaceSnapshot(
            "test",
            7,
            2,
            1,
            100,
            200,
            latency,
            Collections.emptyMap()
        );
        MetricsSnapshot.NodeSnapshot node = new MetricsSnapshot.NodeSnapshot(
            "N1",
            "127.0.0.1",
            3000,
            sync,
            async,
            0, 0, 0, 0, 0,
            0, 0, 0, 0, 7,
            0, 0, 0, 0, 0,
            0, 0, 0, 0, 0,
            Collections.emptyMap(),
            extended ? Collections.singletonList(namespace) : Collections.emptyList()
        );

        Map<String, String> labels = new java.util.LinkedHashMap<String, String>();
        labels.put("team", "payments");
        if (clientId != null) {
            labels.put("client_id", clientId);
        }
        if (includeRegion) {
            labels.put("region", "us-east");
        }

        return new MetricsSnapshot(
            Instant.now(),
            extended,
            "prod",
            "java",
            "10.4.0",
            "orders",
            labels,
            3,
            1,
            8,
            2,
            totalNodes,
            7,
            4,
            3,
            12.5,
            1024,
            99,
            Collections.emptyList(),
            Collections.singletonList(node),
            node,
            MetricsSnapshot.HistogramType.LOGARITHMIC,
            MetricsSnapshot.LatencyUnit.MILLISECONDS,
            2,
            7
        );
    }
}

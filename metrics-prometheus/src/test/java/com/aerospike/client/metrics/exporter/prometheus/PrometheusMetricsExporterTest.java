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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.Test;

import com.aerospike.client.metrics.LatencyType;
import com.aerospike.client.metrics.MetricsSnapshot;

class PrometheusMetricsExporterTest {
    @Test
    void servesCompleteMetricCatalogWithExactValuesAndLabels() throws Exception {
        PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .port(0)
            .snapshotStaleTimeout("5m")
            .build();
        try {
            exporter.export(snapshot(
                true,
                "primary",
                2,
                false,
                List.of(
                    new MetricsSnapshot.EventLoopSnapshot(2, 3),
                    new MetricsSnapshot.EventLoopSnapshot(5, 7)
                )
            ));
            String body = body(connection(exporter, null));

            assertEquals(
                Set.of(
                    "aerospike_client_recover_queue_size",
                    "aerospike_client_invalid_node_count_total",
                    "aerospike_client_retry_count_total",
                    "aerospike_client_delay_queue_timeout_count_total",
                    "aerospike_client_total_nodes",
                    "aerospike_client_open_connections",
                    "aerospike_client_exceeded_max_retries_total",
                    "aerospike_client_exceeded_total_timeout_total",
                    "aerospike_client_eventloop_process_size",
                    "aerospike_client_eventloop_queue_size",
                    "aerospike_client_cpu_percent",
                    "aerospike_client_memory_bytes",
                    "aerospike_client_command_count_total",
                    "aerospike_client_node_connections_in_use",
                    "aerospike_client_node_connections_in_pool",
                    "aerospike_client_node_connections_opened_total",
                    "aerospike_client_node_connections_closed_total",
                    "aerospike_client_namespace_errors_total",
                    "aerospike_client_namespace_timeouts_total",
                    "aerospike_client_namespace_key_busy_total",
                    "aerospike_client_namespace_bytes_in_total",
                    "aerospike_client_namespace_bytes_out_total",
                    "aerospike_client_namespace_latency_read_bucket",
                    "aerospike_client_namespace_latency_read_count"
                ),
                sampleMetricNames(body)
            );

            Map<String, String> global = Map.of(
                "app_id", "orders",
                "client_id", "primary",
                "client_type", "java",
                "client_version", "10.4.0",
                "cluster", "prod",
                "team", "payments"
            );
            assertSample(body, "aerospike_client_recover_queue_size", 3.0, global);
            assertSample(body, "aerospike_client_invalid_node_count_total", 1.0, global);
            assertSample(body, "aerospike_client_retry_count_total", 8.0, global);
            assertSample(body, "aerospike_client_delay_queue_timeout_count_total", 2.0, global);
            assertSample(body, "aerospike_client_total_nodes", 2.0, global);
            assertSample(body, "aerospike_client_open_connections", 7.0, global);
            assertSample(body, "aerospike_client_exceeded_max_retries_total", 4.0, global);
            assertSample(body, "aerospike_client_exceeded_total_timeout_total", 3.0, global);
            assertSample(body, "aerospike_client_eventloop_process_size", 7.0, global);
            assertSample(body, "aerospike_client_eventloop_queue_size", 10.0, global);
            assertSample(body, "aerospike_client_cpu_percent", 12.5, global);
            assertSample(body, "aerospike_client_memory_bytes", 1024.0, global);
            assertSample(body, "aerospike_client_command_count_total", 99.0, global);

            assertConnectionSamples(body, global, "sync", 2.0, 3.0, 10.0, 5.0);
            assertConnectionSamples(body, global, "async", 1.0, 1.0, 4.0, 2.0);

            Map<String, String> namespace = new LinkedHashMap<String, String>(global);
            namespace.put("node", "N1");
            namespace.put("node_address", "127.0.0.1");
            namespace.put("node_port", "3000");
            namespace.put("namespace", "test");
            assertSample(body, "aerospike_client_namespace_errors_total", 7.0, namespace);
            assertSample(body, "aerospike_client_namespace_timeouts_total", 2.0, namespace);
            assertSample(body, "aerospike_client_namespace_key_busy_total", 1.0, namespace);
            assertSample(body, "aerospike_client_namespace_bytes_in_total", 100.0, namespace);
            assertSample(body, "aerospike_client_namespace_bytes_out_total", 200.0, namespace);

            String histogram = "aerospike_client_namespace_latency_read";
            assertHistogramBucket(body, histogram, "1", 1.0);
            assertHistogramBucket(body, histogram, "2", 2.0);
            assertHistogramBucket(body, histogram, "4", 3.0);
            assertHistogramBucket(body, histogram, "8", 3.0);
            assertHistogramBucket(body, histogram, "16", 4.0);
            assertHistogramBucket(body, histogram, "32", 4.0);
            Map<String, String> infinity = new LinkedHashMap<String, String>(namespace);
            infinity.put("le", "+Inf");
            assertSample(body, histogram + "_bucket", 4.0, infinity);
            assertSample(body, histogram + "_count", 4.0, namespace);
        }
        finally {
            exporter.close();
        }
    }

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
            String histogram = "aerospike_client_namespace_latency_read";
            assertHistogramBucket(body, histogram, "1", 1.0);
            assertHistogramBucket(body, histogram, "2", 2.0);
            assertHistogramBucket(body, histogram, "4", 3.0);
            assertHistogramBucket(body, histogram, "8", 3.0);
            assertHistogramBucket(body, histogram, "16", 4.0);
            assertHistogramBucket(body, histogram, "32", 4.0);
            assertHistogramBucket(body, histogram, "+Inf", 4.0);
            assertEquals(
                4.0,
                sampleValue(body, histogram + "_count", "client_id=\"primary\"")
            );
            assertFalse(body.contains(
                "aerospike_client_namespace_latency_read_sum"
            ));
        }
        finally {
            exporter.close();
        }
    }

    @Test
    void servesLinearMicrosecondHistogramBuckets() throws Exception {
        PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .port(0)
            .snapshotStaleTimeout("5m")
            .build();
        try {
            exporter.export(snapshot(
                true,
                "linear-microseconds",
                2,
                false,
                Collections.emptyList(),
                MetricsSnapshot.HistogramType.LINEAR,
                MetricsSnapshot.LatencyUnit.MICROSECONDS,
                3,
                new long[] {1, 2, 3, 4}
            ));
            String body = body(connection(exporter, null));
            String histogram = "aerospike_client_namespace_latency_read";
            assertHistogramBucket(body, histogram, "1", 1.0);
            assertHistogramBucket(body, histogram, "3", 3.0);
            assertHistogramBucket(body, histogram, "6", 6.0);
            assertHistogramBucket(body, histogram, "+Inf", 10.0);
            assertEquals(
                10.0,
                sampleValue(body, histogram + "_count", "client_id=\"linear-microseconds\"")
            );
            assertFalse(body.contains(histogram + "_sum"));
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
    void aggregatesEventLoopsPerClientAndOmitsClientsWithoutThem() throws Exception {
        PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .port(0)
            .snapshotStaleTimeout("5m")
            .build();
        try {
            exporter.export(snapshot(
                false,
                "primary",
                2,
                false,
                List.of(
                    new MetricsSnapshot.EventLoopSnapshot(2, 3),
                    new MetricsSnapshot.EventLoopSnapshot(5, 7)
                )
            ));
            exporter.export(snapshot(
                false,
                "reporting",
                4,
                false,
                Collections.singletonList(new MetricsSnapshot.EventLoopSnapshot(11, 13))
            ));
            exporter.export(snapshot(false, "sync-only", 1, false));

            String body = body(connection(exporter, null));
            assertEquals(
                7.0,
                metricValue(body, "aerospike_client_eventloop_process_size", "primary")
            );
            assertEquals(
                11.0,
                metricValue(body, "aerospike_client_eventloop_process_size", "reporting")
            );
            assertEquals(
                10.0,
                metricValue(body, "aerospike_client_eventloop_queue_size", "primary")
            );
            assertEquals(
                13.0,
                metricValue(body, "aerospike_client_eventloop_queue_size", "reporting")
            );
            assertFalse(hasMetricForClient(
                body,
                "aerospike_client_eventloop_process_size",
                "sync-only"
            ));
            assertFalse(hasMetricForClient(
                body,
                "aerospike_client_eventloop_queue_size",
                "sync-only"
            ));
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
            HttpURLConnection missing = connection(exporter, null);
            assertEquals(401, missing.getResponseCode());
            assertEquals(
                "Basic realm=\"aerospike-client-metrics\"",
                missing.getHeaderField("WWW-Authenticate")
            );

            String wrong = "Basic " + Base64.getEncoder().encodeToString(
                "metrics:wrong".getBytes(StandardCharsets.UTF_8)
            );
            assertEquals(401, connection(exporter, wrong).getResponseCode());

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
            exporter.export(snapshot(
                false,
                "primary",
                2,
                false,
                Collections.singletonList(new MetricsSnapshot.EventLoopSnapshot(2, 3))
            ));
            String body = body(connection(exporter, null));
            assertTrue(body.contains("aerospike_client_total_nodes"));
            assertTrue(body.contains("aerospike_client_eventloop_process_size"));
            assertTrue(body.contains("aerospike_client_eventloop_queue_size"));
            assertFalse(body.contains("aerospike_client_command_count_total"));
            assertFalse(body.contains("aerospike_client_namespace_errors_total"));
        }
        finally {
            exporter.close();
        }
    }

    @Test
    void omitsStaleClientAndResumesWithRetainedStore() throws Exception {
        PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .port(0)
            .snapshotStaleTimeout("5ms")
            .build();
        try {
            exporter.export(snapshot(false));
            assertTrue(body(connection(exporter, null)).contains("aerospike_client_total_nodes"));
            Object store = exporter.snapshotRegistry().snapshotStore("primary");

            Thread.sleep(20);
            assertFalse(body(connection(exporter, null)).contains("aerospike_client_total_nodes"));

            exporter.export(snapshot(false));
            assertEquals(store, exporter.snapshotRegistry().snapshotStore("primary"));
            assertTrue(body(connection(exporter, null)).contains("aerospike_client_total_nodes"));
        }
        finally {
            exporter.close();
        }
    }

    @Test
    void enforcesPathAndMethodAndCloseIsIdempotent() throws Exception {
        PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .port(0)
            .build();
        exporter.export(snapshot(false));
        assertEquals(404, connection(exporter, null, "/missing", "GET").getResponseCode());
        assertEquals(405, connection(exporter, null, "/metrics", "POST").getResponseCode());
        exporter.close();
        exporter.close();
        exporter.export(snapshot(false, "reporting", 4, false));
        assertEquals(0, exporter.snapshotRegistry().size());
    }

    @Test
    void closeClearsSnapshotsWhileExportsAreInFlight() throws Exception {
        PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .port(0)
            .build();
        ExecutorService workers = Executors.newFixedThreadPool(9);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> exports = new ArrayList<Future<?>>();
        try {
            for (int writer = 0; writer < 8; writer++) {
                final int writerId = writer;
                exports.add(workers.submit(() -> {
                    await(start);
                    for (int iteration = 0; iteration < 1_000; iteration++) {
                        exporter.export(snapshot(
                            false,
                            "client-" + writerId + "-" + iteration,
                            writerId,
                            false
                        ));
                    }
                }));
            }
            Future<?> close = workers.submit(() -> {
                await(start);
                exporter.close();
            });

            start.countDown();
            close.get(10, TimeUnit.SECONDS);
            for (Future<?> export : exports) {
                export.get(10, TimeUnit.SECONDS);
            }

            assertEquals(0, exporter.snapshotRegistry().size());
        }
        finally {
            workers.shutdownNow();
            exporter.close();
        }
    }

    @Test
    void failsFastWhenConfiguredPortIsAlreadyBound() {
        PrometheusMetricsExporter first = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .port(0)
            .build();
        try {
            assertThrows(
                IllegalStateException.class,
                () -> PrometheusMetricsExporter.builder()
                    .bindAddress("127.0.0.1")
                    .port(first.port())
                    .build()
            );
        }
        finally {
            first.close();
        }
    }

    @Test
    void supportsConcurrentSnapshotUpdatesAndScrapes() throws Exception {
        PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .port(0)
            .snapshotStaleTimeout("5m")
            .build();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            exporter.export(snapshot(true, "primary", 2, true));
            List<Future<?>> work = new ArrayList<Future<?>>();
            for (int writer = 0; writer < 2; writer++) {
                final int writerId = writer;
                work.add(workers.submit(() -> {
                    for (int iteration = 0; iteration < 100; iteration++) {
                        exporter.export(snapshot(
                            true,
                            writerId == 0 ? "primary" : "reporting",
                            writerId + 2,
                            writerId == 0
                        ));
                    }
                }));
            }
            for (int scraper = 0; scraper < 6; scraper++) {
                work.add(workers.submit(() -> {
                    for (int iteration = 0; iteration < 20; iteration++) {
                        try {
                            HttpURLConnection connection = connection(exporter, null);
                            assertEquals(200, connection.getResponseCode());
                            assertTrue(body(connection).contains(
                                "aerospike_client_total_nodes"
                            ));
                        }
                        catch (Exception e) {
                            throw new AssertionError("Concurrent scrape failed", e);
                        }
                    }
                }));
            }
            for (Future<?> future : work) {
                future.get(15, TimeUnit.SECONDS);
            }
            assertEquals(2, exporter.snapshotRegistry().size());
        }
        finally {
            workers.shutdownNow();
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

    @Test
    void servesMetricsOverTrustedHttps() throws Exception {
        Path fixtures = testFixtures();
        PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
            .bindAddress("127.0.0.1")
            .port(0)
            .tlsEnabled(true)
            .serverCertificate(fixtures.resolve("shared-localhost-test-cert.pem"))
            .serverKey(fixtures.resolve("shared-localhost-test-key.pem"))
            .build();
        try {
            exporter.export(snapshot(false));
            HttpsURLConnection connection = (HttpsURLConnection) URI.create(
                "https://localhost:" + exporter.port() + "/metrics"
            ).toURL().openConnection();
            connection.setSSLSocketFactory(
                trustContext(fixtures.resolve("test-ca.pem")).getSocketFactory()
            );
            assertEquals(200, connection.getResponseCode());
            assertTrue(body(connection).contains("aerospike_client_total_nodes"));
        }
        finally {
            exporter.close();
        }
    }

    private static HttpURLConnection connection(
            PrometheusMetricsExporter exporter,
            String authorization
    ) throws Exception {
        return connection(exporter, authorization, "/metrics", "GET");
    }

    private static HttpURLConnection connection(
            PrometheusMetricsExporter exporter,
            String authorization,
            String path,
            String method
    ) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(
            "http://127.0.0.1:" + exporter.port() + path
        ).toURL().openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(2_000);
        connection.setRequestMethod(method);
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

    private static double metricValue(String body, String metricName, String clientId) {
        for (String line : body.split("\\R")) {
            if (line.startsWith(metricName + "{")
                    && line.contains("client_id=\"" + clientId + "\"")) {
                return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        throw new AssertionError("Missing " + metricName + " for client " + clientId);
    }

    private static boolean hasMetricForClient(
            String body,
            String metricName,
            String clientId
    ) {
        for (String line : body.split("\\R")) {
            if (line.startsWith(metricName + "{")
                    && line.contains("client_id=\"" + clientId + "\"")) {
                return true;
            }
        }
        return false;
    }

    private static void assertHistogramBucket(
            String body,
            String baseName,
            String upperBound,
            double expected
    ) {
        String label = "le=\"" + upperBound + "\"";
        String decimalLabel = "le=\"" + upperBound + ".0\"";
        double value;
        try {
            value = sampleValue(body, baseName + "_bucket", label);
        }
        catch (AssertionError e) {
            value = sampleValue(body, baseName + "_bucket", decimalLabel);
        }
        assertEquals(expected, value);
    }

    private static double sampleValue(
            String body,
            String metricName,
            String label
    ) {
        for (String line : body.split("\\R")) {
            if (line.startsWith(metricName + "{") && line.contains(label)) {
                return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        throw new AssertionError("Missing " + metricName + " with " + label);
    }

    private static void assertConnectionSamples(
            String body,
            Map<String, String> global,
            String connectionType,
            double inUse,
            double inPool,
            double opened,
            double closed
    ) {
        Map<String, String> attributes = new LinkedHashMap<String, String>(global);
        attributes.put("node", "N1");
        attributes.put("node_address", "127.0.0.1");
        attributes.put("node_port", "3000");
        attributes.put("conn_type", connectionType);
        assertSample(body, "aerospike_client_node_connections_in_use", inUse, attributes);
        assertSample(body, "aerospike_client_node_connections_in_pool", inPool, attributes);
        assertSample(body, "aerospike_client_node_connections_opened_total", opened, attributes);
        assertSample(body, "aerospike_client_node_connections_closed_total", closed, attributes);
    }

    private static void assertSample(
            String body,
            String metricName,
            double expectedValue,
            Map<String, String> expectedLabels
    ) {
        for (String line : body.split("\\R")) {
            if (line.startsWith(metricName + "{")
                    && labels(line).equals(expectedLabels)) {
                assertEquals(
                    expectedValue,
                    Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)),
                    metricName
                );
                return;
            }
        }
        throw new AssertionError(
            "Missing " + metricName + " with labels " + expectedLabels + "\n" + body
        );
    }

    private static Set<String> sampleMetricNames(String body) {
        Set<String> names = new HashSet<String>();
        for (String line : body.split("\\R")) {
            if (!line.isEmpty() && line.charAt(0) != '#') {
                int labels = line.indexOf('{');
                int value = line.indexOf(' ');
                names.add(line.substring(0, labels >= 0 ? labels : value));
            }
        }
        return names;
    }

    private static Map<String, String> labels(String sampleLine) {
        int start = sampleLine.indexOf('{') + 1;
        int end = sampleLine.indexOf('}', start);
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (start == 0 || end == start) {
            return result;
        }
        for (String pair : sampleLine.substring(start, end).split(",")) {
            int separator = pair.indexOf('=');
            result.put(
                pair.substring(0, separator),
                pair.substring(separator + 2, pair.length() - 1)
            );
        }
        return result;
    }

    private static SSLContext trustContext(Path caFile) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Certificate ca;
        try (java.io.InputStream input = Files.newInputStream(caFile)) {
            ca = factory.generateCertificate(input);
        }
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("ca", ca);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        );
        trustManagers.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        return context;
    }

    private static Path testFixtures() {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null) {
            Path fixtures = directory.resolve("test-fixtures/tls");
            if (Files.isDirectory(fixtures)) {
                return fixtures;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Unable to locate test-fixtures/tls");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating concurrent export", e);
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
        return snapshot(
            extended,
            clientId,
            totalNodes,
            includeRegion,
            Collections.emptyList()
        );
    }

    private static MetricsSnapshot snapshot(
            boolean extended,
            String clientId,
            int totalNodes,
            boolean includeRegion,
            List<MetricsSnapshot.EventLoopSnapshot> eventLoops
    ) {
        return snapshot(
            extended,
            clientId,
            totalNodes,
            includeRegion,
            eventLoops,
            MetricsSnapshot.HistogramType.LOGARITHMIC,
            MetricsSnapshot.LatencyUnit.MILLISECONDS,
            2,
            new long[] {1, 1, 1, 0, 1, 0, 0}
        );
    }

    private static MetricsSnapshot snapshot(
            boolean extended,
            String clientId,
            int totalNodes,
            boolean includeRegion,
            List<MetricsSnapshot.EventLoopSnapshot> eventLoops,
            MetricsSnapshot.HistogramType histogramType,
            MetricsSnapshot.LatencyUnit latencyUnit,
            int latencyBase,
            long[] buckets
    ) {
        MetricsSnapshot.ConnectionSnapshot sync =
            new MetricsSnapshot.ConnectionSnapshot(2, 3, 10, 5);
        MetricsSnapshot.ConnectionSnapshot async =
            new MetricsSnapshot.ConnectionSnapshot(1, 1, 4, 2);

        long totalCount = 0L;
        for (long bucket : buckets) {
            totalCount += bucket;
        }
        Map<LatencyType, MetricsSnapshot.HistogramSnapshot> latency =
            new EnumMap<LatencyType, MetricsSnapshot.HistogramSnapshot>(LatencyType.class);
        latency.put(
            LatencyType.READ,
            new MetricsSnapshot.HistogramSnapshot(
                buckets,
                1,
                10,
                15.3,
                totalCount
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
            eventLoops,
            Collections.singletonList(node),
            node,
            histogramType,
            latencyUnit,
            latencyBase,
            buckets.length
        );
    }
}

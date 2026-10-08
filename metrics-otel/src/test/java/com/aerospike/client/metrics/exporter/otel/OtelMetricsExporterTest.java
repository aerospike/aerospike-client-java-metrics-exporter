/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import com.aerospike.client.metrics.MetricsSnapshot;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.DoublePointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

class OtelMetricsExporterTest {
    @Test
    void exportsCompleteMetricCatalogWithExactValuesAndAttributes() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setMeterProvider(provider)
            .build();

        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .openTelemetry(openTelemetry)
            .snapshotStaleTimeout("5m")
            .build();
        try {
            exporter.export(OtelTestSnapshots.complete());
            Collection<MetricData> metrics = reader.collectAllMetrics();

            assertEquals(
                Set.of(
                    "aerospike_client_recover_queue_size",
                    "aerospike_client_invalid_node_count",
                    "aerospike_client_retry_count",
                    "aerospike_client_delay_queue_timeout_count",
                    "aerospike_client_total_nodes",
                    "aerospike_client_open_connections",
                    "aerospike_client_exceeded_max_retries",
                    "aerospike_client_exceeded_total_timeout",
                    "aerospike_client_eventloop_process_size",
                    "aerospike_client_eventloop_queue_size",
                    "aerospike_client_cpu_percent",
                    "aerospike_client_memory_bytes",
                    "aerospike_client_command_count",
                    "aerospike_client_node_connections_in_use",
                    "aerospike_client_node_connections_in_pool",
                    "aerospike_client_node_connections_opened",
                    "aerospike_client_node_connections_closed",
                    "aerospike_client_namespace_errors",
                    "aerospike_client_namespace_timeouts",
                    "aerospike_client_namespace_key_busy",
                    "aerospike_client_namespace_bytes_in",
                    "aerospike_client_namespace_bytes_out",
                    "aerospike_client_namespace_latency_conn_bucket",
                    "aerospike_client_namespace_latency_read_bucket",
                    "aerospike_client_namespace_latency_write_bucket",
                    "aerospike_client_namespace_latency_batch_bucket",
                    "aerospike_client_namespace_latency_query_bucket"
                ),
                metricNames(metrics)
            );

            Map<String, String> global = Map.of(
                "app_id", "orders",
                "client_id", "primary",
                "client_type", "java",
                "client_version", "10.4.0",
                "cluster", "prod",
                "team", "payments"
            );
            assertLongMetric(metrics, "aerospike_client_recover_queue_size",
                MetricDataType.LONG_GAUGE, 3L, global);
            assertLongMetric(metrics, "aerospike_client_invalid_node_count",
                MetricDataType.LONG_SUM, 1L, global);
            assertLongMetric(metrics, "aerospike_client_retry_count",
                MetricDataType.LONG_SUM, 8L, global);
            assertLongMetric(metrics, "aerospike_client_delay_queue_timeout_count",
                MetricDataType.LONG_SUM, 2L, global);
            assertLongMetric(metrics, "aerospike_client_total_nodes",
                MetricDataType.LONG_GAUGE, 2L, global);
            assertLongMetric(metrics, "aerospike_client_open_connections",
                MetricDataType.LONG_GAUGE, 7L, global);
            assertLongMetric(metrics, "aerospike_client_exceeded_max_retries",
                MetricDataType.LONG_SUM, 4L, global);
            assertLongMetric(metrics, "aerospike_client_exceeded_total_timeout",
                MetricDataType.LONG_SUM, 3L, global);
            assertLongMetric(metrics, "aerospike_client_eventloop_process_size",
                MetricDataType.LONG_GAUGE, 7L, global);
            assertLongMetric(metrics, "aerospike_client_eventloop_queue_size",
                MetricDataType.LONG_GAUGE, 10L, global);
            assertDoubleMetric(metrics, "aerospike_client_cpu_percent", 12.5, global);
            assertLongMetric(metrics, "aerospike_client_memory_bytes",
                MetricDataType.LONG_GAUGE, 1024L, global);
            assertLongMetric(metrics, "aerospike_client_command_count",
                MetricDataType.LONG_SUM, 99L, global);

            assertConnectionMetrics(metrics, global, "sync", 2L, 3L, 10L, 5L);
            assertConnectionMetrics(metrics, global, "async", 1L, 1L, 4L, 2L);

            Map<String, String> namespace = new LinkedHashMap<String, String>(global);
            namespace.put("node", "N1");
            namespace.put("node_address", "127.0.0.1");
            namespace.put("node_port", "3000");
            namespace.put("namespace", "test");
            assertLongMetric(metrics, "aerospike_client_namespace_errors",
                MetricDataType.LONG_SUM, 7L, namespace);
            assertLongMetric(metrics, "aerospike_client_namespace_timeouts",
                MetricDataType.LONG_SUM, 2L, namespace);
            assertLongMetric(metrics, "aerospike_client_namespace_key_busy",
                MetricDataType.LONG_SUM, 1L, namespace);
            assertLongMetric(metrics, "aerospike_client_namespace_bytes_in",
                MetricDataType.LONG_SUM, 100L, namespace);
            assertLongMetric(metrics, "aerospike_client_namespace_bytes_out",
                MetricDataType.LONG_SUM, 200L, namespace);
            assertLatencyBuckets(metrics, "conn", namespace, 2, 2, 3, 3, 3, 3, 3);
            assertLatencyBuckets(metrics, "read", namespace, 1, 2, 3, 3, 4, 4, 4);
            assertLatencyBuckets(metrics, "write", namespace, 0, 3, 3, 4, 4, 4, 4);
            assertLatencyBuckets(metrics, "batch", namespace, 0, 0, 2, 2, 3, 3, 3);
            assertLatencyBuckets(metrics, "query", namespace, 0, 1, 1, 1, 1, 2, 2);
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void observableCountersSupportCumulativeAndDeltaTemporality() {
        assertCommandCountSequence(
            InMemoryMetricReader.create(),
            AggregationTemporality.CUMULATIVE,
            10L,
            25L
        );
        assertCommandCountSequence(
            InMemoryMetricReader.createDelta(),
            AggregationTemporality.DELTA,
            10L,
            15L
        );
    }

    @Test
    void latencyBucketCountersSupportCumulativeAndDeltaTemporality() {
        assertLatencySequence(
            InMemoryMetricReader.create(),
            AggregationTemporality.CUMULATIVE,
            2L,
            6L
        );
        assertLatencySequence(
            InMemoryMetricReader.createDelta(),
            AggregationTemporality.DELTA,
            2L,
            4L
        );
    }

    @Test
    void exportsLinearMicrosecondLatencyBuckets() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .openTelemetry(OpenTelemetrySdk.builder().setMeterProvider(provider).build())
            .snapshotStaleTimeout("5m")
            .build();
        try {
            exporter.export(OtelTestSnapshots.linearMicroseconds());
            MetricData metric = metric(
                reader.collectAllMetrics(),
                "aerospike_client_namespace_latency_read_bucket"
            );
            assertEquals("{operations}", metric.getUnit());
            Map<String, String> namespace = Map.ofEntries(
                Map.entry("app_id", "orders"),
                Map.entry("client_id", "linear-microseconds"),
                Map.entry("client_type", "java"),
                Map.entry("client_version", "10.4.0"),
                Map.entry("cluster", "prod"),
                Map.entry("team", "payments"),
                Map.entry("node", "N1"),
                Map.entry("node_address", "127.0.0.1"),
                Map.entry("node_port", "3000"),
                Map.entry("namespace", "test")
            );
            assertLatencyBuckets(
                Collections.singleton(metric),
                "read",
                namespace,
                new String[]{"1", "3", "6", "+Inf"},
                new long[]{2, 5, 10, 17}
            );
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void exportsCurrentSnapshotUsingObservableInstruments() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setMeterProvider(provider)
            .build();

        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .openTelemetry(openTelemetry)
            .snapshotStaleTimeout("5m")
            .build();
        try {
            exporter.export(snapshot(true));
            Collection<MetricData> metrics = reader.collectAllMetrics();

            MetricData nodes = metric(metrics, "aerospike_client_total_nodes");
            LongPointData point = nodes.getLongGaugeData().getPoints().iterator().next();
            assertEquals(2L, point.getValue());
            assertEquals(
                "prod",
                point.getAttributes().get(AttributeKey.stringKey("cluster"))
            );

            assertTrue(hasMetric(metrics, "aerospike_client_command_count"));
            assertTrue(hasMetric(metrics, "aerospike_client_namespace_errors"));
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void exportsIndependentMeasurementsForClientsSharingOneExporter() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setMeterProvider(provider)
            .build();

        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .openTelemetry(openTelemetry)
            .snapshotStaleTimeout("5m")
            .build();
        try {
            exporter.export(snapshot(true, "primary", 2));
            exporter.export(snapshot(true, "reporting", 4));

            MetricData nodes = metric(
                reader.collectAllMetrics(),
                "aerospike_client_total_nodes"
            );
            Map<String, Long> values = new LinkedHashMap<String, Long>();
            for (LongPointData point : nodes.getLongGaugeData().getPoints()) {
                values.put(
                    point.getAttributes().get(AttributeKey.stringKey("client_id")),
                    point.getValue()
                );
            }

            assertEquals(2, values.size());
            assertEquals(2L, values.get("primary"));
            assertEquals(4L, values.get("reporting"));
            assertEquals(
                Map.of("primary", 3L, "reporting", 5L),
                sumValuesByClient(
                    metric(
                        reader.collectAllMetrics(),
                        "aerospike_client_namespace_latency_read_bucket"
                    ),
                    "+Inf"
                )
            );
            assertEquals(2, exporter.snapshotRegistry().size());
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void aggregatesEventLoopsPerClientAndOmitsClientsWithoutThem() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setMeterProvider(provider)
            .build();

        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .openTelemetry(openTelemetry)
            .snapshotStaleTimeout("5m")
            .build();
        try {
            exporter.export(snapshot(
                false,
                "primary",
                2,
                List.of(
                    new MetricsSnapshot.EventLoopSnapshot(2, 3),
                    new MetricsSnapshot.EventLoopSnapshot(5, 7)
                )
            ));
            exporter.export(snapshot(
                false,
                "reporting",
                4,
                Collections.singletonList(new MetricsSnapshot.EventLoopSnapshot(11, 13))
            ));
            exporter.export(snapshot(false, "sync-only", 1));

            Collection<MetricData> metrics = reader.collectAllMetrics();
            assertEquals(
                Map.of("primary", 7L, "reporting", 11L),
                gaugeValuesByClient(
                    metric(metrics, "aerospike_client_eventloop_process_size")
                )
            );
            assertEquals(
                Map.of("primary", 10L, "reporting", 13L),
                gaugeValuesByClient(
                    metric(metrics, "aerospike_client_eventloop_queue_size")
                )
            );
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void rejectsSnapshotsWithoutClientId() {
        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .openTelemetry(OpenTelemetrySdk.builder().build())
            .build();
        try {
            assertThrows(
                IllegalArgumentException.class,
                () -> exporter.export(snapshot(false, null, 2))
            );
        }
        finally {
            exporter.close();
        }
    }

    @Test
    void suppressesExtendedMetricsWhenClientDidNotCollectThem() {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setMeterProvider(provider)
            .build();

        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .openTelemetry(openTelemetry)
            .build();
        try {
            exporter.export(snapshot(false));
            Collection<MetricData> metrics = reader.collectAllMetrics();
            assertFalse(hasMetric(metrics, "aerospike_client_command_count"));
            assertFalse(hasMetric(metrics, "aerospike_client_namespace_errors"));
            assertFalse(hasMetric(metrics, "aerospike_client_eventloop_process_size"));
            assertFalse(hasMetric(metrics, "aerospike_client_eventloop_queue_size"));
            assertFalse(hasMetric(
                metrics,
                "aerospike_client_namespace_latency_read_bucket"
            ));
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void omitsStaleClientAndResumesWithRetainedStore() throws Exception {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .openTelemetry(OpenTelemetrySdk.builder().setMeterProvider(provider).build())
            .snapshotStaleTimeout("5ms")
            .build();
        try {
            exporter.export(snapshot(true));
            assertTrue(hasMetric(reader.collectAllMetrics(), "aerospike_client_total_nodes"));
            assertTrue(hasMetric(
                reader.collectAllMetrics(),
                "aerospike_client_namespace_latency_read_bucket"
            ));
            Object store = exporter.snapshotRegistry().snapshotStore("primary");

            Thread.sleep(20);
            assertFalse(hasMetric(reader.collectAllMetrics(), "aerospike_client_total_nodes"));
            assertFalse(hasMetric(
                reader.collectAllMetrics(),
                "aerospike_client_namespace_latency_read_bucket"
            ));

            exporter.export(snapshot(true));
            assertEquals(store, exporter.snapshotRegistry().snapshotStore("primary"));
            assertTrue(hasMetric(reader.collectAllMetrics(), "aerospike_client_total_nodes"));
            assertTrue(hasMetric(
                reader.collectAllMetrics(),
                "aerospike_client_namespace_latency_read_bucket"
            ));
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void closeIsIdempotentAndIgnoresLaterExports() {
        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .openTelemetry(OpenTelemetrySdk.builder().build())
            .build();
        exporter.export(snapshot(false));
        exporter.close();
        exporter.close();
        exporter.export(snapshot(false, "reporting", 4));
        assertEquals(0, exporter.snapshotRegistry().size());
    }

    @Test
    void closeClearsSnapshotsWhileExportsAreInFlight() throws Exception {
        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .openTelemetry(OpenTelemetrySdk.builder().build())
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
                            writerId
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
    void logsProviderShutdownFailuresAndTimeouts() {
        Logger logger = Logger.getLogger(OtelMetricsExporter.class.getName());
        RecordingHandler handler = new RecordingHandler();
        boolean useParentHandlers = logger.getUseParentHandlers();
        Level level = logger.getLevel();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            OtelMetricsExporter.awaitProviderShutdown(
                CompletableResultCode.ofExceptionalFailure(
                    new IllegalStateException("shutdown failed")
                ),
                1,
                TimeUnit.SECONDS
            );
            OtelMetricsExporter.awaitProviderShutdown(
                new CompletableResultCode(),
                0,
                TimeUnit.MILLISECONDS
            );

            assertTrue(handler.contains("shutdown failed"));
            assertTrue(handler.contains("Timed out"));
        }
        finally {
            logger.removeHandler(handler);
            logger.setLevel(level);
            logger.setUseParentHandlers(useParentHandlers);
        }
    }

    @Test
    void resolvesDefaultsAndRejectsGrpcProxy() {
        OtelExporterConfig config = OtelMetricsExporter.builder().resolveConfig();
        assertEquals(OtelExporterConfig.Protocol.GRPC, config.protocol());
        assertEquals(Duration.ofSeconds(60), config.exportInterval());

        assertThrows(
            IllegalArgumentException.class,
            () -> OtelMetricsExporter.builder().proxy("http://proxy:8080").resolveConfig()
        );
    }

    @Test
    void validatesMtlsPairAndClosesOnlyManagedProvider() {
        assertThrows(
            IllegalArgumentException.class,
            () -> OtelMetricsExporter.builder()
                .clientCertificate(Paths.get("client.pem"))
                .resolveConfig()
        );

        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setMeterProvider(provider)
            .build();
        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .openTelemetry(openTelemetry)
            .build();

        exporter.close();
        assertTrue(
            provider.forceFlush()
                .join(1, java.util.concurrent.TimeUnit.SECONDS)
                .isSuccess()
        );
        provider.close();
    }

    private static boolean hasMetric(Collection<MetricData> metrics, String name) {
        for (MetricData metric : metrics) {
            if (name.equals(metric.getName())) {
                return true;
            }
        }
        return false;
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

    private static final class RecordingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<LogRecord>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private boolean contains(String text) {
            for (LogRecord record : records) {
                if (record.getMessage().contains(text)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static MetricData metric(Collection<MetricData> metrics, String name) {
        for (MetricData metric : metrics) {
            if (name.equals(metric.getName())) {
                return metric;
            }
        }
        throw new AssertionError("Missing metric " + name);
    }

    private static Set<String> metricNames(Collection<MetricData> metrics) {
        Set<String> names = new HashSet<String>();
        for (MetricData metric : metrics) {
            names.add(metric.getName());
        }
        return names;
    }

    private static void assertConnectionMetrics(
            Collection<MetricData> metrics,
            Map<String, String> global,
            String connectionType,
            long inUse,
            long inPool,
            long opened,
            long closed
    ) {
        Map<String, String> attributes = new LinkedHashMap<String, String>(global);
        attributes.put("node", "N1");
        attributes.put("node_address", "127.0.0.1");
        attributes.put("node_port", "3000");
        attributes.put("conn_type", connectionType);
        assertLongMetric(metrics, "aerospike_client_node_connections_in_use",
            MetricDataType.LONG_GAUGE, inUse, attributes);
        assertLongMetric(metrics, "aerospike_client_node_connections_in_pool",
            MetricDataType.LONG_GAUGE, inPool, attributes);
        assertLongMetric(metrics, "aerospike_client_node_connections_opened",
            MetricDataType.LONG_SUM, opened, attributes);
        assertLongMetric(metrics, "aerospike_client_node_connections_closed",
            MetricDataType.LONG_SUM, closed, attributes);
    }

    private static void assertLongMetric(
            Collection<MetricData> metrics,
            String name,
            MetricDataType type,
            long expectedValue,
            Map<String, String> expectedAttributes
    ) {
        MetricData metric = metric(metrics, name);
        assertEquals(type, metric.getType(), name);
        Collection<LongPointData> points = type == MetricDataType.LONG_GAUGE
            ? metric.getLongGaugeData().getPoints()
            : metric.getLongSumData().getPoints();
        for (LongPointData point : points) {
            if (hasAttributes(point.getAttributes(), expectedAttributes)) {
                assertEquals(expectedValue, point.getValue(), name);
                assertEquals(expectedAttributes.size(), point.getAttributes().size(), name);
                return;
            }
        }
        throw new AssertionError("Missing " + name + " with attributes " + expectedAttributes);
    }

    private static void assertDoubleMetric(
            Collection<MetricData> metrics,
            String name,
            double expectedValue,
            Map<String, String> expectedAttributes
    ) {
        MetricData metric = metric(metrics, name);
        assertEquals(MetricDataType.DOUBLE_GAUGE, metric.getType(), name);
        for (DoublePointData point : metric.getDoubleGaugeData().getPoints()) {
            if (hasAttributes(point.getAttributes(), expectedAttributes)) {
                assertEquals(expectedValue, point.getValue(), name);
                assertEquals(expectedAttributes.size(), point.getAttributes().size(), name);
                return;
            }
        }
        throw new AssertionError("Missing " + name + " with attributes " + expectedAttributes);
    }

    private static void assertLatencyBuckets(
            Collection<MetricData> metrics,
            String operation,
            Map<String, String> expectedAttributes,
            long... expectedValues
    ) {
        assertLatencyBuckets(
            metrics,
            operation,
            expectedAttributes,
            new String[]{"1", "2", "4", "8", "16", "32", "+Inf"},
            expectedValues
        );
    }

    private static void assertLatencyBuckets(
            Collection<MetricData> metrics,
            String operation,
            Map<String, String> expectedAttributes,
            String[] expectedBounds,
            long[] expectedValues
    ) {
        assertEquals(expectedBounds.length, expectedValues.length);
        MetricData metric = metric(
            metrics,
            "aerospike_client_namespace_latency_" + operation + "_bucket"
        );
        assertEquals(MetricDataType.LONG_SUM, metric.getType());

        Map<String, Long> actual = new LinkedHashMap<String, Long>();
        for (LongPointData point : metric.getLongSumData().getPoints()) {
            if (hasAttributes(point.getAttributes(), expectedAttributes)) {
                assertEquals(
                    expectedAttributes.size() + 1,
                    point.getAttributes().size(),
                    metric.getName()
                );
                actual.put(
                    point.getAttributes().get(AttributeKey.stringKey("le")),
                    point.getValue()
                );
            }
        }

        Map<String, Long> expected = new LinkedHashMap<String, Long>();
        for (int index = 0; index < expectedBounds.length; index++) {
            expected.put(expectedBounds[index], expectedValues[index]);
        }
        assertEquals(expected, actual, metric.getName());
    }

    private static boolean hasAttributes(
            io.opentelemetry.api.common.Attributes actual,
            Map<String, String> expected
    ) {
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (!entry.getValue().equals(actual.get(AttributeKey.stringKey(entry.getKey())))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Long> gaugeValuesByClient(MetricData metric) {
        Map<String, Long> values = new LinkedHashMap<String, Long>();
        for (LongPointData point : metric.getLongGaugeData().getPoints()) {
            values.put(
                point.getAttributes().get(AttributeKey.stringKey("client_id")),
                point.getValue()
            );
        }
        return values;
    }

    private static Map<String, Long> sumValuesByClient(MetricData metric, String bound) {
        Map<String, Long> values = new LinkedHashMap<String, Long>();
        for (LongPointData point : metric.getLongSumData().getPoints()) {
            if (bound.equals(point.getAttributes().get(AttributeKey.stringKey("le")))) {
                values.put(
                    point.getAttributes().get(AttributeKey.stringKey("client_id")),
                    point.getValue()
                );
            }
        }
        return values;
    }

    private static void assertCommandCountSequence(
            InMemoryMetricReader reader,
            AggregationTemporality temporality,
            long firstExpected,
            long secondExpected
    ) {
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setMeterProvider(provider)
            .build();
        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .openTelemetry(openTelemetry)
            .snapshotStaleTimeout("5m")
            .build();
        try {
            exporter.export(OtelTestSnapshots.withCommandCount(10L));
            MetricData first = metric(
                reader.collectAllMetrics(),
                "aerospike_client_command_count"
            );
            assertEquals(temporality, first.getLongSumData().getAggregationTemporality());
            assertEquals(
                firstExpected,
                first.getLongSumData().getPoints().iterator().next().getValue()
            );

            exporter.export(OtelTestSnapshots.withCommandCount(25L));
            MetricData second = metric(
                reader.collectAllMetrics(),
                "aerospike_client_command_count"
            );
            assertEquals(temporality, second.getLongSumData().getAggregationTemporality());
            assertEquals(
                secondExpected,
                second.getLongSumData().getPoints().iterator().next().getValue()
            );
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    private static void assertLatencySequence(
            InMemoryMetricReader reader,
            AggregationTemporality temporality,
            long firstExpected,
            long secondExpected
    ) {
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .openTelemetry(OpenTelemetrySdk.builder().setMeterProvider(provider).build())
            .snapshotStaleTimeout("5m")
            .build();
        try {
            exporter.export(OtelTestSnapshots.withReadLatency(1, 1, 0));
            MetricData first = metric(
                reader.collectAllMetrics(),
                "aerospike_client_namespace_latency_read_bucket"
            );
            assertEquals(temporality, first.getLongSumData().getAggregationTemporality());
            assertEquals(firstExpected, pointForBound(first, "+Inf").getValue());

            exporter.export(OtelTestSnapshots.withReadLatency(2, 3, 1));
            MetricData second = metric(
                reader.collectAllMetrics(),
                "aerospike_client_namespace_latency_read_bucket"
            );
            assertEquals(temporality, second.getLongSumData().getAggregationTemporality());
            assertEquals(secondExpected, pointForBound(second, "+Inf").getValue());
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    private static LongPointData pointForBound(MetricData metric, String bound) {
        for (LongPointData point : metric.getLongSumData().getPoints()) {
            if (bound.equals(point.getAttributes().get(AttributeKey.stringKey("le")))) {
                return point;
            }
        }
        throw new AssertionError("Missing " + metric.getName() + " bucket " + bound);
    }

    private static MetricsSnapshot snapshot(boolean extended) {
        return snapshot(extended, "primary", 2);
    }

    private static MetricsSnapshot snapshot(
            boolean extended,
            String clientId,
            int totalNodes
    ) {
        return snapshot(extended, clientId, totalNodes, Collections.emptyList());
    }

    private static MetricsSnapshot snapshot(
            boolean extended,
            String clientId,
            int totalNodes,
            List<MetricsSnapshot.EventLoopSnapshot> eventLoops
    ) {
        MetricsSnapshot.ConnectionSnapshot sync =
            new MetricsSnapshot.ConnectionSnapshot(2, 3, 10, 5);
        MetricsSnapshot.ConnectionSnapshot async =
            new MetricsSnapshot.ConnectionSnapshot(1, 1, 4, 2);
        Map<com.aerospike.client.metrics.LatencyType, MetricsSnapshot.HistogramSnapshot> latency =
            new LinkedHashMap<>();
        latency.put(
            com.aerospike.client.metrics.LatencyType.READ,
            new MetricsSnapshot.HistogramSnapshot(
                new long[]{totalNodes, 1, 0, 0, 0, 0, 0},
                0,
                0,
                0.0,
                totalNodes + 1L
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

        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("team", "payments");
        if (clientId != null) {
            labels.put("client_id", clientId);
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
            MetricsSnapshot.HistogramType.LOGARITHMIC,
            MetricsSnapshot.LatencyUnit.MILLISECONDS,
            2,
            7
        );
    }
}

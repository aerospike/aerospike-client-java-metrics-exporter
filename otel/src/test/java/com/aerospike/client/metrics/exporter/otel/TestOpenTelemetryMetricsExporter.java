/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;

class TestOpenTelemetryMetricsExporter {
    @Test
    void requiresCustomerProvider() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new OpenTelemetryMetricsExporter(null)
        );
    }

    @Test
    void exportsSpecNamesScopesAndAttributes() {
        TestMetricReader reader = new TestMetricReader();
        SdkMeterProvider provider = provider(reader);
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        try {
            exporter.export(TestSnapshots.complete());
            Collection<MetricData> metrics = reader.collect();
            Set<String> names = names(metrics);

            assertTrue(names.contains("aerospike_client_total_nodes"));
            assertTrue(names.contains("aerospike_client_retry_count"));
            assertTrue(names.contains("aerospike_client_namespace_client_timeouts"));
            assertTrue(names.contains("aerospike_client_validation_open_failure"));
            assertTrue(names.contains("aerospike_client_feature_api_blocking"));
            assertTrue(names.contains("aerospike_client_latency_bucket"));
            assertTrue(names.contains("aerospike_client_namespace_latency_bucket"));
            assertTrue(names.contains("aerospike_client_node_conn_latency_bucket"));
            assertFalse(names.contains("aerospike_client_exceeded_max_retries"));

            MetricData latency = metric(metrics, "aerospike_client_latency_bucket");
            assertTrue(latency.getLongSumData().getPoints().stream().anyMatch(point ->
                "read".equals(point.getAttributes().get(
                    AttributeKey.stringKey("latency_type")
                ))
                    && point.getAttributes().get(AttributeKey.stringKey("le")) != null
            ));

            MetricData namespace = metric(
                metrics,
                "aerospike_client_namespace_client_timeouts"
            );
            assertTrue(namespace.getLongSumData().getPoints().stream().anyMatch(point ->
                "test".equals(point.getAttributes().get(
                    AttributeKey.stringKey("namespace")
                ))
                    && "N1".equals(point.getAttributes().get(
                        AttributeKey.stringKey("node")
                    ))
            ));
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void supportsIndependentEnabledDisabledEnabledTransitions() {
        TestMetricReader reader = new TestMetricReader();
        SdkMeterProvider provider = provider(reader);
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        try {
            exporter.export(TestSnapshots.withGates(true, true));
            assertTiers(reader.collect(), true, true);

            exporter.export(TestSnapshots.withGates(false, true));
            assertTiers(reader.collect(), false, true);

            exporter.export(TestSnapshots.withGates(true, false));
            assertTiers(reader.collect(), true, false);

            exporter.export(TestSnapshots.withGates(true, true));
            assertTiers(reader.collect(), true, true);
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void retainsDisabledSnapshotWithoutForcingAFlush() {
        TestMetricReader reader = new TestMetricReader();
        SdkMeterProvider provider = provider(reader);
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        try {
            exporter.export(TestSnapshots.complete());
            exporter.export(TestSnapshots.disabled());

            assertEquals(1, exporter.snapshotRegistry().size());
            assertEquals(0, reader.flushCount());
            MetricData commands = metric(
                reader.collect(), "aerospike_client_command_count"
            );
            assertTrue(commands.getLongSumData().getPoints().stream().anyMatch(
                point -> point.getValue() == 100L
            ));

            MetricData retained = metric(
                reader.collect(), "aerospike_client_command_count"
            );
            assertTrue(retained.getLongSumData().getPoints().stream().anyMatch(
                point -> point.getValue() == 100L
            ));
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void reEnableReplacesRetainedDisabledSnapshot() {
        TestMetricReader reader = new TestMetricReader();
        SdkMeterProvider provider = provider(reader);
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        try {
            exporter.export(TestSnapshots.complete());
            exporter.export(TestSnapshots.disabled());
            assertEquals(1, exporter.snapshotRegistry().size());

            exporter.export(TestSnapshots.forClient("primary", 101L));
            MetricData commands =
                metric(reader.collect(), "aerospike_client_command_count");
            assertTrue(commands.getLongSumData().getPoints().stream().anyMatch(
                point -> point.getValue() == 101L
            ));
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void closeClearsSnapshotsWithoutClosingCustomerProvider() {
        TestMetricReader reader = new TestMetricReader();
        SdkMeterProvider provider = provider(reader);
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        exporter.export(TestSnapshots.disabled());

        try {
            exporter.close();
            assertEquals(0, exporter.snapshotRegistry().size());
            assertEquals(0, reader.flushCount());
            assertEquals(0, reader.shutdownCount());
            assertThrows(
                IllegalStateException.class,
                () -> exporter.export(TestSnapshots.complete())
            );
        }
        finally {
            provider.close();
        }
        assertEquals(1, reader.shutdownCount());
    }

    @Test
    void supportsMultipleClientsAndDepartedNodes() {
        TestMetricReader reader = new TestMetricReader();
        SdkMeterProvider provider = provider(reader);
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        try {
            exporter.export(TestSnapshots.withDepartedNode());
            exporter.export(TestSnapshots.forClient("reporting", 42L));

            assertEquals(2, exporter.snapshotRegistry().size());
            MetricData connections = metric(
                reader.collect(),
                "aerospike_client_node_connections_opened"
            );
            assertTrue(connections.getLongSumData().getPoints().stream().anyMatch(point ->
                "D1".equals(point.getAttributes().get(AttributeKey.stringKey("node")))
            ));
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void callbackFailureDoesNotPoisonNextValidExport() {
        TestMetricReader reader = new TestMetricReader();
        SdkMeterProvider provider = provider(reader);
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        try {
            exporter.export(TestSnapshots.withInvalidEventLoop());
            assertDoesNotThrow(reader::collect);

            assertDoesNotThrow(() -> exporter.export(TestSnapshots.complete()));
            assertTrue(
                names(reader.collect()).contains("aerospike_client_total_nodes")
            );
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void exporterOwnedAttributesOverrideCustomerLabelsAtEveryScope() {
        TestMetricReader reader = new TestMetricReader();
        SdkMeterProvider provider = provider(reader);
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        try {
            exporter.export(TestSnapshots.withCollidingLabels());
            Collection<MetricData> metrics = reader.collect();

            var global = metric(metrics, "aerospike_client_total_nodes")
                .getLongGaugeData().getPoints().iterator().next().getAttributes();
            assertEquals("prod", global.get(AttributeKey.stringKey("cluster")));
            assertEquals("java", global.get(AttributeKey.stringKey("client_type")));
            assertFalse(global.asMap().containsKey(AttributeKey.stringKey("node")));
            assertFalse(global.asMap().containsKey(AttributeKey.stringKey("le")));

            var connection = metric(
                metrics, "aerospike_client_node_connections_opened"
            ).getLongSumData().getPoints().stream()
                .filter(point -> "sync".equals(point.getAttributes().get(
                    AttributeKey.stringKey("conn_type")
                )))
                .findFirst()
                .orElseThrow()
                .getAttributes();
            assertEquals("N1", connection.get(AttributeKey.stringKey("node")));
            assertEquals(
                "127.0.0.1",
                connection.get(AttributeKey.stringKey("node_address"))
            );

            var namespace = metric(
                metrics, "aerospike_client_namespace_errors"
            ).getLongSumData().getPoints().iterator().next().getAttributes();
            assertEquals(
                "test", namespace.get(AttributeKey.stringKey("namespace"))
            );

            var latency = metric(metrics, "aerospike_client_latency_bucket")
                .getLongSumData().getPoints().iterator().next().getAttributes();
            assertEquals(
                "milliseconds",
                latency.get(AttributeKey.stringKey("latency_unit"))
            );
            assertFalse("customer-bound".equals(
                latency.get(AttributeKey.stringKey("le"))
            ));
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void honorsCumulativeAndDeltaReaderTemporality() {
        assertTemporality(AggregationTemporality.CUMULATIVE, 109L, 20L);
        assertTemporality(AggregationTemporality.DELTA, 10L, 10L);
    }

    private static void assertTemporality(
            AggregationTemporality temporality,
            long expectedCommands,
            long expectedLatency
    ) {
        TestMetricReader reader = new TestMetricReader(temporality);
        SdkMeterProvider provider = provider(reader);
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        try {
            exporter.export(TestSnapshots.complete());
            reader.collect();
            exporter.export(TestSnapshots.updated());
            Collection<MetricData> metrics = reader.collect();

            MetricData commands =
                metric(metrics, "aerospike_client_command_count");
            assertEquals(
                temporality,
                commands.getLongSumData().getAggregationTemporality()
            );
            assertEquals(
                expectedCommands,
                commands.getLongSumData().getPoints().iterator().next().getValue()
            );

            MetricData latency = metric(metrics, "aerospike_client_latency_bucket");
            assertEquals(
                temporality,
                latency.getLongSumData().getAggregationTemporality()
            );
            assertEquals(
                expectedLatency,
                latency.getLongSumData().getPoints().stream()
                    .filter(point ->
                        "read".equals(point.getAttributes().get(
                            AttributeKey.stringKey("latency_type")
                        ))
                            && "+Inf".equals(point.getAttributes().get(
                                AttributeKey.stringKey("le")
                            ))
                    )
                    .findFirst()
                    .orElseThrow()
                    .getValue()
            );

            assertEquals(
                3L,
                metric(metrics, "aerospike_client_total_nodes")
                    .getLongGaugeData().getPoints().iterator().next().getValue()
            );
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    private static void assertTiers(
            Collection<MetricData> metrics,
            boolean operational,
            boolean usage
    ) {
        Set<String> names = names(metrics);
        assertTrue(names.contains("aerospike_client_total_nodes"));
        assertEquals(
            operational,
            names.contains("aerospike_client_command_count")
        );
        assertEquals(
            usage,
            names.contains("aerospike_client_feature_api_blocking")
        );
    }

    private static SdkMeterProvider provider(TestMetricReader reader) {
        return SdkMeterProvider.builder().registerMetricReader(reader).build();
    }

    private static Set<String> names(Collection<MetricData> metrics) {
        return metrics.stream().map(MetricData::getName).collect(Collectors.toSet());
    }

    private static MetricData metric(Collection<MetricData> metrics, String name) {
        return metrics.stream()
            .filter(metric -> name.equals(metric.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing metric " + name));
    }
}

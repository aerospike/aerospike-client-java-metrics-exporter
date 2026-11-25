/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
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

            assertTrue(names.contains("aerospike.client.cluster.total_nodes"));
            assertTrue(names.contains("aerospike.client.cluster.retry_count"));
            assertTrue(names.contains("aerospike.client.namespace.client_timeouts"));
            assertFalse(names.contains(
                "aerospike.client.cluster.validation_open_failure"
            ));
            assertFalse(names.contains("aerospike.client.feature.api_blocking"));
            assertTrue(names.contains("aerospike.client.cluster.latency_bucket"));
            assertTrue(names.contains("aerospike.client.namespace.latency_bucket"));
            assertTrue(names.contains("aerospike.client.node.conn_latency_bucket"));
            assertFalse(names.contains("aerospike.client.cluster.exceeded_max_retries"));

            MetricData latency = metric(
                metrics, "aerospike.client.cluster.latency_bucket"
            );
            assertEquals("{operation}", latency.getUnit());
            assertTrue(latency.getLongSumData().getPoints().stream().anyMatch(point ->
                "read".equals(point.getAttributes().get(
                    AttributeKey.stringKey("latency_type")
                ))
                    && point.getAttributes().get(AttributeKey.stringKey("le")) != null
            ));

            MetricData namespace = metric(
                metrics,
                "aerospike.client.namespace.client_timeouts"
            );
            assertEquals("{timeout}", namespace.getUnit());
            assertTrue(namespace.getLongSumData().getPoints().stream().anyMatch(point ->
                "test".equals(point.getAttributes().get(
                    AttributeKey.stringKey("ns")
                ))
                    && "N1".equals(point.getAttributes().get(
                        AttributeKey.stringKey("server_node_id")
                    ))
            ));
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void supportsOperationalEnabledDisabledEnabledTransitions() {
        TestMetricReader reader = new TestMetricReader();
        SdkMeterProvider provider = provider(reader);
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        try {
            exporter.export(TestSnapshots.withGates(true, true));
            assertTiers(reader.collect(), true);

            exporter.export(TestSnapshots.withGates(false, true));
            assertTiers(reader.collect(), false);

            exporter.export(TestSnapshots.withGates(true, false));
            assertTiers(reader.collect(), true);

            exporter.export(TestSnapshots.withGates(true, true));
            assertTiers(reader.collect(), true);
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
                reader.collect(), "aerospike.client.cluster.command_count"
            );
            assertTrue(commands.getLongSumData().getPoints().stream().anyMatch(
                point -> point.getValue() == 100L
            ));

            MetricData retained = metric(
                reader.collect(), "aerospike.client.cluster.command_count"
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
                metric(reader.collect(), "aerospike.client.cluster.command_count");
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
    void repeatedDisableReEnableKeepsNewestFinalSnapshot() {
        TestMetricReader reader = new TestMetricReader();
        SdkMeterProvider provider = provider(reader);
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        try {
            exporter.export(TestSnapshots.complete());
            exporter.export(TestSnapshots.disabled(100L));
            exporter.export(TestSnapshots.forClient("primary", 101L));
            exporter.export(TestSnapshots.disabled(102L));

            MetricData commands = metric(
                reader.collect(), "aerospike.client.cluster.command_count"
            );
            assertTrue(commands.getLongSumData().getPoints().stream().anyMatch(
                point -> point.getValue() == 102L
            ));
            assertEquals(0, reader.flushCount());
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void sharesCustomerProviderWithMultipleReadersAndApplicationMetrics() {
        TestMetricReader first = new TestMetricReader();
        TestMetricReader second = new TestMetricReader();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(first)
            .registerMetricReader(second)
            .build();
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        try {
            provider.get("application").counterBuilder("application.jobs")
                .build().add(7L);
            exporter.export(TestSnapshots.complete());

            for (TestMetricReader reader : Arrays.asList(first, second)) {
                Set<String> metricNames = names(reader.collect());
                assertTrue(metricNames.contains("application.jobs"));
                assertTrue(metricNames.contains(
                    "aerospike.client.cluster.command_count"
                ));
            }
        }
        finally {
            exporter.close();
            provider.close();
        }
    }

    @Test
    void separateAdaptersKeepIndependentRegistriesAndFailures() {
        TestMetricReader firstReader = new TestMetricReader();
        TestMetricReader secondReader = new TestMetricReader();
        SdkMeterProvider firstProvider = provider(firstReader);
        SdkMeterProvider secondProvider = provider(secondReader);
        OpenTelemetryMetricsExporter first =
            new OpenTelemetryMetricsExporter(firstProvider);
        OpenTelemetryMetricsExporter second =
            new OpenTelemetryMetricsExporter(secondProvider);
        try {
            assertNotSame(first.snapshotRegistry(), second.snapshotRegistry());
            first.export(TestSnapshots.withInvalidEventLoop());
            assertDoesNotThrow(firstReader::collect);

            second.export(TestSnapshots.forClient("primary", 42L));
            assertEquals(
                42L,
                metric(
                    secondReader.collect(),
                    "aerospike.client.cluster.command_count"
                ).getLongSumData().getPoints().iterator().next().getValue()
            );
        }
        finally {
            first.close();
            second.close();
            firstProvider.close();
            secondProvider.close();
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
            Collection<MetricData> metrics = reader.collect();
            Map<String, Long> commandsByClient = metric(
                metrics,
                "aerospike.client.cluster.command_count"
            ).getLongSumData().getPoints().stream().collect(Collectors.toMap(
                point -> point.getAttributes().get(
                    AttributeKey.stringKey("client_id")
                ),
                point -> point.getValue()
            ));
            Map<String, Long> expected = new LinkedHashMap<String, Long>();
            expected.put("primary", 99L);
            expected.put("reporting", 42L);
            assertEquals(expected, commandsByClient);

            MetricData connections = metric(
                metrics,
                "aerospike.client.node.connections_opened"
            );
            assertTrue(connections.getLongSumData().getPoints().stream().anyMatch(point ->
                "D1".equals(point.getAttributes().get(
                    AttributeKey.stringKey("server_node_id")
                ))
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
            Collection<MetricData> partial = assertDoesNotThrow(reader::collect);
            assertTrue(names(partial).contains(
                "aerospike.client.cluster.total_nodes"
            ));

            assertDoesNotThrow(() -> exporter.export(TestSnapshots.complete()));
            Collection<MetricData> recovered = reader.collect();
            assertTrue(names(recovered).contains(
                "aerospike.client.eventloop.process_size"
            ));
            assertEquals(
                7L,
                metric(recovered, "aerospike.client.eventloop.process_size")
                    .getLongGaugeData().getPoints().iterator().next().getValue()
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

            Attributes global = metric(
                metrics,
                "aerospike.client.cluster.total_nodes"
            )
                .getLongGaugeData().getPoints().iterator().next().getAttributes();
            assertEquals("prod", global.get(AttributeKey.stringKey("cluster")));
            assertEquals("java", global.get(AttributeKey.stringKey("client_type")));
            assertEquals("payments", global.get(AttributeKey.stringKey("team")));
            assertFalse(global.asMap().containsKey(
                AttributeKey.stringKey("feature")
            ));
            assertFalse(global.asMap().containsKey(
                AttributeKey.stringKey("server_node_id")
            ));
            assertFalse(global.asMap().containsKey(AttributeKey.stringKey("le")));

            Attributes connection = metric(
                metrics, "aerospike.client.node.connections_opened"
            ).getLongSumData().getPoints().stream()
                .filter(point -> "sync".equals(point.getAttributes().get(
                    AttributeKey.stringKey("conn_type")
                )))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "missing sync connection metric"
                ))
                .getAttributes();
            assertEquals(
                "N1", connection.get(AttributeKey.stringKey("server_node_id"))
            );
            assertEquals(
                "127.0.0.1",
                connection.get(AttributeKey.stringKey("server_node_address"))
            );

            Attributes namespace = metric(
                metrics, "aerospike.client.namespace.errors"
            ).getLongSumData().getPoints().iterator().next().getAttributes();
            assertEquals(
                "test", namespace.get(AttributeKey.stringKey("ns"))
            );

            Attributes latency = metric(
                metrics,
                "aerospike.client.cluster.latency_bucket"
            )
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
                metric(metrics, "aerospike.client.cluster.command_count");
            assertEquals(
                temporality,
                commands.getLongSumData().getAggregationTemporality()
            );
            assertEquals(
                expectedCommands,
                commands.getLongSumData().getPoints().iterator().next().getValue()
            );

            MetricData latency = metric(
                metrics, "aerospike.client.cluster.latency_bucket"
            );
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
                    .orElseThrow(() -> new AssertionError(
                        "missing read +Inf latency bucket"
                    ))
                    .getValue()
            );

            assertEquals(
                3L,
                metric(metrics, "aerospike.client.cluster.total_nodes")
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
            boolean operational
    ) {
        Set<String> names = names(metrics);
        assertTrue(names.contains("aerospike.client.cluster.total_nodes"));
        assertEquals(
            operational,
            names.contains("aerospike.client.cluster.command_count")
        );
        assertFalse(names.contains("aerospike.client.feature.api_blocking"));
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

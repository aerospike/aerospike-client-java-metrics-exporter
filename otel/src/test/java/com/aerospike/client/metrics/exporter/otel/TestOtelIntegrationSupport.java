/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

final class TestOtelIntegrationSupport {
    private TestOtelIntegrationSupport() {
    }

    static Collection<MetricData> awaitMetrics(
            InMemoryMetricReader reader,
            Predicate<Collection<MetricData>> predicate
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Collection<MetricData> latest = java.util.List.of();
        while (System.nanoTime() < deadline) {
            latest = reader.collectAllMetrics();
            if (predicate.test(latest)) {
                return latest;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("Timed out waiting for OTel metrics: " + latest);
    }

    static boolean contains(Collection<MetricData> metrics, String name) {
        return metrics.stream().anyMatch(metric -> name.equals(metric.getName()));
    }

    static MetricData metric(Collection<MetricData> metrics, String name) {
        return metrics.stream()
            .filter(metric -> name.equals(metric.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing metric " + name));
    }

    static long firstLongValue(MetricData metric) {
        if (!metric.getLongSumData().getPoints().isEmpty()) {
            return metric.getLongSumData().getPoints().iterator().next().getValue();
        }
        if (!metric.getLongGaugeData().getPoints().isEmpty()) {
            return metric.getLongGaugeData().getPoints().iterator().next().getValue();
        }
        throw new AssertionError("Metric has no long points: " + metric.getName());
    }
}

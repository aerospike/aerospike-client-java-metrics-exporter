/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class TestMetricRecorder implements MetricRecorder {
    private final List<Point> points = new ArrayList<Point>();

    @Override
    public void recordLong(
            MetricDescriptor descriptor,
            long value,
            Map<String, String> attributes
    ) {
        points.add(new Point(descriptor, value, Map.copyOf(attributes)));
    }

    @Override
    public void recordDouble(
            MetricDescriptor descriptor,
            double value,
            Map<String, String> attributes
    ) {
        points.add(new Point(descriptor, value, Map.copyOf(attributes)));
    }

    List<Point> points(String metricName) {
        return points.stream()
            .filter(point -> metricName.equals(point.descriptor().name()))
            .toList();
    }

    Set<String> names() {
        return points.stream()
            .map(point -> point.descriptor().name())
            .collect(Collectors.toSet());
    }

    record Point(
        MetricDescriptor descriptor,
        Number value,
        Map<String, String> attributes
    ) {
    }
}

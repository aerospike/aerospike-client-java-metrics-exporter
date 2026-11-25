/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
        points.add(new Point(descriptor, value, immutableCopy(attributes)));
    }

    @Override
    public void recordDouble(
            MetricDescriptor descriptor,
            double value,
            Map<String, String> attributes
    ) {
        points.add(new Point(descriptor, value, immutableCopy(attributes)));
    }

    List<Point> points(String metricName) {
        return points.stream()
            .filter(point -> metricName.equals(point.descriptor().name()))
            .collect(Collectors.toList());
    }

    Set<String> names() {
        return points.stream()
            .map(point -> point.descriptor().name())
            .collect(Collectors.toSet());
    }

    private static Map<String, String> immutableCopy(
            Map<String, String> attributes
    ) {
        return Collections.unmodifiableMap(
            new LinkedHashMap<String, String>(attributes)
        );
    }

    static final class Point {
        private final MetricDescriptor descriptor;
        private final Number value;
        private final Map<String, String> attributes;

        Point(
                MetricDescriptor descriptor,
                Number value,
                Map<String, String> attributes
        ) {
            this.descriptor = descriptor;
            this.value = value;
            this.attributes = attributes;
        }

        MetricDescriptor descriptor() {
            return descriptor;
        }

        Number value() {
            return value;
        }

        Map<String, String> attributes() {
            return attributes;
        }
    }
}

/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import java.util.Map;

import com.aerospike.client.metrics.exporter.common.MetricDescriptor;
import com.aerospike.client.metrics.exporter.common.MetricRecorder;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;

/**
 * Records one descriptor on the measurement active for its callback.
 */
final class OtelMetricRecorder implements MetricRecorder {
    private final MetricDescriptor expected;
    private final ObservableLongMeasurement longMeasurement;
    private final ObservableDoubleMeasurement doubleMeasurement;

    OtelMetricRecorder(
            MetricDescriptor expected,
            ObservableLongMeasurement measurement
    ) {
        this.expected = expected;
        this.longMeasurement = measurement;
        this.doubleMeasurement = null;
    }

    OtelMetricRecorder(
            MetricDescriptor expected,
            ObservableDoubleMeasurement measurement
    ) {
        this.expected = expected;
        this.longMeasurement = null;
        this.doubleMeasurement = measurement;
    }

    @Override
    public void recordLong(
            MetricDescriptor descriptor,
            long value,
            Map<String, String> attributes
    ) {
        requireExpected(descriptor);
        if (longMeasurement == null) {
            throw new IllegalStateException(
                "No long OTel measurement registered for " + descriptor.name()
            );
        }
        longMeasurement.record(value, attributes(attributes));
    }

    @Override
    public void recordDouble(
            MetricDescriptor descriptor,
            double value,
            Map<String, String> attributes
    ) {
        requireExpected(descriptor);
        if (doubleMeasurement == null) {
            throw new IllegalStateException(
                "No double OTel measurement registered for " + descriptor.name()
            );
        }
        doubleMeasurement.record(value, attributes(attributes));
    }

    private void requireExpected(MetricDescriptor descriptor) {
        if (descriptor != expected) {
            throw new IllegalStateException(
                "Callback for " + expected.name()
                    + " attempted to record " + descriptor.name()
            );
        }
    }

    private static Attributes attributes(Map<String, String> values) {
        AttributesBuilder builder = Attributes.builder();
        for (Map.Entry<String, String> value : values.entrySet()) {
            builder.put(value.getKey(), value.getValue());
        }
        return builder.build();
    }
}

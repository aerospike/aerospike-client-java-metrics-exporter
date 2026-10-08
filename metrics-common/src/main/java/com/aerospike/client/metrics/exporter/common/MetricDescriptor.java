/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

/**
 * Backend-neutral definition of an exported metric.
 *
 * <p>Descriptors keep schema decisions separate from snapshot traversal and
 * transport-specific registration.</p>
 */
public final class MetricDescriptor {
    /**
     * Metric behavior expected by OTel and Prometheus backends.
     */
    public enum Type {
        GAUGE,
        COUNTER,
        HISTOGRAM
    }

    private final String name;
    private final String description;
    private final String unit;
    private final Type type;
    private final boolean extended;

    public MetricDescriptor(String name, String description, String unit, Type type, boolean extended) {
        this.name = name;
        this.description = description;
        this.unit = unit;
        this.type = type;
        this.extended = extended;
    }

    /**
     * Return the canonical cross-backend metric name.
     */
    public String name() {
        return name;
    }

    /**
     * Return the operator-facing metric description.
     */
    public String description() {
        return description;
    }

    /**
     * Return the metric unit supplied to telemetry backends.
     */
    public String unit() {
        return unit;
    }

    /**
     * Return the metric type.
     */
    public Type type() {
        return type;
    }

    /**
     * Return whether the metric is emitted only for extended snapshots.
     */
    public boolean extended() {
        return extended;
    }

    /**
     * Return the Prometheus-compatible name. Counters use the conventional
     * {@code _total} suffix.
     */
    public String prometheusName() {
        String normalized = name.replace('.', '_').replace('-', '_');
        return type == Type.COUNTER && !normalized.endsWith("_total")
            ? normalized + "_total"
            : normalized;
    }
}

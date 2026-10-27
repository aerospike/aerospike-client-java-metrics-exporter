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
     * Backend-neutral metric behavior.
     *
     * <p>Cumulative buckets are mapped to observable counters by OTel. A
     * future Prometheus adapter can map the same points to histogram bucket
     * families without changing snapshot traversal.</p>
     */
    public enum Type {
        GAUGE,
        COUNTER,
        CUMULATIVE_BUCKETS
    }

    public enum ValueType {
        LONG,
        DOUBLE
    }

    public enum Tier {
        STANDARD,
        OPERATIONAL,
        USAGE
    }

    private final String name;
    private final String description;
    private final String unit;
    private final Type type;
    private final ValueType valueType;
    private final Tier tier;

    public MetricDescriptor(
            String name,
            String description,
            String unit,
            Type type,
            ValueType valueType,
            Tier tier
    ) {
        this.name = name;
        this.description = description;
        this.unit = unit;
        this.type = type;
        this.valueType = valueType;
        this.tier = tier;
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
     * Return the numeric representation used by backend instruments.
     */
    public ValueType valueType() {
        return valueType;
    }

    /**
     * Return the collection tier controlling this metric.
     */
    public Tier tier() {
        return tier;
    }

}

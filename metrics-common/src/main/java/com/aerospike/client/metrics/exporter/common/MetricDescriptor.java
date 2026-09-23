/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

public final class MetricDescriptor {
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

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String unit() {
        return unit;
    }

    public Type type() {
        return type;
    }

    public boolean extended() {
        return extended;
    }

    //todo: verify
    public String prometheusName() {
        String normalized = name.replace('.', '_').replace('-', '_');
        return type == Type.COUNTER && !normalized.endsWith("_total")
            ? normalized + "_total"
            : normalized;
    }
}

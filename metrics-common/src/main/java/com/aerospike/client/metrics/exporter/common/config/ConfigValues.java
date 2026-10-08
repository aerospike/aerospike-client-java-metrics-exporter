/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable flattened configuration values.
 */
public final class ConfigValues {
    private final Map<String, String> values;

    public ConfigValues(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<String, String>(values));
    }

    /**
     * Return the raw value for a flattened configuration key.
     */
    public String get(String key) {
        return values.get(key);
    }

    public String getOrDefault(String key, String defaultValue) {
        String value = values.get(key);
        return value == null ? defaultValue : value;
    }

    /**
     * Parse an integer or return the supplied default when absent.
     */
    public int getInt(String key, int defaultValue) {
        String value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer", e);
        }
    }

    /**
     * Parse a strict, case-insensitive boolean or return the default.
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(value.trim())) {
            return false;
        }
        throw new IllegalArgumentException(key + " must be true or false");
    }

    /**
     * Parse a positive duration or return the supplied default.
     */
    public Duration getDuration(String key, Duration defaultValue) {
        String value = values.get(key);
        return value == null ? defaultValue : DurationParser.parse(value, key);
    }

    /**
     * Select matching keys and strip the requested prefix from the result.
     * This is used for open-ended maps such as OTLP headers.
     */
    public Map<String, String> withPrefix(String prefix) {
        Map<String, String> selected = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                selected.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(selected);
    }

    /**
     * Return all immutable flattened values.
     */
    public Map<String, String> asMap() {
        return values;
    }
}

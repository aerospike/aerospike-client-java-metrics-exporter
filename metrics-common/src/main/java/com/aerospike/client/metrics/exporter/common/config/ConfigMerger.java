/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Merges configuration from lowest to highest precedence.
 */
public final class ConfigMerger {
    private ConfigMerger() {
    }

    /**
     * Merge sources in declaration order. Later non-null values override
     * earlier values, allowing callers to express configuration precedence.
     *
     * @param sourcesLowToHigh maps ordered from lowest to highest precedence
     */
    @SafeVarargs
    public static ConfigValues merge(Map<String, String>... sourcesLowToHigh) {
        Map<String, String> merged = new LinkedHashMap<String, String>();
        if (sourcesLowToHigh != null) {
            for (Map<String, String> source : sourcesLowToHigh) {
                if (source == null) {
                    continue;
                }
                for (Map.Entry<String, String> entry : source.entrySet()) {
                    if (entry.getValue() != null) {
                        merged.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
        return new ConfigValues(merged);
    }
}

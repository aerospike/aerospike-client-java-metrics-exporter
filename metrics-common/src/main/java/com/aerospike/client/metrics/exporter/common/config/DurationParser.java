/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config;

import java.time.Duration;
import java.util.Locale;

/**
 * Parses positive exporter durations from concise or ISO-8601 text.
 */
public final class DurationParser {
    private DurationParser() {
    }

    /**
     * Parse milliseconds by default, or values suffixed with {@code ms},
     * {@code s}, {@code m}, {@code h}; ISO-8601 values such as {@code PT10S}
     * are also accepted.
     *
     * @param raw configured duration
     * @param fieldName field used in validation messages
     */
    public static Duration parse(String raw, String fieldName) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }

        String value = raw.trim().toLowerCase(Locale.ROOT);
        try {
            Duration duration;
            if (value.startsWith("p")) {
                duration = Duration.parse(value.toUpperCase(Locale.ROOT));
            }
            else if (value.endsWith("ms")) {
                duration = Duration.ofMillis(parseNumber(value, 2));
            }
            else if (value.endsWith("s")) {
                duration = Duration.ofSeconds(parseNumber(value, 1));
            }
            else if (value.endsWith("m")) {
                duration = Duration.ofMinutes(parseNumber(value, 1));
            }
            else if (value.endsWith("h")) {
                duration = Duration.ofHours(parseNumber(value, 1));
            }
            else {
                duration = Duration.ofMillis(Long.parseLong(value));
            }

            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException(fieldName + " must be positive");
            }
            return duration;
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                fieldName + " must be a duration such as 500ms, 10s, 2m or PT10S",
                e
            );
        }
    }

    private static long parseNumber(String value, int suffixLength) {
        return Long.parseLong(value.substring(0, value.length() - suffixLength).trim());
    }
}

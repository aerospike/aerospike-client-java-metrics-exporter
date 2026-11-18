/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aerospike.client.metrics.exporter.prometheus;

import java.util.HashMap;
import java.util.Map;

import io.prometheus.metrics.model.snapshots.PrometheusNaming;
import io.prometheus.metrics.model.snapshots.Unit;

/**
 * Converts UCUM-style OpenTelemetry units to Prometheus unit names.
 */
final class PrometheusUnits {
    private static final Map<String, String> PLURAL_NAMES =
        new HashMap<String, String>();
    private static final Map<String, String> SINGULAR_NAMES =
        new HashMap<String, String>();
    private static final Map<String, Unit> PREDEFINED_UNITS =
        new HashMap<String, Unit>();

    static {
        initialize("a", "years", "year");
        initialize("mo", "months", "month");
        initialize("wk", "weeks", "week");
        initialize("d", "days", "day");
        initialize("h", "hours", "hour");
        initialize("min", "minutes", "minute");
        initialize("s", "seconds", "second");
        initialize("ms", "milliseconds", "millisecond");
        initialize("us", "microseconds", "microsecond");
        initialize("ns", "nanoseconds", "nanosecond");
        initialize("By", "bytes", "byte");
        initialize("KiBy", "kibibytes", "kibibyte");
        initialize("MiBy", "mebibytes", "mebibyte");
        initialize("GiBy", "gibibytes", "gibibyte");
        initialize("TiBy", "tibibytes", "tibibyte");
        initialize("KBy", "kilobytes", "kilobyte");
        initialize("MBy", "megabytes", "megabyte");
        initialize("GBy", "gigabytes", "gigabyte");
        initialize("TBy", "terabytes", "terabyte");
        initialize("m", "meters", "meter");
        initialize("V", "volts", "volt");
        initialize("A", "amperes", "ampere");
        initialize("J", "joules", "joule");
        initialize("W", "watts", "watt");
        initialize("g", "grams", "gram");
        initialize("Cel", "celsius");
        initialize("Hz", "hertz");
        initialize("%", "percent");
    }

    private PrometheusUnits() {
    }

    static Unit convert(String otelUnit) {
        if (otelUnit == null || otelUnit.isEmpty() || "1".equals(otelUnit)) {
            return null;
        }
        String normalized = otelUnit;
        if (normalized.contains("{")) {
            normalized = normalized.replaceAll("\\{[^}]*}", "").trim();
            if (normalized.isEmpty()
                    || "/".equals(normalized)
                    || "1".equals(normalized)) {
                return null;
            }
        }
        Unit predefined = PREDEFINED_UNITS.get(normalized);
        if (predefined != null) {
            return predefined;
        }
        if (normalized.contains("/")) {
            String[] parts = normalized.split("/", 2);
            String numerator = PLURAL_NAMES
                .getOrDefault(parts[0], parts[0])
                .trim();
            String denominator = SINGULAR_NAMES
                .getOrDefault(parts[1], parts[1])
                .trim();
            return unitOrNull(
                numerator.isEmpty()
                    ? "per_" + denominator
                    : numerator + "_per_" + denominator
            );
        }
        return unitOrNull(normalized);
    }

    private static void initialize(String otel, String plural) {
        PLURAL_NAMES.put(otel, plural);
        PREDEFINED_UNITS.put(otel, new Unit(plural));
    }

    private static void initialize(
            String otel,
            String plural,
            String singular
    ) {
        initialize(otel, plural);
        SINGULAR_NAMES.put(otel, singular);
    }

    private static Unit unitOrNull(String name) {
        try {
            return new Unit(PrometheusNaming.sanitizeUnitName(name));
        }
        catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}

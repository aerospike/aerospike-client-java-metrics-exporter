/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import static io.prometheus.metrics.model.snapshots.PrometheusNaming.prometheusName;
import static io.prometheus.metrics.model.snapshots.PrometheusNaming.sanitizeLabelName;
import static io.prometheus.metrics.model.snapshots.PrometheusNaming.sanitizeMetricName;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.DoublePointData;
import io.opentelemetry.sdk.metrics.data.HistogramData;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.SumData;
import io.opentelemetry.sdk.resources.Resource;
import io.prometheus.metrics.model.snapshots.ClassicHistogramBuckets;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.CounterSnapshot.CounterDataPointSnapshot;
import io.prometheus.metrics.model.snapshots.Exemplars;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot.GaugeDataPointSnapshot;
import io.prometheus.metrics.model.snapshots.HistogramSnapshot;
import io.prometheus.metrics.model.snapshots.HistogramSnapshot.HistogramDataPointSnapshot;
import io.prometheus.metrics.model.snapshots.InfoSnapshot;
import io.prometheus.metrics.model.snapshots.InfoSnapshot.InfoDataPointSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricMetadata;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import io.prometheus.metrics.model.snapshots.Unit;

/**
 * Converts stable OpenTelemetry metric data into stable client_java snapshots.
 */
final class OtelMetricDataConverter {
    private static final Logger LOGGER =
        Logger.getLogger(OtelMetricDataConverter.class.getName());
    private static final long NANOS_PER_MILLISECOND =
        TimeUnit.MILLISECONDS.toNanos(1);

    private final String metricPrefix;
    private final boolean targetInfoEnabled;
    private final boolean scopeInformationEnabled;
    private final Predicate<String> resourceAttributeFilter;

    OtelMetricDataConverter(PrometheusConfig config) {
        metricPrefix = normalizePrefix(config.getMetricPrefix());
        targetInfoEnabled = config.isTargetInfoEnabled();
        scopeInformationEnabled = config.isScopeInformationEnabled();
        resourceAttributeFilter = config.getResourceAttributeFilter();
    }

    MetricSnapshots convert(Collection<MetricData> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return MetricSnapshots.of();
        }

        Map<String, MetricSnapshot> converted =
            new HashMap<String, MetricSnapshot>(metrics.size());
        Resource targetResource = null;
        for (MetricData metric : metrics) {
            MetricSnapshot snapshot = convert(metric);
            if (snapshot != null) {
                putOrMerge(converted, snapshot);
                if (targetResource == null) {
                    targetResource = metric.getResource();
                }
            }
        }
        if (targetInfoEnabled && targetResource != null) {
            putOrMerge(converted, targetInfo(targetResource));
        }
        return new MetricSnapshots(converted.values());
    }

    private MetricSnapshot convert(MetricData metric) {
        MetricMetadata metadata = metadata(metric);
        InstrumentationScopeInfo scope =
            metric.getInstrumentationScopeInfo();
        Resource resource = metric.getResource();

        switch (metric.getType()) {
            case LONG_GAUGE:
                return longGauge(
                    metadata,
                    scope,
                    resource,
                    metric.getLongGaugeData().getPoints()
                );
            case DOUBLE_GAUGE:
                return doubleGauge(
                    metadata,
                    scope,
                    resource,
                    metric.getDoubleGaugeData().getPoints()
                );
            case LONG_SUM:
                return longSum(
                    metadata,
                    scope,
                    resource,
                    metric.getLongSumData()
                );
            case DOUBLE_SUM:
                return doubleSum(
                    metadata,
                    scope,
                    resource,
                    metric.getDoubleSumData()
                );
            case HISTOGRAM:
                return histogram(
                    metadata,
                    scope,
                    resource,
                    metric.getHistogramData()
                );
            default:
                LOGGER.log(
                    Level.WARNING,
                    "Unsupported OpenTelemetry metric type {0} for {1}; metric omitted",
                    new Object[] {metric.getType(), metric.getName()}
                );
                return null;
        }
    }

    private MetricSnapshot longSum(
            MetricMetadata metadata,
            InstrumentationScopeInfo scope,
            Resource resource,
            SumData<LongPointData> sum
    ) {
        if (!isCumulative(metadata, sum.getAggregationTemporality())) {
            return null;
        }
        if (!sum.isMonotonic()) {
            return longGauge(metadata, scope, resource, sum.getPoints());
        }

        List<CounterDataPointSnapshot> points =
            new ArrayList<CounterDataPointSnapshot>(sum.getPoints().size());
        for (LongPointData point : sum.getPoints()) {
            points.add(new CounterDataPointSnapshot(
                (double) point.getValue(),
                labels(resource, scope, point.getAttributes()),
                null,
                point.getStartEpochNanos() / NANOS_PER_MILLISECOND
            ));
        }
        return new CounterSnapshot(metadata, points);
    }

    private MetricSnapshot doubleSum(
            MetricMetadata metadata,
            InstrumentationScopeInfo scope,
            Resource resource,
            SumData<DoublePointData> sum
    ) {
        if (!isCumulative(metadata, sum.getAggregationTemporality())) {
            return null;
        }
        if (!sum.isMonotonic()) {
            return doubleGauge(metadata, scope, resource, sum.getPoints());
        }

        List<CounterDataPointSnapshot> points =
            new ArrayList<CounterDataPointSnapshot>(sum.getPoints().size());
        for (DoublePointData point : sum.getPoints()) {
            points.add(new CounterDataPointSnapshot(
                point.getValue(),
                labels(resource, scope, point.getAttributes()),
                null,
                point.getStartEpochNanos() / NANOS_PER_MILLISECOND
            ));
        }
        return new CounterSnapshot(metadata, points);
    }

    private GaugeSnapshot longGauge(
            MetricMetadata metadata,
            InstrumentationScopeInfo scope,
            Resource resource,
            Collection<LongPointData> source
    ) {
        List<GaugeDataPointSnapshot> points =
            new ArrayList<GaugeDataPointSnapshot>(source.size());
        for (LongPointData point : source) {
            points.add(new GaugeDataPointSnapshot(
                (double) point.getValue(),
                labels(resource, scope, point.getAttributes()),
                null
            ));
        }
        return new GaugeSnapshot(metadata, points);
    }

    private GaugeSnapshot doubleGauge(
            MetricMetadata metadata,
            InstrumentationScopeInfo scope,
            Resource resource,
            Collection<DoublePointData> source
    ) {
        List<GaugeDataPointSnapshot> points =
            new ArrayList<GaugeDataPointSnapshot>(source.size());
        for (DoublePointData point : source) {
            points.add(new GaugeDataPointSnapshot(
                point.getValue(),
                labels(resource, scope, point.getAttributes()),
                null
            ));
        }
        return new GaugeSnapshot(metadata, points);
    }

    private MetricSnapshot histogram(
            MetricMetadata metadata,
            InstrumentationScopeInfo scope,
            Resource resource,
            HistogramData data
    ) {
        if (!isCumulative(metadata, data.getAggregationTemporality())) {
            return null;
        }

        List<HistogramDataPointSnapshot> points =
            new ArrayList<HistogramDataPointSnapshot>(
                data.getPoints().size()
            );
        for (HistogramPointData point : data.getPoints()) {
            List<Double> boundaries =
                new ArrayList<Double>(point.getBoundaries().size() + 1);
            boundaries.addAll(point.getBoundaries());
            boundaries.add(Double.POSITIVE_INFINITY);
            points.add(new HistogramDataPointSnapshot(
                ClassicHistogramBuckets.of(boundaries, point.getCounts()),
                point.getSum(),
                labels(resource, scope, point.getAttributes()),
                Exemplars.EMPTY,
                point.getStartEpochNanos() / NANOS_PER_MILLISECOND
            ));
        }
        return new HistogramSnapshot(metadata, points);
    }

    private boolean isCumulative(
            MetricMetadata metadata,
            AggregationTemporality temporality
    ) {
        if (temporality == AggregationTemporality.CUMULATIVE) {
            return true;
        }
        LOGGER.log(
            Level.WARNING,
            "Delta metric {0} cannot be exposed as a Prometheus cumulative metric; omitted",
            metadata.getPrometheusName()
        );
        return false;
    }

    private MetricMetadata metadata(MetricData metric) {
        String name = sanitizeMetricName(
            prometheusName(metricPrefix + metric.getName())
        );
        Unit unit = PrometheusUnits.convert(metric.getUnit());
        if (unit != null && !name.endsWith(unit.toString())) {
            name = name + "_" + unit;
        }
        while (name.contains("__")) {
            name = name.replace("__", "_");
        }
        return new MetricMetadata(name, metric.getDescription(), unit);
    }

    private InfoSnapshot targetInfo(Resource resource) {
        return new InfoSnapshot(
            new MetricMetadata("target"),
            List.of(new InfoDataPointSnapshot(
                labels(null, null, resource.getAttributes())
            ))
        );
    }

    private Labels labels(
            Resource resource,
            InstrumentationScopeInfo scope,
            Attributes attributes
    ) {
        Map<String, String> values = new HashMap<String, String>();
        Map<String, String> pointOrigins = new HashMap<String, String>();
        for (Map.Entry<AttributeKey<?>, Object> entry
                : attributes.asMap().entrySet()) {
            putAttributeLabel(
                values,
                pointOrigins,
                entry.getKey().getKey(),
                labelValue(entry.getValue()),
                true
            );
        }

        if (scope != null && scopeInformationEnabled) {
            values.putIfAbsent("otel_scope_name", scope.getName());
            if (scope.getVersion() != null) {
                values.putIfAbsent("otel_scope_version", scope.getVersion());
            }
            if (scope.getSchemaUrl() != null) {
                values.putIfAbsent(
                    "otel_scope_schema_url",
                    scope.getSchemaUrl()
                );
            }
            Map<String, String> scopeOrigins =
                new HashMap<String, String>();
            for (Map.Entry<AttributeKey<?>, Object> entry
                    : scope.getAttributes().asMap().entrySet()) {
                putAttributeLabel(
                    values,
                    scopeOrigins,
                    "otel_scope_" + entry.getKey().getKey(),
                    String.valueOf(entry.getValue()),
                    false
                );
            }
        }

        if (resource != null && resourceAttributeFilter != null) {
            Map<String, String> resourceOrigins =
                new HashMap<String, String>();
            for (Map.Entry<AttributeKey<?>, Object> entry
                    : resource.getAttributes().asMap().entrySet()) {
                if (resourceAttributeFilter.test(entry.getKey().getKey())) {
                    putAttributeLabel(
                        values,
                        resourceOrigins,
                        entry.getKey().getKey(),
                        String.valueOf(entry.getValue()),
                        false
                    );
                }
            }
        }

        return Labels.of(
            values.keySet().toArray(new String[0]),
            values.values().toArray(new String[0])
        );
    }

    private static void putAttributeLabel(
            Map<String, String> values,
            Map<String, String> origins,
            String originalName,
            String value,
            boolean overwrite
    ) {
        String normalizedName = labelName(originalName);
        String previousOrigin = origins.putIfAbsent(
            normalizedName,
            originalName
        );
        if (previousOrigin != null
                && !previousOrigin.equals(originalName)) {
            throw new IllegalArgumentException(
                "OpenTelemetry attribute names "
                    + previousOrigin
                    + " and "
                    + originalName
                    + " both normalize to Prometheus label "
                    + normalizedName
            );
        }
        if (overwrite) {
            values.put(normalizedName, value);
        }
        else {
            values.putIfAbsent(normalizedName, value);
        }
    }

    private static String normalizePrefix(String prefix) {
        if (prefix.isEmpty()) {
            return "";
        }
        String normalized = sanitizeMetricName(prometheusName(prefix));
        return normalized.endsWith("_") ? normalized : normalized + "_";
    }

    private static String labelName(String name) {
        return sanitizeLabelName(prometheusName(name));
    }

    private static String labelValue(Object value) {
        if (!(value instanceof List<?>)) {
            return String.valueOf(value);
        }
        StringJoiner result = new StringJoiner(",", "[", "]");
        for (Object item : (List<?>) value) {
            result.add(
                item instanceof String
                    ? jsonString((String) item)
                    : String.valueOf(item)
            );
        }
        return result.toString();
    }

    private static String jsonString(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"':
                    result.append("\\\"");
                    break;
                case '\\':
                    result.append("\\\\");
                    break;
                case '\b':
                    result.append("\\b");
                    break;
                case '\f':
                    result.append("\\f");
                    break;
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '\t':
                    result.append("\\t");
                    break;
                default:
                    if (current <= 0x1f) {
                        result.append(String.format(
                            Locale.ROOT,
                            "\\u%04X",
                            (int) current
                        ));
                    }
                    else {
                        result.append(current);
                    }
            }
        }
        return result.append('"').toString();
    }

    private static void putOrMerge(
            Map<String, MetricSnapshot> metrics,
            MetricSnapshot incoming
    ) {
        String name = incoming.getMetadata().getPrometheusName();
        MetricSnapshot existing = metrics.get(name);
        if (existing == null) {
            metrics.put(name, incoming);
            return;
        }

        MetricSnapshot merged = merge(existing, incoming);
        if (merged == null) {
            LOGGER.log(
                Level.WARNING,
                "Conflicting Prometheus metric types for {0}; later metric omitted",
                name
            );
        }
        else {
            metrics.put(name, merged);
        }
    }

    private static MetricSnapshot merge(
            MetricSnapshot first,
            MetricSnapshot second
    ) {
        MetricMetadata metadata = mergeMetadata(
            first.getMetadata(),
            second.getMetadata()
        );
        if (metadata == null) {
            return null;
        }
        if (first instanceof GaugeSnapshot && second instanceof GaugeSnapshot) {
            List<GaugeDataPointSnapshot> points =
                new ArrayList<GaugeDataPointSnapshot>(
                    ((GaugeSnapshot) first).getDataPoints()
                );
            points.addAll(((GaugeSnapshot) second).getDataPoints());
            return new GaugeSnapshot(metadata, points);
        }
        if (first instanceof CounterSnapshot
                && second instanceof CounterSnapshot) {
            List<CounterDataPointSnapshot> points =
                new ArrayList<CounterDataPointSnapshot>(
                    ((CounterSnapshot) first).getDataPoints()
                );
            points.addAll(((CounterSnapshot) second).getDataPoints());
            return new CounterSnapshot(metadata, points);
        }
        if (first instanceof HistogramSnapshot
                && second instanceof HistogramSnapshot) {
            List<HistogramDataPointSnapshot> points =
                new ArrayList<HistogramDataPointSnapshot>(
                    ((HistogramSnapshot) first).getDataPoints()
                );
            points.addAll(((HistogramSnapshot) second).getDataPoints());
            return new HistogramSnapshot(metadata, points);
        }
        if (first instanceof InfoSnapshot && second instanceof InfoSnapshot) {
            List<InfoDataPointSnapshot> points =
                new ArrayList<InfoDataPointSnapshot>(
                    ((InfoSnapshot) first).getDataPoints()
                );
            points.addAll(((InfoSnapshot) second).getDataPoints());
            return new InfoSnapshot(metadata, points);
        }
        return null;
    }

    private static MetricMetadata mergeMetadata(
            MetricMetadata first,
            MetricMetadata second
    ) {
        Unit firstUnit = first.getUnit();
        Unit secondUnit = second.getUnit();
        if (firstUnit != null && !firstUnit.equals(secondUnit)) {
            LOGGER.log(
                Level.WARNING,
                "Conflicting units for Prometheus metric {0}; later metric omitted",
                first.getPrometheusName()
            );
            return null;
        }
        String name = first.getPrometheusName();
        if (first.getName().equals(second.getName())) {
            name = first.getName();
        }
        String help = first.getHelp() != null
                && first.getHelp().equals(second.getHelp())
            ? first.getHelp()
            : null;
        return new MetricMetadata(name, help, firstUnit);
    }
}

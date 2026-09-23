/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import static com.aerospike.client.metrics.exporter.common.MetricCatalog.COMMAND_COUNT;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.CONNECTIONS_CLOSED;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.CONNECTIONS_IN_POOL;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.CONNECTIONS_IN_USE;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.CONNECTIONS_OPENED;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.CPU_PERCENT;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.DELAY_QUEUE_TIMEOUTS;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.EXCEEDED_MAX_RETRIES;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.EXCEEDED_TOTAL_TIMEOUT;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.INVALID_NODES;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.MEMORY_BYTES;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.NAMESPACE_BYTES_IN;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.NAMESPACE_BYTES_OUT;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.NAMESPACE_ERRORS;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.NAMESPACE_KEY_BUSY;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.NAMESPACE_TIMEOUTS;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.OPEN_CONNECTIONS;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.RECOVER_QUEUE_SIZE;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.RETRIES;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.TOTAL_NODES;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.aerospike.client.metrics.LatencyType;
import com.aerospike.client.metrics.MetricsSnapshot;
import com.aerospike.client.metrics.exporter.common.MetricCatalog;
import com.aerospike.client.metrics.exporter.common.MetricDescriptor;
import com.aerospike.client.metrics.exporter.common.SnapshotAttributes;
import com.aerospike.client.metrics.exporter.common.SnapshotStore;

import io.prometheus.client.Collector;
import io.prometheus.client.CounterMetricFamily;
import io.prometheus.client.GaugeMetricFamily;

final class AerospikeSnapshotCollector extends Collector {
    private static final String SYNC = "sync";
    private static final String ASYNC = "async";

    private final SnapshotStore snapshots;

    AerospikeSnapshotCollector(SnapshotStore snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    public List<MetricFamilySamples> collect() {
        MetricsSnapshot snapshot = snapshots.currentIfFresh();
        if (snapshot == null) {
            return Collections.emptyList();
        }

        List<MetricFamilySamples> metrics = new ArrayList<MetricFamilySamples>();
        addGlobalGauge(metrics, snapshot, RECOVER_QUEUE_SIZE, snapshot.recoverQueueSize);
        addGlobalGauge(metrics, snapshot, TOTAL_NODES, snapshot.totalNodes);
        addGlobalGauge(metrics, snapshot, OPEN_CONNECTIONS, snapshot.openConnections);
        addGlobalCounter(metrics, snapshot, INVALID_NODES, snapshot.invalidNodeCount);
        addGlobalCounter(metrics, snapshot, RETRIES, snapshot.retryCount);
        addGlobalCounter(metrics, snapshot, DELAY_QUEUE_TIMEOUTS, snapshot.delayQueueTimeoutCount);
        addGlobalCounter(metrics, snapshot, EXCEEDED_MAX_RETRIES, snapshot.exceededMaxRetries);
        addGlobalCounter(metrics, snapshot, EXCEEDED_TOTAL_TIMEOUT, snapshot.exceededTotalTimeout);

        if (snapshot.extendedMetricsEnabled) {
            addGlobalGauge(metrics, snapshot, CPU_PERCENT, snapshot.cpuPercent);
            addGlobalGauge(metrics, snapshot, MEMORY_BYTES, snapshot.memoryBytes);
            addGlobalCounter(metrics, snapshot, COMMAND_COUNT, snapshot.commandCount);
        }

        addNodeGauge(metrics, snapshot, CONNECTIONS_IN_USE, true);
        addNodeGauge(metrics, snapshot, CONNECTIONS_IN_POOL, false);
        addNodeCounter(metrics, snapshot, CONNECTIONS_OPENED, true);
        addNodeCounter(metrics, snapshot, CONNECTIONS_CLOSED, false);

        if (snapshot.extendedMetricsEnabled) {
            addNamespaceCounter(metrics, snapshot, NAMESPACE_ERRORS, namespace -> namespace.errors);
            addNamespaceCounter(metrics, snapshot, NAMESPACE_TIMEOUTS, namespace -> namespace.timeouts);
            addNamespaceCounter(metrics, snapshot, NAMESPACE_KEY_BUSY, namespace -> namespace.keyBusy);
            addNamespaceCounter(metrics, snapshot, NAMESPACE_BYTES_IN, namespace -> namespace.bytesIn);
            addNamespaceCounter(metrics, snapshot, NAMESPACE_BYTES_OUT, namespace -> namespace.bytesOut);
            addLatencyHistograms(metrics, snapshot);
        }
        return metrics;
    }

    private static void addGlobalGauge(
            List<MetricFamilySamples> metrics,
            MetricsSnapshot snapshot,
            MetricDescriptor descriptor,
            double value
    ) {
        Map<String, String> attributes = SnapshotAttributes.global(snapshot);
        GaugeMetricFamily family = new GaugeMetricFamily(
            baseName(descriptor),
            descriptor.description(),
            labelNames(attributes)
        );
        family.addMetric(labelValues(attributes), value);
        metrics.add(family);
    }

    private static void addGlobalCounter(
            List<MetricFamilySamples> metrics,
            MetricsSnapshot snapshot,
            MetricDescriptor descriptor,
            double value
    ) {
        Map<String, String> attributes = SnapshotAttributes.global(snapshot);
        CounterMetricFamily family = new CounterMetricFamily(
            baseName(descriptor),
            descriptor.description(),
            labelNames(attributes)
        );
        family.addMetric(labelValues(attributes), value);
        metrics.add(family);
    }

    private static void addNodeGauge(
            List<MetricFamilySamples> metrics,
            MetricsSnapshot snapshot,
            MetricDescriptor descriptor,
            boolean inUse
    ) {
        if (snapshot.nodes.isEmpty()) {
            return;
        }
        List<String> labels = nodeLabelNames(snapshot);
        GaugeMetricFamily family = new GaugeMetricFamily(
            baseName(descriptor),
            descriptor.description(),
            labels
        );
        for (MetricsSnapshot.NodeSnapshot node : snapshot.nodes) {
            family.addMetric(
                labelValues(labels, SnapshotAttributes.node(snapshot, node, SYNC)),
                inUse ? node.syncConnections.inUse : node.syncConnections.inPool
            );
            family.addMetric(
                labelValues(labels, SnapshotAttributes.node(snapshot, node, ASYNC)),
                inUse ? node.asyncConnections.inUse : node.asyncConnections.inPool
            );
        }
        metrics.add(family);
    }

    private static void addNodeCounter(
            List<MetricFamilySamples> metrics,
            MetricsSnapshot snapshot,
            MetricDescriptor descriptor,
            boolean opened
    ) {
        if (snapshot.nodes.isEmpty()) {
            return;
        }
        List<String> labels = nodeLabelNames(snapshot);
        CounterMetricFamily family = new CounterMetricFamily(
            baseName(descriptor),
            descriptor.description(),
            labels
        );
        for (MetricsSnapshot.NodeSnapshot node : snapshot.nodes) {
            family.addMetric(
                labelValues(labels, SnapshotAttributes.node(snapshot, node, SYNC)),
                opened ? node.syncConnections.opened : node.syncConnections.closed
            );
            family.addMetric(
                labelValues(labels, SnapshotAttributes.node(snapshot, node, ASYNC)),
                opened ? node.asyncConnections.opened : node.asyncConnections.closed
            );
        }
        metrics.add(family);
    }

    private static void addNamespaceCounter(
            List<MetricFamilySamples> metrics,
            MetricsSnapshot snapshot,
            MetricDescriptor descriptor,
            NamespaceLongValue value
    ) {
        if (!hasNamespaces(snapshot)) {
            return;
        }
        List<String> labels = namespaceLabelNames(snapshot);
        CounterMetricFamily family = new CounterMetricFamily(
            baseName(descriptor),
            descriptor.description(),
            labels
        );
        for (MetricsSnapshot.NodeSnapshot node : snapshot.nodes) {
            for (MetricsSnapshot.NamespaceSnapshot namespace : node.namespaces) {
                family.addMetric(
                    labelValues(
                        labels,
                        SnapshotAttributes.namespace(snapshot, node, namespace)
                    ),
                    value.read(namespace)
                );
            }
        }
        metrics.add(family);
    }

    private static void addLatencyHistograms(
            List<MetricFamilySamples> metrics,
            MetricsSnapshot snapshot
    ) {
        for (LatencyType type : LatencyType.values()) {
            String operation = type.name().toLowerCase(Locale.ROOT);
            String unit = snapshot.latencyUnit == MetricsSnapshot.LatencyUnit.MICROSECONDS
                ? "us"
                : "ms";
            MetricDescriptor descriptor = MetricCatalog.latency(operation, unit);
            List<MetricFamilySamples.Sample> samples = new ArrayList<MetricFamilySamples.Sample>();

            for (MetricsSnapshot.NodeSnapshot node : snapshot.nodes) {
                for (MetricsSnapshot.NamespaceSnapshot namespace : node.namespaces) {
                    MetricsSnapshot.HistogramSnapshot histogram =
                        namespace.compatibilityLatencies.get(type);
                    if (histogram != null) {
                        addHistogramSamples(samples, snapshot, node, namespace, descriptor, histogram);
                    }
                }
            }

            if (!samples.isEmpty()) {
                metrics.add(new MetricFamilySamples(
                    baseName(descriptor),
                    Type.HISTOGRAM,
                    descriptor.description(),
                    samples
                ));
            }
        }
    }

    private static void addHistogramSamples(
            List<MetricFamilySamples.Sample> samples,
            MetricsSnapshot snapshot,
            MetricsSnapshot.NodeSnapshot node,
            MetricsSnapshot.NamespaceSnapshot namespace,
            MetricDescriptor descriptor,
            MetricsSnapshot.HistogramSnapshot histogram
    ) {
        Map<String, String> attributes = SnapshotAttributes.namespace(snapshot, node, namespace);
        List<String> baseNames = namespaceLabelNames(snapshot);
        List<String> baseValues = labelValues(baseNames, attributes);
        long[] buckets = histogram.getBuckets();
        long cumulative = 0L;
        double upperBound = 1.0;

        for (int i = 0; i < buckets.length; i++) {
            cumulative += buckets[i];
            List<String> names = new ArrayList<String>(baseNames);
            List<String> values = new ArrayList<String>(baseValues);
            names.add("le");
            values.add(i == buckets.length - 1 ? "+Inf" : formatBound(upperBound));
            samples.add(new MetricFamilySamples.Sample(
                baseName(descriptor) + "_bucket",
                names,
                values,
                cumulative
            ));

            if (i == 0) {
                upperBound = snapshot.latencyBase;
            }
            else if (snapshot.histogramType == MetricsSnapshot.HistogramType.LOGARITHMIC) {
                upperBound *= snapshot.latencyBase;
            }
            else {
                upperBound += snapshot.latencyBase;
            }
        }

        samples.add(new MetricFamilySamples.Sample(
            baseName(descriptor) + "_count",
            baseNames,
            baseValues,
            cumulative
        ));
    }

    private static List<String> nodeLabelNames(MetricsSnapshot snapshot) {
        List<String> labels = labelNames(SnapshotAttributes.global(snapshot));
        labels.add(SnapshotAttributes.NODE);
        labels.add(SnapshotAttributes.NODE_ADDRESS);
        labels.add(SnapshotAttributes.NODE_PORT);
        labels.add(SnapshotAttributes.CONNECTION_TYPE);
        return labels;
    }

    private static List<String> namespaceLabelNames(MetricsSnapshot snapshot) {
        List<String> labels = labelNames(SnapshotAttributes.global(snapshot));
        labels.add(SnapshotAttributes.NODE);
        labels.add(SnapshotAttributes.NODE_ADDRESS);
        labels.add(SnapshotAttributes.NODE_PORT);
        labels.add(SnapshotAttributes.NAMESPACE);
        return labels;
    }

    private static boolean hasNamespaces(MetricsSnapshot snapshot) {
        for (MetricsSnapshot.NodeSnapshot node : snapshot.nodes) {
            if (!node.namespaces.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String baseName(MetricDescriptor descriptor) {
        return descriptor.name().replace('.', '_').replace('-', '_');
    }

    private static List<String> labelNames(Map<String, String> attributes) {
        return new ArrayList<String>(attributes.keySet());
    }

    private static List<String> labelValues(Map<String, String> attributes) {
        return new ArrayList<String>(attributes.values());
    }

    private static List<String> labelValues(
            List<String> labelNames,
            Map<String, String> attributes
    ) {
        List<String> values = new ArrayList<String>(labelNames.size());
        for (String labelName : labelNames) {
            String value = attributes.get(labelName);
            values.add(value == null ? "" : value);
        }
        return values;
    }

    private static String formatBound(double value) {
        long integral = (long) value;
        return integral == value ? Long.toString(integral) : Double.toString(value);
    }

    private interface NamespaceLongValue {
        long read(MetricsSnapshot.NamespaceSnapshot namespace);
    }
}

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
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.EVENT_LOOP_PROCESS_SIZE;
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.EVENT_LOOP_QUEUE_SIZE;
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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.aerospike.client.metrics.LatencyType;
import com.aerospike.client.metrics.MetricsSnapshot;
import com.aerospike.client.metrics.exporter.common.ClientSnapshotRegistry;
import com.aerospike.client.metrics.exporter.common.MetricCatalog;
import com.aerospike.client.metrics.exporter.common.MetricDescriptor;
import com.aerospike.client.metrics.exporter.common.SnapshotAttributes;

import io.prometheus.client.Collector;
import io.prometheus.client.CounterMetricFamily;
import io.prometheus.client.GaugeMetricFamily;

/**
 * Converts current backend-neutral snapshots into Prometheus metric families
 * at scrape time.
 *
 * <p>The registry supplies a stable global label-name union. Missing values
 * become empty strings so every sample in a metric family has the same ordered
 * label schema.</p>
 */
final class AerospikePrometheusCollector extends Collector {
    private static final String SYNC = "sync";
    private static final String ASYNC = "async";

    private final ClientSnapshotRegistry snapshots;

    AerospikePrometheusCollector(ClientSnapshotRegistry snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    public List<MetricFamilySamples> collect() {
        Collection<MetricsSnapshot> current = snapshots.currentSnapshots();
        if (current.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> globalLabels = snapshots.knownGlobalLabelNames();
        List<MetricFamilySamples> metrics = new ArrayList<MetricFamilySamples>();
        addGlobalGauge(metrics, current, globalLabels, RECOVER_QUEUE_SIZE, snapshot -> snapshot.recoverQueueSize);
        addGlobalGauge(metrics, current, globalLabels, TOTAL_NODES, snapshot -> snapshot.totalNodes);
        addGlobalGauge(metrics, current, globalLabels, OPEN_CONNECTIONS, snapshot -> snapshot.openConnections);
        addGlobalCounter(metrics, current, globalLabels, INVALID_NODES, snapshot -> snapshot.invalidNodeCount);
        addGlobalCounter(metrics, current, globalLabels, RETRIES, snapshot -> snapshot.retryCount);
        addGlobalCounter(metrics, current, globalLabels, DELAY_QUEUE_TIMEOUTS, snapshot -> snapshot.delayQueueTimeoutCount);
        addGlobalCounter(metrics, current, globalLabels, EXCEEDED_MAX_RETRIES, snapshot -> snapshot.exceededMaxRetries);
        addGlobalCounter(metrics, current, globalLabels, EXCEEDED_TOTAL_TIMEOUT, snapshot -> snapshot.exceededTotalTimeout);
        addEventLoopGauge(metrics, current, globalLabels, EVENT_LOOP_PROCESS_SIZE, eventLoop -> eventLoop.processSize);
        addEventLoopGauge(metrics, current, globalLabels, EVENT_LOOP_QUEUE_SIZE, eventLoop -> eventLoop.queueSize);
        addGlobalGauge(metrics, current, globalLabels, CPU_PERCENT, snapshot -> snapshot.cpuPercent);
        addGlobalGauge(metrics, current, globalLabels, MEMORY_BYTES, snapshot -> snapshot.memoryBytes);
        addGlobalCounter(metrics, current, globalLabels, COMMAND_COUNT, snapshot -> snapshot.commandCount);

        addNodeGauge(metrics, current, globalLabels, CONNECTIONS_IN_USE, true);
        addNodeGauge(metrics, current, globalLabels, CONNECTIONS_IN_POOL, false);
        addNodeCounter(metrics, current, globalLabels, CONNECTIONS_OPENED, true);
        addNodeCounter(metrics, current, globalLabels, CONNECTIONS_CLOSED, false);

        addNamespaceCounter(metrics, current, globalLabels, NAMESPACE_ERRORS, namespace -> namespace.errors);
        addNamespaceCounter(metrics, current, globalLabels, NAMESPACE_TIMEOUTS, namespace -> namespace.timeouts);
        addNamespaceCounter(metrics, current, globalLabels, NAMESPACE_KEY_BUSY, namespace -> namespace.keyBusy);
        addNamespaceCounter(metrics, current, globalLabels, NAMESPACE_BYTES_IN, namespace -> namespace.bytesIn);
        addNamespaceCounter(metrics, current, globalLabels, NAMESPACE_BYTES_OUT, namespace -> namespace.bytesOut);
        addLatencyHistograms(metrics, current, globalLabels);
        return metrics;
    }

    private static void addGlobalGauge(
            List<MetricFamilySamples> metrics,
            Collection<MetricsSnapshot> snapshots,
            List<String> labels,
            MetricDescriptor descriptor,
            SnapshotDoubleValue value
    ) {
        GaugeMetricFamily family = new GaugeMetricFamily(
            baseName(descriptor),
            descriptor.description(),
            labels
        );
        boolean populated = false;
        for (MetricsSnapshot snapshot : snapshots) { // can we optimsie this loop, instead of lopoping eahc yime?, check MetricFamilySample
            if (!descriptor.extended() || snapshot.extendedMetricsEnabled) {
                family.addMetric(
                    labelValues(labels, SnapshotAttributes.global(snapshot)),
                    value.read(snapshot)
                );
                populated = true;
            }
        }
        if (populated) {
            metrics.add(family);
        }
    }

    private static void addEventLoopGauge(
            List<MetricFamilySamples> metrics,
            Collection<MetricsSnapshot> snapshots,
            List<String> labels,
            MetricDescriptor descriptor,
            EventLoopLongValue value
    ) {
        GaugeMetricFamily family = new GaugeMetricFamily(
            baseName(descriptor),
            descriptor.description(),
            labels
        );
        boolean populated = false;
        for (MetricsSnapshot snapshot : snapshots) {
            if (snapshot.eventLoops.isEmpty()) {
                continue;
            }
            long total = 0L;
            for (MetricsSnapshot.EventLoopSnapshot eventLoop : snapshot.eventLoops) {
                total += value.read(eventLoop);
            }
            family.addMetric(
                labelValues(labels, SnapshotAttributes.global(snapshot)),
                total
            );
            populated = true;
        }
        if (populated) {
            metrics.add(family);
        }
    }

    private static void addGlobalCounter(
            List<MetricFamilySamples> metrics,
            Collection<MetricsSnapshot> snapshots,
            List<String> labels,
            MetricDescriptor descriptor,
            SnapshotDoubleValue value
    ) {
        CounterMetricFamily family = new CounterMetricFamily(
            baseName(descriptor),
            descriptor.description(),
            labels
        );
        boolean populated = false;
        for (MetricsSnapshot snapshot : snapshots) {
            if (!descriptor.extended() || snapshot.extendedMetricsEnabled) {
                family.addMetric(
                    labelValues(labels, SnapshotAttributes.global(snapshot)),
                    value.read(snapshot)
                );
                populated = true;
            }
        }
        if (populated) {
            metrics.add(family);
        }
    }

    private static void addNodeGauge(
            List<MetricFamilySamples> metrics,
            Collection<MetricsSnapshot> snapshots,
            List<String> globalLabels,
            MetricDescriptor descriptor,
            boolean inUse
    ) {
        List<String> labels = nodeLabelNames(globalLabels);
        GaugeMetricFamily family = new GaugeMetricFamily(
            baseName(descriptor),
            descriptor.description(),
            labels
        );
        boolean populated = false;
        for (MetricsSnapshot snapshot : snapshots) {
            for (MetricsSnapshot.NodeSnapshot node : snapshot.nodes) {
                family.addMetric(
                    labelValues(labels, SnapshotAttributes.node(snapshot, node, SYNC)),
                    inUse ? node.syncConnections.inUse : node.syncConnections.inPool
                );
                family.addMetric(
                    labelValues(labels, SnapshotAttributes.node(snapshot, node, ASYNC)),
                    inUse ? node.asyncConnections.inUse : node.asyncConnections.inPool
                );
                populated = true;
            }
        }
        if (populated) {
            metrics.add(family);
        }
    }

    private static void addNodeCounter(
            List<MetricFamilySamples> metrics,
            Collection<MetricsSnapshot> snapshots,
            List<String> globalLabels,
            MetricDescriptor descriptor,
            boolean opened
    ) {
        List<String> labels = nodeLabelNames(globalLabels);
        CounterMetricFamily family = new CounterMetricFamily(
            baseName(descriptor),
            descriptor.description(),
            labels
        );
        boolean populated = false;
        for (MetricsSnapshot snapshot : snapshots) {
            for (MetricsSnapshot.NodeSnapshot node : snapshot.nodes) {
                family.addMetric(
                    labelValues(labels, SnapshotAttributes.node(snapshot, node, SYNC)),
                    opened ? node.syncConnections.opened : node.syncConnections.closed
                );
                family.addMetric(
                    labelValues(labels, SnapshotAttributes.node(snapshot, node, ASYNC)),
                    opened ? node.asyncConnections.opened : node.asyncConnections.closed
                );
                populated = true;
            }
        }
        if (populated) {
            metrics.add(family);
        }
    }

    private static void addNamespaceCounter(
            List<MetricFamilySamples> metrics,
            Collection<MetricsSnapshot> snapshots,
            List<String> globalLabels,
            MetricDescriptor descriptor,
            NamespaceLongValue value
    ) {
        List<String> labels = namespaceLabelNames(globalLabels);
        CounterMetricFamily family = new CounterMetricFamily(
            baseName(descriptor),
            descriptor.description(),
            labels
        );
        boolean populated = false;
        for (MetricsSnapshot snapshot : snapshots) {
            if (!descriptor.extended() || snapshot.extendedMetricsEnabled) {
                for (MetricsSnapshot.NodeSnapshot node : snapshot.nodes) {
                    for (MetricsSnapshot.NamespaceSnapshot namespace : node.namespaces) {
                        family.addMetric(
                            labelValues(
                                labels,
                                SnapshotAttributes.namespace(snapshot, node, namespace)
                            ),
                            value.read(namespace)
                        );
                        populated = true;
                    }
                }
            }
        }
        if (populated) {
            metrics.add(family);
        }
    }

    private static void addLatencyHistograms(
            List<MetricFamilySamples> metrics,
            Collection<MetricsSnapshot> snapshots,
            List<String> globalLabels
    ) {
        // Latency operation names are separate metric families in the current
        // compatibility schema.
        for (LatencyType type : LatencyType.values()) {
            String operation = type.name().toLowerCase(Locale.ROOT);
            List<MetricFamilySamples.Sample> samples = new ArrayList<MetricFamilySamples.Sample>();
            MetricDescriptor descriptor = null;

            for (MetricsSnapshot snapshot : snapshots) {
                if (snapshot.extendedMetricsEnabled) {
                    if (descriptor == null) {
                        String unit =
                            snapshot.latencyUnit == MetricsSnapshot.LatencyUnit.MICROSECONDS
                                ? "us"
                                : "ms";
                        descriptor = MetricCatalog.latency(operation, unit);
                    }
                    for (MetricsSnapshot.NodeSnapshot node : snapshot.nodes) {
                        for (MetricsSnapshot.NamespaceSnapshot namespace : node.namespaces) {
                            MetricsSnapshot.HistogramSnapshot histogram =
                                namespace.compatibilityLatencies.get(type);
                            if (histogram != null) {
                                addHistogramSamples(
                                    samples,
                                    snapshot,
                                    node,
                                    namespace,
                                    globalLabels,
                                    descriptor,
                                    histogram
                                );
                            }
                        }
                    }
                }
            }

            if (descriptor != null && !samples.isEmpty()) {
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
            List<String> globalLabels,
            MetricDescriptor descriptor,
            MetricsSnapshot.HistogramSnapshot histogram
    ) {
        Map<String, String> attributes = SnapshotAttributes.namespace(snapshot, node, namespace);
        List<String> baseNames = namespaceLabelNames(globalLabels);
        List<String> baseValues = labelValues(baseNames, attributes);
        long[] buckets = histogram.getBuckets();
        long cumulative = 0L;
        double upperBound = 1.0;

        // Client snapshots store mutually exclusive bucket counts, while
        // classic Prometheus histograms require cumulative "le" buckets.
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
        // A sum cannot be reconstructed from bucket counts without inventing
        // raw observations, so this compatibility histogram omits "_sum".
    }

    private static List<String> nodeLabelNames(List<String> globalLabels) {
        List<String> labels = new ArrayList<String>(globalLabels);
        labels.add(SnapshotAttributes.NODE);
        labels.add(SnapshotAttributes.NODE_ADDRESS);
        labels.add(SnapshotAttributes.NODE_PORT);
        labels.add(SnapshotAttributes.CONNECTION_TYPE);
        return labels;
    }

    private static List<String> namespaceLabelNames(List<String> globalLabels) {
        List<String> labels = new ArrayList<String>(globalLabels);
        labels.add(SnapshotAttributes.NODE);
        labels.add(SnapshotAttributes.NODE_ADDRESS);
        labels.add(SnapshotAttributes.NODE_PORT);
        labels.add(SnapshotAttributes.NAMESPACE);
        return labels;
    }

    private static String baseName(MetricDescriptor descriptor) {
        return descriptor.name().replace('.', '_').replace('-', '_');
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

    private interface EventLoopLongValue {
        long read(MetricsSnapshot.EventLoopSnapshot eventLoop);
    }

    private interface SnapshotDoubleValue {
        double read(MetricsSnapshot snapshot);
    }
}

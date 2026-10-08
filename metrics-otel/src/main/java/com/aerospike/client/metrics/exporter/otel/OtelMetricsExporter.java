/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

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
import static com.aerospike.client.metrics.exporter.common.MetricCatalog.latencyBucket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.aerospike.client.metrics.IMetricsExporter;
import com.aerospike.client.metrics.LatencyType;
import com.aerospike.client.metrics.MetricsSnapshot;
import com.aerospike.client.metrics.exporter.common.ClientSnapshotRegistry;
import com.aerospike.client.metrics.exporter.common.MetricDescriptor;
import com.aerospike.client.metrics.exporter.common.SnapshotAttributes;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableDoubleGauge;
import io.opentelemetry.api.metrics.ObservableLongCounter;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;

/**
 * OpenTelemetry exporter for immutable Aerospike client metric snapshots.
 *
 * <p>{@link #export(MetricsSnapshot)} only replaces the latest per-client
 * snapshot. OTel observable instruments read current snapshots when the SDK
 * collects them, so the Aerospike metrics thread never performs network I/O.</p>
 */
public final class OtelMetricsExporter implements IMetricsExporter {
    private static final Logger LOGGER =
        Logger.getLogger(OtelMetricsExporter.class.getName());
    private static final String SYNC = "sync";
    private static final String ASYNC = "async";

    private final ClientSnapshotRegistry snapshots;
    private final SdkMeterProvider ownedMeterProvider;
    private final List<AutoCloseable> instruments = new ArrayList<AutoCloseable>();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    OtelMetricsExporter(
            Meter meter,
            ClientSnapshotRegistry snapshots,
            SdkMeterProvider ownedMeterProvider
    ) {
        if (meter == null) {
            throw new IllegalArgumentException("meter must not be null");
        }
        if (snapshots == null) {
            throw new IllegalArgumentException("snapshots must not be null");
        }
        this.snapshots = snapshots;
        this.ownedMeterProvider = ownedMeterProvider;
        registerInstruments(meter);
    }

    /**
     * Create a new OTel exporter builder.
     */
    public static OtelMetricsExporterBuilder builder() {
        return new OtelMetricsExporterBuilder();
    }

    /**
     * Retain the latest snapshot for its {@code client_id}. Calls made after
     * close are ignored.
     */
    @Override
    public void export(MetricsSnapshot snapshot) {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                return;
            }
            snapshots.update(snapshot);
        }
    }

    /**
     * Expose registry state for diagnostics and tests.
     */
    public ClientSnapshotRegistry snapshotRegistry() {
        return snapshots;
    }

    /**
     * Unregister callbacks and close only telemetry infrastructure created by
     * this exporter. Application-supplied providers remain application-owned.
     * Repeated calls are safe.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (lifecycleLock) {
            snapshots.clear();
        }
        for (AutoCloseable instrument : instruments) {
            try {
                instrument.close();
            }
            catch (Exception ignored) {
                // Best-effort callback unregistration; provider shutdown follows.
            }
        }
        if (ownedMeterProvider != null) {
            try {
                awaitProviderShutdown(
                    ownedMeterProvider.shutdown(),
                    10,
                    TimeUnit.SECONDS
                );
            }
            catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Unable to shut down the OTel meter provider", e);
            }
        }
    }

    static void awaitProviderShutdown(
            CompletableResultCode result,
            long timeout,
            TimeUnit unit
    ) {
        result.join(timeout, unit);
        if (!result.isDone()) {
            LOGGER.warning("Timed out while shutting down the OTel meter provider");
            return;
        }
        if (!result.isSuccess()) {
            Throwable failure = result.getFailureThrowable();
            if (failure == null) {
                LOGGER.warning("OTel meter provider shutdown failed");
            }
            else {
                LOGGER.log(Level.WARNING, "OTel meter provider shutdown failed", failure);
            }
        }
    }

    private void registerInstruments(Meter meter) {
        // Instruments are registered once. Their callbacks read the registry so
        // any number of Aerospike clients can share this exporter instance.
        registerGlobalLongGauge(meter, RECOVER_QUEUE_SIZE, snapshot -> snapshot.recoverQueueSize);
        registerGlobalLongGauge(meter, TOTAL_NODES, snapshot -> snapshot.totalNodes);
        registerGlobalLongGauge(meter, OPEN_CONNECTIONS, snapshot -> snapshot.openConnections);
        registerGlobalLongCounter(meter, INVALID_NODES, snapshot -> snapshot.invalidNodeCount);
        registerGlobalLongCounter(meter, RETRIES, snapshot -> snapshot.retryCount);
        registerGlobalLongCounter(meter, DELAY_QUEUE_TIMEOUTS, snapshot -> snapshot.delayQueueTimeoutCount);
        registerGlobalLongCounter(meter, EXCEEDED_MAX_RETRIES, snapshot -> snapshot.exceededMaxRetries);
        registerGlobalLongCounter(meter, EXCEEDED_TOTAL_TIMEOUT, snapshot -> snapshot.exceededTotalTimeout);
        registerEventLoopGauge(meter, EVENT_LOOP_PROCESS_SIZE, eventLoop -> eventLoop.processSize);
        registerEventLoopGauge(meter, EVENT_LOOP_QUEUE_SIZE, eventLoop -> eventLoop.queueSize);

        ObservableDoubleGauge cpu = meter.gaugeBuilder(CPU_PERCENT.name())
            .setDescription(CPU_PERCENT.description())
            .setUnit(CPU_PERCENT.unit())
            .buildWithCallback(measurement -> {
                for (MetricsSnapshot snapshot : snapshots.currentSnapshots()) {
                    if (!CPU_PERCENT.extended() || snapshot.extendedMetricsEnabled) {
                        measurement.record(
                            snapshot.cpuPercent,
                            attributes(SnapshotAttributes.global(snapshot))
                        );
                    }
                }
            });
        instruments.add(cpu);

        registerGlobalLongGauge(meter, MEMORY_BYTES, snapshot -> snapshot.memoryBytes);
        registerGlobalLongCounter(meter, COMMAND_COUNT, snapshot -> snapshot.commandCount);

        registerNodeGauge(meter, CONNECTIONS_IN_USE, true);
        registerNodeGauge(meter, CONNECTIONS_IN_POOL, false);
        registerNodeCounter(meter, CONNECTIONS_OPENED, true);
        registerNodeCounter(meter, CONNECTIONS_CLOSED, false);

        registerNamespaceCounter(meter, NAMESPACE_ERRORS, namespace -> namespace.errors);
        registerNamespaceCounter(meter, NAMESPACE_TIMEOUTS, namespace -> namespace.timeouts);
        registerNamespaceCounter(meter, NAMESPACE_KEY_BUSY, namespace -> namespace.keyBusy);
        registerNamespaceCounter(meter, NAMESPACE_BYTES_IN, namespace -> namespace.bytesIn);
        registerNamespaceCounter(meter, NAMESPACE_BYTES_OUT, namespace -> namespace.bytesOut);

        for (int index = 0; index < LatencyType.getMax(); index++) {
            registerLatencyBucketCounter(meter, LatencyType.values()[index]);
        }
    }

    private void registerGlobalLongGauge(
            Meter meter,
            MetricDescriptor descriptor,
            SnapshotLongValue value
    ) {
        ObservableLongGauge instrument = meter.gaugeBuilder(descriptor.name())
            .ofLongs()
            .setDescription(descriptor.description())
            .setUnit(descriptor.unit())
            .buildWithCallback(measurement -> {
                for (MetricsSnapshot snapshot : snapshots.currentSnapshots()) {
                    if (!descriptor.extended() || snapshot.extendedMetricsEnabled) {
                        measurement.record(
                            value.read(snapshot),
                            attributes(SnapshotAttributes.global(snapshot))
                        );
                    }
                }
            });
        instruments.add(instrument);
    }

    private void registerEventLoopGauge(
            Meter meter,
            MetricDescriptor descriptor,
            EventLoopLongValue value
    ) {
        ObservableLongGauge instrument = meter.gaugeBuilder(descriptor.name())
            .ofLongs()
            .setDescription(descriptor.description())
            .setUnit(descriptor.unit())
            .buildWithCallback(measurement -> {
                for (MetricsSnapshot snapshot : snapshots.currentSnapshots()) {
                    if (snapshot.eventLoops.isEmpty()) {
                        continue;
                    }
                    long total = 0L;
                    for (MetricsSnapshot.EventLoopSnapshot eventLoop : snapshot.eventLoops) {
                        total += value.read(eventLoop);
                    }
                    measurement.record(total, attributes(SnapshotAttributes.global(snapshot)));
                }
            });
        instruments.add(instrument);
    }

    private void registerGlobalLongCounter(
            Meter meter,
            MetricDescriptor descriptor,
            SnapshotLongValue value
    ) {
        ObservableLongCounter instrument = meter.counterBuilder(descriptor.name())
            .setDescription(descriptor.description())
            .setUnit(descriptor.unit())
            .buildWithCallback(measurement -> {
                for (MetricsSnapshot snapshot : snapshots.currentSnapshots()) {
                    if (!descriptor.extended() || snapshot.extendedMetricsEnabled) {
                        measurement.record(
                            value.read(snapshot),
                            attributes(SnapshotAttributes.global(snapshot))
                        );
                    }
                }
            });
        instruments.add(instrument);
    }

    private void registerNodeGauge(Meter meter, MetricDescriptor descriptor, boolean inUse) {
        ObservableLongGauge instrument = meter.gaugeBuilder(descriptor.name())
            .ofLongs()
            .setDescription(descriptor.description())
            .setUnit(descriptor.unit())
            .buildWithCallback(measurement -> {
                for (MetricsSnapshot snapshot : snapshots.currentSnapshots()) {
                    for (MetricsSnapshot.NodeSnapshot node : snapshot.nodes) {
                        measurement.record(
                            inUse ? node.syncConnections.inUse : node.syncConnections.inPool,
                            attributes(SnapshotAttributes.node(snapshot, node, SYNC))
                        );
                        measurement.record(
                            inUse ? node.asyncConnections.inUse : node.asyncConnections.inPool,
                            attributes(SnapshotAttributes.node(snapshot, node, ASYNC))
                        );
                    }
                }
            });
        instruments.add(instrument);
    }

    private void registerNodeCounter(Meter meter, MetricDescriptor descriptor, boolean opened) {
        ObservableLongCounter instrument = meter.counterBuilder(descriptor.name())
            .setDescription(descriptor.description())
            .setUnit(descriptor.unit())
            .buildWithCallback(measurement -> {
                for (MetricsSnapshot snapshot : snapshots.currentSnapshots()) {
                    for (MetricsSnapshot.NodeSnapshot node : snapshot.nodes) {
                        measurement.record(
                            opened ? node.syncConnections.opened : node.syncConnections.closed,
                            attributes(SnapshotAttributes.node(snapshot, node, SYNC))
                        );
                        measurement.record(
                            opened ? node.asyncConnections.opened : node.asyncConnections.closed,
                            attributes(SnapshotAttributes.node(snapshot, node, ASYNC))
                        );
                    }
                }
            });
        instruments.add(instrument);
    }

    private void registerNamespaceCounter(
            Meter meter,
            MetricDescriptor descriptor,
            NamespaceLongValue value
    ) {
        ObservableLongCounter instrument = meter.counterBuilder(descriptor.name())
            .setDescription(descriptor.description())
            .setUnit(descriptor.unit())
            .buildWithCallback(measurement -> {
                for (MetricsSnapshot snapshot : snapshots.currentSnapshots()) {
                    if (!descriptor.extended() || snapshot.extendedMetricsEnabled) {
                        for (MetricsSnapshot.NodeSnapshot node : snapshot.nodes) {
                            for (MetricsSnapshot.NamespaceSnapshot namespace : node.namespaces) {
                                measurement.record(
                                    value.read(namespace),
                                    attributes(SnapshotAttributes.namespace(
                                        snapshot,
                                        node,
                                        namespace
                                    ))
                                );
                            }
                        }
                    }
                }
            });
        instruments.add(instrument);
    }

    private void registerLatencyBucketCounter(Meter meter, LatencyType latencyType) {
        String operation = latencyType.name().toLowerCase(Locale.ROOT);
        MetricDescriptor descriptor = latencyBucket(operation);
        ObservableLongCounter instrument = meter.counterBuilder(descriptor.name())
            .setDescription(descriptor.description())
            .setUnit(descriptor.unit())
            .buildWithCallback(measurement -> {
                for (MetricsSnapshot snapshot : snapshots.currentSnapshots()) {
                    if (!snapshot.extendedMetricsEnabled) {
                        continue;
                    }
                    for (MetricsSnapshot.NodeSnapshot node : snapshot.nodes) {
                        for (MetricsSnapshot.NamespaceSnapshot namespace : node.namespaces) {
                            MetricsSnapshot.HistogramSnapshot histogram =
                                namespace.compatibilityLatencies.get(latencyType);
                            if (histogram == null) {
                                continue;
                            }
                            long[] buckets = histogram.getBuckets();
                            long cumulative = 0L;
                            double upperBound = 1.0;
                            for (int index = 0; index < buckets.length; index++) {
                                cumulative += buckets[index];
                                Map<String, String> values =
                                    SnapshotAttributes.namespace(snapshot, node, namespace);
                                values.put(
                                    "le",
                                    index == buckets.length - 1
                                        ? "+Inf"
                                        : formatBound(upperBound)
                                );
                                measurement.record(cumulative, attributes(values));

                                if (index == 0) {
                                    upperBound = snapshot.latencyBase;
                                }
                                else if (snapshot.histogramType
                                        == MetricsSnapshot.HistogramType.LOGARITHMIC) {
                                    upperBound *= snapshot.latencyBase;
                                }
                                else {
                                    upperBound += snapshot.latencyBase;
                                }
                            }
                        }
                    }
                }
            });
        instruments.add(instrument);
    }

    private static String formatBound(double value) {
        long integral = (long) value;
        return integral == value ? Long.toString(integral) : Double.toString(value);
    }

    private static Attributes attributes(Map<String, String> values) {
        AttributesBuilder builder = Attributes.builder();
        for (Map.Entry<String, String> value : values.entrySet()) {
            builder.put(value.getKey(), value.getValue());
        }
        return builder.build();
    }

    private interface SnapshotLongValue {
        long read(MetricsSnapshot snapshot);
    }

    private interface EventLoopLongValue {
        long read(MetricsSnapshot.EventLoopSnapshot eventLoop);
    }

    private interface NamespaceLongValue {
        long read(MetricsSnapshot.NamespaceSnapshot namespace);
    }
}

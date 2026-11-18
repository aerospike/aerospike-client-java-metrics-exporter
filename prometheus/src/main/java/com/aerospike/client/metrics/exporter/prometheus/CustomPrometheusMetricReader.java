/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;

/**
 * Stable OpenTelemetry-to-client_java bridge.
 *
 * <p>The reader is registered both with an {@code SdkMeterProvider} and with
 * its Prometheus registry. Every registry scrape performs a fresh
 * {@link CollectionRegistration#collectAllMetrics()} call. Scrapes are
 * serialized so one response always represents one complete collection.</p>
 */
public final class CustomPrometheusMetricReader
        implements MetricReader, MultiCollector {
    private enum State {
        OPEN,
        SHUTTING_DOWN,
        CLOSED
    }

    private final PrometheusConfig config;
    private final PrometheusRegistry registry;
    private final OtelMetricDataConverter converter;
    private final AtomicReference<CollectionRegistration> registration =
        new AtomicReference<CollectionRegistration>();
    private final AtomicReference<State> state =
        new AtomicReference<State>(State.OPEN);
    private final CompletableResultCode shutdownResult =
        new CompletableResultCode();
    private final Object scrapeLock = new Object();
    private final Object lifecycleLock = new Object();

    private volatile PrometheusHttpServer httpServer;

    private CustomPrometheusMetricReader(PrometheusConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        registry = config.getRegistry() == null
            ? new PrometheusRegistry()
            : config.getRegistry();
        converter = new OtelMetricDataConverter(config);
        registry.register(this);
    }

    public static CustomPrometheusMetricReader create() {
        return create(PrometheusConfig.defaults());
    }

    public static CustomPrometheusMetricReader create(
            PrometheusConfig config
    ) {
        return new CustomPrometheusMetricReader(config);
    }

    public PrometheusRegistry getRegistry() {
        return registry;
    }

    /**
     * Explicitly start an exporter-owned HTTP server.
     *
     * @throws IllegalStateException if already started, not registered with a
     *         provider, or already shut down
     * @throws IOException if the listener cannot be started
     */
    public void startHttpServer() throws IOException {
        synchronized (lifecycleLock) {
            ensureOpen();
            if (registration.get() == null) {
                throw new IllegalStateException(
                    "reader must be registered with SdkMeterProvider before starting HTTP server"
                );
            }
            if (httpServer != null) {
                throw new IllegalStateException(
                    "Prometheus HTTP server is already running"
                );
            }

            PrometheusHttpServer candidate =
                PrometheusHttpServer.start(config, registry);
            if (state.get() != State.OPEN) {
                candidate.close();
                throw new IllegalStateException(
                    "reader was shut down while starting HTTP server"
                );
            }
            httpServer = candidate;
        }
    }

    public boolean isHttpServerRunning() {
        return httpServer != null;
    }

    /**
     * Return the bound server port, including a selected ephemeral port.
     */
    public int getHttpServerPort() {
        PrometheusHttpServer server = httpServer;
        if (server == null) {
            throw new IllegalStateException(
                "Prometheus HTTP server is not running"
            );
        }
        return server.getPort();
    }

    @Override
    public AggregationTemporality getAggregationTemporality(
            InstrumentType instrumentType
    ) {
        return AggregationTemporality.CUMULATIVE;
    }

    @Override
    public void register(CollectionRegistration newRegistration) {
        if (newRegistration == null) {
            throw new IllegalArgumentException(
                "collection registration must not be null"
            );
        }
        synchronized (lifecycleLock) {
            ensureOpen();
            if (!registration.compareAndSet(null, newRegistration)) {
                throw new IllegalStateException(
                    "reader may only be registered with one SdkMeterProvider"
                );
            }
        }
    }

    @Override
    public MetricSnapshots collect() {
        if (state.get() != State.OPEN) {
            return MetricSnapshots.of();
        }
        synchronized (scrapeLock) {
            if (state.get() != State.OPEN) {
                return MetricSnapshots.of();
            }
            CollectionRegistration current = registration.get();
            if (current == null) {
                return MetricSnapshots.of();
            }
            return converter.convert(current.collectAllMetrics());
        }
    }

    @Override
    public CompletableResultCode forceFlush() {
        return state.get() == State.OPEN
            ? CompletableResultCode.ofSuccess()
            : CompletableResultCode.ofFailure();
    }

    @Override
    public CompletableResultCode shutdown() {
        if (!state.compareAndSet(State.OPEN, State.SHUTTING_DOWN)) {
            return shutdownResult;
        }

        try {
            synchronized (lifecycleLock) {
                PrometheusHttpServer server = httpServer;
                httpServer = null;
                if (server != null) {
                    server.close();
                }
                registry.unregister(this);
            }

            registration.set(null);
            state.set(State.CLOSED);
            shutdownResult.succeed();
        }
        catch (RuntimeException e) {
            state.set(State.CLOSED);
            shutdownResult.fail();
        }
        return shutdownResult;
    }

    private void ensureOpen() {
        if (state.get() != State.OPEN) {
            throw new IllegalStateException(
                "Prometheus metric reader is shut down"
            );
        }
    }
}

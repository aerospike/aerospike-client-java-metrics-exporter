/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.resources.Resource;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.HistogramSnapshot;
import io.prometheus.metrics.model.snapshots.InfoSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;

class TestCustomPrometheusMetricReader {
    @Test
    void requestsCumulativeTemporalityForEveryInstrument() {
        CustomPrometheusMetricReader reader =
            CustomPrometheusMetricReader.create();
        try {
            for (InstrumentType type : InstrumentType.values()) {
                assertEquals(
                    AggregationTemporality.CUMULATIVE,
                    reader.getAggregationTemporality(type)
                );
            }
        }
        finally {
            reader.shutdown();
        }
    }

    @Test
    void convertsCountersGaugesHistogramsAndMetadata() {
        PrometheusConfig config = PrometheusConfig.builder()
            .setMetricPrefix("demo")
            .setResourceAttributeFilter(name -> name.equals("service.name"))
            .build();
        CustomPrometheusMetricReader reader =
            CustomPrometheusMetricReader.create(config);
        Resource resource = Resource.create(Attributes.of(
            stringKey("service.name"),
            "orders"
        ));
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(reader)
            .build();
        try {
            Meter meter = provider.meterBuilder("reader-test")
                .setInstrumentationVersion("1.2.3")
                .setSchemaUrl("https://example.test/schema")
                .build();
            Attributes attributes = Attributes.of(
                stringKey("http.route"),
                "/orders"
            );

            LongCounter counter = meter.counterBuilder("http.requests")
                .setDescription("HTTP requests")
                .build();
            counter.add(7, attributes);

            LongUpDownCounter connections = meter
                .upDownCounterBuilder("connections")
                .build();
            connections.add(-2, attributes);

            meter.gaugeBuilder("temperature")
                .setUnit("Cel")
                .buildWithCallback(measurement ->
                    measurement.record(21.5, attributes)
                );

            meter.histogramBuilder("latency")
                .setUnit("ms")
                .build()
                .record(12.5, attributes);

            MetricSnapshots snapshots = reader.getRegistry().scrape();
            CounterSnapshot counterSnapshot = assertInstanceOf(
                CounterSnapshot.class,
                find(snapshots, "demo_http_requests")
            );
            assertEquals(
                7.0,
                counterSnapshot.getDataPoints().get(0).getValue()
            );
            assertEquals(
                "/orders",
                counterSnapshot.getDataPoints().get(0)
                    .getLabels()
                    .get("http_route")
            );
            assertEquals(
                "orders",
                counterSnapshot.getDataPoints().get(0)
                    .getLabels()
                    .get("service_name")
            );
            assertEquals(
                "reader-test",
                counterSnapshot.getDataPoints().get(0)
                    .getLabels()
                    .get("otel_scope_name")
            );

            GaugeSnapshot upDownSnapshot = assertInstanceOf(
                GaugeSnapshot.class,
                find(snapshots, "demo_connections")
            );
            assertEquals(
                -2.0,
                upDownSnapshot.getDataPoints().get(0).getValue()
            );
            assertInstanceOf(
                GaugeSnapshot.class,
                find(snapshots, "demo_temperature_celsius")
            );
            HistogramSnapshot histogram = assertInstanceOf(
                HistogramSnapshot.class,
                find(snapshots, "demo_latency_milliseconds")
            );
            assertEquals(
                1,
                histogram.getDataPoints().get(0).getCount()
            );
            assertEquals(
                12.5,
                histogram.getDataPoints().get(0).getSum()
            );
            assertTrue(
                histogram.getDataPoints().get(0)
                    .getClassicBuckets()
                    .size() > 1
            );
            assertInstanceOf(
                InfoSnapshot.class,
                find(snapshots, "target")
            );
        }
        finally {
            provider.close();
        }
    }

    @Test
    void convertsSynchronousGaugeWhenApiProvidesIt() throws Exception {
        Class<?> gaugeType;
        Method build;
        try {
            gaugeType = Class.forName(
                "io.opentelemetry.api.metrics.DoubleGauge"
            );
            build = Class
                .forName(
                    "io.opentelemetry.api.metrics.DoubleGaugeBuilder"
                )
                .getMethod("build");
        }
        catch (ClassNotFoundException | NoSuchMethodException unavailable) {
            assumeTrue(
                false,
                "Synchronous gauges are unavailable in this tested OTel version"
            );
            return;
        }

        CustomPrometheusMetricReader reader =
            CustomPrometheusMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        try {
            Object gauge = build.invoke(
                provider.get("synchronous-gauge")
                    .gaugeBuilder("sync.gauge")
            );
            gaugeType
                .getMethod("set", double.class)
                .invoke(gauge, 19.5);

            GaugeSnapshot snapshot = assertInstanceOf(
                GaugeSnapshot.class,
                find(reader.getRegistry().scrape(), "sync_gauge")
            );
            assertEquals(
                19.5,
                snapshot.getDataPoints().get(0).getValue()
            );
        }
        finally {
            provider.close();
        }
    }

    @Test
    void supportsObservableCountersAndEmptyCallbacks() {
        CustomPrometheusMetricReader reader =
            CustomPrometheusMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        try {
            Meter meter = provider.get("observable-test");
            meter.counterBuilder("observable.work")
                .buildWithCallback(measurement -> measurement.record(11));
            meter.gaugeBuilder("empty.gauge")
                .buildWithCallback(measurement -> {
                    // An empty callback intentionally records no point.
                });

            MetricSnapshots snapshots = reader.getRegistry().scrape();
            assertInstanceOf(
                CounterSnapshot.class,
                find(snapshots, "observable_work")
            );
            assertFalse(hasMetric(snapshots, "empty_gauge"));
        }
        finally {
            provider.close();
        }
    }

    @Test
    void rejectsLabelsThatCollideAfterSanitization() {
        CustomPrometheusMetricReader reader =
            CustomPrometheusMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        try {
            provider.get("collision-test")
                .counterBuilder("colliding.labels")
                .build()
                .add(1, Attributes.builder()
                    .put("label.dot", "first")
                    .put("label-dot", "second")
                    .build());

            IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> reader.getRegistry().scrape()
            );
            assertTrue(failure.getMessage().contains(
                "both normalize to Prometheus label label_dot"
            ));
        }
        finally {
            provider.close();
        }
    }

    @Test
    void supportsPrivateAndCallerOwnedRegistriesAndOptions() {
        CustomPrometheusMetricReader privateReader =
            CustomPrometheusMetricReader.create();
        PrometheusRegistry supplied = new PrometheusRegistry();
        CustomPrometheusMetricReader suppliedReader =
            CustomPrometheusMetricReader.create(
                PrometheusConfig.builder()
                    .setRegistry(supplied)
                    .setTargetInfoEnabled(false)
                    .setScopeInformationEnabled(false)
                    .build()
            );
        try {
            assertNotSame(
                PrometheusRegistry.defaultRegistry,
                privateReader.getRegistry()
            );
            assertSame(supplied, suppliedReader.getRegistry());
        }
        finally {
            privateReader.shutdown();
            suppliedReader.shutdown();
        }
        assertEquals(0, supplied.scrape().size());
    }

    @Test
    void keepsProvidersAndReadersIsolated() {
        CustomPrometheusMetricReader first =
            CustomPrometheusMetricReader.create();
        CustomPrometheusMetricReader second =
            CustomPrometheusMetricReader.create();
        SdkMeterProvider firstProvider = SdkMeterProvider.builder()
            .registerMetricReader(first)
            .build();
        SdkMeterProvider secondProvider = SdkMeterProvider.builder()
            .registerMetricReader(second)
            .build();
        try {
            firstProvider.get("first")
                .counterBuilder("isolated")
                .build()
                .add(1);
            secondProvider.get("second")
                .counterBuilder("isolated")
                .build()
                .add(2);

            CounterSnapshot firstSnapshot = assertInstanceOf(
                CounterSnapshot.class,
                find(first.getRegistry().scrape(), "isolated")
            );
            CounterSnapshot secondSnapshot = assertInstanceOf(
                CounterSnapshot.class,
                find(second.getRegistry().scrape(), "isolated")
            );
            assertEquals(
                1.0,
                firstSnapshot.getDataPoints().get(0).getValue()
            );
            assertEquals(
                2.0,
                secondSnapshot.getDataPoints().get(0).getValue()
            );
        }
        finally {
            firstProvider.close();
            secondProvider.close();
        }
    }

    @Test
    void registrationAndShutdownAreOneTimeAndIdempotent() {
        CustomPrometheusMetricReader reader =
            CustomPrometheusMetricReader.create();
        CollectionRegistration registration =
            CollectionRegistration.noop();

        reader.register(registration);
        assertThrows(
            IllegalStateException.class,
            () -> reader.register(CollectionRegistration.noop())
        );
        assertTrue(reader.shutdown().join(5, TimeUnit.SECONDS).isSuccess());
        assertTrue(reader.shutdown().join(5, TimeUnit.SECONDS).isSuccess());
        assertEquals(0, reader.getRegistry().scrape().size());
    }

    @Test
    void serializesScrapesAndDoesNotBlockShutdownOnActiveScrape()
            throws Exception {
        CustomPrometheusMetricReader reader =
            CustomPrometheusMetricReader.create();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        reader.register(new CollectionRegistration() {
            @Override
            public Collection<MetricData> collectAllMetrics() {
                int now = active.incrementAndGet();
                maximumActive.accumulateAndGet(now, Math::max);
                entered.countDown();
                await(release);
                active.decrementAndGet();
                return Collections.emptyList();
            }
        });

        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> first =
                executor.submit(() -> reader.getRegistry().scrape());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<?> second =
                executor.submit(() -> reader.collect());
            Future<?> shutdown =
                executor.submit(() -> reader.shutdown().join(
                    5,
                    TimeUnit.SECONDS
                ));

            shutdown.get(5, TimeUnit.SECONDS);
            assertFalse(first.isDone());
            release.countDown();

            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(1, maximumActive.get());
        }
        finally {
            release.countDown();
            executor.shutdownNow();
            reader.shutdown();
        }
    }

    private static MetricSnapshot find(
            MetricSnapshots snapshots,
            String name
    ) {
        return snapshots.stream()
            .filter(snapshot ->
                name.equals(snapshot.getMetadata().getName()))
            .findFirst()
            .orElseThrow(() ->
                new AssertionError("metric not found: " + name)
            );
    }

    private static boolean hasMetric(
            MetricSnapshots snapshots,
            String name
    ) {
        return snapshots.stream().anyMatch(snapshot ->
            name.equals(snapshot.getMetadata().getName())
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}

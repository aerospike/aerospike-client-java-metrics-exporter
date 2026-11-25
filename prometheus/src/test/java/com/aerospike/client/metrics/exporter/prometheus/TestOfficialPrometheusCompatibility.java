/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.exporter.prometheus.PrometheusMetricReader;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.resources.Resource;
import io.prometheus.metrics.config.EscapingScheme;
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

/**
 * Test-only comparison against the pinned official alpha exporter.
 */
class TestOfficialPrometheusCompatibility {
    @Test
    void matchesOfficialOutputWithDefaultOptions() throws Exception {
        compare(false, true, true);
    }

    @Test
    void matchesOfficialOutputWhenTargetAndScopeAreDisabled()
            throws Exception {
        compare(true, false, false);
    }

    private static void compare(
            boolean filterResource,
            boolean targetInfo,
            boolean scopeInformation
    ) throws Exception {
        assumeTrue(
            officialAlphaMatchesRuntime(),
            "Pinned official alpha comparison runs only with current OTel"
        );
        PrometheusRegistry customRegistry = new PrometheusRegistry();
        CustomPrometheusMetricReader custom =
            CustomPrometheusMetricReader.create(
                PrometheusConfig.builder()
                    .setRegistry(customRegistry)
                    .setTargetInfoEnabled(targetInfo)
                    .setScopeInformationEnabled(scopeInformation)
                    .setResourceAttributeFilter(
                        filterResource
                            ? name -> name.equals("service.name")
                            : null
                    )
                    .build()
            );

        PrometheusRegistry officialRegistry = new PrometheusRegistry();
        PrometheusMetricReader official = PrometheusMetricReader.builder()
            .setTargetInfoMetricEnabled(targetInfo)
            .setOtelScopeLabelsEnabled(scopeInformation)
            .setAllowedResourceAttributesFilter(
                filterResource
                    ? name -> name.equals("service.name")
                    : null
            )
            .build();
        officialRegistry.register(official);

        Resource resource = Resource.create(Attributes.builder()
            .put("service.name", "orders")
            .put("service.version", "2")
            .build());
        SdkMeterProvider customProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(custom)
            .build();
        SdkMeterProvider officialProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(official)
            .build();
        try {
            recordMetrics(customProvider);
            recordMetrics(officialProvider);

            assertEquals(
                normalizedScrape(officialRegistry),
                normalizedScrape(customRegistry)
            );
        }
        finally {
            customProvider.close();
            officialProvider.close();
            officialRegistry.unregister(official);
        }
    }

    private static boolean officialAlphaMatchesRuntime() {
        try {
            Class.forName(
                "io.opentelemetry.sdk.common.internal.ThrottlingLogger"
            );
            return true;
        }
        catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void recordMetrics(SdkMeterProvider provider) {
        Meter meter = provider.meterBuilder("compatibility-scope")
            .setInstrumentationVersion("1.0.0")
            .setSchemaUrl("https://example.test/schema")
            .build();
        Attributes labels = Attributes.builder()
            .put(stringKey("http.route"), "/orders")
            .put(stringKey("label-with-dash"), "value")
            .build();

        meter.counterBuilder("request.count")
            .setDescription("Requests")
            .build()
            .add(12, labels);
        meter.counterBuilder("payload.size")
            .ofDoubles()
            .setUnit("By")
            .build()
            .add(4.5, labels);
        meter.upDownCounterBuilder("queue.depth")
            .build()
            .add(-3, labels);
        meter.gaugeBuilder("temperature")
            .setUnit("Cel")
            .buildWithCallback(measurement ->
                measurement.record(22.5, labels)
            );
        meter.histogramBuilder("request.duration")
            .setUnit("ms")
            .build()
            .record(17.0, labels);
    }

    private static List<String> normalizedScrape(
            PrometheusRegistry registry
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrometheusTextFormatWriter.create().write(
            output,
            registry.scrape(),
            EscapingScheme.UNDERSCORE_ESCAPING
        );
        return Arrays.stream(
                output.toString(StandardCharsets.UTF_8.name()).split("\\R")
            )
            .filter(line -> !line.trim().isEmpty())
            .sorted()
            .collect(Collectors.toList());
    }
}

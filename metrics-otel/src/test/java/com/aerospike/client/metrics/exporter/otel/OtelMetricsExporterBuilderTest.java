/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import com.aerospike.client.metrics.exporter.common.config.ConfigValues;

import io.opentelemetry.sdk.OpenTelemetrySdk;

class OtelMetricsExporterBuilderTest {
    @Test
    void defaultsToUncompressedCumulativeMetrics() {
        OtelExporterConfig config = OtelMetricsExporter.builder().resolveConfig();

        assertEquals(OtelExporterConfig.Compression.NONE, config.compression());
        assertEquals(OtelExporterConfig.Temporality.CUMULATIVE, config.temporality());
    }

    @Test
    void explicitValuesResolveAllTransportOptions() {
        OtelExporterConfig config = OtelMetricsExporter.builder()
            .serviceName("orders")
            .endpoint("https://collector.example:4318/v1/metrics")
            .protocol(OtelExporterConfig.Protocol.HTTP_PROTOBUF)
            .header("authorization", "token")
            .timeout(Duration.ofSeconds(3))
            .exportInterval(Duration.ofSeconds(5))
            .compression(OtelExporterConfig.Compression.GZIP)
            .temporality(OtelExporterConfig.Temporality.DELTA)
            .proxy("http://proxy.example:8080")
            .snapshotStaleTimeout("20s")
            .resolveConfig();

        assertEquals("orders", config.serviceName());
        assertEquals(URI.create("https://collector.example:4318/v1/metrics"), config.endpoint());
        assertEquals(OtelExporterConfig.Protocol.HTTP_PROTOBUF, config.protocol());
        assertEquals("token", config.headers().get("authorization"));
        assertEquals(Duration.ofSeconds(3), config.timeout());
        assertEquals(Duration.ofSeconds(5), config.exportInterval());
        assertEquals(OtelExporterConfig.Compression.GZIP, config.compression());
        assertEquals(OtelExporterConfig.Temporality.DELTA, config.temporality());
        assertEquals(URI.create("http://proxy.example:8080"), config.proxyUrl());
        assertEquals(Duration.ofSeconds(20), config.freshness().createPolicy().staleTimeout());
    }

    @Test
    void metricSpecificEnvironmentAndSystemValuesWin() {
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("OTEL_EXPORTER_OTLP_ENDPOINT", "http://generic:4317");
        environment.put("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT", "http://metrics:4318/v1/metrics");
        environment.put("OTEL_EXPORTER_OTLP_HEADERS", "generic=one");
        environment.put("OTEL_EXPORTER_OTLP_METRICS_HEADERS", "specific=two");
        environment.put("OTEL_EXPORTER_OTLP_COMPRESSION", "none");
        environment.put("OTEL_EXPORTER_OTLP_METRICS_COMPRESSION", "gzip");
        environment.put("OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE", "delta");
        Map<String, String> mapped = OtelMetricsExporterBuilder.environment(environment);
        assertEquals(
            "http://metrics:4318/v1/metrics",
            mapped.get(OtelMetricsExporterBuilder.OTEL + "endpoint")
        );
        assertEquals("one", mapped.get(OtelMetricsExporterBuilder.OTEL + "headers.generic"));
        assertEquals("two", mapped.get(OtelMetricsExporterBuilder.OTEL + "headers.specific"));
        assertEquals("gzip", mapped.get(OtelMetricsExporterBuilder.OTEL + "compression"));
        assertEquals("delta", mapped.get(OtelMetricsExporterBuilder.OTEL + "temporality"));

        Properties properties = new Properties();
        properties.setProperty("otel.exporter.otlp.protocol", "grpc");
        properties.setProperty("otel.exporter.otlp.metrics.protocol", "http/protobuf");
        properties.setProperty("otel.exporter.otlp.compression", "gzip");
        properties.setProperty("otel.exporter.otlp.metrics.compression", "none");
        properties.setProperty(
            "otel.exporter.otlp.metrics.temporality.preference",
            "cumulative"
        );
        Map<String, String> propertyValues =
            OtelMetricsExporterBuilder.systemProperties(properties);
        assertEquals(
            "http/protobuf",
            propertyValues.get(OtelMetricsExporterBuilder.OTEL + "protocol")
        );
        assertEquals("none", propertyValues.get(OtelMetricsExporterBuilder.OTEL + "compression"));
        assertEquals(
            "cumulative",
            propertyValues.get(OtelMetricsExporterBuilder.OTEL + "temporality")
        );
    }

    @Test
    void resolvesGenericAndMetricsSpecificOtlpEndpoints() {
        Map<String, String> genericHttp = OtelMetricsExporterBuilder.environment(
            Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4318")
        );
        assertEquals(
            URI.create("http://collector:4318/v1/metrics"),
            OtelMetricsExporterBuilder.resolveEndpoint(
                new ConfigValues(genericHttp),
                OtelExporterConfig.Protocol.HTTP_PROTOBUF
            )
        );

        Map<String, String> genericHttpWithPath = OtelMetricsExporterBuilder.environment(
            Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4318/otel")
        );
        assertEquals(
            URI.create("http://collector:4318/otel/v1/metrics"),
            OtelMetricsExporterBuilder.resolveEndpoint(
                new ConfigValues(genericHttpWithPath),
                OtelExporterConfig.Protocol.HTTP_PROTOBUF
            )
        );

        Map<String, String> metricsSpecific = OtelMetricsExporterBuilder.environment(Map.of(
            "OTEL_EXPORTER_OTLP_ENDPOINT", "http://generic:4318",
            "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT", "http://metrics:4318/custom/metrics"
        ));
        assertEquals(
            URI.create("http://metrics:4318/custom/metrics"),
            OtelMetricsExporterBuilder.resolveEndpoint(
                new ConfigValues(metricsSpecific),
                OtelExporterConfig.Protocol.HTTP_PROTOBUF
            )
        );

        Properties genericGrpcProperties = new Properties();
        genericGrpcProperties.setProperty(
            "otel.exporter.otlp.endpoint",
            "http://collector:4317"
        );
        assertEquals(
            URI.create("http://collector:4317"),
            OtelMetricsExporterBuilder.resolveEndpoint(
                new ConfigValues(
                    OtelMetricsExporterBuilder.systemProperties(genericGrpcProperties)
                ),
                OtelExporterConfig.Protocol.GRPC
            )
        );
    }

    @Test
    void rejectsInvalidBuilderAndTransportValues() {
        assertThrows(
            IllegalArgumentException.class,
            () -> OtelMetricsExporter.builder().endpoint("not-a-uri").resolveConfig()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> OtelMetricsExporter.builder().timeout(Duration.ZERO)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> OtelMetricsExporter.builder().header(" ", "value")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> OtelMetricsExporterBuilder.environment(
                java.util.Collections.singletonMap("OTEL_EXPORTER_OTLP_HEADERS", "invalid")
            )
        );
    }

    @Test
    void rejectsInvalidFreshnessBeforeSnapshotsArrive() {
        assertThrows(
            IllegalArgumentException.class,
            () -> OtelMetricsExporter.builder()
                .snapshotStaleTimeout("0s")
                .resolveConfig()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> OtelMetricsExporter.builder()
                .openTelemetry(OpenTelemetrySdk.builder().build())
                .snapshotStaleTimeout("invalid")
                .build()
        );
    }

    @Test
    void applicationOwnedOpenTelemetryIgnoresTransportConfiguration() {
        String property = "otel.exporter.otlp.headers";
        String previous = System.getProperty(property);
        System.setProperty(property, "invalid-header");
        try {
            OtelMetricsExporter exporter = OtelMetricsExporter.builder()
                .openTelemetry(OpenTelemetrySdk.builder().build())
                .endpoint("not-a-uri")
                .proxy("https://proxy.example")
                .clientCertificate(Paths.get("missing-client.pem"))
                .build();
            exporter.close();
        }
        finally {
            if (previous == null) {
                System.clearProperty(property);
            }
            else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void usesSchemeDefaultWhenProxyPortIsOmitted() {
        InetSocketAddress http = OtelMetricsExporterBuilder.proxyAddress(
            URI.create("http://proxy.example")
        );
        InetSocketAddress https = OtelMetricsExporterBuilder.proxyAddress(
            URI.create("https://proxy.example")
        );
        InetSocketAddress explicit = OtelMetricsExporterBuilder.proxyAddress(
            URI.create("https://proxy.example:8443")
        );

        assertEquals(80, http.getPort());
        assertEquals(443, https.getPort());
        assertEquals(8443, explicit.getPort());
    }
}

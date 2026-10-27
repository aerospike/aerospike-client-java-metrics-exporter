/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.examples.metrics;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Host;
import com.aerospike.client.Key;
import com.aerospike.client.metrics.MetricsExporterType;
import com.aerospike.client.metrics.MetricsPolicy;
import com.aerospike.client.metrics.exporter.otel.OpenTelemetryMetricsExporter;
import com.aerospike.client.policy.AuthMode;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.TlsPolicy;

import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;

/**
 * Generates a small workload using an application-owned OpenTelemetry pipeline.
 */
public final class MetricsExporterExample {
    private static final Duration DEFAULT_DURATION = Duration.ofSeconds(60);
    private static final Duration DEFAULT_EXPORT_INTERVAL = Duration.ofSeconds(5);

    private MetricsExporterExample() {
    }

    public static void main(String[] args) throws InterruptedException {
        Duration duration = parseDuration(args);
        String host = environment("AEROSPIKE_HOST", "localhost");
        int port = Integer.parseInt(environment("AEROSPIKE_PORT", "3000"));
        String namespace = environment("AEROSPIKE_NAMESPACE", "test");
        String endpoint = environment(
            "OTEL_EXPORTER_OTLP_ENDPOINT",
            "http://localhost:4317"
        );

        OtlpGrpcMetricExporter transport = OtlpGrpcMetricExporter.builder()
            .setEndpoint(endpoint)
            .build();
        PeriodicMetricReader reader = PeriodicMetricReader.builder(transport)
            .setInterval(DEFAULT_EXPORT_INTERVAL)
            .build();
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OpenTelemetryMetricsExporter aerospikeExporter =
            new OpenTelemetryMetricsExporter(meterProvider);

        AerospikeClient primaryClient = null;
        AerospikeClient reportingClient = null;
        try {
            Host[] hosts = Host.parseHosts(host, port);
            primaryClient = new AerospikeClient(
                clientPolicy("metrics-exporter-primary"),
                hosts
            );
            reportingClient = new AerospikeClient(
                clientPolicy("metrics-exporter-reporting"),
                hosts
            );

            primaryClient.enableMetrics(metricsPolicy("primary", aerospikeExporter));
            reportingClient.enableMetrics(metricsPolicy("reporting", aerospikeExporter));

            System.out.println("Two example clients connected to " + host + ":" + port);
            System.out.println("Sending OTLP metrics to " + endpoint);
            runWorkload(primaryClient, reportingClient, namespace, duration);
        }
        finally {
            disableAndClose(primaryClient);
            disableAndClose(reportingClient);
            // The adapter unregisters only its callbacks and waits for final attempts.
            aerospikeExporter.close();
            // The application owns provider, reader, transport, and OTLP teardown.
            meterProvider.close();
        }
    }

    private static MetricsPolicy metricsPolicy(
            String clientId,
            OpenTelemetryMetricsExporter exporter
    ) {
        MetricsPolicy policy = new MetricsPolicy();
        policy.exporter = MetricsExporterType.CUSTOM;
        policy.exportInterval = Duration.ofSeconds(1);
        policy.enableOperationalMetrics = true;
        policy.enableUsageMetrics = true;
        policy.labels = labels(clientId);
        policy.addExporter(exporter);
        return policy;
    }

    private static Map<String, String> labels(String clientId) {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("client_id", clientId);
        labels.put("environment", "example");
        return labels;
    }

    private static void runWorkload(
            AerospikeClient primaryClient,
            AerospikeClient reportingClient,
            String namespace,
            Duration duration
    ) throws InterruptedException {
        long deadline = System.nanoTime() + duration.toNanos();
        int sequence = 0;
        while (System.nanoTime() < deadline) {
            Key key = new Key(namespace, "metrics_example", sequence % 100);
            primaryClient.put(
                null,
                key,
                new Bin("sequence", sequence),
                new Bin("source", "metrics-exporter-example")
            );
            primaryClient.get(null, key);
            reportingClient.get(null, key);
            sequence++;
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private static ClientPolicy clientPolicy(String appId) {
        ClientPolicy policy = new ClientPolicy();
        policy.appId = appId;
        policy.user = environment("AEROSPIKE_USER", null);
        policy.password = environment("AEROSPIKE_PASSWORD", null);
        policy.authMode = AuthMode.valueOf(
            environment("AEROSPIKE_AUTH_MODE", AuthMode.INTERNAL.name())
                .toUpperCase(Locale.ROOT)
        );
        if (Boolean.parseBoolean(environment("AEROSPIKE_TLS_ENABLED", "false"))) {
            policy.tlsPolicy = new TlsPolicy();
        }
        return policy;
    }

    private static void disableAndClose(AerospikeClient client) {
        if (client == null) {
            return;
        }
        try {
            client.disableMetrics();
        }
        finally {
            client.close();
        }
    }

    private static Duration parseDuration(String[] args) {
        if (args.length == 0) {
            return DEFAULT_DURATION;
        }
        if (args.length != 1 || !args[0].startsWith("--duration=")) {
            throw new IllegalArgumentException(
                "Expected no arguments or --duration=<positive-seconds>"
            );
        }
        long seconds = Long.parseLong(args[0].substring("--duration=".length()));
        if (seconds <= 0) {
            throw new IllegalArgumentException("duration seconds must be positive");
        }
        return Duration.ofSeconds(seconds);
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }
}

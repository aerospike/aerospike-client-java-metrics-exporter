/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.examples.metrics;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Host;
import com.aerospike.client.Key;
import com.aerospike.client.metrics.MetricsPolicy;
import com.aerospike.client.metrics.exporter.otel.OtelMetricsExporter;
import com.aerospike.client.metrics.exporter.prometheus.PrometheusMetricsExporter;
import com.aerospike.client.policy.ClientPolicy;

/**
 * Generates a small Aerospike workload and sends the same client snapshots
 * through the OTel and direct Prometheus exporters.
 */
public final class ExporterE2eExample {
    private static final String DEFAULT_CONFIG =
        "examples/e2e/aerospike-metrics-exporter.yml";
    private static final Duration DEFAULT_DURATION = Duration.ofSeconds(60);

    private ExporterE2eExample() {
    }

    public static void main(String[] args) throws InterruptedException {
        Path configPath = Paths.get(args.length > 0 ? args[0] : DEFAULT_CONFIG);
        Duration duration = args.length > 1
            ? Duration.ofSeconds(parsePositiveSeconds(args[1]))
            : DEFAULT_DURATION;
        String host = environment("AEROSPIKE_HOST", "localhost");
        int port = Integer.parseInt(environment("AEROSPIKE_PORT", "3000"));
        String namespace = environment("AEROSPIKE_NAMESPACE", "test");

        OtelMetricsExporter otelExporter = OtelMetricsExporter.builder()
            .fromYaml(configPath)
            .build();
        PrometheusMetricsExporter prometheusExporter = null;
        AerospikeClient primaryClient = null;
        AerospikeClient reportingClient = null;
        boolean primaryMetricsEnabled = false;
        boolean reportingMetricsEnabled = false;

        try {
            prometheusExporter = PrometheusMetricsExporter.builder()
                .fromYaml(configPath)
                .build();

            ClientPolicy clientPolicy = new ClientPolicy();
            clientPolicy.appId = "metrics-exporter-e2e";
            primaryClient = new AerospikeClient(clientPolicy, new Host(host, port));
            reportingClient = new AerospikeClient(clientPolicy, new Host(host, port));

            primaryClient.enableMetrics(
                metricsPolicy("primary", otelExporter, prometheusExporter)
            );
            primaryMetricsEnabled = true;
            reportingClient.enableMetrics(
                metricsPolicy("reporting", otelExporter, prometheusExporter)
            );
            reportingMetricsEnabled = true;

            System.out.println("Two E2E clients connected to " + host + ":" + port);
            System.out.println("Direct Prometheus metrics: http://localhost:"
                + prometheusExporter.port() + "/metrics");
            System.out.println("Running workload for " + duration.getSeconds() + " seconds");
            runWorkload(primaryClient, reportingClient, namespace, duration);
            System.out.println("E2E workload completed");
        }
        finally {
            if (primaryClient != null) {
                if (primaryMetricsEnabled) {
                    primaryClient.disableMetrics();
                }
                primaryClient.close();
            }
            if (reportingClient != null) {
                if (reportingMetricsEnabled) {
                    reportingClient.disableMetrics();
                }
                reportingClient.close();
            }
            if (prometheusExporter != null) {
                prometheusExporter.close();
            }
            otelExporter.close();
        }
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
            int keyId = sequence % 100;
            Key key = new Key(namespace, "metrics_e2e", keyId);
            primaryClient.put(
                null,
                key,
                new Bin("sequence", sequence),
                new Bin("source", "metrics-exporter-e2e")
            );
            primaryClient.get(null, key);
            reportingClient.get(null, key);
            sequence++;
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private static MetricsPolicy metricsPolicy(
            String clientId,
            OtelMetricsExporter otelExporter,
            PrometheusMetricsExporter prometheusExporter
    ) {
        MetricsPolicy policy = new MetricsPolicy();
        policy.interval = 1;
        policy.enableExtendedMetrics = true;
        policy.labels = labels(clientId);
        policy.addExporter(otelExporter);
        policy.addExporter(prometheusExporter);
        return policy;
    }

    private static Map<String, String> labels(String clientId) {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("client_id", clientId);
        labels.put("environment", "e2e");
        labels.put("export_test", "dual-backend");
        return labels;
    }

    private static long parsePositiveSeconds(String raw) {
        long value = Long.parseLong(raw);
        if (value <= 0L) {
            throw new IllegalArgumentException("duration seconds must be positive");
        }
        return value;
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? defaultValue : value;
    }
}

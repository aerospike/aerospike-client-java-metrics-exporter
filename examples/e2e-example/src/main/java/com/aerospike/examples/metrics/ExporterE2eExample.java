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
        AerospikeClient client = null;
        boolean metricsEnabled = false;

        try {
            prometheusExporter = PrometheusMetricsExporter.builder()
                .fromYaml(configPath)
                .build();

            ClientPolicy clientPolicy = new ClientPolicy();
            clientPolicy.appId = "metrics-exporter-e2e";
            client = new AerospikeClient(clientPolicy, new Host(host, port));

            MetricsPolicy metricsPolicy = new MetricsPolicy();
            metricsPolicy.interval = 1;
            metricsPolicy.enableExtendedMetrics = true;
            metricsPolicy.labels = labels();
            metricsPolicy.addExporter(otelExporter);
            metricsPolicy.addExporter(prometheusExporter);
            client.enableMetrics(metricsPolicy);
            metricsEnabled = true;

            System.out.println("E2E workload connected to " + host + ":" + port);
            System.out.println("Direct Prometheus metrics: http://localhost:"
                + prometheusExporter.port() + "/metrics");
            System.out.println("Running workload for " + duration.getSeconds() + " seconds");
            runWorkload(client, namespace, duration);
            System.out.println("E2E workload completed");
        }
        finally {
            if (client != null) {
                if (metricsEnabled) {
                    client.disableMetrics();
                }
                client.close();
            }
            if (prometheusExporter != null) {
                prometheusExporter.close();
            }
            otelExporter.close();
        }
    }

    private static void runWorkload(
            AerospikeClient client,
            String namespace,
            Duration duration
    ) throws InterruptedException {
        long deadline = System.nanoTime() + duration.toNanos();
        int sequence = 0;

        while (System.nanoTime() < deadline) {
            int keyId = sequence % 100;
            Key key = new Key(namespace, "metrics_e2e", keyId);
            client.put(
                null,
                key,
                new Bin("sequence", sequence),
                new Bin("source", "metrics-exporter-e2e")
            );
            client.get(null, key);
            sequence++;
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
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

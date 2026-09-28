/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.examples.metrics;

import java.nio.file.Paths;
import java.util.Collections;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Host;
import com.aerospike.client.metrics.MetricsPolicy;
import com.aerospike.client.metrics.exporter.prometheus.PrometheusMetricsExporter;
import com.aerospike.client.policy.ClientPolicy;

public final class PrometheusExporterExample {
    private PrometheusExporterExample() {
    }

    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] : "aerospike-metrics-exporter.yml";
        PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
            .fromYaml(Paths.get(configPath))
            .build();

        MetricsPolicy metricsPolicy = new MetricsPolicy();
        metricsPolicy.labels = Collections.singletonMap("client_id", "primary");
        metricsPolicy.addExporter(exporter);

        AerospikeClient client = new AerospikeClient(
            new ClientPolicy(),
            new Host("localhost", 3000)
        );
        try {
            client.enableMetrics(metricsPolicy);
            // Run application operations here.
        }
        finally {
            client.disableMetrics();
            client.close();
            exporter.close();
        }
    }
}

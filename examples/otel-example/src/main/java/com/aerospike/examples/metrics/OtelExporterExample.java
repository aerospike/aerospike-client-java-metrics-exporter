/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.examples.metrics;

import java.nio.file.Paths;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Host;
import com.aerospike.client.metrics.MetricsPolicy;
import com.aerospike.client.metrics.exporter.otel.OtelMetricsExporter;
import com.aerospike.client.policy.ClientPolicy;

public final class OtelExporterExample {
    private OtelExporterExample() {
    }

    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] : "aerospike-metrics-exporter.yml";
        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .fromYaml(Paths.get(configPath))
            .build();

        MetricsPolicy metricsPolicy = new MetricsPolicy();
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

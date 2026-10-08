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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Host;
import com.aerospike.client.Key;
import com.aerospike.client.metrics.MetricsPolicy;
import com.aerospike.client.metrics.exporter.otel.OtelMetricsExporter;
import com.aerospike.client.metrics.exporter.otel.OtelMetricsExporterBuilder;
import com.aerospike.client.metrics.exporter.prometheus.PrometheusMetricsExporter;
import com.aerospike.client.metrics.exporter.prometheus.PrometheusMetricsExporterBuilder;
import com.aerospike.client.policy.AuthMode;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.TlsPolicy;

/**
 * Generates a small Aerospike workload and exports client metrics through
 * OpenTelemetry, Prometheus or both backends.
 */
public final class MetricsExporterExample {
    private static final Duration DEFAULT_DURATION = Duration.ofSeconds(60);

    private MetricsExporterExample() {
    }

    public static void main(String[] args) throws InterruptedException {
        Options options = Options.parse(args);
        String host = environment("AEROSPIKE_HOST", "localhost");
        int port = Integer.parseInt(environment("AEROSPIKE_PORT", "3000"));
        String namespace = environment("AEROSPIKE_NAMESPACE", "test");

        OtelMetricsExporter otelExporter = null;
        PrometheusMetricsExporter prometheusExporter = null;
        AerospikeClient primaryClient = null;
        AerospikeClient reportingClient = null;
        boolean primaryMetricsEnabled = false;
        boolean reportingMetricsEnabled = false;

        try {
            if (options.exporterMode.includesOtel()) {
                OtelMetricsExporterBuilder builder = OtelMetricsExporter.builder();
                if (options.configPath != null) {
                    builder.fromYaml(options.configPath);
                }
                otelExporter = builder.build();
            }
            if (options.exporterMode.includesPrometheus()) {
                PrometheusMetricsExporterBuilder builder =
                    PrometheusMetricsExporter.builder();
                if (options.configPath != null) {
                    builder.fromYaml(options.configPath);
                }
                prometheusExporter = builder.build();
            }

            Host[] hosts = Host.parseHosts(host, port);
            primaryClient = new AerospikeClient(clientPolicy("metrics-exporter-primary"), hosts);
            reportingClient = new AerospikeClient(
                clientPolicy("metrics-exporter-reporting"),
                hosts
            );

            primaryClient.enableMetrics(
                metricsPolicy("primary", otelExporter, prometheusExporter)
            );
            primaryMetricsEnabled = true;
            reportingClient.enableMetrics(
                metricsPolicy("reporting", otelExporter, prometheusExporter)
            );
            reportingMetricsEnabled = true;

            System.out.println("Two example clients connected to " + host + ":" + port);
            System.out.println("Exporter mode: " + options.exporterMode.optionValue);
            if (prometheusExporter != null) {
                System.out.println("Direct Prometheus metrics: http://localhost:"
                    + prometheusExporter.port() + "/metrics");
            }
            System.out.println(
                "Running workload for " + options.duration.getSeconds() + " seconds"
            );
            runWorkload(primaryClient, reportingClient, namespace, options.duration);
            System.out.println("Example workload completed");
        }
        finally {
            closeClient(primaryClient, primaryMetricsEnabled);
            closeClient(reportingClient, reportingMetricsEnabled);
            if (prometheusExporter != null) {
                prometheusExporter.close();
            }
            if (otelExporter != null) {
                otelExporter.close();
            }
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
            Key key = new Key(namespace, "metrics_example", keyId);
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

    private static MetricsPolicy metricsPolicy(
            String clientId,
            OtelMetricsExporter otelExporter,
            PrometheusMetricsExporter prometheusExporter
    ) {
        MetricsPolicy policy = new MetricsPolicy();
        policy.interval = 1;
        policy.enableExtendedMetrics = true;
        policy.labels = labels(clientId);
        if (otelExporter != null) {
            policy.addExporter(otelExporter);
        }
        if (prometheusExporter != null) {
            policy.addExporter(prometheusExporter);
        }
        return policy;
    }

    private static Map<String, String> labels(String clientId) {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("client_id", clientId);
        labels.put("environment", "example");
        return labels;
    }

    private static ClientPolicy clientPolicy(String appId) {
        ClientPolicy policy = new ClientPolicy();
        policy.appId = appId;
        policy.user = environment("AEROSPIKE_USER", null);
        policy.password = environment("AEROSPIKE_PASSWORD", null);
        policy.authMode = AuthMode.valueOf(
            environment("AEROSPIKE_AUTH_MODE", AuthMode.INTERNAL.name()).toUpperCase(Locale.ROOT)
        );
        if (Boolean.parseBoolean(environment("AEROSPIKE_TLS_ENABLED", "false"))) {
            policy.tlsPolicy = new TlsPolicy();
        }
        return policy;
    }

    private static void closeClient(AerospikeClient client, boolean metricsEnabled) {
        if (client == null) {
            return;
        }
        try {
            if (metricsEnabled) {
                client.disableMetrics();
            }
        }
        finally {
            client.close();
        }
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private enum ExporterMode {
        OTEL("otel"),
        PROMETHEUS("prometheus"),
        BOTH("both");

        private final String optionValue;

        ExporterMode(String optionValue) {
            this.optionValue = optionValue;
        }

        private boolean includesOtel() {
            return this == OTEL || this == BOTH;
        }

        private boolean includesPrometheus() {
            return this == PROMETHEUS || this == BOTH;
        }

        private static ExporterMode parse(String value) {
            for (ExporterMode mode : values()) {
                if (mode.optionValue.equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException(
                "Unsupported exporter '" + value + "'. Expected otel, prometheus or both."
            );
        }
    }

    private static final class Options {
        private final ExporterMode exporterMode;
        private final Path configPath;
        private final Duration duration;

        private Options(
                ExporterMode exporterMode,
                Path configPath,
                Duration duration
        ) {
            this.exporterMode = exporterMode;
            this.configPath = configPath;
            this.duration = duration;
        }

        private static Options parse(String[] args) {
            ExporterMode exporterMode = ExporterMode.BOTH;
            Path configPath = null;
            Duration duration = DEFAULT_DURATION;

            for (String argument : args) {
                if (argument.startsWith("--exporter=")) {
                    exporterMode = ExporterMode.parse(optionValue(argument));
                }
                else if (argument.startsWith("--config=")) {
                    configPath = Paths.get(optionValue(argument));
                }
                else if (argument.startsWith("--duration=")) {
                    duration = Duration.ofSeconds(parsePositiveSeconds(optionValue(argument)));
                }
                else {
                    throw new IllegalArgumentException(
                        "Unsupported argument '" + argument + "'. Expected "
                            + "--exporter=otel|prometheus|both, --config=<path> or "
                            + "--duration=<seconds>."
                    );
                }
            }
            return new Options(exporterMode, configPath, duration);
        }

        private static String optionValue(String argument) {
            String value = argument.substring(argument.indexOf('=') + 1).trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(
                    "Option value must not be empty: " + argument
                );
            }
            return value;
        }

        private static long parsePositiveSeconds(String value) {
            long seconds = Long.parseLong(value);
            if (seconds <= 0L) {
                throw new IllegalArgumentException("duration seconds must be positive");
            }
            return seconds;
        }
    }
}

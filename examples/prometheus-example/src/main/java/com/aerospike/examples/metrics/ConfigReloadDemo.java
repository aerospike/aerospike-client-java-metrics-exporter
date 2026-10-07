/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.examples.metrics;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Host;
import com.aerospike.client.Key;
import com.aerospike.client.metrics.MetricsPolicy;
import com.aerospike.client.metrics.exporter.common.config.reload.ConfigFileWatcher;
import com.aerospike.client.metrics.exporter.common.config.reload.ExporterConfigWatcher;
import com.aerospike.client.metrics.exporter.prometheus.PrometheusMetricsExporter;
import com.aerospike.client.policy.ClientPolicy;

/**
 * Starts a Prometheus exporter alongside a configuration watcher and waits, so
 * credential rotation can be tried by hand.
 *
 * <p>The watcher is wired up by this application rather than by the exporter,
 * which is why no exporter class needs to know about reload. An Aerospike
 * server is optional: when one is reachable the demo drives a little traffic so
 * the endpoint serves real measurements.</p>
 */
public final class ConfigReloadDemo {
    private static final String NAMESPACE = "test";

    private ConfigReloadDemo() {
    }

    public static void main(String[] args) throws InterruptedException {
        // Must be set before the logging handlers are created.
        System.setProperty(
            "java.util.logging.SimpleFormatter.format",
            "%1$tH:%1$tM:%1$tS  %4$-7s  %5$s%n"
        );

        Path config = Paths.get(
            args.length > 0 ? args[0] : "aerospike-metrics-exporter.yml"
        );
        String seed = args.length > 1 ? args[1] : "localhost:3000";

        System.out.println();
        System.out.println("Configuration:  " + config.toAbsolutePath());
        System.out.println("Watching:       "
            + ExporterConfigWatcher.describe(ExporterConfigWatcher.watchedFiles(config)));
        System.out.println();

        final PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
            .fromYaml(config)
            .build();
        final ConfigFileWatcher watcher = ExporterConfigWatcher.watchAndLog("prometheus", config);
        final AerospikeClient client = connect(seed, exporter);

        System.out.println();
        System.out.println("Change any watched file and the change is logged below.");
        System.out.println("Press Ctrl-C to stop.");
        System.out.println();

        final CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (watcher != null) {
                watcher.close();
            }
            if (client != null) {
                client.disableMetrics();
                client.close();
            }
            exporter.close();
            stopped.countDown();
        }));
        stopped.await();
    }

    /** Returns null when no server is reachable; the demo still runs. */
    private static AerospikeClient connect(String seed, PrometheusMetricsExporter exporter) {
        int separator = seed.lastIndexOf(':');
        String hostname = separator < 0 ? seed : seed.substring(0, separator);
        int port = separator < 0 ? 3000 : Integer.parseInt(seed.substring(separator + 1));

        ClientPolicy clientPolicy = new ClientPolicy();
        clientPolicy.timeout = 2000;
        clientPolicy.failIfNotConnected = true;

        AerospikeClient client;
        try {
            client = new AerospikeClient(clientPolicy, new Host(hostname, port));
        }
        catch (RuntimeException e) {
            System.out.println("No Aerospike server at " + seed
                + ", so the endpoint stays empty: " + e.getMessage());
            return null;
        }

        MetricsPolicy metricsPolicy = new MetricsPolicy();
        metricsPolicy.interval = 5;
        metricsPolicy.labels = Collections.singletonMap("client_id", "reload-demo");
        metricsPolicy.addExporter(exporter);
        client.enableMetrics(metricsPolicy);
        System.out.println("Connected to " + seed + ", reporting every 5s as client_id=reload-demo");

        startTraffic(client);
        return client;
    }

    private static void startTraffic(final AerospikeClient client) {
        Thread traffic = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int id = ThreadLocalRandom.current().nextInt(1000);
                    Key key = new Key(NAMESPACE, "reload-demo", id);
                    client.put(null, key, new Bin("value", id));
                    client.get(null, key);
                    Thread.sleep(200L);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                catch (RuntimeException e) {
                    // Keep the demo alive; the point is the configuration watcher.
                }
            }
        }, "reload-demo-traffic");
        traffic.setDaemon(true);
        traffic.start();
    }
}

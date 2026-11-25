/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.Host;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.async.EventLoops;
import com.aerospike.client.listener.RecordListener;
import com.aerospike.client.listener.WriteListener;
import com.aerospike.client.metrics.MetricsExporterType;
import com.aerospike.client.metrics.MetricsPolicy;
import com.aerospike.client.metrics.MetricsSnapshot;
import com.aerospike.client.policy.AuthMode;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.TlsPolicy;

final class TestAerospikeIntegrationSupport {
    private final String host;
    private final int port;
    private final String namespace;
    private final String set;

    TestAerospikeIntegrationSupport() {
        this(
            environment("AEROSPIKE_HOST", "localhost"),
            Integer.parseInt(environment("AEROSPIKE_PORT", "3000")),
            environment("AEROSPIKE_NAMESPACE", "test")
        );
    }

    TestAerospikeIntegrationSupport(String host, int port, String namespace) {
        this.host = host;
        this.port = port;
        this.namespace = namespace;
        this.set = environment("AEROSPIKE_SET", "metrics_exporter_it");
    }

    AerospikeClient newClient(String appId) {
        return newClient(clientPolicy(appId));
    }

    AerospikeClient newAsyncClient(String appId, EventLoops eventLoops) {
        ClientPolicy policy = clientPolicy(appId);
        policy.eventLoops = eventLoops;
        return newClient(policy);
    }

    AerospikeClient newClient(ClientPolicy policy) {
        try {
            return new AerospikeClient(policy, Host.parseHosts(host, port));
        }
        catch (RuntimeException e) {
            throw new IllegalStateException(
                "Unable to connect to Aerospike integration server at " + host + ":" + port,
                e
            );
        }
    }

    ClientPolicy clientPolicy(String appId) {
        ClientPolicy policy = new ClientPolicy();
        policy.appId = appId;
        policy.user = environment("AEROSPIKE_USER", null);
        policy.password = environment("AEROSPIKE_PASSWORD", null);
        policy.authMode = AuthMode.valueOf(
            environment("AEROSPIKE_AUTH_MODE", AuthMode.INTERNAL.name()).toUpperCase()
        );
        if (Boolean.parseBoolean(environment("AEROSPIKE_TLS_ENABLED", "false"))) {
            policy.tlsPolicy = new TlsPolicy();
        }
        return policy;
    }

    MetricsPolicy metricsPolicy(
            OpenTelemetryMetricsExporter exporter,
            String clientId,
            boolean operational
    ) {
        MetricsPolicy policy = new MetricsPolicy();
        policy.exporter = MetricsExporterType.CUSTOM;
        policy.exportInterval = Duration.ofMillis(100);
        policy.enableOperationalMetrics = operational;
        policy.labels = Collections.singletonMap("client_id", clientId);
        policy.addExporter(exporter);
        return policy;
    }

    MetricsSnapshot awaitSnapshot(
            OpenTelemetryMetricsExporter exporter,
            Predicate<MetricsSnapshot> predicate
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            for (MetricsSnapshot snapshot :
                    exporter.snapshotRegistry().currentSnapshots()) {
                if (predicate.test(snapshot)) {
                    return snapshot;
                }
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("Timed out waiting for an Aerospike metrics snapshot");
    }

    String namespace() {
        return namespace;
    }

    int runWorkload(
            AerospikeClient writer,
            AerospikeClient reader,
            Duration duration
    ) throws InterruptedException {
        long deadline = System.nanoTime() + duration.toNanos();
        int sequence = 0;
        while (System.nanoTime() < deadline) {
            Key key = new Key(namespace, set, sequence % 50);
            writer.put(null, key, new Bin("sequence", sequence));
            writer.get(null, key);
            reader.get(null, key);
            sequence++;
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return sequence;
    }

    void runOperations(
            AerospikeClient writer,
            AerospikeClient reader,
            int iterations
    ) {
        for (int sequence = 0; sequence < iterations; sequence++) {
            Key key = new Key(namespace, set, sequence % 50);
            writer.put(null, key, new Bin("sequence", sequence));
            writer.get(null, key);
            reader.get(null, key);
        }
    }

    void runAsyncWorkload(
            AerospikeClient client,
            EventLoops eventLoops,
            Duration duration
    ) throws InterruptedException {
        long deadline = System.nanoTime() + duration.toNanos();
        int sequence = 0;
        while (System.nanoTime() < deadline) {
            Key key = new Key(namespace, set, sequence % 50);
            awaitWrite(client, eventLoops, key, sequence);
            awaitRead(client, eventLoops, key);
            sequence++;
        }
    }

    private static void awaitWrite(
            AerospikeClient client,
            EventLoops eventLoops,
            Key key,
            int sequence
    ) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AerospikeException> failure = new AtomicReference<>();
        client.put(
            eventLoops.next(),
            new WriteListener() {
                @Override
                public void onSuccess(Key ignored) {
                    done.countDown();
                }

                @Override
                public void onFailure(AerospikeException exception) {
                    failure.set(exception);
                    done.countDown();
                }
            },
            null,
            key,
            new Bin("sequence", sequence)
        );
        await("async put", done, failure);
    }

    private static void awaitRead(
            AerospikeClient client,
            EventLoops eventLoops,
            Key key
    ) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AerospikeException> failure = new AtomicReference<>();
        client.get(
            eventLoops.next(),
            new RecordListener() {
                @Override
                public void onSuccess(Key ignored, Record record) {
                    done.countDown();
                }

                @Override
                public void onFailure(AerospikeException exception) {
                    failure.set(exception);
                    done.countDown();
                }
            },
            null,
            key
        );
        await("async get", done, failure);
    }

    private static void await(
            String operation,
            CountDownLatch done,
            AtomicReference<AerospikeException> failure
    ) throws InterruptedException {
        if (!done.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting for " + operation);
        }
        if (failure.get() != null) {
            throw new AssertionError(operation + " failed", failure.get());
        }
    }

    static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }
}

/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLParameters;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import io.prometheus.metrics.exporter.httpserver.MetricsHandler;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

/**
 * Exporter-owned HTTP server with complete startup-failure cleanup.
 */
final class PrometheusHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    private PrometheusHttpServer(
            HttpServer server,
            ExecutorService executor
    ) {
        this.server = server;
        this.executor = executor;
    }

    static PrometheusHttpServer start(
            PrometheusConfig config,
            PrometheusRegistry registry
    ) throws IOException {
        TlsSupport.LoadedTls tls = config.getTlsConfig() == null
            ? null
            : TlsSupport.load(config.getTlsConfig());

        HttpServer candidate = null;
        ExecutorService candidateExecutor = null;
        try {
            if (tls == null) {
                candidate = HttpServer.create();
            }
            else {
                HttpsServer https = HttpsServer.create();
                https.setHttpsConfigurator(configurator(tls));
                candidate = https;
            }

            candidate.bind(
                new InetSocketAddress(
                    config.getBindHost(),
                    config.getPort()
                ),
                3
            );
            candidate.createContext("/metrics", new MetricsHandler(registry));
            candidateExecutor = Executors.newFixedThreadPool(
                4,
                new ServerThreadFactory()
            );
            candidate.setExecutor(candidateExecutor);
            candidate.start();
            return new PrometheusHttpServer(candidate, candidateExecutor);
        }
        catch (IOException e) {
            cleanup(candidate, candidateExecutor);
            throw new IOException(
                "Unable to start Prometheus endpoint on "
                    + config.getBindHost()
                    + ":"
                    + config.getPort()
                    + ": "
                    + e.getMessage(),
                e
            );
        }
        catch (RuntimeException e) {
            cleanup(candidate, candidateExecutor);
            throw e;
        }
    }

    int getPort() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            server.stop(0);
            executor.shutdown();
        }
    }

    private static HttpsConfigurator configurator(
            TlsSupport.LoadedTls tls
    ) {
        return new HttpsConfigurator(tls.getContext()) {
            @Override
            public void configure(HttpsParameters parameters) {
                SSLParameters ssl =
                    tls.getContext().getDefaultSSLParameters();
                ssl.setProtocols(tls.getProtocols());
                if (tls.isClientCertificateRequired()) {
                    ssl.setNeedClientAuth(true);
                }
                else if (tls.isClientCertificateRequested()) {
                    ssl.setWantClientAuth(true);
                }
                parameters.setSSLParameters(ssl);
            }
        };
    }

    private static void cleanup(
            HttpServer server,
            ExecutorService executor
    ) {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static final class ServerThreadFactory
            implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(
                task,
                "aerospike-prometheus-http-" + sequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        }
    }
}

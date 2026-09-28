/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import com.aerospike.client.metrics.IMetricsExporter;
import com.aerospike.client.metrics.MetricsSnapshot;
import com.aerospike.client.metrics.exporter.common.ClientSnapshotRegistry;
import com.aerospike.client.metrics.exporter.common.config.PemSupport;
import com.aerospike.client.metrics.exporter.common.config.TlsConfig;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;

/**
 * Embedded Prometheus endpoint backed by the latest client metrics snapshot.
 */
public final class PrometheusMetricsExporter implements IMetricsExporter {
    private static final Logger LOGGER =
        Logger.getLogger(PrometheusMetricsExporter.class.getName());

    private final ClientSnapshotRegistry snapshots;
    private final CollectorRegistry registry;
    private final AerospikeSnapshotCollector collector;
    private final HttpServer server;
    private final ExecutorService executor;
    private final ScheduledExecutorService timeoutExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();

    PrometheusMetricsExporter(PrometheusExporterConfig config) {
        this.snapshots = new ClientSnapshotRegistry(config.freshness()::createPolicy);
        this.registry = new CollectorRegistry();
        this.collector = new AerospikeSnapshotCollector(snapshots);
        this.collector.register(registry);
        this.executor = Executors.newFixedThreadPool(2, new DaemonThreadFactory());
        this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor(
            new DaemonThreadFactory()
        );
        this.server = createServer(config);
        this.server.setExecutor(executor);
        this.server.createContext(
            config.path(),
            new MetricsHandler(config, registry, timeoutExecutor)
        );
        this.server.start();
    }

    public static PrometheusMetricsExporterBuilder builder() {
        return new PrometheusMetricsExporterBuilder();
    }

    @Override
    public void export(MetricsSnapshot snapshot) {
        if (!closed.get()) {
            snapshots.update(snapshot);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public ClientSnapshotRegistry snapshotRegistry() {
        return snapshots;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        snapshots.clear();
        server.stop(1);
        executor.shutdownNow();
        timeoutExecutor.shutdownNow();
        registry.unregister(collector);
    }

    private static HttpServer createServer(PrometheusExporterConfig config) {
        InetSocketAddress address = new InetSocketAddress(config.bindAddress(), config.port());
        try {
            if (!config.tlsEnabled()) {
                return HttpServer.create(address, 0);
            }

            SSLContext sslContext = PemSupport.createServerContext(config.tls());
            HttpsServer httpsServer = HttpsServer.create(address, 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                @Override
                public void configure(HttpsParameters parameters) {
                    SSLParameters sslParameters = getSSLContext().getDefaultSSLParameters();
                    sslParameters.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
                    if (config.tls().clientAuth() == TlsConfig.ClientAuth.REQUIRED) {
                        sslParameters.setNeedClientAuth(true);
                    }
                    else if (config.tls().clientAuth() == TlsConfig.ClientAuth.OPTIONAL) {
                        sslParameters.setWantClientAuth(true);
                    }
                    parameters.setSSLParameters(sslParameters);
                }
            });
            return httpsServer;
        }
        catch (IOException e) {
            throw new IllegalStateException(
                "Unable to bind Prometheus endpoint at " + address,
                e
            );
        }
    }

    private static final class MetricsHandler implements HttpHandler {
        private final CollectorRegistry registry;
        private final byte[] expectedAuthorization;
        private final String endpointPath;
        private final long timeoutMillis;
        private final ScheduledExecutorService timeoutExecutor;

        private MetricsHandler(
                PrometheusExporterConfig config,
                CollectorRegistry registry,
                ScheduledExecutorService timeoutExecutor
        ) {
            this.registry = registry;
            this.endpointPath = config.path();
            this.timeoutMillis = config.httpTimeout().toMillis();
            this.timeoutExecutor = timeoutExecutor;
            if (config.hasBasicAuth()) {
                String credentials = config.username() + ":" + config.password();
                String header = "Basic " + Base64.getEncoder().encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8)
                );
                this.expectedAuthorization = header.getBytes(StandardCharsets.UTF_8);
            }
            else {
                this.expectedAuthorization = null;
            }
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            ScheduledFuture<?> timeout = timeoutExecutor.schedule(
                exchange::close,
                timeoutMillis,
                TimeUnit.MILLISECONDS
            );
            try {
                if (!endpointPath.equals(exchange.getRequestURI().getPath())) {
                    send(exchange, 404, new byte[0], "text/plain");
                    return;
                }
                if (!"GET".equals(exchange.getRequestMethod())) {
                    send(exchange, 405, new byte[0], "text/plain");
                    return;
                }
                if (!authorized(exchange.getRequestHeaders())) {
                    exchange.getResponseHeaders().set(
                        "WWW-Authenticate",
                        "Basic realm=\"aerospike-client-metrics\""
                    );
                    send(exchange, 401, new byte[0], "text/plain");
                    return;
                }

                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                Writer writer = new OutputStreamWriter(bytes, StandardCharsets.UTF_8);
                TextFormat.write004(writer, registry.metricFamilySamples());
                writer.flush();
                send(exchange, 200, bytes.toByteArray(), TextFormat.CONTENT_TYPE_004);
            }
            catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE, "Unable to collect Prometheus metrics", e);
                throw e;
            }
            catch (IOException e) {
                LOGGER.log(Level.WARNING, "Unable to serve Prometheus metrics", e);
                throw e;
            }
            finally {
                timeout.cancel(false);
                exchange.close();
            }
        }

        private boolean authorized(Headers headers) {
            if (expectedAuthorization == null) {
                return true;
            }
            String supplied = headers.getFirst("Authorization");
            byte[] actual = supplied == null
                ? new byte[expectedAuthorization.length]
                : supplied.getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expectedAuthorization, actual);
        }

        private static void send(
                HttpExchange exchange,
                int status,
                byte[] body,
                String contentType
        ) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private int sequence;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(
                runnable,
                "aerospike-prometheus-exporter-" + sequence++
            );
            thread.setDaemon(true);
            return thread;
        }
    }
}

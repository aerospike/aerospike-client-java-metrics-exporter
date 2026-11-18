/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

@Tag("integration")
class TestPrometheusHttpServerIntegration {
    @Test
    void startsOnlyWhenRequestedAndServesMetrics() throws Exception {
        CustomPrometheusMetricReader reader =
            CustomPrometheusMetricReader.create(
                PrometheusConfig.builder().setPort(0).build()
            );
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        try {
            assertFalse(reader.isHttpServerRunning());
            Meter meter = provider.get("http-test");
            meter.counterBuilder("requests").build().add(4);

            reader.startHttpServer();
            assertTrue(reader.isHttpServerRunning());
            String body = get(
                "http://127.0.0.1:"
                    + reader.getHttpServerPort()
                    + "/metrics"
            );
            assertTrue(body.contains("requests_total"));
            assertTrue(body.contains("requests_total{otel_scope_name=\"http-test\"} 4.0"));
            assertThrows(
                IllegalStateException.class,
                reader::startHttpServer
            );
        }
        finally {
            provider.close();
        }
        assertFalse(reader.isHttpServerRunning());
    }

    @Test
    void reportsPortConflictsAndDoesNotLeakPartialStartup()
            throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.setReuseAddress(false);
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = occupied.getLocalPort();
            CustomPrometheusMetricReader reader =
                CustomPrometheusMetricReader.create(
                    PrometheusConfig.builder().setPort(port).build()
                );
            SdkMeterProvider provider = SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .build();
            try {
                IOException failure = assertThrows(
                    IOException.class,
                    reader::startHttpServer
                );
                assertTrue(failure.getMessage().contains(
                    "127.0.0.1:" + port
                ));
                assertFalse(reader.isHttpServerRunning());
            }
            finally {
                provider.close();
            }
            assertEquals(0, reader.getRegistry().scrape().size());
        }
    }

    @Test
    void applicationOwnedServerRemainsAliveAfterReaderShutdown()
            throws Exception {
        PrometheusRegistry registry = new PrometheusRegistry();
        CustomPrometheusMetricReader reader =
            CustomPrometheusMetricReader.create(
                PrometheusConfig.builder()
                    .setRegistry(registry)
                    .build()
            );
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        HTTPServer server = HTTPServer.builder()
            .hostname("127.0.0.1")
            .port(0)
            .registry(registry)
            .buildAndStart();
        try {
            provider.get("application-server")
                .counterBuilder("alive")
                .build()
                .add(1);
            assertTrue(get(
                "http://127.0.0.1:" + server.getPort() + "/metrics"
            ).contains("alive"));

            provider.close();

            HttpResponse<String> response = request(
                "http://127.0.0.1:" + server.getPort() + "/metrics"
            );
            assertEquals(200, response.statusCode());
            assertFalse(response.body().contains("alive"));
        }
        finally {
            provider.close();
            server.close();
        }
    }

    @Test
    void exporterOwnedServerStopsWhenProviderShutsDown()
            throws Exception {
        CustomPrometheusMetricReader reader =
            CustomPrometheusMetricReader.create(
                PrometheusConfig.builder().setPort(0).build()
            );
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        reader.startHttpServer();
        String endpoint =
            "http://127.0.0.1:" + reader.getHttpServerPort() + "/metrics";
        assertEquals(200, request(endpoint).statusCode());

        provider.close();

        assertThrows(IOException.class, () -> get(endpoint));
    }

    @Test
    void shutdownDoesNotWaitIndefinitelyForBlockedHttpScrape()
            throws Exception {
        CustomPrometheusMetricReader reader =
            CustomPrometheusMetricReader.create(
                PrometheusConfig.builder().setPort(0).build()
            );
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicBoolean callbackInterrupted = new AtomicBoolean();
        provider.get("blocked-http")
            .gaugeBuilder("blocked")
            .buildWithCallback(measurement -> {
                callbackEntered.countDown();
                try {
                    releaseCallback.await();
                    measurement.record(1);
                }
                catch (InterruptedException e) {
                    callbackInterrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            });
        reader.startHttpServer();
        String endpoint =
            "http://127.0.0.1:" + reader.getHttpServerPort() + "/metrics";

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> scrape = executor.submit(() -> {
                try {
                    get(endpoint);
                }
                catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));

            Future<?> shutdown = executor.submit(provider::close);
            shutdown.get(5, TimeUnit.SECONDS);

            releaseCallback.countDown();
            try {
                scrape.get(5, TimeUnit.SECONDS);
            }
            catch (ExecutionException expectedConnectionClosure) {
                // Closing an owned server may terminate the active response.
            }
            assertFalse(callbackInterrupted.get());
        }
        finally {
            releaseCallback.countDown();
            provider.close();
            executor.shutdownNow();
        }
    }

    private static String get(String endpoint)
            throws IOException, InterruptedException {
        return request(endpoint).body();
    }

    private static HttpResponse<String> request(String endpoint)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
        return client.send(
            request,
            HttpResponse.BodyHandlers.ofString()
        );
    }
}

/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;

class OtelMetricsExporterTransportIT {
    private static final DockerImageName COLLECTOR_IMAGE = DockerImageName.parse(
        "otel/opentelemetry-collector-contrib:0.161.0"
    );

    @Test
    void sendsUncompressedAndGzipHttpProtobufPayloads() throws Exception {
        assertCompression(OtelExporterConfig.Compression.NONE, false);
        assertCompression(OtelExporterConfig.Compression.GZIP, true);
    }

    @Test
    void sendsCumulativeAndDeltaCounterValues() throws Exception {
        assertTemporality(
            OtelExporterConfig.Temporality.CUMULATIVE,
            "Cumulative",
            11_234_567L,
            90_000L
        );
        assertTemporality(
            OtelExporterConfig.Temporality.DELTA,
            "Delta",
            1_234_567L,
            30_000L
        );
        assertTemporality(
            OtelExporterConfig.Temporality.LOWMEMORY,
            "Cumulative",
            11_234_567L,
            90_000L
        );
    }

    @Test
    void routesHttpProtobufThroughConfiguredProxy() throws Exception {
        CountDownLatch proxyReceived = new CountDownLatch(1);
        CountDownLatch targetReceived = new CountDownLatch(1);
        AtomicReference<CapturedRequest> proxied = new AtomicReference<>();
        AtomicReference<CapturedRequest> targeted = new AtomicReference<>();
        HttpServer target = captureServer(targetReceived, targeted);
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                proxied.compareAndSet(
                    null,
                    new CapturedRequest(
                        exchange.getRequestHeaders().getFirst("Content-Encoding"),
                        body
                    )
                );
                forward(exchange, body);
            }
            finally {
                proxyReceived.countDown();
            }
        });
        target.start();
        proxy.start();

        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .endpoint("http://127.0.0.1:" + target.getAddress().getPort() + "/v1/metrics")
            .protocol(OtelExporterConfig.Protocol.HTTP_PROTOBUF)
            .proxy("http://127.0.0.1:" + proxy.getAddress().getPort())
            .exportInterval(Duration.ofSeconds(1))
            .snapshotStaleTimeout("5m")
            .build();
        try {
            exporter.export(OtelTestSnapshots.withCommandCount(10L));
            assertTrue(proxyReceived.await(10, TimeUnit.SECONDS));
            assertTrue(targetReceived.await(10, TimeUnit.SECONDS));
            Thread.sleep(200);
            assertTrue(proxied.get().body().length > 0);
            assertTrue(targeted.get().body().length > 0);
        }
        finally {
            exporter.close();
            proxy.stop(0);
            target.stop(0);
        }
    }

    @Test
    void exportsThroughApplicationOwnedOpenTelemetry() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = captureServer(requestReceived, captured);
        server.start();

        OtlpHttpMetricExporter otlp = OtlpHttpMetricExporter.builder()
            .setEndpoint(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/metrics"
            )
            .build();
        PeriodicMetricReader reader = PeriodicMetricReader.builder(otlp)
            .setInterval(Duration.ofSeconds(1))
            .build();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .build();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setMeterProvider(provider)
            .build();
        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .openTelemetry(openTelemetry)
            .snapshotStaleTimeout("5m")
            .build();
        try {
            exporter.export(OtelTestSnapshots.withCommandCount(10L));
            assertTrue(requestReceived.await(10, TimeUnit.SECONDS));
            Thread.sleep(200);
            assertTrue(captured.get().body().length > 0);

            exporter.close();
            assertTrue(provider.forceFlush().join(5, TimeUnit.SECONDS).isSuccess());
        }
        finally {
            exporter.close();
            provider.close();
            server.stop(0);
        }
    }

    @Test
    void retriesTransientHttpFailuresAndRecovers() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/metrics", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int attempt = attempts.incrementAndGet();
            if (attempt <= 2) {
                sendStatus(exchange, 503);
            }
            else {
                sendEmptySuccess(exchange);
                recovered.countDown();
            }
        });
        server.start();

        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .endpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/metrics")
            .protocol(OtelExporterConfig.Protocol.HTTP_PROTOBUF)
            .exportInterval(Duration.ofMillis(200))
            .snapshotStaleTimeout("5m")
            .build();
        try {
            exporter.export(OtelTestSnapshots.withCommandCount(10L));
            assertTrue(
                recovered.await(10, TimeUnit.SECONDS),
                "Timed out waiting for retryable HTTP failures to recover"
            );
            assertEquals(3, attempts.get());
        }
        finally {
            exporter.close();
            server.stop(0);
        }
    }

    @Test
    void recoversAfterHttpRequestTimeout() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/metrics", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                try {
                    Thread.sleep(400);
                    sendEmptySuccess(exchange);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                catch (IOException ignored) {
                    // The client is expected to close the timed-out exchange.
                }
            }
            else {
                sendEmptySuccess(exchange);
                recovered.countDown();
            }
        });
        server.start();

        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .endpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/metrics")
            .protocol(OtelExporterConfig.Protocol.HTTP_PROTOBUF)
            .timeout(Duration.ofMillis(100))
            .exportInterval(Duration.ofMillis(200))
            .snapshotStaleTimeout("5m")
            .build();
        try {
            exporter.export(OtelTestSnapshots.withCommandCount(10L));
            assertTrue(
                recovered.await(10, TimeUnit.SECONDS),
                "Timed out waiting for export after the HTTP timeout"
            );
            assertTrue(attempts.get() >= 2);
        }
        finally {
            exporter.close();
            server.stop(0);
        }
    }

    private static void assertCompression(
            OtelExporterConfig.Compression compression,
            boolean gzip
    ) throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/metrics", exchange -> {
            try {
                captured.compareAndSet(
                    null,
                    new CapturedRequest(
                        exchange.getRequestHeaders().getFirst("Content-Encoding"),
                        exchange.getRequestBody().readAllBytes()
                    )
                );
                sendEmptySuccess(exchange);
            }
            finally {
                requestReceived.countDown();
            }
        });
        server.start();

        OtelMetricsExporter exporter = OtelMetricsExporter.builder()
            .endpoint(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/metrics"
            )
            .protocol(OtelExporterConfig.Protocol.HTTP_PROTOBUF)
            .compression(compression)
            .temporality(OtelExporterConfig.Temporality.CUMULATIVE)
            .exportInterval(Duration.ofSeconds(1))
            .snapshotStaleTimeout("5m")
            .build();
        try {
            exporter.export(OtelTestSnapshots.withCommandCount(10L));
            assertTrue(
                requestReceived.await(10, TimeUnit.SECONDS),
                "Timed out waiting for the OTLP HTTP request"
            );
            // Let the asynchronous HTTP call consume the successful response
            // before exporter shutdown cancels outstanding transport work.
            Thread.sleep(200);
            CapturedRequest request = captured.get();
            assertTrue(request.body().length > 0);
            if (gzip) {
                assertEquals("gzip", request.contentEncoding());
                assertTrue(gunzip(request.body()).length > 0);
            }
            else {
                assertNull(request.contentEncoding());
                assertFalse(isGzip(request.body()));
            }
        }
        finally {
            exporter.close();
            server.stop(0);
        }
    }

    private static void assertTemporality(
            OtelExporterConfig.Temporality temporality,
            String expectedTemporality,
            long expectedSecondValue,
            long expectedSecondLatencyValue
    ) throws Exception {
        try (GenericContainer<?> collector = collector()) {
            collector.start();
            OtelMetricsExporter exporter = OtelMetricsExporter.builder()
                .endpoint(
                    "http://" + collector.getHost() + ":"
                        + collector.getMappedPort(4318) + "/v1/metrics"
                )
                .protocol(OtelExporterConfig.Protocol.HTTP_PROTOBUF)
                .compression(OtelExporterConfig.Compression.NONE)
                .temporality(temporality)
                .exportInterval(Duration.ofMillis(500))
                .snapshotStaleTimeout("5m")
                .build();
            try {
                exporter.export(OtelTestSnapshots.withCommandCountAndReadLatency(
                    10_000_000L,
                    10_000L,
                    20_000L,
                    30_000L
                ));
                awaitCollectorLog(
                    collector,
                    "aerospike_client_command_count",
                    expectedTemporality,
                    10_000_000L,
                    Duration.ofSeconds(10)
                );
                awaitCollectorLog(
                    collector,
                    "aerospike_client_namespace_latency_read_bucket",
                    expectedTemporality,
                    60_000L,
                    Duration.ofSeconds(10)
                );

                exporter.export(OtelTestSnapshots.withCommandCountAndReadLatency(
                    11_234_567L,
                    15_000L,
                    30_000L,
                    45_000L
                ));
                awaitCollectorLog(
                    collector,
                    "aerospike_client_command_count",
                    expectedTemporality,
                    expectedSecondValue,
                    Duration.ofSeconds(10)
                );
                awaitCollectorLog(
                    collector,
                    "aerospike_client_namespace_latency_read_bucket",
                    expectedTemporality,
                    expectedSecondLatencyValue,
                    Duration.ofSeconds(10)
                );
            }
            finally {
                exporter.close();
            }
        }
    }

    @SuppressWarnings("resource") // The caller owns the returned container.
    private static GenericContainer<?> collector() {
        return new GenericContainer<>(COLLECTOR_IMAGE)
            .withCopyToContainer(
                MountableFile.forClasspathResource("otel-collector-transport-it.yml"),
                "/etc/otelcol-contrib/config.yaml"
            )
            .withCommand("--config=/etc/otelcol-contrib/config.yaml")
            .withExposedPorts(4318)
            .waitingFor(Wait.forListeningPort());
    }

    private static HttpServer captureServer(
            CountDownLatch requestReceived,
            AtomicReference<CapturedRequest> captured
    ) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/metrics", exchange -> {
            try {
                captured.compareAndSet(
                    null,
                    new CapturedRequest(
                        exchange.getRequestHeaders().getFirst("Content-Encoding"),
                        exchange.getRequestBody().readAllBytes()
                    )
                );
                sendEmptySuccess(exchange);
            }
            finally {
                requestReceived.countDown();
            }
        });
        return server;
    }

    private static void forward(HttpExchange exchange, byte[] body)
            throws IOException {
        URI requestUri = exchange.getRequestURI();
        if (!requestUri.isAbsolute()) {
            requestUri = URI.create(
                "http://" + exchange.getRequestHeaders().getFirst("Host") + requestUri
            );
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(requestUri)
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        copyHeader(exchange, request, "Content-Type");
        copyHeader(exchange, request, "Content-Encoding");
        HttpResponse<byte[]> response;
        try {
            response = HttpClient.newHttpClient().send(
                request.build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while forwarding OTLP request", e);
        }
        response.headers().firstValue("Content-Type")
            .ifPresent(value -> exchange.getResponseHeaders().set("Content-Type", value));
        exchange.sendResponseHeaders(response.statusCode(), response.body().length);
        exchange.getResponseBody().write(response.body());
        exchange.close();
    }

    private static void copyHeader(
            HttpExchange exchange,
            HttpRequest.Builder request,
            String name
    ) {
        String value = exchange.getRequestHeaders().getFirst(name);
        if (value != null) {
            request.header(name, value);
        }
    }

    private static void awaitCollectorLog(
            GenericContainer<?> collector,
            String metricName,
            String temporality,
            long value,
            Duration timeout
    ) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        String logs = "";
        String expectedTemporality = "AggregationTemporality: " + temporality;
        String expectedValue = "Value: " + value;
        while (System.nanoTime() < deadline) {
            logs = collector.getLogs();
            if (logs.contains(metricName)
                    && logs.contains(expectedTemporality)
                    && logs.contains(expectedValue)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(
            "Timed out waiting for " + metricName + ", " + expectedTemporality
                + " and " + expectedValue
                + "\n" + logs
        );
    }

    private static void sendEmptySuccess(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    private static void sendStatus(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static byte[] gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream input =
                new GZIPInputStream(new ByteArrayInputStream(compressed));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toByteArray();
        }
    }

    private static boolean isGzip(byte[] body) {
        return body.length >= 2
            && (body[0] & 0xff) == 0x1f
            && (body[1] & 0xff) == 0x8b;
    }

    private record CapturedRequest(String contentEncoding, byte[] body) {
    }
}

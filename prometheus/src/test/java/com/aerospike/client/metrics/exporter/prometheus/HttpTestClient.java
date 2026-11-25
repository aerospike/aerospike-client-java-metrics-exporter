/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

/**
 * Minimal Java 8 HTTP client used by endpoint integration tests.
 */
final class HttpTestClient {
    private HttpTestClient() {
    }

    static Response get(String endpoint) throws IOException {
        return get(endpoint, null);
    }

    static Response get(String endpoint, SSLContext sslContext)
            throws IOException {
        HttpURLConnection connection =
            (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(5_000);
        connection.setRequestMethod("GET");
        if (connection instanceof HttpsURLConnection && sslContext != null) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(
                sslContext.getSocketFactory()
            );
        }
        try {
            int statusCode = connection.getResponseCode();
            InputStream input = statusCode >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
            return new Response(statusCode, read(input));
        }
        finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4_096];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static final class Response {
        private final int statusCode;
        private final String body;

        private Response(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        int statusCode() {
            return statusCode;
        }

        String body() {
            return body;
        }
    }
}

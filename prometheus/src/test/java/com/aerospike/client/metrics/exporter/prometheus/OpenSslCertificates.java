/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class OpenSslCertificates {
    static final char[] STORE_PASSWORD = "changeit".toCharArray();

    private OpenSslCertificates() {
    }

    static boolean isAvailable() {
        try {
            Process process = new ProcessBuilder("openssl", "version")
                .redirectErrorStream(true)
                .start();
            return process.waitFor(10, TimeUnit.SECONDS)
                && process.exitValue() == 0;
        }
        catch (IOException e) {
            return false;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static Material generate(Path directory) throws Exception {
        Path caKey = directory.resolve("ca.key");
        Path caCertificate = directory.resolve("ca.crt");
        Path serverKey = directory.resolve("server.key");
        Path serverRequest = directory.resolve("server.csr");
        Path serverCertificate = directory.resolve("server.crt");
        Path otherServerKey = directory.resolve("other-server.key");
        Path clientKey = directory.resolve("client.key");
        Path clientRequest = directory.resolve("client.csr");
        Path clientCertificate = directory.resolve("client.crt");
        Path clientStore = directory.resolve("client.p12");

        run(directory,
            "genpkey",
            "-algorithm", "RSA",
            "-pkeyopt", "rsa_keygen_bits:2048",
            "-out", caKey.toString());
        run(directory,
            "req",
            "-x509",
            "-new",
            "-key", caKey.toString(),
            "-out", caCertificate.toString(),
            "-days", "2",
            "-subj", "/CN=Aerospike Prometheus Test CA",
            "-addext", "basicConstraints=critical,CA:TRUE",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign");

        run(directory,
            "genpkey",
            "-algorithm", "RSA",
            "-pkeyopt", "rsa_keygen_bits:2048",
            "-out", serverKey.toString());
        run(directory,
            "req",
            "-new",
            "-key", serverKey.toString(),
            "-out", serverRequest.toString(),
            "-subj", "/CN=localhost",
            "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1",
            "-addext", "extendedKeyUsage=serverAuth");
        run(directory,
            "x509",
            "-req",
            "-in", serverRequest.toString(),
            "-CA", caCertificate.toString(),
            "-CAkey", caKey.toString(),
            "-CAcreateserial",
            "-out", serverCertificate.toString(),
            "-days", "2",
            "-copy_extensions", "copy");

        run(directory,
            "genpkey",
            "-algorithm", "RSA",
            "-pkeyopt", "rsa_keygen_bits:2048",
            "-out", otherServerKey.toString());

        run(directory,
            "genpkey",
            "-algorithm", "RSA",
            "-pkeyopt", "rsa_keygen_bits:2048",
            "-out", clientKey.toString());
        run(directory,
            "req",
            "-new",
            "-key", clientKey.toString(),
            "-out", clientRequest.toString(),
            "-subj", "/CN=prometheus-test-client",
            "-addext", "extendedKeyUsage=clientAuth");
        run(directory,
            "x509",
            "-req",
            "-in", clientRequest.toString(),
            "-CA", caCertificate.toString(),
            "-CAkey", caKey.toString(),
            "-CAcreateserial",
            "-out", clientCertificate.toString(),
            "-days", "2",
            "-copy_extensions", "copy");
        run(directory,
            "pkcs12",
            "-export",
            "-inkey", clientKey.toString(),
            "-in", clientCertificate.toString(),
            "-certfile", caCertificate.toString(),
            "-out", clientStore.toString(),
            "-passout", "pass:" + new String(STORE_PASSWORD));

        return new Material(
            caCertificate,
            serverCertificate,
            serverKey,
            otherServerKey,
            clientStore
        );
    }

    private static void run(Path directory, String... arguments)
            throws Exception {
        List<String> command = new ArrayList<String>();
        command.add("openssl");
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start();
        boolean completed = process.waitFor(
            Duration.ofSeconds(30).toMillis(),
            TimeUnit.MILLISECONDS
        );
        if (!completed) {
            process.destroyForcibly();
            throw new AssertionError(
                "OpenSSL command timed out: " + String.join(" ", command)
            );
        }
        String output = new String(
            readAll(process.getInputStream()),
            StandardCharsets.UTF_8
        );
        if (process.exitValue() != 0) {
            throw new AssertionError(
                "OpenSSL command failed ("
                    + process.exitValue()
                    + "): "
                    + String.join(" ", command)
                    + System.lineSeparator()
                    + output
            );
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4_096];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static final class Material {
        final Path caCertificate;
        final Path serverCertificate;
        final Path serverKey;
        final Path mismatchedServerKey;
        final Path clientStore;

        private Material(
                Path caCertificate,
                Path serverCertificate,
                Path serverKey,
                Path mismatchedServerKey,
                Path clientStore
        ) {
            this.caCertificate = caCertificate;
            this.serverCertificate = serverCertificate;
            this.serverKey = serverKey;
            this.mismatchedServerKey = mismatchedServerKey;
            this.clientStore = clientStore;
        }
    }
}

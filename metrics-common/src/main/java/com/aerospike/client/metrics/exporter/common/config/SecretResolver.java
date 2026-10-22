/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves environment placeholders and file-backed secret values.
 */
public final class SecretResolver {
    private static final Pattern ENV_PLACEHOLDER =
        Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final String FILE_PREFIX = "file:";

    private SecretResolver() {
    }

    /**
     * Resolve all {@code ${ENV_NAME}} placeholders, then load the result from
     * disk when it starts with {@code file:}. File contents are trimmed to
     * support newline-terminated secret mounts.
     *
     * @param value literal, environment-templated, or file-backed value
     */
    public static String resolve(String value) {
        return resolve(value, System.getenv());
    }

    static String resolve(String value, Map<String, String> environment) {
        String resolved = substitute(value, environment);
        Path path = fileReference(resolved);
        if (path != null) {
            try {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
            }
            catch (IOException e) {
                throw new IllegalArgumentException("Unable to read secret file: " + path, e);
            }
        }
        return resolved;
    }

    /**
     * Resolve a final, effective value that directly names a file.
     */
    public static Path resolveRequiredPathReference(String value) {
        return resolveRequiredPathReference(value, System.getenv());
    }

    static Path resolveRequiredPathReference(
            String value,
            Map<String, String> environment
    ) {
        String resolved = substitute(value, environment);
        return resolved == null || resolved.trim().isEmpty()
            ? null
            : Paths.get(resolved.trim());
    }

    /**
     * Resolve the path behind a final, effective {@code file:} secret value.
     */
    public static Path resolveRequiredFileReference(String value) {
        return resolveRequiredFileReference(value, System.getenv());
    }

    static Path resolveRequiredFileReference(
            String value,
            Map<String, String> environment
    ) {
        return fileReference(substitute(value, environment));
    }

    private static Path fileReference(String value) {
        if (value == null || !value.startsWith(FILE_PREFIX)) {
            return null;
        }
        String path = value.substring(FILE_PREFIX.length()).trim();
        if (path.isEmpty()) {
            throw new IllegalArgumentException("file: secret reference must include a path");
        }
        return Paths.get(path);
    }

    private static String substitute(
            String value,
            Map<String, String> environment
    ) {
        if (value == null) {
            return null;
        }

        Matcher matcher = ENV_PLACEHOLDER.matcher(value);
        StringBuffer resolvedValue = new StringBuffer();
        boolean foundPlaceholder = false;
        while (matcher.find()) {
            foundPlaceholder = true;
            String name = matcher.group(1);
            String resolved = environment.get(name);
            if (resolved == null) {
                throw new IllegalArgumentException(
                    "Required environment variable is not set: " + name
                );
            }
            matcher.appendReplacement(resolvedValue, Matcher.quoteReplacement(resolved));
        }
        if (!foundPlaceholder) {
            return value;
        }
        matcher.appendTail(resolvedValue);
        return resolvedValue.toString();
    }
}

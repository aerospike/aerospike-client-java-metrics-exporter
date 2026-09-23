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

    private SecretResolver() {
    }

    public static String resolve(String value) {
        return resolve(value, System.getenv());
    }

    static String resolve(String value, Map<String, String> environment) {
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
                throw new IllegalArgumentException("Required environment variable is not set: " + name);
            }
            matcher.appendReplacement(resolvedValue, Matcher.quoteReplacement(resolved));
        }
        if (foundPlaceholder) {
            matcher.appendTail(resolvedValue);
            value = resolvedValue.toString();
        }

        if (value.startsWith("file:")) {
            Path path = Paths.get(value.substring("file:".length()));
            try {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
            }
            catch (IOException e) {
                throw new IllegalArgumentException("Unable to read secret file: " + path, e);
            }
        }
        return value;
    }
}

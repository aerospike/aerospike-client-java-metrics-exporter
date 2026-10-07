/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config.reload;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the file behind a configuration value.
 *
 * <p>Deliberately separate from
 * {@code com.aerospike.client.metrics.exporter.common.config.SecretResolver},
 * which returns a secret's <em>contents</em>. Reload needs the location so it
 * can watch it, and keeping that here leaves the resolver untouched.</p>
 */
final class SecretReference {
    private static final Pattern ENV_PLACEHOLDER =
        Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final String FILE_PREFIX = "file:";

    private SecretReference() {
    }

    /**
     * Returns the path held by a value that names a file outright, such as
     * {@code tls.cert_file}, or {@code null} when it is unset.
     */
    static Path path(String value, Map<String, String> environment) {
        String substituted = substitute(value, environment);
        return substituted == null || substituted.trim().isEmpty()
            ? null
            : Paths.get(substituted.trim());
    }

    /**
     * Returns the path behind a {@code file:} secret, or {@code null} when the
     * value is inline. An inline secret cannot rotate, so there is nothing to
     * watch.
     */
    static Path secret(String value, Map<String, String> environment) {
        String substituted = substitute(value, environment);
        return substituted != null && substituted.startsWith(FILE_PREFIX)
            ? Paths.get(substituted.substring(FILE_PREFIX.length()).trim())
            : null;
    }

    private static String substitute(String value, Map<String, String> environment) {
        if (value == null) {
            return null;
        }

        Matcher matcher = ENV_PLACEHOLDER.matcher(value);
        StringBuffer resolved = new StringBuffer();
        boolean found = false;
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = environment.get(name);
            if (replacement == null) {
                // Leave resolution errors to the exporter's own validation; a
                // watcher must not be the thing that fails a startup.
                return null;
            }
            found = true;
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        if (!found) {
            return value;
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }
}

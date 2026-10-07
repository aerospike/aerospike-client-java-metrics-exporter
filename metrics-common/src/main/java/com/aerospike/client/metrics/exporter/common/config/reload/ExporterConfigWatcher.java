/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config.reload;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.aerospike.client.metrics.exporter.common.config.DurationParser;
import com.aerospike.client.metrics.exporter.common.config.YamlConfigSource;

/**
 * Watches the credential files an exporter YAML points at.
 *
 * <p>Entirely self-contained: it reads the YAML itself rather than asking an
 * exporter what it resolved, so no exporter, builder or configuration class
 * has to change. The cost is that only paths written in the YAML are seen. A
 * path supplied through an environment variable or a builder call is invisible
 * here, and the configuration file remains the documented way to point at
 * certificates and secrets.</p>
 *
 * <p>Typical use, after the exporter has been built:</p>
 *
 * <pre>{@code
 * ConfigFileWatcher watcher = ExporterConfigWatcher.watchAndLog("prometheus", yamlPath);
 * ...
 * if (watcher != null) {
 *     watcher.close();
 * }
 * }</pre>
 */
public final class ExporterConfigWatcher {
    private static final Logger LOGGER =
        Logger.getLogger(ExporterConfigWatcher.class.getName());

    private static final String PREFIX = "aerospike_metrics_exporter.";
    private static final String RELOAD_ENABLED = PREFIX + "reload.enabled";
    private static final String RELOAD_INTERVAL = PREFIX + "reload.interval";
    private static final String OTEL_HEADERS = PREFIX + "otel.headers.";

    /** Keys whose value is a path outright. */
    private static final String[] FILE_KEYS = {
        PREFIX + "prometheus.tls.cert_file",
        PREFIX + "prometheus.tls.key_file",
        PREFIX + "prometheus.tls.client_ca_file",
        PREFIX + "otel.tls.ca_file",
        PREFIX + "otel.tls.client_cert_file",
        PREFIX + "otel.tls.client_key_file",
    };

    /** Keys whose value may be a {@code file:} secret. */
    private static final String[] SECRET_KEYS = {
        PREFIX + "prometheus.basic_auth.username",
        PREFIX + "prometheus.basic_auth.password",
        PREFIX + "prometheus.tls.key_password",
        PREFIX + "otel.tls.client_key_password",
    };

    private ExporterConfigWatcher() {
    }

    /**
     * Starts a watcher that logs every detected change and applies nothing.
     *
     * @return the started watcher, or {@code null} when reload is switched off
     *     or the YAML names no files
     */
    public static ConfigFileWatcher watchAndLog(final String name, Path yamlPath) {
        final Map<Path, String> watched = watchedFiles(yamlPath);
        return watch(name, yamlPath, changed -> {
            for (Path path : changed) {
                LOGGER.log(
                    Level.INFO,
                    "{0} exporter: {1} changed on disk ({2})",
                    new Object[] {name, watched.get(path), path}
                );
            }
            LOGGER.log(
                Level.INFO,
                "{0} exporter: change detection only; "
                    + "the running configuration was not modified",
                name
            );
        });
    }

    /**
     * Starts a watcher over the files the YAML names.
     *
     * @return the started watcher, or {@code null} when reload is switched off
     *     or the YAML names no files
     */
    public static ConfigFileWatcher watch(
            String name,
            Path yamlPath,
            ConfigFileWatcher.Listener listener
    ) {
        Map<String, String> yaml = YamlConfigSource.load(yamlPath);
        ReloadSettings settings = reloadSettings(yaml);
        if (!settings.enabled()) {
            LOGGER.log(Level.INFO, "{0} exporter: configuration reload is disabled", name);
            return null;
        }

        Map<Path, String> watched = watchedFiles(yamlPath);
        if (watched.isEmpty()) {
            return null;
        }

        ConfigFileWatcher watcher = new ConfigFileWatcher(
            name,
            watched.keySet(),
            settings.interval(),
            listener
        );
        watcher.start();
        return watcher;
    }

    /**
     * Files the YAML points at, mapped to the key that names them. The label is
     * what reload logs, so it stays a configuration key rather than prose.
     */
    public static Map<Path, String> watchedFiles(Path yamlPath) {
        Map<String, String> yaml = YamlConfigSource.load(yamlPath);
        Map<String, String> environment = System.getenv();
        Map<Path, String> watched = new LinkedHashMap<Path, String>();

        // The YAML itself, so a secret typed directly into it is covered too.
        add(watched, yamlPath, "exporter YAML");

        for (String key : FILE_KEYS) {
            add(watched, SecretReference.path(yaml.get(key), environment), key);
        }
        for (String key : SECRET_KEYS) {
            add(watched, SecretReference.secret(yaml.get(key), environment), key);
        }
        for (Map.Entry<String, String> entry : yaml.entrySet()) {
            if (entry.getKey().startsWith(OTEL_HEADERS)) {
                add(
                    watched,
                    SecretReference.secret(entry.getValue(), environment),
                    entry.getKey()
                );
            }
        }
        return Collections.unmodifiableMap(watched);
    }

    private static ReloadSettings reloadSettings(Map<String, String> yaml) {
        String enabled = yaml.get(RELOAD_ENABLED);
        String interval = yaml.get(RELOAD_INTERVAL);
        return new ReloadSettings(
            enabled == null || Boolean.parseBoolean(enabled.trim()),
            DurationParser.parse(
                interval == null ? ReloadSettings.DEFAULT_INTERVAL : interval,
                RELOAD_INTERVAL
            )
        );
    }

    private static void add(Map<Path, String> target, Path path, String label) {
        if (path == null) {
            return;
        }
        String existing = target.get(path);
        target.put(path, existing == null ? label : existing + ", " + label);
    }

    /** Files whose change the listener will be told about, for logging at startup. */
    public static String describe(Map<Path, String> watched) {
        if (watched.isEmpty()) {
            return "nothing (the configuration names no files)";
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Path, String> entry : watched.entrySet()) {
            text.append("\n  ").append(entry.getValue()).append("  ->  ").append(entry.getKey());
        }
        return text.toString();
    }
}

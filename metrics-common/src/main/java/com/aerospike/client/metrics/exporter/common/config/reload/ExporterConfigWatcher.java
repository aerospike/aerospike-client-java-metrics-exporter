/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config.reload;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Starts change detection over already-resolved credential-file references.
 *
 * <p>Configuration sources and paths are resolved by the exporter builder at
 * startup. This class never reads YAML, discovers new paths, or modifies a
 * running exporter.</p>
 *
 * <p>Typical detection-only use:</p>
 *
 * <pre>{@code
 * FileWatchConfiguration files = builder.resolveFileWatchConfiguration();
 * ConfigFileWatcher watcher = ExporterConfigWatcher.watchAndLog("prometheus", files);
 * ...
 * if (watcher != null) {
 *     watcher.close();
 * }
 * }</pre>
 */
public final class ExporterConfigWatcher {
    private static final Logger LOGGER =
        Logger.getLogger(ExporterConfigWatcher.class.getName());

    /** Receives typed reasons for every changed physical file. */
    public interface Listener {
        void onFilesChanged(Set<ReloadableFileReference> changed);
    }

    private ExporterConfigWatcher() {
    }

    /**
     * Starts a watcher that logs every detected change and applies nothing.
     *
     * @return the started watcher, or {@code null} when reload is switched off
     *     or the resolved configuration names no credential files
     */
    public static ConfigFileWatcher watchAndLog(
            final String name,
            FileWatchConfiguration configuration
    ) {
        return watch(name, configuration, changed -> {
            for (ReloadableFileReference reference : changed) {
                LOGGER.log(
                    Level.INFO,
                    "{0} exporter: {1} changed on disk ({2})",
                    new Object[] {name, reference.field(), reference.path()}
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
     * Starts a watcher over the fixed paths selected during configuration
     * resolution.
     *
     * @return the started watcher, or {@code null} when reload is switched off
     *     or the resolved configuration names no credential files
     */
    public static ConfigFileWatcher watch(
            String name,
            FileWatchConfiguration configuration,
            Listener listener
    ) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (!configuration.settings().enabled()) {
            LOGGER.log(Level.INFO, "{0} exporter: file change detection is disabled", name);
            return null;
        }

        if (configuration.paths().isEmpty()) {
            return null;
        }

        ConfigFileWatcher watcher = new ConfigFileWatcher(
            name,
            configuration.paths(),
            configuration.settings().interval(),
            changedPaths -> {
                Set<ReloadableFileReference> changed =
                    new LinkedHashSet<ReloadableFileReference>();
                for (Path path : changedPaths) {
                    changed.addAll(configuration.referencesFor(path));
                }
                if (!changed.isEmpty()) {
                    listener.onFilesChanged(Collections.unmodifiableSet(changed));
                }
            }
        );
        watcher.start();
        return watcher;
    }

    /** Files whose change the listener will be told about, for logging at startup. */
    public static String describe(FileWatchConfiguration configuration) {
        if (configuration == null || configuration.paths().isEmpty()) {
            return "nothing (the configuration names no files)";
        }
        StringBuilder text = new StringBuilder();
        for (Path path : configuration.paths()) {
            String separator = "";
            text.append("\n  ");
            for (ReloadableFileReference reference : configuration.referencesFor(path)) {
                text.append(separator).append(reference.field());
                separator = ", ";
            }
            text.append("  ->  ").append(path);
        }
        return text.toString();
    }
}

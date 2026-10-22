/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config.reload;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.aerospike.client.metrics.exporter.common.config.ConfigValues;
import com.aerospike.client.metrics.exporter.common.config.SecretResolver;
import com.aerospike.client.metrics.exporter.common.config.reload.ReloadableFileReference.Backend;

/**
 * Immutable startup result used by the credential-file watcher.
 *
 * <p>Only file contents are dynamic. Settings and paths are resolved once and
 * remain fixed until the process restarts.</p>
 */
public final class FileWatchConfiguration {
    private static final String PREFIX = "aerospike_metrics_exporter.";
    private static final String PROMETHEUS = PREFIX + "prometheus.";
    private static final String OTEL = PREFIX + "otel.";

    private final ReloadSettings settings;
    private final List<ReloadableFileReference> references;
    private final Map<Path, Set<ReloadableFileReference>> referencesByPath;

    public FileWatchConfiguration(
            ReloadSettings settings,
            Collection<ReloadableFileReference> references
    ) {
        if (settings == null) {
            throw new IllegalArgumentException("settings must not be null");
        }
        if (references == null) {
            throw new IllegalArgumentException("references must not be null");
        }
        this.settings = settings;
        this.references = Collections.unmodifiableList(
            new ArrayList<ReloadableFileReference>(
                new LinkedHashSet<ReloadableFileReference>(references)
            )
        );

        Map<Path, Set<ReloadableFileReference>> grouped =
            new LinkedHashMap<Path, Set<ReloadableFileReference>>();
        for (ReloadableFileReference reference : this.references) {
            grouped.computeIfAbsent(
                reference.path(),
                ignored -> new LinkedHashSet<ReloadableFileReference>()
            ).add(reference);
        }
        Map<Path, Set<ReloadableFileReference>> immutable =
            new LinkedHashMap<Path, Set<ReloadableFileReference>>();
        for (Map.Entry<Path, Set<ReloadableFileReference>> entry : grouped.entrySet()) {
            immutable.put(
                entry.getKey(),
                Collections.unmodifiableSet(
                    new LinkedHashSet<ReloadableFileReference>(entry.getValue())
                )
            );
        }
        this.referencesByPath = Collections.unmodifiableMap(immutable);
    }

    /**
     * Derive watched files from the final merged configuration values.
     */
    public static FileWatchConfiguration from(ConfigValues values) {
        return from(values, null);
    }

    /**
     * Derive watched files for one backend from final merged values.
     *
     * <p>Filtering before path parsing prevents an unused backend's optional
     * references from affecting the active backend.</p>
     */
    public static FileWatchConfiguration from(ConfigValues values, Backend backend) {
        if (values == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        ReloadSettings settings = ReloadSettings.from(values);
        List<ReloadableFileReference> references =
            new ArrayList<ReloadableFileReference>();

        if (backend == null || backend == Backend.PROMETHEUS) {
            addPath(
                references,
                Backend.PROMETHEUS,
                "prometheus.tls.cert_file",
                values.get(PROMETHEUS + "tls.cert_file")
            );
            addPath(
                references,
                Backend.PROMETHEUS,
                "prometheus.tls.key_file",
                values.get(PROMETHEUS + "tls.key_file")
            );
            addSecret(
                references,
                Backend.PROMETHEUS,
                "prometheus.tls.key_password",
                values.get(PROMETHEUS + "tls.key_password")
            );
            addPath(
                references,
                Backend.PROMETHEUS,
                "prometheus.tls.client_ca_file",
                values.get(PROMETHEUS + "tls.client_ca_file")
            );
            addSecret(
                references,
                Backend.PROMETHEUS,
                "prometheus.basic_auth.username",
                values.get(PROMETHEUS + "basic_auth.username")
            );
            addSecret(
                references,
                Backend.PROMETHEUS,
                "prometheus.basic_auth.password",
                values.get(PROMETHEUS + "basic_auth.password")
            );
        }
        if (backend == null || backend == Backend.OTEL) {
            addPath(
                references,
                Backend.OTEL,
                "otel.tls.ca_file",
                values.get(OTEL + "tls.ca_file")
            );
            addPath(
                references,
                Backend.OTEL,
                "otel.tls.client_cert_file",
                values.get(OTEL + "tls.client_cert_file")
            );
            addPath(
                references,
                Backend.OTEL,
                "otel.tls.client_key_file",
                values.get(OTEL + "tls.client_key_file")
            );
            addSecret(
                references,
                Backend.OTEL,
                "otel.tls.client_key_password",
                values.get(OTEL + "tls.client_key_password")
            );
            for (Map.Entry<String, String> header
                    : values.withPrefix(OTEL + "headers.").entrySet()) {
                addSecret(
                    references,
                    Backend.OTEL,
                    "otel.headers." + header.getKey(),
                    header.getValue()
                );
            }
        }
        return new FileWatchConfiguration(settings, references);
    }

    public ReloadSettings settings() {
        return settings;
    }

    public List<ReloadableFileReference> references() {
        return references;
    }

    public Set<Path> paths() {
        return referencesByPath.keySet();
    }

    public Set<ReloadableFileReference> referencesFor(Path path) {
        if (path == null) {
            return Collections.emptySet();
        }
        Set<ReloadableFileReference> found =
            referencesByPath.get(path.toAbsolutePath().normalize());
        return found == null ? Collections.emptySet() : found;
    }

    /**
     * Return the same startup settings with references for one backend only.
     */
    public FileWatchConfiguration forBackend(Backend backend) {
        if (backend == null) {
            throw new IllegalArgumentException("backend must not be null");
        }
        List<ReloadableFileReference> selected =
            new ArrayList<ReloadableFileReference>();
        for (ReloadableFileReference reference : references) {
            if (reference.backend() == backend) {
                selected.add(reference);
            }
        }
        return new FileWatchConfiguration(settings, selected);
    }

    private static void addPath(
            Collection<ReloadableFileReference> references,
            Backend backend,
            String field,
            String value
    ) {
        add(
            references,
            backend,
            field,
            SecretResolver.resolveRequiredPathReference(value)
        );
    }

    private static void addSecret(
            Collection<ReloadableFileReference> references,
            Backend backend,
            String field,
            String value
    ) {
        add(
            references,
            backend,
            field,
            SecretResolver.resolveRequiredFileReference(value)
        );
    }

    private static void add(
            Collection<ReloadableFileReference> references,
            Backend backend,
            String field,
            Path path
    ) {
        if (path != null) {
            references.add(new ReloadableFileReference(backend, field, path));
        }
    }
}

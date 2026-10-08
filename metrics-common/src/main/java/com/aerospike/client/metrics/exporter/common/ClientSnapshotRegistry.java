/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.aerospike.client.metrics.MetricsSnapshot;

/**
 * Stores the latest snapshot independently for every client that shares an
 * exporter instance. Updates may arrive concurrently from different client
 * metrics dispatchers.
 *
 * <p>Client entries are intentionally retained when their snapshots become
 * stale. A client that enables metrics again with the same {@code client_id}
 * resumes by updating its existing store.</p>
 *
 * <p>Customer labels are validated before any registry state changes. The
 * stable union of their names gives Prometheus collectors one deterministic
 * label schema across all clients.</p>
 */
public final class ClientSnapshotRegistry {
    public static final String CLIENT_ID = "client_id";

    private static final Pattern LABEL_NAME =
        Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    // These dimensions are derived from the snapshot at their proper scope.
    // Accepting them as global labels would silently overwrite OTel attributes
    // and create duplicate Prometheus label names. "le" belongs exclusively to
    // Prometheus histogram buckets.
    private static final Set<String> RESERVED_LABEL_NAMES = Set.of(
        SnapshotAttributes.CLUSTER,
        "cluster_name",
        SnapshotAttributes.CLIENT_TYPE,
        SnapshotAttributes.CLIENT_VERSION,
        SnapshotAttributes.APP_ID,
        SnapshotAttributes.NODE,
        SnapshotAttributes.NODE_ADDRESS,
        SnapshotAttributes.NODE_PORT,
        SnapshotAttributes.CONNECTION_TYPE,
        SnapshotAttributes.NAMESPACE,
        "le"
    );

    private final Supplier<SnapshotFreshnessPolicy> freshnessPolicyFactory;
    private final LongSupplier nanoClock;
    private final ConcurrentMap<String, SnapshotStore> stores =
        new ConcurrentHashMap<String, SnapshotStore>();
    private final ConcurrentSkipListSet<String> knownGlobalLabelNames =
        new ConcurrentSkipListSet<String>();

    /**
     * Create a registry whose clients each receive an independent freshness
     * policy.
     *
     * @param freshnessPolicyFactory creates one policy per client id
     */
    public ClientSnapshotRegistry(
            Supplier<SnapshotFreshnessPolicy> freshnessPolicyFactory
    ) {
        this(freshnessPolicyFactory, System::nanoTime);
    }

    ClientSnapshotRegistry(
            Supplier<SnapshotFreshnessPolicy> freshnessPolicyFactory,
            LongSupplier nanoClock
    ) {
        if (freshnessPolicyFactory == null) {
            throw new IllegalArgumentException("freshnessPolicyFactory must not be null");
        }
        if (nanoClock == null) {
            throw new IllegalArgumentException("nanoClock must not be null");
        }
        this.freshnessPolicyFactory = freshnessPolicyFactory;
        this.nanoClock = nanoClock;
    }

    /**
     * Validate and retain the latest snapshot for its {@code client_id}.
     *
     * @param snapshot immutable client snapshot to retain
     * @throws IllegalArgumentException if the snapshot or its labels are invalid
     */
    public void update(MetricsSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }

        String clientId = clientId(snapshot);
        knownGlobalLabelNames.addAll(SnapshotAttributes.global(snapshot).keySet());
        stores.computeIfAbsent(clientId, ignored -> newStore()).update(snapshot);
    }

    /**
     * Returns current snapshots only. Stale clients remain registered but are
     * omitted until another snapshot arrives.
     */
    public Collection<MetricsSnapshot> currentSnapshots() {
        List<MetricsSnapshot> current = new ArrayList<MetricsSnapshot>(stores.size());
        for (SnapshotStore store : stores.values()) {
            MetricsSnapshot snapshot = store.currentIfFresh();
            if (snapshot != null) {
                current.add(snapshot);
            }
        }
        return Collections.unmodifiableList(current);
    }

    /**
     * Returns the stable union of global label names observed by this registry.
     * Names remain present when a client is stale so Prometheus metric families
     * do not change their label layout between scrapes.
     *
     * @return immutable, sorted label names
     */
    public List<String> knownGlobalLabelNames() {
        return Collections.unmodifiableList(
            new ArrayList<String>(knownGlobalLabelNames)
        );
    }

    /**
     * Return the store associated with a client id, primarily for lifecycle
     * diagnostics.
     *
     * @param clientId stable client identifier
     * @return store for the client, or {@code null} if no snapshot has arrived
     */
    public SnapshotStore snapshotStore(String clientId) {
        return stores.get(clientId);
    }

    /**
     * Return the number of client ids observed by this registry.
     */
    public int size() {
        return stores.size();
    }

    /**
     * Remove all retained snapshots and label-schema state.
     */
    public void clear() {
        for (SnapshotStore store : stores.values()) {
            store.clear();
        }
        stores.clear();
        knownGlobalLabelNames.clear();
    }

    private SnapshotStore newStore() {
        SnapshotFreshnessPolicy policy = freshnessPolicyFactory.get();
        if (policy == null) {
            throw new IllegalStateException("freshnessPolicyFactory returned null");
        }
        return new SnapshotStore(policy, nanoClock);
    }

    private static String clientId(MetricsSnapshot snapshot) {
        if (snapshot.labels == null) {
            throw new IllegalArgumentException(
                "MetricsSnapshot.labels must not be null"
            );
        }
        for (Map.Entry<String, String> label : snapshot.labels.entrySet()) {
            String name = label.getKey();
            if (name == null || !LABEL_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(
                    "MetricsSnapshot label name must match [a-zA-Z_][a-zA-Z0-9_]*: "
                        + name
                );
            }
            if (RESERVED_LABEL_NAMES.contains(name)) {
                throw new IllegalArgumentException(
                    "MetricsSnapshot label name is reserved by the exporter: " + name
                );
            }
            if (label.getValue() == null) {
                throw new IllegalArgumentException(
                    "MetricsSnapshot label value must not be null: " + name
                );
            }
        }

        String clientId = snapshot.labels.get(CLIENT_ID);
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "MetricsSnapshot.labels must contain a non-blank client_id"
            );
        }
        if (!clientId.equals(clientId.trim())) {
            throw new IllegalArgumentException(
                "MetricsSnapshot client_id must not have leading or trailing whitespace"
            );
        }
        return clientId;
    }
}

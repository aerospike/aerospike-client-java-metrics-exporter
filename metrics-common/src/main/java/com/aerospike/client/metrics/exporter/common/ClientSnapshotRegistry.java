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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.aerospike.client.metrics.MetricsSnapshot;

/**
 * Stores the latest snapshot independently for every client that shares an
 * exporter instance.
 *
 * <p>Client entries are intentionally retained when their snapshots become
 * stale. A client that enables metrics again with the same {@code client_id}
 * resumes by updating its existing store.</p>
 */
public final class ClientSnapshotRegistry {
    public static final String CLIENT_ID = "client_id";

    private final Supplier<SnapshotFreshnessPolicy> freshnessPolicyFactory;
    private final LongSupplier nanoClock;
    private final ConcurrentMap<String, SnapshotStore> stores =
        new ConcurrentHashMap<String, SnapshotStore>();
    private final ConcurrentSkipListSet<String> knownGlobalLabelNames =
        new ConcurrentSkipListSet<String>();

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
     */
    public List<String> knownGlobalLabelNames() {
        return Collections.unmodifiableList(
            new ArrayList<String>(knownGlobalLabelNames)
        );
    }

    public SnapshotStore snapshotStore(String clientId) {
        return stores.get(clientId);
    }

    public int size() {
        return stores.size();
    }

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

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import com.aerospike.client.metrics.MetricsSnapshot;

/**
 * Stores the latest snapshot independently for every client that shares an
 * exporter instance. Updates may arrive concurrently from different client
 * metrics dispatchers. Disabled snapshots remain observable until a later
 * snapshot replaces them or the registry is cleared.
 */
public final class ClientSnapshotRegistry {
    public static final String CLIENT_ID = "client_id";

    private static final Pattern LABEL_NAME =
        Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    private final ConcurrentMap<String, MetricsSnapshot> snapshots =
        new ConcurrentHashMap<String, MetricsSnapshot>();

    public ClientSnapshotRegistry() {
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
        if (snapshot.timestamp == null) {
            throw new IllegalArgumentException("MetricsSnapshot.timestamp must not be null");
        }
        if (snapshot.latencyUnit == null) {
            throw new IllegalArgumentException("MetricsSnapshot.latencyUnit must not be null");
        }
        if (snapshot.latencyColumns < 1) {
            throw new IllegalArgumentException(
                "MetricsSnapshot.latencyColumns must be positive"
            );
        }
        if (snapshot.latencyShift < 0) {
            throw new IllegalArgumentException(
                "MetricsSnapshot.latencyShift must not be negative"
            );
        }
        if (snapshot.usageMetricsEnabled && snapshot.usage == null) {
            throw new IllegalArgumentException(
                "MetricsSnapshot.usage must not be null when usage metrics are enabled"
            );
        }

        String clientId = clientId(snapshot);
        snapshots.put(clientId, snapshot);
    }

    /**
     * Return immutable snapshots currently exposed to observable callbacks.
     */
    public Collection<MetricsSnapshot> currentSnapshots() {
        List<MetricsSnapshot> current =
            new ArrayList<MetricsSnapshot>(snapshots.values());
        return Collections.unmodifiableList(current);
    }

    /**
     * Return the number of client ids observed by this registry.
     */
    public int size() {
        return snapshots.size();
    }

    /**
     * Remove all retained snapshots and label-schema state.
     */
    public void clear() {
        snapshots.clear();
    }

    //todo: Verify if this is needed or will be passed
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

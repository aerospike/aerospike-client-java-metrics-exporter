/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import com.aerospike.client.metrics.MetricsSnapshot;

/**
 * Thread-safe holder for the latest immutable client snapshot.
 *
 * <p>Freshness is based on local arrival time rather than a snapshot wall-clock
 * timestamp, avoiding clock-skew and time-zone dependencies.</p>
 */
public final class SnapshotStore {
    private static final class Entry {
        private final MetricsSnapshot snapshot;
        private final long arrivalNanos;

        private Entry(MetricsSnapshot snapshot, long arrivalNanos) {
            this.snapshot = snapshot;
            this.arrivalNanos = arrivalNanos;
        }
    }

    private final SnapshotFreshnessPolicy freshnessPolicy;
    private final LongSupplier nanoClock;
    private final AtomicReference<Entry> latest = new AtomicReference<Entry>();

    /**
     * Create a store with adaptive freshness detection.
     */
    public SnapshotStore() {
        this(SnapshotFreshnessPolicy.automatic(), System::nanoTime);
    }

    /**
     * Create a store with the supplied freshness policy.
     *
     * @param freshnessPolicy policy used to suppress stale snapshots
     */
    public SnapshotStore(SnapshotFreshnessPolicy freshnessPolicy) {
        this(freshnessPolicy, System::nanoTime);
    }

    SnapshotStore(SnapshotFreshnessPolicy freshnessPolicy, LongSupplier nanoClock) {
        if (freshnessPolicy == null) {
            throw new IllegalArgumentException("freshnessPolicy must not be null");
        }
        if (nanoClock == null) {
            throw new IllegalArgumentException("nanoClock must not be null");
        }
        this.freshnessPolicy = freshnessPolicy;
        this.nanoClock = nanoClock;
    }

    /**
     * Replace the retained snapshot and teach the freshness policy the observed
     * delivery interval.
     *
     * @param snapshot latest immutable client snapshot
     */
    public void update(MetricsSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }

        long now = nanoClock.getAsLong();
        Entry previous = latest.get();
        if (previous != null && now >= previous.arrivalNanos) {
            freshnessPolicy.recordInterval(now - previous.arrivalNanos);
        }
        latest.set(new Entry(snapshot, now));
    }

    /**
     * Returns the latest snapshot, or {@code null} when none has arrived or it
     * is stale.
     */
    public MetricsSnapshot currentIfFresh() {
        Entry entry = latest.get();
        if (entry == null) {
            return null;
        }
        return freshnessPolicy.isFresh(entry.arrivalNanos, nanoClock.getAsLong())
            ? entry.snapshot
            : null;
    }

    public boolean isStale() {
        return latest.get() != null && currentIfFresh() == null;
    }

    /**
     * Remove the retained snapshot without resetting the configured policy.
     */
    public void clear() {
        latest.set(null);
    }

    /**
     * Return the policy owned by this store.
     */
    public SnapshotFreshnessPolicy freshnessPolicy() {
        return freshnessPolicy;
    }
}

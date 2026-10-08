/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config;

import java.time.Duration;

import com.aerospike.client.metrics.exporter.common.SnapshotFreshnessPolicy;

/**
 * Parsed configuration for suppressing stale client snapshots.
 */
public final class FreshnessConfig {
    public static final String AUTO = "auto";

    private final String staleTimeout;
    private final Duration fixedTimeout;

    public FreshnessConfig(String staleTimeout) {
        this.staleTimeout = staleTimeout == null ? AUTO : staleTimeout.trim();
        this.fixedTimeout = AUTO.equalsIgnoreCase(this.staleTimeout)
            ? null
            : DurationParser.parse(this.staleTimeout, "snapshot_stale_timeout");
    }

    /**
     * Create a new policy for one client. Policies are not shared because
     * automatic mode learns each client's delivery interval independently.
     */
    public SnapshotFreshnessPolicy createPolicy() {
        if (AUTO.equalsIgnoreCase(staleTimeout)) {
            return SnapshotFreshnessPolicy.automatic();
        }
        return SnapshotFreshnessPolicy.fixed(fixedTimeout);
    }

    /**
     * Return the configured duration text or {@value #AUTO}.
     */
    public String staleTimeout() {
        return staleTimeout;
    }
}

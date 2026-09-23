/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config;

import com.aerospike.client.metrics.exporter.common.SnapshotFreshnessPolicy;

public final class FreshnessConfig {
    public static final String AUTO = "auto";

    private final String staleTimeout;

    public FreshnessConfig(String staleTimeout) {
        this.staleTimeout = staleTimeout == null ? AUTO : staleTimeout.trim();
    }

    public SnapshotFreshnessPolicy createPolicy() {
        if (AUTO.equalsIgnoreCase(staleTimeout)) {
            return SnapshotFreshnessPolicy.automatic();
        }
        return SnapshotFreshnessPolicy.fixed(
            DurationParser.parse(staleTimeout, "snapshot_stale_timeout")
        );
    }

    public String staleTimeout() {
        return staleTimeout;
    }
}

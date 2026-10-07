/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config.reload;

import java.time.Duration;

/**
 * How often the exporter re-reads the files its configuration points at.
 *
 * <p>Both values are startup-only. Changing them in the configuration file has
 * no effect on a running exporter.</p>
 */
public final class ReloadSettings {
    public static final String DEFAULT_ENABLED = "true";
    public static final String DEFAULT_INTERVAL = "5s";

    /** Below a second the digest work stops being free and gains nothing. */
    static final Duration MINIMUM_INTERVAL = Duration.ofSeconds(1);

    private final boolean enabled;
    private final Duration interval;

    public ReloadSettings(boolean enabled, Duration interval) {
        if (interval == null || interval.compareTo(MINIMUM_INTERVAL) < 0) {
            throw new IllegalArgumentException(
                "reload.interval must be at least " + MINIMUM_INTERVAL
            );
        }
        this.enabled = enabled;
        this.interval = interval;
    }

    public boolean enabled() {
        return enabled;
    }

    public Duration interval() {
        return interval;
    }
}

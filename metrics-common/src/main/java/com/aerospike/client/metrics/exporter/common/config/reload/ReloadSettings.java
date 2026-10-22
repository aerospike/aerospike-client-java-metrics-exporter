/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config.reload;

import java.time.Duration;

import com.aerospike.client.metrics.exporter.common.config.ConfigValues;

/**
 * Controls detection of content changes in configured credential files.
 *
 * <p>Both values are startup-only. Changing them in the configuration file has
 * no effect on a running exporter.</p>
 */
public final class ReloadSettings {
    private static final String PREFIX = "aerospike_metrics_exporter.reload.";

    public static final boolean DEFAULT_ENABLED = true;
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(5);

    /** Below a second the digest work stops being free and gains nothing. */
    public static final Duration MINIMUM_INTERVAL = Duration.ofSeconds(1);

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

    /**
     * Resolve settings from final merged configuration values.
     */
    public static ReloadSettings from(ConfigValues values) {
        if (values == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        return new ReloadSettings(
            values.getBoolean(PREFIX + "enabled", DEFAULT_ENABLED),
            values.getDuration(PREFIX + "interval", DEFAULT_INTERVAL)
        );
    }

    public boolean enabled() {
        return enabled;
    }

    public Duration interval() {
        return interval;
    }
}

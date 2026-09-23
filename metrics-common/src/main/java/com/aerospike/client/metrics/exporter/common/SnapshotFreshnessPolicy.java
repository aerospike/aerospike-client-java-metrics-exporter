/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Determines when the latest client snapshot is too old to expose.
 *
 * <p>Automatic mode starts with a 30 second expected interval and considers a
 * snapshot stale after three missed intervals. It adapts using the median of
 * the five most recent non-stale arrival gaps.</p>
 */
public final class SnapshotFreshnessPolicy {
    public static final Duration DEFAULT_EXPECTED_INTERVAL = Duration.ofSeconds(30);
    public static final int DEFAULT_MISSED_INTERVALS = 3;

    private static final int WINDOW_SIZE = 5;

    private final boolean automatic;
    private final long fixedTimeoutNanos;
    private final long initialIntervalNanos;
    private final int missedIntervals;
    private final List<Long> observedIntervals = new ArrayList<Long>(WINDOW_SIZE);

    private volatile long expectedIntervalNanos;

    private SnapshotFreshnessPolicy(
            boolean automatic,
            long fixedTimeoutNanos,
            long initialIntervalNanos,
            int missedIntervals
    ) {
        this.automatic = automatic;
        this.fixedTimeoutNanos = fixedTimeoutNanos;
        this.initialIntervalNanos = initialIntervalNanos;
        this.expectedIntervalNanos = initialIntervalNanos;
        this.missedIntervals = missedIntervals;
    }

    public static SnapshotFreshnessPolicy automatic() {
        return automatic(DEFAULT_EXPECTED_INTERVAL, DEFAULT_MISSED_INTERVALS);
    }

    public static SnapshotFreshnessPolicy automatic(Duration initialInterval, int missedIntervals) {
        requirePositive(initialInterval, "initialInterval");
        if (missedIntervals < 2) {
            throw new IllegalArgumentException("missedIntervals must be at least 2");
        }
        return new SnapshotFreshnessPolicy(
            true,
            0L,
            initialInterval.toNanos(),
            missedIntervals
        );
    }

    public static SnapshotFreshnessPolicy fixed(Duration timeout) {
        requirePositive(timeout, "timeout");
        return new SnapshotFreshnessPolicy(false, timeout.toNanos(), 0L, 1);
    }

    synchronized void recordInterval(long intervalNanos) {
        if (!automatic || intervalNanos <= 0L) {
            return;
        }

        // A stale gap represents a stopped/restarted collection session, not
        // the normal delivery interval. Restart learning from the default.
        if (intervalNanos > staleTimeoutNanos()) {
            observedIntervals.clear();
            expectedIntervalNanos = initialIntervalNanos;
            return;
        }

        if (observedIntervals.size() == WINDOW_SIZE) {
            observedIntervals.remove(0);
        }
        observedIntervals.add(intervalNanos);

        if (!observedIntervals.isEmpty()) {
            List<Long> sorted = new ArrayList<Long>(observedIntervals);
            Collections.sort(sorted);
            int middle = sorted.size() / 2;
            expectedIntervalNanos = sorted.size() % 2 == 0
                ? safeAverage(sorted.get(middle - 1), sorted.get(middle))
                : sorted.get(middle);
        }
    }

    public long staleTimeoutNanos() {
        if (!automatic) {
            return fixedTimeoutNanos;
        }
        if (expectedIntervalNanos > Long.MAX_VALUE / missedIntervals) {
            return Long.MAX_VALUE;
        }
        return expectedIntervalNanos * missedIntervals;
    }

    public Duration staleTimeout() {
        return Duration.ofNanos(staleTimeoutNanos());
    }

    public boolean isFresh(long lastArrivalNanos, long nowNanos) {
        if (lastArrivalNanos == 0L || nowNanos < lastArrivalNanos) {
            return false;
        }
        return nowNanos - lastArrivalNanos <= staleTimeoutNanos();
    }

    private static long safeAverage(long left, long right) {
        return (left / 2L) + (right / 2L) + ((left % 2L + right % 2L) / 2L);
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

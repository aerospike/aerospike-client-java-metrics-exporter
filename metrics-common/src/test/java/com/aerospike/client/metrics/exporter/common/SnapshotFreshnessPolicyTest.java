/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class SnapshotFreshnessPolicyTest {
    @Test
    void learnsMedianFromBoundedObservationWindow() {
        SnapshotFreshnessPolicy policy =
            SnapshotFreshnessPolicy.automatic(Duration.ofSeconds(10), 3);
        policy.recordInterval(Duration.ofSeconds(4).toNanos());
        policy.recordInterval(Duration.ofSeconds(8).toNanos());
        policy.recordInterval(Duration.ofSeconds(6).toNanos());
        assertEquals(Duration.ofSeconds(18), policy.staleTimeout());

        policy.recordInterval(Duration.ofSeconds(2).toNanos());
        policy.recordInterval(Duration.ofSeconds(10).toNanos());
        policy.recordInterval(Duration.ofSeconds(12).toNanos());
        assertEquals(Duration.ofSeconds(24), policy.staleTimeout());
    }

    @Test
    void resetsLearningAfterStaleGap() {
        SnapshotFreshnessPolicy policy =
            SnapshotFreshnessPolicy.automatic(Duration.ofSeconds(10), 3);
        policy.recordInterval(Duration.ofSeconds(2).toNanos());
        assertEquals(Duration.ofSeconds(6), policy.staleTimeout());
        policy.recordInterval(Duration.ofSeconds(7).toNanos());
        assertEquals(Duration.ofSeconds(30), policy.staleTimeout());
    }

    @Test
    void validatesInputsAndClockOrdering() {
        assertThrows(
            IllegalArgumentException.class,
            () -> SnapshotFreshnessPolicy.automatic(Duration.ZERO, 3)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> SnapshotFreshnessPolicy.automatic(Duration.ofSeconds(1), 1)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> SnapshotFreshnessPolicy.fixed(Duration.ofSeconds(-1))
        );

        SnapshotFreshnessPolicy policy =
            SnapshotFreshnessPolicy.fixed(Duration.ofSeconds(1));
        assertFalse(policy.isFresh(0, 1));
        assertFalse(policy.isFresh(2, 1));
        assertTrue(policy.isFresh(1, 1));
    }
}

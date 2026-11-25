/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.metrics.MetricsPolicy;
import com.aerospike.client.metrics.MetricsSnapshot;

import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

@Tag("integration")
class TestIdentityDefaultsIntegration {
    @Test
    void sdkPopulatesExplicitAndDefaultClientIdentity() throws Exception {
        TestAerospikeIntegrationSupport aerospike =
            new TestAerospikeIntegrationSupport();
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider provider =
            SdkMeterProvider.builder().registerMetricReader(reader).build();
        OpenTelemetryMetricsExporter exporter =
            new OpenTelemetryMetricsExporter(provider);
        AerospikeClient explicit = null;
        AerospikeClient defaults = null;
        try {
            explicit = aerospike.newClient("orders-app");
            defaults = aerospike.newClient((String) null);
            MetricsPolicy explicitPolicy =
                aerospike.metricsPolicy(exporter, "explicit", true);
            MetricsPolicy defaultPolicy =
                aerospike.metricsPolicy(exporter, "defaults", true);
            explicit.enableMetrics(explicitPolicy);
            defaults.enableMetrics(defaultPolicy);

            MetricsSnapshot explicitSnapshot = aerospike.awaitSnapshot(
                exporter,
                snapshot -> snapshot.metricsEnabled
                    && "explicit".equals(snapshot.labels.get("client_id"))
            );
            MetricsSnapshot defaultSnapshot = aerospike.awaitSnapshot(
                exporter,
                snapshot -> snapshot.metricsEnabled
                    && "defaults".equals(snapshot.labels.get("client_id"))
            );

            assertEquals("orders-app", explicitSnapshot.appId);
            assertEquals(
                TestAerospikeIntegrationSupport.environment(
                    "AEROSPIKE_USER", "not-set"
                ),
                defaultSnapshot.appId
            );
            assertEquals("java", defaultSnapshot.clientType);
            assertNotNull(defaultSnapshot.clusterName);
            assertFalse(defaultSnapshot.clientVersion.trim().isEmpty());
            assertEquals("defaults", defaultSnapshot.labels.get("client_id"));
        }
        finally {
            if (explicit != null) {
                explicit.disableMetrics();
                explicit.close();
            }
            if (defaults != null) {
                defaults.disableMetrics();
                defaults.close();
            }
            exporter.close();
            provider.close();
        }
    }
}

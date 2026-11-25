/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.prometheus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.metrics.data.Data;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.resources.Resource;

class TestOtelMetricDataConverter {
    @Test
    void logsAndOmitsUnsupportedMetricData() {
        Logger logger = Logger.getLogger(
            OtelMetricDataConverter.class.getName()
        );
        List<LogRecord> records = new ArrayList<LogRecord>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            OtelMetricDataConverter converter =
                new OtelMetricDataConverter(PrometheusConfig.defaults());

            assertEquals(
                0,
                converter.convert(
                    Collections.singletonList(unsupportedSummary())
                ).size()
            );
            assertTrue(records.stream().anyMatch(record ->
                record.getMessage().contains("Unsupported")
            ));
        }
        finally {
            logger.removeHandler(handler);
        }
    }

    private static MetricData unsupportedSummary() {
        return new MetricData() {
            @Override
            public Resource getResource() {
                return Resource.empty();
            }

            @Override
            public InstrumentationScopeInfo getInstrumentationScopeInfo() {
                return InstrumentationScopeInfo.create("unsupported-test");
            }

            @Override
            public String getName() {
                return "unsupported.summary";
            }

            @Override
            public String getDescription() {
                return "unsupported test metric";
            }

            @Override
            public String getUnit() {
                return "1";
            }

            @Override
            public MetricDataType getType() {
                return MetricDataType.SUMMARY;
            }

            @Override
            public Data<?> getData() {
                return null;
            }
        };
    }
}

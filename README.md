# Aerospike Java Client Metrics OpenTelemetry Exporter

This repository provides an optional OpenTelemetry adapter for Aerospike Java
client metrics. It does not add telemetry dependencies to the core Aerospike
client and is not the server-side
[Aerospike Prometheus Exporter](https://github.com/aerospike/aerospike-prometheus-exporter).

## Modules

- `common`: backend-neutral catalog, typed snapshot-to-point mapping,
  attributes, validation, and per-client snapshot registry. A future backend
  can consume the same mapped points without duplicating snapshot traversal.
- `otel`: `OpenTelemetryMetricsExporter`, published as the separate
  `aerospike-client-metrics-otel` artifact.
- `examples`: customer-owned OTLP pipeline and Grafana demonstration.

The Java adapter currently supports OpenTelemetry only. Prometheus or Mimir can
still be used downstream of an OpenTelemetry Collector.

## Dependencies

The adapter is intentionally lightweight. Its OTel API and metrics SDK
dependencies are `provided`; it does not pull an SDK implementation, OTLP
transport, gRPC, OkHttp, or Kotlin into an application.

```xml
<dependency>
  <groupId>com.aerospike</groupId>
  <artifactId>aerospike-client-metrics-otel</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>

<!-- Selected and owned by the application. -->
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-sdk-metrics</artifactId>
  <version>1.49.0</version>
</dependency>
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-exporter-otlp</artifactId>
  <version>1.49.0</version>
</dependency>
```

This version is built and tested with OpenTelemetry Java `1.49.0`. The
artifact's tested compatibility target is `1.49.0`.

## Customer-owned OpenTelemetry setup

The application constructs and owns the OTLP exporter, metric reader, and meter
provider. The Aerospike adapter registers callbacks with that provider.

```java
OtlpGrpcMetricExporter transport = OtlpGrpcMetricExporter.builder()
    .setEndpoint("http://localhost:4317")
    .build();

PeriodicMetricReader reader = PeriodicMetricReader.builder(transport)
    .setInterval(Duration.ofSeconds(30))
    .build();

SdkMeterProvider meterProvider = SdkMeterProvider.builder()
    .registerMetricReader(reader)
    .build();

OpenTelemetryMetricsExporter aerospikeExporter =
    new OpenTelemetryMetricsExporter(meterProvider);

MetricsPolicy metricsPolicy = new MetricsPolicy();
metricsPolicy.exporter = MetricsExporterType.CUSTOM;
metricsPolicy.exportInterval = Duration.ofSeconds(30);
metricsPolicy.enableOperationalMetrics = true;
metricsPolicy.enableUsageMetrics = true;
metricsPolicy.labels = Map.of("client_id", "orders-client");
metricsPolicy.addExporter(aerospikeExporter);

AerospikeClient client =
    new AerospikeClient(new ClientPolicy(), "localhost", 3000);
client.enableMetrics(metricsPolicy);

// Use the client.

client.disableMetrics();
client.close();
aerospikeExporter.close();
meterProvider.close();
```

`client_id` must be a non-blank, stable label. It lets one adapter safely keep
independent state for multiple Aerospike clients.

The adapter never closes the application provider, readers, or OTLP transport.
Closing the adapter unregisters only its observable callbacks. The application
must close the adapter before shutting down its provider.

## Configuration ownership

Configure these settings on the customer-owned OTel objects:

- OTLP endpoint and protocol
- Headers and credentials
- TLS/mTLS and proxy
- Compression, timeout, retry, and reader interval
- Resource and service attributes
- Metric temporality

The adapter has no exporter YAML and does not watch credential or certificate
files. Aerospike client collection settings remain on `MetricsPolicy`, including
the export interval, operational and usage flags, sampler, latency shape,
application labels, and `app_id`.

## Collection and final disable behavior

`export(snapshot)` retains the latest immutable snapshot for each `client_id`.
OTel observable callbacks read that registry when the customer SDK collects.
No backend network call occurs during ordinary Aerospike snapshot updates.

For a final snapshot with `metricsEnabled=false`, the adapter retains the final
values without forcing an OTel collection. The values remain observable until
the client is re-enabled and supplies a newer snapshot, or the adapter closes.
No null or fabricated zero observation is emitted to signal disable.

This retained-value behavior is temporary while final disabled-snapshot
lifecycle and optional flushing are reviewed. OTLP retry, timeout, failure
detection, and transport logging remain the responsibility of the
customer-owned OTel pipeline.

## Metric scope

The adapter follows the current metrics tech specifications:

- Tier 0 cluster, event-loop, and sync/async connection-pool metrics.
- Operational cluster, node, namespace, CM-8 failure, and timeout-split metrics.
- Independent usage counters.
- Separate latency bucket instruments for:
  - successful whole calls at cluster scope
  - successful RPC/sub-batches at node and namespace scope
  - successful connection opens at node scope
- Final `nodes_departed` objects when supplied by the client.

Deferred CM-28/CM-29 instruments and unimplemented latency summary values are
not exported.

## Grafana example

The example flow is:

```text
Java application
  → OTLP
  → OpenTelemetry Collector
  → Prometheus or Mimir
  → Grafana
```

With an Aerospike server available on `localhost:3000`:

```bash
./examples/run.sh
```

Environment variables include `AEROSPIKE_HOST`, `AEROSPIKE_PORT`,
`AEROSPIKE_NAMESPACE`, authentication/TLS settings, and
`OTEL_EXPORTER_OTLP_ENDPOINT`.

The stack exposes:

- Collector Prometheus endpoint: `http://localhost:9464/metrics`
- Prometheus: `http://localhost:9091`
- Grafana: `http://localhost:3004/d/aerospike-client-metrics-example`

## Build and test

The project requires Java 21.

```bash
mvn verify
```

Integration tests are tagged and disabled by default:

```bash
mvn -Pintegration-tests -pl otel -am verify
```

They use `AEROSPIKE_HOST`, `AEROSPIKE_PORT`, `AEROSPIKE_NAMESPACE`,
`AEROSPIKE_USER`, `AEROSPIKE_PASSWORD`, `AEROSPIKE_AUTH_MODE`, and
`AEROSPIKE_TLS_ENABLED`.

## Current client dependency

The exporter is developed against the local Java-client snapshot work. Two
companion client issues must be resolved there:

- `MetricsSnapshotFactory` currently hardcodes `metricsEnabled=true`; it must
  copy the actual cluster state so the final disable snapshot is detectable.
- `LatencyBuckets` currently converts elapsed time to milliseconds even when a
  snapshot advertises microseconds; collection must become unit-aware or reject
  microseconds.
- The current cluster latency fields are assembled from namespace/RPC buckets,
  not measured around the complete public call. The client must populate them
  with whole-call timings that include retries, backoff, and pool waits.

The exporter repository does not modify the client implementation.

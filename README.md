# Aerospike Java Client Metrics Exporter

This repository provides optional OpenTelemetry and Prometheus adapters for
Aerospike Java client metrics. It does not add telemetry dependencies to the
core Aerospike client and is not the server-side
[Aerospike Prometheus Exporter](https://github.com/aerospike/aerospike-prometheus-exporter).

## Modules

- `common`: backend-neutral catalog, typed snapshot-to-point mapping,
  attributes, validation, and per-client snapshot registry. A future backend
  can consume the same mapped points without duplicating snapshot traversal.
- `otel`: `OpenTelemetryMetricsExporter`, published as the separate
  `aerospike-client-metrics-otel` artifact.
- `prometheus`: stable client_java reader and optional HTTP/TLS server,
  published as `aerospike-client-metrics-prometheus`.
- `examples`: customer-owned OTLP/Prometheus pipeline and Grafana
  demonstration.

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
  <version>1.62.0</version>
</dependency>
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-exporter-otlp</artifactId>
  <version>1.62.0</version>
</dependency>
```

For Prometheus customers, use one Aerospike exporter dependency:

```xml
<dependency>
  <groupId>com.aerospike</groupId>
  <artifactId>aerospike-client-metrics-prometheus</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>

<!-- Still selected and owned by the application. -->
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-sdk-metrics</artifactId>
  <version>1.62.0</version>
</dependency>
```

The Prometheus artifact includes the Aerospike OTel artifact transitively, but
does not supply or force an OTel SDK. Stable Prometheus client_java `1.10.0`
is used in production. The official OTel Prometheus alpha exporter is a
test-scope compatibility oracle only and is never shipped.

### OTel compatibility

OTel API and SDK dependencies are `provided` and application-owned. Production
code uses only public stable APIs and is tested with OTel Java `1.31.0`
(minimum) and `1.62.0` (current). Compatibility with versions outside those
tested endpoints is not claimed.

TODO before GA: maintain and publish a Java-client/exporter/OTel compatibility
matrix whenever any of those versions changes.

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

## Prometheus setup

Prometheus-only applications still use an application-owned
`SdkMeterProvider`; they simply register the pull reader instead of an OTLP
reader:

```java
CustomPrometheusMetricReader prometheusReader =
    CustomPrometheusMetricReader.create(
        PrometheusConfig.builder()
            .setBindHost("127.0.0.1")
            .setPort(9464)
            .build()
    );

SdkMeterProvider meterProvider = SdkMeterProvider.builder()
    .registerMetricReader(prometheusReader)
    .build();

OpenTelemetryMetricsExporter aerospikeExporter =
    new OpenTelemetryMetricsExporter(meterProvider);

// Configure MetricsPolicy and enable client metrics as in the OTel example.

// Binding is always explicit; construction never opens a listener.
prometheusReader.startHttpServer();
```

The endpoint is `http://127.0.0.1:9464/metrics` by default. Provider shutdown
stops an exporter-owned server and unregisters its collector. Startup errors,
including a port conflict, are returned to the caller.

An optional metric prefix applies to ordinary metric names but never renames
the standard `target_info` family.

To expose OTLP and Prometheus together, register both readers on the same
provider:

```java
SdkMeterProvider meterProvider = SdkMeterProvider.builder()
    .registerMetricReader(periodicOtlpReader)
    .registerMetricReader(prometheusReader)
    .build();
```

Each reader performs an independent collection and may observe a different
latest Aerospike snapshot generation.

### Application-managed HTTP server

An application can supply and expose its own private registry:

```java
PrometheusRegistry registry = new PrometheusRegistry();
CustomPrometheusMetricReader prometheusReader =
    CustomPrometheusMetricReader.create(
        PrometheusConfig.builder().setRegistry(registry).build()
    );

HTTPServer applicationServer = HTTPServer.builder()
    .hostname("127.0.0.1")
    .port(9464)
    .registry(registry)
    .buildAndStart();
```

Reader/provider shutdown unregisters the Aerospike collector but does not stop
an application-owned server. The application must close that server itself.
The default is always a private registry; the global client_java registry is
never selected implicitly.

### TLS and mTLS

Exporter-managed mode supports PEM certificate chains, unencrypted PKCS#8 or
RSA PKCS#1 private keys, an optional client CA, and TLS version bounds:

```java
TlsConfig tls = TlsConfig.builder()
    .setCertificatePath(Path.of("/run/secrets/server.crt"))
    .setPrivateKeyPath(Path.of("/run/secrets/server.key"))
    .setClientCaPath(Path.of("/run/secrets/client-ca.crt"))
    .setClientCertificateRequired(true)
    .setMinimumVersion("TLSv1.2")
    .setMaximumVersion("TLSv1.3")
    .build();

PrometheusConfig config = PrometheusConfig.builder()
    .setTlsConfig(tls)
    .build();
```

Certificate, key, CA, and protocol validation occurs before the listener is
bound. Applications provide production files. Tests generate temporary
certificates at runtime with OpenSSL; the repository contains no certificate
or key material.

One process can bind a given host/port only once. The embedded endpoint does
not coordinate multiple JVMs or processes; use distinct ports or an external
server/collector for multiprocess deployments. The library installs no signal
handlers.

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

`export(snapshot)` deep-copies the complete client-owned snapshot while that
callback is valid. Only the exporter-owned copy is retained for each
`client_id`. OTel and Prometheus callbacks read that registry when the customer
SDK collects. No backend network call or scrape wait occurs during ordinary
Aerospike snapshot updates.

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
- Operational cluster, node-latency, and currently collected namespace metrics.
- Usage instruments are deferred until client-side CM-29 collection replaces
  the current placeholder zeros.
- Separate latency bucket instruments for:
  - successful whole calls at cluster scope
  - successful RPC/sub-batches at node and namespace scope
  - successful connection opens at node scope
- Final `nodes_departed` objects when supplied by the client.

Deferred CM-28/CM-29 instruments—including placeholder validation, connection
failure, namespace failure, batch-partial-failure, and usage counters—and
unimplemented latency summary values are not exported.

### OpenTelemetry schema alignment

Instrument names use dotted OTel namespaces, for example
`aerospike.client.cluster.command_count`,
`aerospike.client.node.connections_in_use`, and
`aerospike.client.namespace.errors`. Count units use singular UCUM annotations
such as `{command}`, `{connection}`, and `{error}`.

Node attributes are exported as `server_node_id`, `server_node_address`, and
`server_node_port`; namespace attributes use `ns`. These names align with the
Python exporter and the OTel backend schema selected by the Clients team. They
intentionally supersede the underscore metric names and shorter node/namespace
labels currently shown in the exporter tech spec; that specification still
needs a corresponding cross-language update.

Blank namespace snapshots are omitted because a namespace metric without `ns`
would collapse unrelated observations into the same OTel series. If a node is
present in both active and departed lists, the active node wins.

The latest-snapshot model can replace a transient departed-node series before
an OTel reader observes it. This limitation applies to any transient attribute
set; retaining unobserved transient points requires a future lifecycle design.

### Prometheus latency limitation

Current Aerospike latency instruments are observable cumulative counters with
an `le` attribute. The generic reader therefore exposes counter series such as
`aerospike_client_cluster_latency_total{le="..."}`. It does not expose a real
Prometheus histogram family with `_bucket`, `_sum`, and `_count`, so
`histogram_quantile()` is not supported for these Aerospike latency series.

True Prometheus histograms require a separately designed direct
snapshot-to-Prometheus conversion. Aerospike metric names are intentionally not
hardcoded into the generic OTel reader.

OTel summary and exponential-histogram data are currently logged and omitted.
Like the official bridge, long values are represented as Prometheus doubles
and can lose integer precision above `2^53`.

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
- Direct Java Prometheus endpoint: `http://localhost:9465/metrics`
- Prometheus: `http://localhost:9091`
- Grafana: `http://localhost:3004/d/aerospike-client-metrics-example`

## Build and test

The project requires Java 21.

```bash
mvn verify
```

Integration tests are tagged and disabled by default:

```bash
mvn -Pintegration-tests verify
```

They use `AEROSPIKE_HOST`, `AEROSPIKE_PORT`, `AEROSPIKE_NAMESPACE`,
`AEROSPIKE_USER`, `AEROSPIKE_PASSWORD`, `AEROSPIKE_AUTH_MODE`, and
`AEROSPIKE_TLS_ENABLED`. Most tests expect that Aerospike server to be
available. Docker is also required for the isolated server-loss test and the
Testcontainers-based OTLP gRPC/HTTP Collector tests. The departed-node
assertion in the server-loss test remains disabled until client-java CM-28
populates departed snapshots.

Prometheus integration tests include real HTTP scraping, lifecycle and port
conflicts, dual OTel/Prometheus readers, and TLS/mTLS. OpenSSL is required for
the TLS tests; they skip with an explicit message when it is unavailable.

The OTel compatibility endpoints can be checked with:

```bash
mvn -pl prometheus -am test -Dopentelemetry.version=1.31.0
mvn -pl prometheus -am test -Dopentelemetry.version=1.62.0
```

## Current client dependency

The exporter is developed against the local Java-client snapshot work. Two
companion client issues must be resolved there:

- `LatencyBuckets` currently converts elapsed time to milliseconds even when a
  snapshot advertises microseconds; collection must become unit-aware or reject
  microseconds.
- The current cluster latency fields are assembled from namespace/RPC buckets,
  not measured around the complete public call. The client must populate them
  with whole-call timings that include retries, backoff, and pool waits.

The exporter repository does not modify the client implementation.

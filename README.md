# Aerospike Client Java Metrics Exporter

**Work in progress.** Optional Java library that exports client-side metrics from
[aerospike-client-java](https://github.com/aerospike/aerospike-client-java).
Add it as a separate dependency; it is not bundled in the core client JAR.

This is not the server-side [aerospike-prometheus-exporter](https://github.com/aerospike/aerospike-prometheus-exporter).

## Modules

- `aerospike-client-metrics-common`: shared snapshot freshness, metric catalog,
  configuration and TLS utilities. It is pulled transitively.
- `aerospike-client-metrics-otel`: OTLP push over gRPC or HTTP/protobuf.
- `aerospike-client-metrics-prometheus`: standalone Prometheus scrape endpoint
  using the official Prometheus Java client.

OTel and Prometheus dependencies are isolated. Applications add only the
exporter they use:

```xml
<dependency>
  <groupId>com.aerospike</groupId>
  <artifactId>aerospike-client-metrics-otel</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

or:

```xml
<dependency>
  <groupId>com.aerospike</groupId>
  <artifactId>aerospike-client-metrics-prometheus</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

The application must also provide the matching `aerospike-client-jdk21`
version. The exporter declares the client API as `provided` to avoid pulling a
second client implementation.

## Application setup

Create one exporter per backend and share it across the process. Every client
must supply a unique `client_id` through `MetricsPolicy.labels`; the exporter
uses that existing snapshot label to keep each client's measurements separate.

```java
OtelMetricsExporter sharedExporter = OtelMetricsExporter.builder()
    .endpoint("https://collector.example.com:4317")
    .protocol(OtelExporterConfig.Protocol.GRPC)
    .serviceName("orders-service")
    .build();

MetricsPolicy primaryPolicy = new MetricsPolicy();
primaryPolicy.labels = Collections.singletonMap("client_id", "primary");
primaryPolicy.addExporter(sharedExporter);

MetricsPolicy reportingPolicy = new MetricsPolicy();
reportingPolicy.labels = Collections.singletonMap("client_id", "reporting");
reportingPolicy.addExporter(sharedExporter);

primaryClient.enableMetrics(primaryPolicy);
reportingClient.enableMetrics(reportingPolicy);

try {
    // Application work.
}
finally {
    primaryClient.disableMetrics();
    reportingClient.disableMetrics();
    primaryClient.close();
    reportingClient.close();
    sharedExporter.close();
}
```

The same sharing and lifecycle apply to Prometheus; one exporter owns one
registry, HTTP server, and port:

```java
PrometheusMetricsExporter exporter = PrometheusMetricsExporter.builder()
    .bindAddress("0.0.0.0")
    .port(9146)
    .path("/metrics")
    .build();
```

An application that already owns OpenTelemetry can reuse it. The exporter will
register instruments but will not close the customer-owned provider:

```java
OtelMetricsExporter exporter = OtelMetricsExporter.builder()
    .openTelemetry(openTelemetry)
    .build();
```

Choose `client_id` values that are nonblank, unique per live Aerospike client,
stable across disable/re-enable, and drawn from a bounded set. Do not generate a
new value for every snapshot. A shared OTel exporter owns one meter/instrument
pipeline, while a shared Prometheus exporter owns one scrape endpoint. Close a
shared exporter only once, after every client using it has disabled metrics or
closed. The exporter intentionally does not impose a maximum or evict stale
client IDs; every new ID remains registered until the exporter closes, so an
unbounded ID source causes unbounded registry and metric-cardinality growth.

All `MetricsPolicy.labels` are client-wide dimensions applied to every metric.
Use stable, low-cardinality names such as `environment`, `region` or `team`;
do not use request IDs or metric-specific dimensions. Custom label names must
match `[a-zA-Z_][a-zA-Z0-9_]*`, while values may contain punctuation such as
dots and hyphens. The exporter reserves `cluster`, legacy `cluster_name`,
`client_type`, `client_version`, `app_id`, `node`, `node_address`, `node_port`,
`conn_type`, `namespace` and the latency bucket label `le`. The canonical
cluster identity exported from `MetricsSnapshot.clusterName` is `cluster`.

## Configuration

Configuration sources are applied in this order:

```text
explicit builder values
> Java system properties
> environment variables
> exporter YAML
> defaults
```

Client collection settings such as metrics enablement, collection interval,
extended metrics, labels and histogram shape remain owned by
`aerospike-client-java`.

Example exporter YAML:

```yaml
aerospike_metrics_exporter:
  # "auto" starts with a 30s expected snapshot interval and a 90s stale
  # timeout, then adapts to observed snapshot delivery intervals. If the
  # client's MetricsPolicy.interval exceeds 90s, use a fixed timeout that is
  # typically at least 3x that interval (for example, 120s -> 6m).
  snapshot_stale_timeout: auto

  otel:
    service_name: orders-service
    endpoint: https://collector.example.com:4317
    protocol: grpc
    timeout: 10s
    export_interval: 60s
    compression: gzip
    temporality: cumulative
    headers:
      authorization: ${OTEL_TOKEN}
    tls:
      ca_file: /etc/certs/collector-ca.pem
      client_cert_file: /etc/certs/client.pem
      client_key_file: /etc/certs/client-key.pem
      client_key_password: file:/etc/secrets/client-key-password

  prometheus:
    bind_address: 0.0.0.0
    port: 9146
    path: /metrics
    http_timeout: 10s
    basic_auth:
      username: metrics
      password: ${PROMETHEUS_PASSWORD}
    tls:
      enabled: true
      cert_file: /etc/certs/server.pem
      key_file: /etc/certs/server-key.pem
      key_password: file:/etc/secrets/server-key-password
      client_auth: required
      client_ca_file: /etc/certs/prometheus-ca.pem
```

Load it with:

```java
OtelMetricsExporter exporter = OtelMetricsExporter.builder()
    .fromYaml(Paths.get("/etc/aerospike/metrics-exporter.yml"))
    .build();
```

The OTel module supports standard `OTEL_*` environment variables and
corresponding `otel.*` system properties for endpoint, protocol, headers,
timeout, export interval, compression, temporality and TLS. Prometheus uses the
`AEROSPIKE_METRICS_EXPORTER_PROMETHEUS_*` environment prefix and
`aerospike.metrics.exporter.prometheus.*` system-property prefix.

For HTTP/protobuf, the generic `OTEL_EXPORTER_OTLP_ENDPOINT` is a base URL and
the exporter appends `/v1/metrics`. The metrics-specific
`OTEL_EXPORTER_OTLP_METRICS_ENDPOINT`, Java builder endpoint and YAML endpoint
are complete metrics URLs and are used unchanged.

OTLP proxying is supported for HTTP/protobuf only. TLS uses the JVM trust store
unless a CA file is provided. A client certificate and key enable mTLS.
Insecure certificate verification is intentionally unsupported.

The Prometheus `http_timeout` closes a scrape exchange that exceeds its
deadline. Prometheus collector traversal is an in-process, non-cancellable API,
so closing the exchange prevents a late response but does not forcibly
interrupt collection already running on the handler thread.

## Snapshot freshness

`IMetricsExporter` has no disable callback. The exporter therefore learns the
snapshot delivery interval and stops emitting Aerospike measurements after
three missed intervals. The client-side `MetricsPolicy.interval` defaults to
30 seconds:

```text
initial expected interval: 30s
initial stale timeout:     90s
```

Use `snapshot_stale_timeout: auto` (default) or set a fixed duration such as
`2m`. Automatic mode can learn a delivery interval only when the next snapshot
arrives within its current stale timeout. If `MetricsPolicy.interval` exceeds
the initial 90-second timeout, configure a fixed timeout that is typically at
least three times the client interval; for example, use `6m` for a 120-second
client interval. The OTel `export_interval` controls OTLP collection/export and
does not control snapshot freshness.

When stale, OTel observes nothing and Prometheus omits the series; neither
backend publishes false zero values. The next snapshot automatically resumes
export. Per-client stores are retained rather than periodically evicted, so a
client that disables and later re-enables metrics with the same `client_id`
reuses its store.

## Current schema baseline

The implementation maps the `MetricsSnapshot` fields currently present in the
local Java client changes and ignores reserved fields that are always zero.
The shared catalog isolates names and field mappings so the exporter can adopt
the unfinished Metrics v3 specification without rewriting transport,
configuration or freshness code.

Prometheus converts the current exclusive latency bucket arrays into cumulative
`le` buckets and emits `_count`. It does not invent a `_sum`.

OTel exports the same cumulative `le` values as observable counters using the
current operation-specific bucket names, such as
`aerospike_client_namespace_latency_read_bucket`. These counters are not native
OTel histograms, and the exporter does not fabricate raw observations or a
`_sum`. Migration to the draft Metrics v3 unified
`aerospike_client_latency_bucket` schema is deferred with the other schema
changes.

Clients sharing either exporter should use the same histogram type, latency
unit, latency base and bucket count. The exporter does not enforce this
constraint; mixing schemas produces different `le` boundaries under the same
metric family and makes cross-client aggregation misleading.

## Tests

Fast unit and self-contained component tests run with:

```bash
mvn verify
```

Live integration tests belong to their backend modules and are opt-in:

```bash
mvn -Pintegration-tests -pl metrics-prometheus -am verify
mvn -Pintegration-tests -pl metrics-otel -am verify
```

Both use an existing Aerospike server configured through
`AEROSPIKE_HOST` (default `localhost`), `AEROSPIKE_PORT` (default `3000`),
`AEROSPIKE_NAMESPACE` (default `test`), and optional `AEROSPIKE_SET`,
`AEROSPIKE_USER`, `AEROSPIKE_PASSWORD`, `AEROSPIKE_AUTH_MODE`, and
`AEROSPIKE_TLS_ENABLED`. For TLS, encode the TLS name in the host seed as
`host:tls-name:port` and configure the JVM trust store using
`javax.net.ssl.trustStore` and `javax.net.ssl.trustStorePassword`.

The OTel integration test starts only a pinned OTel Collector with
Testcontainers. The Prometheus integration test scrapes the exporter's embedded
endpoint directly. Neither module integration test starts Grafana.

## Exporter example

The `examples` module is one executable application that can use OTel,
Prometheus or both exporters. It depends on both exporter modules so that the
complete stack remains in one place; applications should use only the
backend-specific dependency shown in [Modules](#modules).

Run one backend directly with Maven:

```bash
mvn -pl examples -am install -DskipTests
mvn -pl examples exec:java \
  -Dexec.args="--exporter=otel --config=examples/config/aerospike-metrics-exporter.yml"
mvn -pl examples exec:java \
  -Dexec.args="--exporter=prometheus --config=examples/config/aerospike-metrics-exporter.yml"
```

Supported exporter modes are `otel`, `prometheus` and `both`; `both` is the
default. Use `--duration=<seconds>` to change the default 60-second workload.
Omit `--config` to resolve exporter settings from Java system properties,
environment variables and defaults. The application uses the same
`AEROSPIKE_*` connection settings described above.

The included stack runner uses `both` and sends the same client snapshots
through both supported paths:

```text
Java client -> direct Prometheus endpoint -> Prometheus
Java client -> OTLP -> OTel Collector -> Prometheus
```

Prerequisites:

- Docker Desktop with Docker Compose.
- Maven and Java 21.
- The locally modified `aerospike-client-jdk21:10.4.0` installed in this
  repository's `.m2` directory.
- A reachable Aerospike server. It defaults to `localhost:3000` with namespace
  `test`; use the same `AEROSPIKE_*` settings described above for another
  server.

Run the complete smoke test from the repository root:

```bash
bash examples/run.sh
```

The script builds the example, starts an isolated OTel Collector, Prometheus
and Grafana, generates Aerospike put/get traffic, and verifies:

- Direct metrics at `http://localhost:9146/metrics`.
- OTLP-delivered metrics at `http://localhost:9464/metrics`.
- Both Prometheus scrape jobs and Collector debug logs.

The isolated containers and volumes are removed after a successful or failed
run. Existing Aerospike, Prometheus, and Grafana containers are not changed.

To keep the isolated observability stack available for inspection and run the
workload for five minutes:

```bash
KEEP_EXAMPLE_STACK=true EXAMPLE_DURATION_SECONDS=300 bash examples/run.sh
```

Open:

- Prometheus: `http://localhost:9091`
- Grafana dashboard:
  `http://localhost:3004/d/aerospike-client-metrics-example`

Useful Prometheus queries:

```promql
aerospike_client_total_nodes{job="aerospike-client-direct"}
aerospike_client_total_nodes{job="aerospike-client-via-otel"}
aerospike_client_open_connections
rate(aerospike_client_command_count_total[1m])
```

Stop a retained stack with:

```bash
docker compose \
  --project-name aerospike-metrics-exporter-example \
  --file examples/stack/docker-compose.yml \
  down --volumes
```

If the Java workload cannot compile, reinstall the modified local client into
`.m2`. If direct metrics fail, check that port `9146` is free. For the OTLP
path, inspect:

```bash
docker compose \
  --project-name aerospike-metrics-exporter-example \
  --file examples/stack/docker-compose.yml \
  logs otel-collector
```

## Branches

Development follows aerospike-client-java. Do not push or open PRs against `main`.

| Branch | Role |
| --- | --- |
| `stage` | JDK 21 development (default integration branch) |
| `stage-jdk8` | JDK 8 development |
| `main` | Protected; not for daily commits (release stand-in until a `master` cut) |

## Get the source

    git clone https://github.com/aerospike/aerospike-client-java-metrics-exporter.git
    cd aerospike-client-java-metrics-exporter
    git checkout stage

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE).

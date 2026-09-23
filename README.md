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

```java
OtelMetricsExporter exporter = OtelMetricsExporter.builder()
    .endpoint("https://collector.example.com:4317")
    .protocol(OtelExporterConfig.Protocol.GRPC)
    .serviceName("orders-service")
    .build();

MetricsPolicy policy = new MetricsPolicy();
policy.addExporter(exporter);
client.enableMetrics(policy);

try {
    // Application work.
}
finally {
    client.disableMetrics();
    exporter.close();
}
```

Prometheus setup differs only in construction:

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

## Configuration

Configuration is startup-only in v1. Sources are applied in this order:

```text
explicit builder values
> Java system properties
> environment variables
> exporter YAML
> defaults
```

There is no YAML watcher, dynamic reload or certificate hot reload. Client
collection settings such as metrics enablement, collection interval, extended
metrics, labels and histogram shape remain owned by `aerospike-client-java`.

Example exporter YAML:

```yaml
aerospike_metrics_exporter:
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

OTLP proxying is supported for HTTP/protobuf only. TLS uses the JVM trust store
unless a CA file is provided. A client certificate and key enable mTLS.
Insecure certificate verification is intentionally unsupported.

## Snapshot freshness

`IMetricsExporter` has no disable callback. The exporter therefore learns the
snapshot delivery interval and stops emitting Aerospike measurements after
three missed intervals:

```text
initial expected interval: 30s
initial stale timeout:     90s
```

Use `snapshot_stale_timeout: auto` (default) or set a fixed duration such as
`2m`. When stale, OTel observes nothing and Prometheus omits the series; neither
backend publishes false zero values. The next snapshot automatically resumes
export.

## Current schema baseline

The implementation maps the `MetricsSnapshot` fields currently present in the
local Java client changes and ignores reserved fields that are always zero.
The shared catalog isolates names and field mappings so the exporter can adopt
the unfinished Metrics v3 specification without rewriting transport,
configuration or freshness code.

Prometheus converts the current exclusive latency bucket arrays into cumulative
`le` buckets and emits `_count`. It does not invent a `_sum`. Native OTel
pre-aggregated histogram mapping remains isolated until its representation is
approved; raw observations are never fabricated from bucket boundaries.

## End-to-end test

The E2E example sends the same client snapshots through both supported paths:

```text
Java client -> direct Prometheus endpoint -> Prometheus
Java client -> OTLP -> OTel Collector -> Prometheus
```

Prerequisites:

- Docker Desktop with Docker Compose.
- Maven and Java 21.
- The locally modified `aerospike-client-jdk21:10.4.0` installed in this
  repository's `.m2` directory.
- A running container named `aerospike-server-enterprise`, reachable at
  `localhost:3000`, with namespace `test`.

Run the complete smoke test from the repository root:

```bash
./examples/e2e/run-e2e.sh
```

The script builds the example, starts an isolated OTel Collector, Prometheus,
and Grafana, generates Aerospike put/get traffic, and verifies:

- Direct metrics at `http://localhost:9146/metrics`.
- OTLP-delivered metrics at `http://localhost:9464/metrics`.
- Both Prometheus scrape jobs and Collector debug logs.

The isolated containers and volumes are removed after a successful or failed
run. Existing Aerospike, Prometheus, and Grafana containers are not changed.

To keep the isolated observability stack available for inspection and run the
workload for five minutes:

```bash
KEEP_E2E_STACK=true E2E_DURATION_SECONDS=300 ./examples/e2e/run-e2e.sh
```

Open:

- Prometheus: `http://localhost:9091`
- Grafana dashboard:
  `http://localhost:3004/d/aerospike-client-metrics-e2e`

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
  --project-name aerospike-metrics-exporter-e2e \
  --file examples/e2e/docker-compose.yml \
  down --volumes
```

If the Java workload cannot compile, reinstall the modified local client into
`.m2`. If direct metrics fail, check that port `9146` is free. For the OTLP
path, inspect:

```bash
docker compose \
  --project-name aerospike-metrics-exporter-e2e \
  --file examples/e2e/docker-compose.yml \
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

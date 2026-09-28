#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_PROJECT="aerospike-metrics-exporter-e2e"
CONFIG_FILE="${SCRIPT_DIR}/aerospike-metrics-exporter.yml"
MAVEN_REPO="${REPO_ROOT}/.m2"
E2E_DURATION_SECONDS="${E2E_DURATION_SECONDS:-45}"
KEEP_E2E_STACK="${KEEP_E2E_STACK:-false}"
APP_LOG="$(mktemp -t aerospike-metrics-e2e.XXXXXX.log)"
APP_PID=""

COMPOSE=(
  docker compose
  --project-name "${COMPOSE_PROJECT}"
  --file "${SCRIPT_DIR}/docker-compose.yml"
)
MAVEN=(
  mvn
  "-Dmaven.repo.local=${MAVEN_REPO}"
)

cleanup() {
  local exit_code=$?

  if [[ -n "${APP_PID}" ]] && kill -0 "${APP_PID}" 2>/dev/null; then
    kill "${APP_PID}" 2>/dev/null || true
    wait "${APP_PID}" 2>/dev/null || true
  fi

  if [[ "${KEEP_E2E_STACK}" == "true" ]]; then
    echo "E2E stack left running:"
    echo "  Prometheus: http://localhost:9091"
    echo "  Grafana:    http://localhost:3004/d/aerospike-client-metrics-e2e"
    echo "Stop it with: ${COMPOSE[*]} down --volumes"
  else
    "${COMPOSE[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi

  if [[ ${exit_code} -ne 0 ]]; then
    echo "Java workload output:"
    cat "${APP_LOG}" || true
  fi
  rm -f "${APP_LOG}"
  exit "${exit_code}"
}
trap cleanup EXIT INT TERM

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

app_is_running() {
  [[ -n "${APP_PID}" ]] && kill -0 "${APP_PID}" 2>/dev/null
}

wait_for_url() {
  local description=$1
  local url=$2
  local attempts=${3:-60}

  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if curl --fail --silent --show-error "${url}" >/dev/null 2>&1; then
      return 0
    fi
    if [[ -n "${APP_PID}" ]] && ! app_is_running; then
      echo "Java workload stopped while waiting for ${description}" >&2
      return 1
    fi
    sleep 1
  done
  echo "Timed out waiting for ${description}: ${url}" >&2
  return 1
}

wait_for_metric() {
  local description=$1
  local url=$2
  local metric=$3
  local attempts=${4:-60}

  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if curl --fail --silent "${url}" 2>/dev/null |
        grep --fixed-strings "${metric}" >/dev/null; then
      return 0
    fi
    if ! app_is_running; then
      echo "Java workload stopped while waiting for ${description}" >&2
      return 1
    fi
    sleep 1
  done
  echo "Timed out waiting for ${description}" >&2
  return 1
}

prometheus_query_has_result() {
  local query=$1
  curl --fail --silent --get \
    --data-urlencode "query=${query}" \
    "http://localhost:9091/api/v1/query" |
    python3 -c 'import json, sys; result=json.load(sys.stdin); sys.exit(0 if result.get("data", {}).get("result") else 1)'
}

wait_for_prometheus_query() {
  local description=$1
  local query=$2
  local attempts=${3:-60}

  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if prometheus_query_has_result "${query}"; then
      return 0
    fi
    if ! app_is_running; then
      echo "Java workload stopped while waiting for ${description}" >&2
      return 1
    fi
    sleep 1
  done
  echo "Timed out waiting for ${description}: ${query}" >&2
  return 1
}

require_command docker
require_command mvn
require_command curl
require_command python3
docker info >/dev/null
"${COMPOSE[@]}" config --quiet

if ! docker ps --format '{{.Names}}' |
    grep --fixed-strings --line-regexp "aerospike-server-enterprise" >/dev/null; then
  echo "Expected the existing aerospike-server-enterprise container to be running" >&2
  exit 1
fi

echo "Building the E2E application"
(
  cd "${REPO_ROOT}"
  "${MAVEN[@]}" -q -pl examples/e2e-example -am install -DskipTests
)

echo "Starting isolated Collector, Prometheus, and Grafana"
"${COMPOSE[@]}" up --detach
wait_for_url "Collector Prometheus endpoint" "http://localhost:9464/metrics"
wait_for_url "Prometheus" "http://localhost:9091/-/ready"
wait_for_url "Grafana" "http://localhost:3004/api/health"
wait_for_url \
  "provisioned Grafana dashboard" \
  "http://localhost:3004/api/dashboards/uid/aerospike-client-metrics-e2e"

echo "Starting the dual-exporter Aerospike workload"
(
  cd "${REPO_ROOT}"
  "${MAVEN[@]}" -q -pl examples/e2e-example exec:java \
    -Dexec.args="${CONFIG_FILE} ${E2E_DURATION_SECONDS}"
) >"${APP_LOG}" 2>&1 &
APP_PID=$!

wait_for_metric \
  "direct Prometheus exporter metrics" \
  "http://localhost:9146/metrics" \
  "aerospike_client_total_nodes"
wait_for_metric \
  "direct primary client metrics" \
  "http://localhost:9146/metrics" \
  'client_id="primary"'
wait_for_metric \
  "direct reporting client metrics" \
  "http://localhost:9146/metrics" \
  'client_id="reporting"'
echo "Verified direct Prometheus exporter flow"

wait_for_metric \
  "OTel metrics exposed by the Collector" \
  "http://localhost:9464/metrics" \
  "aerospike_client_total_nodes"
wait_for_metric \
  "OTel primary client metrics" \
  "http://localhost:9464/metrics" \
  'client_id="primary"'
wait_for_metric \
  "OTel reporting client metrics" \
  "http://localhost:9464/metrics" \
  'client_id="reporting"'
echo "Verified OTel exporter -> Collector flow"

wait_for_prometheus_query \
  "direct Prometheus scrape" \
  'aerospike_client_total_nodes{job="aerospike-client-direct",client_id="primary"}'
wait_for_prometheus_query \
  "direct Prometheus reporting client scrape" \
  'aerospike_client_total_nodes{job="aerospike-client-direct",client_id="reporting"}'
wait_for_prometheus_query \
  "Collector Prometheus scrape" \
  'aerospike_client_total_nodes{job="aerospike-client-via-otel",client_id="primary"}'
wait_for_prometheus_query \
  "Collector Prometheus reporting client scrape" \
  'aerospike_client_total_nodes{job="aerospike-client-via-otel",client_id="reporting"}'
echo "Verified both Prometheus scrape jobs"

if ! "${COMPOSE[@]}" logs otel-collector |
    grep --fixed-strings "aerospike_client_total_nodes" >/dev/null; then
  echo "Collector debug logs did not contain the expected Aerospike metric" >&2
  exit 1
fi
echo "Verified Collector debug output"

wait "${APP_PID}"
APP_PID=""
cat "${APP_LOG}"

echo "E2E verification passed"
echo "  Prometheus: http://localhost:9091"
echo "  Grafana:    http://localhost:3004/d/aerospike-client-metrics-e2e"

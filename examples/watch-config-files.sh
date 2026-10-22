#!/usr/bin/env bash
#
# Starts the Prometheus exporter and logs stable content changes to its
# certificate, key, and password files. This demonstrates detection only:
# the running exporter continues to use the values loaded at startup.
#
# No Aerospike server or Docker environment is required.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
WORK="${REPO_ROOT}/target/config-watch-example"
MAVEN_REPO="${REPO_ROOT}/.m2"
CONFIG="${WORK}/aerospike-metrics-exporter.yml"

MAVEN=(mvn -q "-Dmaven.repo.local=${MAVEN_REPO}")

mkdir -p "${WORK}"

if [[ ! -f "${WORK}/ca.pem" ]]; then
  echo "Generating a test CA and two server certificates in ${WORK}"
  openssl req -x509 -newkey rsa:2048 -nodes -days 36500 \
    -subj "/CN=aerospike-metrics-config-watch-ca" \
    -keyout "${WORK}/ca-key.pem" -out "${WORK}/ca.pem" 2>/dev/null
  for name in a b; do
    openssl req -newkey rsa:2048 -nodes -subj "/CN=localhost" \
      -keyout "${WORK}/server-${name}-key.pem" \
      -out "${WORK}/server-${name}.csr" 2>/dev/null
    openssl x509 -req -in "${WORK}/server-${name}.csr" \
      -CA "${WORK}/ca.pem" -CAkey "${WORK}/ca-key.pem" -CAcreateserial -days 36500 \
      -out "${WORK}/server-${name}.pem" \
      -extfile <(printf "subjectAltName=DNS:localhost,IP:127.0.0.1") 2>/dev/null
  done
  rm -f "${WORK}"/*.csr "${WORK}/ca.srl"
fi

# Start on certificate A so there is something to rotate away from.
cp "${WORK}/server-a.pem" "${WORK}/server.pem"
cp "${WORK}/server-a-key.pem" "${WORK}/server-key.pem"
printf 'first-password' > "${WORK}/password"

cat > "${CONFIG}" <<YAML
aerospike_metrics_exporter:
  snapshot_stale_timeout: auto

  # Two seconds keeps the example quick. A change is reported once it has
  # remained stable for another interval, so expect it within about four seconds.
  reload:
    enabled: true
    interval: 2s

  prometheus:
    bind_address: 127.0.0.1
    port: 9146
    path: /metrics
    basic_auth:
      username: metrics
      password: file:${WORK}/password
    tls:
      enabled: true
      cert_file: ${WORK}/server.pem
      key_file: ${WORK}/server-key.pem
YAML

echo "Building the active examples module"
(
  cd "${REPO_ROOT}"
  "${MAVEN[@]}" -DskipTests -pl examples -am install
  "${MAVEN[@]}" -pl examples dependency:build-classpath \
    "-Dmdep.outputFile=${WORK}/classpath.txt"
)

cat <<EOF

Try any of these in another terminal:

  # rotate the password
  printf 'second-password' > ${WORK}/password

  # rotate the certificate and key
  cp ${WORK}/server-b.pem ${WORK}/server.pem
  cp ${WORK}/server-b-key.pem ${WORK}/server-key.pem

The watcher logs stable file changes. It does not reload the running exporter.
The watched paths were resolved once at startup. YAML and path changes are not watched.
The endpoint therefore keeps serving the certificate and password loaded at startup:

  curl -sk -u metrics:first-password https://127.0.0.1:9146/metrics

EOF

exec java \
  -cp "${REPO_ROOT}/examples/target/classes:$(cat "${WORK}/classpath.txt")" \
  com.aerospike.examples.metrics.PrometheusConfigWatchExample "${CONFIG}"

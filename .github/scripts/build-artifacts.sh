#!/usr/bin/env bash
# Build the Maven artifacts that the shared artifacts CI/CD workflow signs and
# deploys to JFrog: the parent POM, plus the jar and POM of each published module.
#
# Required environment:
#   VERSION     Maven version to publish, for example 0.0.1-pr10-1a2b3c4
#   CLIENT_REF  aerospike-client-java commit that provides the metrics exporter API
#
# The script rewrites the project version in every POM, so run it on a
# throwaway checkout. Artifacts are written to dist/.
set -euo pipefail

: "${VERSION:?VERSION must be set}"
: "${CLIENT_REF:?CLIENT_REF must be set}"

MODULES=(common otel)
DIST_DIR=dist
CLIENT_DIR="${RUNNER_TEMP:-target}/aerospike-client-java"

echo "Building aerospike-client-java at ${CLIENT_REF}"
rm -rf "${CLIENT_DIR}"
git init --quiet "${CLIENT_DIR}"
git -C "${CLIENT_DIR}" fetch --quiet --depth 1 \
  https://github.com/aerospike/aerospike-client-java.git "${CLIENT_REF}"
git -C "${CLIENT_DIR}" checkout --quiet FETCH_HEAD
mvn -B -ntp -f "${CLIENT_DIR}/pom.xml" -pl client -am install -DskipTests

echo "Packaging version ${VERSION}"
mvn -B -ntp versions:set -DnewVersion="${VERSION}" -DgenerateBackupPoms=false
module_list=$(
  IFS=,
  echo "${MODULES[*]}"
)
mvn -B -ntp -pl "${module_list}" -am package -DskipTests

rm -rf "${DIST_DIR}"
mkdir -p "${DIST_DIR}"

parent_id=$(mvn -B -q -N help:evaluate -Dexpression=project.artifactId -DforceStdout)
cp pom.xml "${DIST_DIR}/${parent_id}-${VERSION}.pom"

for module in "${MODULES[@]}"; do
  jars=("${module}"/target/*-"${VERSION}".jar)
  if [[ ${#jars[@]} -ne 1 || ! -f ${jars[0]} ]]; then
    echo "Expected exactly one ${module} jar for ${VERSION}, found: ${jars[*]}" >&2
    exit 1
  fi
  artifact_id=$(basename "${jars[0]}" "-${VERSION}.jar")
  cp "${jars[0]}" "${DIST_DIR}/"
  cp "${module}/pom.xml" "${DIST_DIR}/${artifact_id}-${VERSION}.pom"
done

echo "Artifacts in ${DIST_DIR}:"
ls -l "${DIST_DIR}"

#!/usr/bin/env bash
# Build the Maven artifacts that the shared artifacts CI/CD workflow signs and
# deploys to JFrog: the jar, sources jar, javadoc jar and POM of each published
# module. Published modules have no parent POM, so nothing else is deployed.
#
# Environment:
#   VERSION     Required. Maven version to publish, for example 0.0.1
#   CLIENT_REF  Optional. aerospike-client-java commit to build and compile against.
#               When unset, the released client named in otel/pom.xml is used.
#
# The script rewrites the version in each published module's POM, so run it on a
# throwaway checkout. Artifacts are written to dist/.
set -euo pipefail

: "${VERSION:?VERSION must be set}"

MODULES=(otel)
DIST_DIR=dist
CLIENT_DIR="${RUNNER_TEMP:-target}/aerospike-client-java"

if [[ -n "${CLIENT_REF:-}" ]]; then
  echo "Building aerospike-client-java at ${CLIENT_REF}"
  rm -rf "${CLIENT_DIR}"
  git init --quiet "${CLIENT_DIR}"
  git -C "${CLIENT_DIR}" fetch --quiet --depth 1 \
    https://github.com/aerospike/aerospike-client-java.git "${CLIENT_REF}"
  git -C "${CLIENT_DIR}" checkout --quiet FETCH_HEAD
  mvn -B -ntp -f "${CLIENT_DIR}/pom.xml" -pl client -am install -DskipTests
else
  echo "Using the released client named in otel/pom.xml"
fi

echo "Packaging version ${VERSION}"
for module in "${MODULES[@]}"; do
  mvn -B -ntp -f "${module}/pom.xml" versions:set -DnewVersion="${VERSION}" -DgenerateBackupPoms=false
done
module_list=$(
  IFS=,
  echo "${MODULES[*]}"
)
mvn -B -ntp -pl "${module_list}" -am package -DskipTests

rm -rf "${DIST_DIR}"
mkdir -p "${DIST_DIR}"

for module in "${MODULES[@]}"; do
  jars=("${module}"/target/*-"${VERSION}".jar)
  if [[ ${#jars[@]} -ne 1 || ! -f ${jars[0]} ]]; then
    echo "Expected exactly one ${module} jar for ${VERSION}, found: ${jars[*]}" >&2
    exit 1
  fi
  artifact_id=$(basename "${jars[0]}" "-${VERSION}.jar")
  cp "${jars[0]}" "${DIST_DIR}/"
  for classifier in sources javadoc; do
    jar="${module}/target/${artifact_id}-${VERSION}-${classifier}.jar"
    if [[ ! -f ${jar} ]]; then
      echo "Missing ${jar}" >&2
      exit 1
    fi
    cp "${jar}" "${DIST_DIR}/"
  done
  # The shared deploy takes coordinates from pom.properties inside each jar and otherwise parses the
  # file name, which misreads versions like 0.0.1-pr10-318adda. Javadoc jars carry no pom.properties.
  pom_properties="${module}/target/maven-archiver/pom.properties"
  group_id=$(sed -n 's/^groupId=//p' "${pom_properties}")
  descriptor_dir=$(mktemp -d)
  mkdir -p "${descriptor_dir}/META-INF/maven/${group_id}/${artifact_id}"
  cp "${pom_properties}" "${descriptor_dir}/META-INF/maven/${group_id}/${artifact_id}/"
  jar uf "${DIST_DIR}/${artifact_id}-${VERSION}-javadoc.jar" -C "${descriptor_dir}" META-INF
  rm -rf "${descriptor_dir}"
  cp "${module}/pom.xml" "${DIST_DIR}/${artifact_id}-${VERSION}.pom"
done

echo "Artifacts in ${DIST_DIR}:"
ls -l "${DIST_DIR}"

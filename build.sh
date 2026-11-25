#!/usr/bin/env sh
# ---------------------------------------------------------------------------
# DrugRunTerritories build script (Linux/macOS)
# Validates Java 25+, compiles, runs every test, and reports the plugin JAR.
# ---------------------------------------------------------------------------
set -eu
cd "$(dirname "$0")"

if [ -n "${JAVA_HOME:-}" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="java"
fi
if ! "$JAVA" -version >/dev/null 2>&1; then
  echo "[build] ERROR: Java was not found. Install JDK 25 and set JAVA_HOME." >&2
  exit 1
fi
MAJOR=$("$JAVA" -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); print v[1]; exit}')
if [ -z "$MAJOR" ] || [ "$MAJOR" -lt 25 ]; then
  echo "[build] ERROR: Java ${MAJOR:-unknown} found; DrugRunTerritories needs Java 25 or newer." >&2
  exit 1
fi
echo "[build] Using Java $MAJOR"

if [ ! -f "$HOME/.m2/repository/dev/aponder/DrugLab/1.0.0/DrugLab-1.0.0.jar" ]; then
  echo "[build] ERROR: DrugLab 1.0.0 is not in your local Maven repository." >&2
  echo "[build]        Run './mvnw install -DskipTests' in the DrugLab project first. See INSTALLATION.md." >&2
  exit 1
fi

if ! ./mvnw -B clean verify "$@"; then
  echo "[build] BUILD FAILED. Test reports are in target/surefire-reports." >&2
  exit 1
fi

JAR="$(pwd)/target/DrugRunTerritories-0.1.0-SNAPSHOT.jar"
if [ ! -f "$JAR" ]; then
  echo "[build] ERROR: Build reported success but the JAR was not produced." >&2
  exit 1
fi
echo "[build] BUILD SUCCEEDED"
echo "[build] Plugin JAR: $JAR"

#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The jar name carries the release it was built from (`dev` for a source build), so match it
# by glob: a second jar in the same directory would make the choice arbitrary, never silent.
shopt -s nullglob
JARS=("$SCRIPT_DIR"/homer-runner/build/libs/homer-runner-*-all.jar)
if [ "${#JARS[@]}" -eq 0 ]; then
  echo "homer-runner JAR not found. Build it first:"
  echo "  cd $SCRIPT_DIR && ./gradlew :homer-runner:shadowJar"
  exit 1
fi
if [ "${#JARS[@]}" -gt 1 ]; then
  echo "more than one homer-runner JAR in homer-runner/build/libs; remove the stale ones:"
  printf '  %s\n' "${JARS[@]}"
  exit 1
fi
exec java --enable-native-access=ALL-UNNAMED -jar "${JARS[0]}" "$@"

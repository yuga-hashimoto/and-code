#!/usr/bin/env bash
# Regenerates THIRD_PARTY_LICENSES/release-dependencies-releaseRuntimeClasspath.txt from the
# actual resolved Gradle dependency graph, so the list backing THIRD_PARTY_NOTICES.md's Gradle
# dependency summary is generated and verifiable rather than hand-maintained. Run this after any
# dependency change and re-check THIRD_PARTY_NOTICES.md against the diff.
set -euo pipefail

cd "$(dirname "$0")/.."

OUTPUT="THIRD_PARTY_LICENSES/release-dependencies-releaseRuntimeClasspath.txt"

./gradlew :app:dependencies --configuration releaseRuntimeClasspath --console=plain \
  | grep -oE '[a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+:[0-9][a-zA-Z0-9_.-]*' \
  | sort -u -t: -k1,2 \
  | sort -u \
  > "$OUTPUT"

echo "Wrote $(wc -l < "$OUTPUT" | tr -d ' ') resolved dependency coordinates to $OUTPUT"

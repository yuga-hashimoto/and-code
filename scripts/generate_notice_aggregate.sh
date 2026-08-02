#!/usr/bin/env bash
# Regenerates THIRD_PARTY_LICENSES/NOTICE-aggregate.txt by extracting the NOTICE files actually
# embedded in the resolved releaseRuntimeClasspath dependency archives. Run this after any
# dependency change and re-copy the output into app/src/main/assets/legal/ (see
# LegalDisclosureComplianceTest's drift check) before committing.
set -euo pipefail

cd "$(dirname "$0")/.."

./gradlew :app:generateNoticeAggregate

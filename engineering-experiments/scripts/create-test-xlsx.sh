#!/usr/bin/env bash
set -euo pipefail

OUTPUT_PATH="${1:-build/test-data/sample.xlsx}"
ROW_COUNT="${2:-100}"

./gradlew -q generateTestXlsx --args="${OUTPUT_PATH} ${ROW_COUNT}"

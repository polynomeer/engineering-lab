#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# The legacy labs keep their own Gradle 8 / Java 21 build boundary.
if [[ -n "${CAREER_LAB_JAVA_HOME:-}" ]]; then
  export JAVA_HOME="$CAREER_LAB_JAVA_HOME"
elif [[ "$(uname -s)" == "Darwin" ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -F -v 21)"
  export JAVA_HOME
fi
exec "$repo_root/career-lab/gradlew" -p "$repo_root/career-lab" "$@"

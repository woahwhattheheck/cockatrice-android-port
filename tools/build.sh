#!/usr/bin/env bash
# Build the native Android host. Runtime binaries remain external build inputs.
set -euo pipefail
project="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project"
if [[ ! -f app/build/generated/standalone-assets/runtime.json ]]; then
    python3 tools/prepare_runtime.py
fi
if [[ -z "${JAVA_HOME:-}" && -n "${PREFIX:-}" && -d "$PREFIX/lib/jvm/java-17-openjdk" ]]; then
    export JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk"
fi
options=(--no-daemon --console=plain)
if [[ "${PREFIX:-}" == *com.termux* ]] && command -v aapt2 >/dev/null 2>&1; then
    options+=("-Pandroid.aapt2FromMavenOverride=$(command -v aapt2)")
fi
bash ./gradlew assembleRelease "${options[@]}" "$@"

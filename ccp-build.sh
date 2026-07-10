#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

gradle \
  -b "$SCRIPT_DIR/build.local.gradle.kts" \
  buildLocalPlugin \
  --no-daemon \
  --no-configuration-cache \
  --no-build-cache \
  "-Dorg.gradle.configuration-cache=false" \
  "-Dorg.gradle.caching=false"

PLUGIN_VERSION="$(grep -E '^pluginVersion[[:space:]]*=' "$SCRIPT_DIR/gradle.properties" | sed -E 's/^[^=]+=[[:space:]]*//')"
echo "Built: $SCRIPT_DIR/build/distributions/devspaces-gateway-plugin-${PLUGIN_VERSION}.zip"

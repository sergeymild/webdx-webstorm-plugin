#!/usr/bin/env bash
#
# Build the WebDX plugin and install it into the locally-installed WebStorm.
# After this finishes, just restart WebStorm and the plugin is active.
#
# Usage:
#   ./install-to-webstorm.sh                 # auto-detect newest WebStorm config dir
#   WEBSTORM_CONFIG_DIR=... ./install-to-webstorm.sh   # force a specific config dir
#
set -euo pipefail

cd "$(dirname "$0")"

JETBRAINS_DIR="$HOME/Library/Application Support/JetBrains"

# 1. Find the WebStorm config directory (where per-user plugins live).
if [[ -n "${WEBSTORM_CONFIG_DIR:-}" ]]; then
    CONFIG_DIR="$WEBSTORM_CONFIG_DIR"
else
    # Pick the newest WebStorm<version> directory (e.g. WebStorm2026.1).
    CONFIG_DIR="$(ls -d "$JETBRAINS_DIR"/WebStorm* 2>/dev/null | sort -V | tail -n 1 || true)"
fi

if [[ -z "${CONFIG_DIR:-}" || ! -d "$CONFIG_DIR" ]]; then
    echo "ERROR: Could not find a WebStorm config directory under:" >&2
    echo "       $JETBRAINS_DIR" >&2
    echo "       Set WEBSTORM_CONFIG_DIR explicitly and re-run." >&2
    exit 1
fi

PLUGINS_DIR="$CONFIG_DIR/plugins"
echo "==> Target WebStorm config: $CONFIG_DIR"

# 2. Build the plugin zip.
echo "==> Building plugin (./gradlew buildPlugin)…"
./gradlew buildPlugin

# 3. Locate the freshly-built zip (buildPlugin wipes older zips first).
ZIP="$(ls -t build/distributions/*.zip 2>/dev/null | head -n 1 || true)"
if [[ -z "$ZIP" || ! -f "$ZIP" ]]; then
    echo "ERROR: No plugin zip found in build/distributions/" >&2
    exit 1
fi
echo "==> Built: $ZIP"

# 4. Determine the top-level folder name inside the zip (the plugin dir name).
PLUGIN_NAME="$(unzip -Z1 "$ZIP" | head -n 1 | cut -d/ -f1)"
if [[ -z "$PLUGIN_NAME" ]]; then
    echo "ERROR: Could not read plugin folder name from $ZIP" >&2
    exit 1
fi

# 5. Replace any existing install and unpack the new one.
mkdir -p "$PLUGINS_DIR"
if [[ -d "$PLUGINS_DIR/$PLUGIN_NAME" ]]; then
    echo "==> Removing old install: $PLUGINS_DIR/$PLUGIN_NAME"
    rm -rf "$PLUGINS_DIR/$PLUGIN_NAME"
fi

echo "==> Installing into: $PLUGINS_DIR/$PLUGIN_NAME"
unzip -q -o "$ZIP" -d "$PLUGINS_DIR"

echo
echo "✅ Done. Plugin '$PLUGIN_NAME' installed."
echo "   Now restart WebStorm to load it."

#!/usr/bin/env bash
# Stage embedded resources into setup-app resources (run before mvn package of setup-app).
# Env var TOOLKIT_SRC must point to the toolkit jar (CI sets this; local dev can export it or rely on default).
set -e

BASE="$(cd "$(dirname "$0")" && pwd)"
RES="$BASE/setup-app/src/main/resources"

# Default locations (local dev)
if [ -z "$TOOLKIT_SRC" ]; then
  if [ -d "$BASE/oracle-mcp-src" ]; then
    TOOLKIT_SRC="$BASE/oracle-mcp-src/src/oracle-db-mcp-java-toolkit/target/oracle-db-mcp-toolkit-1.0.0.jar"
  elif [ -n "$USERPROFILE" ]; then
    TOOLKIT_SRC="$USERPROFILE/.qoderwork/mcp/oracle-db-mcp/oracle-db-mcp-toolkit-1.0.0.jar"
  elif [ -n "$HOME" ]; then
    TOOLKIT_SRC="$HOME/.qoderwork/mcp/oracle-db-mcp/oracle-db-mcp-toolkit-1.0.0.jar"
  fi
fi

[ -n "$TOOLKIT_SRC" ] || { echo "[ERROR] TOOLKIT_SRC env var required"; exit 1; }
[ -f "$TOOLKIT_SRC" ] || { echo "[ERROR] toolkit JAR not found: $TOOLKIT_SRC"; exit 1; }
[ -f "$BASE/mcp-tap/target/mcp-tap.jar" ] || { echo "[ERROR] build mcp-tap first: mvn package -pl mcp-tap"; exit 1; }
[ -f "$BASE/dist/mcp-runtime.zip" ] || { echo "[ERROR] missing dist/mcp-runtime.zip (jlink + zip)"; exit 1; }

mkdir -p "$RES/toolkit" "$RES/tap" "$RES/runtime"
cp -f "$TOOLKIT_SRC" "$RES/toolkit/oracle-db-mcp-toolkit-1.0.0.jar"
cp -f "$BASE/mcp-tap/target/mcp-tap.jar" "$RES/tap/mcp-tap.jar"
cp -f "$BASE/dist/mcp-runtime.zip" "$RES/runtime/mcp-runtime.zip"

echo "[OK] resources staged: toolkit + tap + runtime (skill and platforms.json already in resources)"

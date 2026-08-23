#!/usr/bin/env bash
# Stage embedded resources into setup-app resources (run before mvn package of setup-app).
# Pure copier: all three source paths are supplied by package-windows.ps1 via env vars.
# TOOLKIT_SRC / TAP_JAR / RUNTIME_ZIP — if empty, fall back to conventional locations.
set -e

BASE="$(cd "$(dirname "$0")" && pwd)"
RES="$BASE/setup-app/src/main/resources"

if [ -z "$TOOLKIT_SRC" ]; then TOOLKIT_SRC="$BASE/oracle-mcp-src/src/oracle-db-mcp-java-toolkit/target/oracle-db-mcp-toolkit-1.0.0.jar"; fi
if [ -z "$TAP_JAR" ]; then TAP_JAR="$BASE/mcp-tap/target/mcp-tap.jar"; fi
if [ -z "$RUNTIME_ZIP" ]; then RUNTIME_ZIP="$BASE/dist/mcp-runtime.zip"; fi

[ -f "$TOOLKIT_SRC" ] || { echo "[ERROR] toolkit JAR not found: $TOOLKIT_SRC"; exit 1; }
[ -f "$TAP_JAR" ] || { echo "[ERROR] build mcp-tap first: mvn package -pl mcp-tap"; exit 1; }
[ -f "$RUNTIME_ZIP" ] || { echo "[ERROR] missing dist/mcp-runtime.zip (jlink + zip)"; exit 1; }

mkdir -p "$RES/toolkit" "$RES/tap" "$RES/runtime"
cp -f "$TOOLKIT_SRC" "$RES/toolkit/oracle-db-mcp-toolkit-1.0.0.jar"
cp -f "$TAP_JAR" "$RES/tap/mcp-tap.jar"
cp -f "$RUNTIME_ZIP" "$RES/runtime/mcp-runtime.zip"

echo "[OK] resources staged: toolkit + tap + runtime (skill and platforms.json already in resources)"

#!/usr/bin/env bash
# Stage embedded resources into setup-app resources (run before mvn package of setup-app).
# Pure copier: source paths are supplied by package-windows.ps1 via env vars.
# TOOLKIT_SRC / TAP_JAR / RUNTIME_ZIP — Oracle runtime (required).
# MYSQL_TOOLKIT_SRC / NODE_RUNTIME_ZIP — optional, only when MySQL support is bundled.
set -e

BASE="$(cd "$(dirname "$0")" && pwd)"
RES="$BASE/setup-app/src/main/resources"

if [ -z "$TOOLKIT_SRC" ]; then TOOLKIT_SRC="$BASE/oracle-mcp-src/src/oracle-db-mcp-java-toolkit/target/oracle-db-mcp-toolkit-1.0.0.jar"; fi
if [ -z "$TAP_JAR" ]; then TAP_JAR="$BASE/mcp-tap/target/mcp-tap.jar"; fi
if [ -z "$RUNTIME_ZIP" ]; then RUNTIME_ZIP="$BASE/dist/runtime.zip"; fi

[ -f "$TOOLKIT_SRC" ] || { echo "[ERROR] Oracle toolkit JAR not found: $TOOLKIT_SRC"; exit 1; }
[ -f "$TAP_JAR" ] || { echo "[ERROR] build mcp-tap first: mvn package -pl mcp-tap"; exit 1; }
[ -f "$RUNTIME_ZIP" ] || { echo "[ERROR] missing dist/runtime.zip (jlink + zip)"; exit 1; }

mkdir -p "$RES/toolkit/oracle" "$RES/toolkit/mysql" "$RES/tap" "$RES/runtime" "$RES/runtime/mysql"

# Oracle toolkit (per-db subdir, matches backend Installer.deployToolkit)
cp -f "$TOOLKIT_SRC" "$RES/toolkit/oracle/oracle-db-mcp-toolkit-1.0.0.jar"
cp -f "$TAP_JAR" "$RES/tap/mcp-tap.jar"
cp -f "$RUNTIME_ZIP" "$RES/runtime/runtime.zip"

# Optional MySQL toolkit (file or directory) -> toolkit/mysql/mysql-mcp-server
if [ -n "$MYSQL_TOOLKIT_SRC" ] && [ -e "$MYSQL_TOOLKIT_SRC" ]; then
  if [ -d "$MYSQL_TOOLKIT_SRC" ]; then
    rm -rf "$RES/toolkit/mysql/mysql-mcp-server"
    cp -r "$MYSQL_TOOLKIT_SRC" "$RES/toolkit/mysql/mysql-mcp-server"
  else
    cp -f "$MYSQL_TOOLKIT_SRC" "$RES/toolkit/mysql/mysql-mcp-server"
  fi
  echo "[OK] MySQL toolkit staged"
elif [ -n "$MYSQL_TOOLKIT_SRC" ]; then
  echo "[WARN] MYSQL_TOOLKIT_SRC set but not found: $MYSQL_TOOLKIT_SRC"
fi

# Overlay version-controlled shim (Doris CONNECT_ATTRS patch + env bridging)
if [ -d "$RES/toolkit/mysql/mysql-mcp-server/build" ]; then
  cp -f "$BASE/setup-app/src/main/shims/mysql-build-index.js" "$RES/toolkit/mysql/mysql-mcp-server/build/index.js"
  echo "[OK] MySQL build/index.js shim overlaid"
fi

# Optional Node runtime for MySQL -> runtime/mysql/node (zip extracted / dir copied)
if [ -n "$NODE_RUNTIME_ZIP" ] && [ -e "$NODE_RUNTIME_ZIP" ]; then
  if [ -d "$NODE_RUNTIME_ZIP" ]; then
    rm -rf "$RES/runtime/mysql/node"
    cp -r "$NODE_RUNTIME_ZIP" "$RES/runtime/mysql/node"
  else
    mkdir -p "$RES/runtime/mysql"
    (cd "$RES/runtime/mysql" && unzip -oq "$NODE_RUNTIME_ZIP")
  fi
  echo "[OK] Node runtime staged"
elif [ -n "$NODE_RUNTIME_ZIP" ]; then
  echo "[WARN] NODE_RUNTIME_ZIP set but not found: $NODE_RUNTIME_ZIP"
fi

echo "[OK] resources staged: toolkit + tap + runtime (skill and platforms.json already in resources)"

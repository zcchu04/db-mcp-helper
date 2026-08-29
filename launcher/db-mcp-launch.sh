#!/usr/bin/env bash
# DB MCP Helper 启动器（macOS / Linux）：优先使用系统 JDK 17（含 jdk.httpserver），
# 否则回退到安装目录内捆绑运行时。由对应平台 CI 复制到 app-image 并作为入口。
set -e

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$APP_DIR/db-mcp-setup.jar"

JAVA=""
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
fi
if [ -z "$JAVA" ] && command -v java >/dev/null 2>&1; then
  JAVA="$(command -v java)"
fi

if [ -n "$JAVA" ]; then
  if "$JAVA" --list-modules 2>/dev/null | grep -q '^jdk.httpserver@' \
     && "$JAVA" -version 2>&1 | grep -Eq '"1[789]\.|"2[0-9]\.'; then
    exec "$JAVA" -jar "$JAR" "$@"
  fi
fi

BUNDLED="$APP_DIR/runtime/bin/java"
if [ -x "$BUNDLED" ]; then
  exec "$BUNDLED" -jar "$JAR" "$@"
fi

echo "[ERROR] No suitable Java found (need JDK 17+ with jdk.httpserver). Install JDK 17 or reinstall." >&2
exit 1

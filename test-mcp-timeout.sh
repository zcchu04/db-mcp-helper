#!/bin/bash
# 直接测试 oracle-prod MCP 链路：mcp-tap → toolkit
# 发送 initialize + 长SQL tools/call，测量每步耗时

JAVA="D:/Other Program Files/MCP Server/DB MCP Helper/bundle/runtime/bin/java.exe"
TAP="D:/Other Program Files/MCP Server/DB MCP Helper/bundle/tap/mcp-tap.jar"
TOOLKIT="D:/Other Program Files/MCP Server/DB MCP Helper/bundle/oracle/toolkit/oracle-db-mcp-toolkit-1.0.0.jar"
CONFIG="D:/Other Program Files/MCP Server/DB MCP Helper/bundle/oracle/instance/prod/config.yaml"
LOGFILE="/tmp/mcp-tap-test-calllog.jsonl"

# 生成 ~300 IDs 的长 SQL（复现用户报告的场景）
IDS=""
for i in $(seq 9192789 9193089); do
  if [ -z "$IDS" ]; then
    IDS="$i"
  else
    IDS="$IDS,$i"
  fi
done

SQL="SELECT COUNT(*) AS TOTAL_IN_DB, SUM(CASE WHEN IS_PRIMARY = 1 THEN 1 ELSE 0 END) AS PRIMARY_1, SUM(CASE WHEN IS_PRIMARY = 0 THEN 1 ELSE 0 END) AS PRIMARY_0 FROM ORG_DEPT_STRUCT_EMPLOYEE WHERE ID IN ($IDS) AND DEL_FLAG = '0'"

echo "=== SQL 长度: ${#SQL} 字符 ==="
echo "=== IDs 数量: $(echo $IDS | tr ',' '\n' | wc -l) ==="

# 构造 JSON-RPC 消息（每条一行）
INIT_MSG='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}'
INITIALIZED_MSG='{"jsonrpc":"2.0","method":"notifications/initialized"}'
CALL_MSG="{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"read-query\",\"arguments\":{\"sql\":\"$SQL\"}}}"

echo "=== initialize 消息长度: ${#INIT_MSG} ==="
echo "=== tools/call 消息长度: ${#CALL_MSG} ==="
echo ""

# 用 coproc 启动后台进程，手动控制发送时序
coproc MCP_PROC {
  "$JAVA" -jar "$TAP" --log "$LOGFILE" -- \
    "$JAVA" -DconfigFile="$CONFIG" \
    "-Dtools=read-query,db-ping,table,explain-plan" \
    -jar "$TOOLKIT" 2>/tmp/mcp-test-stderr.log
}

# 读取响应的函数（带超时和计时）
read_response() {
  local timeout=$1
  local label=$2
  local start_ms=$(date +%s%3N)
  local line=""

  # 从 MCP_PROC 的 stdout 读取一行
  while IFS= read -r -t "$timeout" line <&"${MCP_PROC[0]}"; do
    local end_ms=$(date +%s%3N)
    local elapsed=$((end_ms - start_ms))
    echo "[$label] 收到响应 (${elapsed}ms): ${line:0:200}..."
    return 0
  done

  local end_ms=$(date +%s%3N)
  local elapsed=$((end_ms - start_ms))
  echo "[$label] 超时 (${elapsed}ms)"
  return 1
}

echo ">>> 发送 initialize..."
T_INIT=$(date +%s%3N)
echo "$INIT_MSG" >&"${MCP_PROC[1]}"

read_response 30 "initialize"
T_INIT_DONE=$(date +%s%3N)
echo "  initialize 耗时: $((T_INIT_DONE - T_INIT))ms"
echo ""

echo ">>> 发送 initialized 通知..."
echo "$INITIALIZED_MSG" >&"${MCP_PROC[1]}"
sleep 0.5

echo ">>> 发送长 SQL tools/call..."
T_CALL=$(date +%s%3N)
echo "$CALL_MSG" >&"${MCP_PROC[1]}"

read_response 120 "tools/call"
T_CALL_DONE=$(date +%s%3N)
echo "  tools/call 耗时: $((T_CALL_DONE - T_CALL))ms"
echo ""

echo "=== stderr 输出 ==="
cat /tmp/mcp-test-stderr.log 2>/dev/null | head -20
echo ""
echo "=== calllog ==="
cat "$LOGFILE" 2>/dev/null
echo ""
echo "=== 测试完成 ==="

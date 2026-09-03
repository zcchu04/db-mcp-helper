"""
直接测试 oracle-prod MCP 链路耗时。
模拟 AI 客户端 → mcp-tap → oracle toolkit 的完整 stdio 链路。
"""
import subprocess, json, time, sys, os, threading, queue

JAVA = r"D:\Other Program Files\MCP Server\DB MCP Helper\bundle\runtime\bin\java.exe"
TAP  = r"D:\Other Program Files\MCP Server\DB MCP Helper\bundle\tap\mcp-tap.jar"
TOOLKIT = r"D:\Other Program Files\MCP Server\DB MCP Helper\bundle\oracle\toolkit\oracle-db-mcp-toolkit-1.0.0.jar"
CONFIG  = r"D:\Other Program Files\MCP Server\DB MCP Helper\bundle\oracle\instance\prod\config.yaml"
LOGFILE = r"D:\Code\person\backends\oracle-mcp-install-tools\test-calllog.jsonl"

# 生成 ~300 IDs 的长 SQL
ids = list(range(9192789, 9193090))  # 301 个 ID
sql = (
    "SELECT COUNT(*) AS TOTAL_IN_DB, "
    "SUM(CASE WHEN IS_PRIMARY = 1 THEN 1 ELSE 0 END) AS PRIMARY_1, "
    "SUM(CASE WHEN IS_PRIMARY = 0 THEN 1 ELSE 0 END) AS PRIMARY_0 "
    "FROM ORG_DEPT_STRUCT_EMPLOYEE "
    f"WHERE ID IN ({','.join(str(i) for i in ids)}) AND DEL_FLAG = '0'"
)

init_msg = json.dumps({
    "jsonrpc": "2.0", "id": 1, "method": "initialize",
    "params": {
        "protocolVersion": "2024-11-05",
        "capabilities": {},
        "clientInfo": {"name": "test-client", "version": "1.0"}
    }
}, ensure_ascii=False)

initialized_msg = json.dumps({
    "jsonrpc": "2.0", "method": "notifications/initialized"
}, ensure_ascii=False)

call_msg = json.dumps({
    "jsonrpc": "2.0", "id": 2, "method": "tools/call",
    "params": {"name": "read-query", "arguments": {"sql": sql}}
}, ensure_ascii=False)

print(f"=== SQL 长度: {len(sql)} 字符 ===")
print(f"=== IDs 数量: {len(ids)} ===")
print(f"=== initialize 消息长度: {len(init_msg)} ===")
print(f"=== tools/call 消息长度: {len(call_msg)} ===")
print()

# 启动进程链
cmd = [JAVA, "-jar", TAP, "--log", LOGFILE, "--",
       JAVA, f"-DconfigFile={CONFIG}",
       "-Dtools=read-query,db-ping,table,explain-plan",
       "-jar", TOOLKIT]

print(f">>> 启动 MCP 进程链...")
proc = subprocess.Popen(
    cmd,
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
    bufsize=1
)

# 用 Queue + Thread 读 stdout/stderr（Windows 兼容）
stdout_q = queue.Queue()
stderr_lines = []

def read_stdout():
    for line in proc.stdout:
        stdout_q.put(line.rstrip())
    stdout_q.put(None)  # sentinel

def read_stderr():
    for line in proc.stderr:
        stderr_lines.append(line.rstrip())

t_out = threading.Thread(target=read_stdout, daemon=True)
t_err = threading.Thread(target=read_stderr, daemon=True)
t_out.start()
t_err.start()

def recv_line(timeout=120):
    """从 stdout queue 读取一行，带超时"""
    deadline = time.monotonic() + timeout
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return None
        try:
            line = stdout_q.get(timeout=min(remaining, 1.0))
            if line is None:
                return None  # process ended
            return line
        except queue.Empty:
            continue

def send_and_recv(msg, label, timeout=120, expected_id=None):
    """发送 JSON-RPC，等待匹配 id 的响应"""
    print(f">>> 发送 {label} ({len(msg)} 字节)...")
    t0 = time.monotonic()
    proc.stdin.write(msg + "\n")
    proc.stdin.flush()

    while True:
        elapsed = (time.monotonic() - t0) * 1000
        remaining_s = timeout - elapsed / 1000
        if remaining_s <= 0:
            print(f"    [{label}] 超时! ({elapsed:.0f}ms)")
            return None, elapsed

        line = recv_line(timeout=remaining_s)
        if line is None:
            elapsed = (time.monotonic() - t0) * 1000
            print(f"    [{label}] 进程退出! ({elapsed:.0f}ms)")
            return None, elapsed

        try:
            j = json.loads(line)
            msg_id = j.get("id")
            if "method" in j and "id" not in j:
                # 通知消息，跳过
                print(f"    [{label}] 跳过通知 ({elapsed:.0f}ms): {line[:120]}")
                continue
            if expected_id is not None and msg_id != expected_id:
                print(f"    [{label}] 跳过 id={msg_id} ({elapsed:.0f}ms)")
                continue
            preview = line[:300] + "..." if len(line) > 300 else line
            print(f"    [{label}] 收到响应 ({elapsed:.0f}ms): {preview}")
            return j, elapsed
        except json.JSONDecodeError:
            print(f"    [{label}] 非JSON ({elapsed:.0f}ms): {line[:120]}")
            continue

# Step 1: initialize
resp, init_ms = send_and_recv(init_msg, "initialize", timeout=30, expected_id=1)
print()
if resp is None:
    print("!!! initialize 超时，终止测试 !!!")
    proc.kill()
    sys.exit(1)

# Step 2: initialized 通知
time.sleep(0.3)
proc.stdin.write(initialized_msg + "\n")
proc.stdin.flush()
print(">>> 已发送 initialized 通知")
print()

# Step 3: 长 SQL tools/call
resp, call_ms = send_and_recv(call_msg, "tools/call (长SQL)", timeout=120, expected_id=2)
print()
print(f"    tools/call 总耗时: {call_ms:.0f}ms")
print()

# 结果汇总
print("=" * 60)
print(f"  initialize:  {init_ms:>8.0f} ms")
print(f"  tools/call:  {call_ms:>8.0f} ms  ({'成功' if resp else '超时/失败'})")
print("=" * 60)

if resp:
    if "error" in resp:
        print(f"  JSON-RPC 错误: {json.dumps(resp['error'], ensure_ascii=False)}")
    elif "result" in resp:
        result = resp["result"]
        if isinstance(result, dict) and result.get("isError"):
            for c in result.get("content", []):
                if isinstance(c, dict) and c.get("type") == "text":
                    print(f"  业务错误: {c['text'][:500]}")
        else:
            content = result.get("content", []) if isinstance(result, dict) else []
            for c in content:
                if isinstance(c, dict) and c.get("type") == "text":
                    print(f"  结果: {c['text'][:500]}")

print()
print("=== stderr (前30行) ===")
for line in stderr_lines[:30]:
    print(f"  {line}")

print()
print(f"=== calllog ===")
try:
    with open(LOGFILE, "r", encoding="utf-8") as f:
        for line in f:
            print(f"  {line.rstrip()}")
except FileNotFoundError:
    print("  (文件不存在)")

proc.stdin.close()
try:
    proc.wait(timeout=5)
except:
    proc.kill()
print("\n=== 测试完成 ===")

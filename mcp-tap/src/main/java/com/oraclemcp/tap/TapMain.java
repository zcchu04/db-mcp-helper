package com.oraclemcp.tap;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP stdio 监听代理（tap）。
 *
 * <p>职责：在 AI 客户端与真实 MCP 服务器之间做<b>字节级透传</b>，同时旁路解析 JSON-RPC
 * 消息，把工具调用事件（时间、工具名、耗时、成败、SQL 摘要）追加写入 JSONL 日志。
 *
 * <p>设计原则：协议流零改动——透传线程直接搬运原始字节，不做任何重编码；解析只作用于
 * 行的副本，且任何解析异常都被静默吞掉，绝不影响协议本身。
 *
 * <p>用法：{@code java -jar mcp-tap.jar --log <日志文件> -- <目标命令...>}
 */
public final class TapMain {

    /** 提取 JSON-RPC 消息 id（数字或字符串）。 */
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]*\"|\\d+)");
    /** 提取请求方法名。 */
    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
    /** 提取 tools/call 请求中的工具名（params.name）。 */
    private static final Pattern TOOL_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    /** 提取 SQL 文本用于摘要。 */
    private static final Pattern SQL = Pattern.compile("\"sql\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    /** 响应中的业务错误标记（CallToolResult.isError）。 */
    private static final Pattern IS_ERROR = Pattern.compile("\"isError\"\\s*:\\s*true");

    /** 在途调用：消息 id → 调用信息。 */
    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();

    /** 日志写入器，为 null 表示未启用日志。 */
    private static Writer logWriter;

    private TapMain() {
    }

    public static void main(String[] args) throws Exception {
        int split = -1;
        String logFile = null;
        for (int i = 0; i < args.length; i++) {
            if ("--log".equals(args[i]) && i + 1 < args.length) {
                logFile = args[++i];
            } else if ("--".equals(args[i])) {
                split = i;
                break;
            }
        }
        if (split < 0 || split + 1 >= args.length) {
            System.err.println("[mcp-tap] usage: java -jar mcp-tap.jar [--log <file>] -- <command...>");
            System.exit(2);
        }
        if (logFile != null) {
            try {
                logWriter = new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("[mcp-tap] cannot open log file: " + e.getMessage());
            }
        }

        String[] cmd = new String[args.length - split - 1];
        System.arraycopy(args, split + 1, cmd, 0, cmd.length);
        Process child = new ProcessBuilder(cmd).redirectErrorStream(false).start();

        Thread outPump = pump(child.getInputStream(), System.out, TapMain::scanResponse, "out");
        Thread errPump = pump(child.getErrorStream(), System.err, null, "err");
        // stdin 泵在当前线程执行，结束后等待子进程
        pumpBlocking(System.in, child.getOutputStream(), TapMain::scanRequest);

        int code = child.waitFor();
        outPump.join(3000);
        errPump.join(3000);
        if (logWriter != null) {
            try {
                logWriter.close();
            } catch (IOException ignored) {
                // 关闭失败不影响退出
            }
        }
        System.exit(code);
    }

    /** 启动一个守护线程做 输入流→输出流 的字节泵，可选行扫描。 */
    private static Thread pump(InputStream in, OutputStream out, LineHandler scan, String name) {
        Thread t = new Thread(() -> pumpBlocking(in, out, scan), "tap-" + name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 字节泵：逐块透传并把行副本交给扫描器；任何异常只结束本方向。 */
    private static void pumpBlocking(InputStream in, OutputStream out, LineHandler scan) {
        LineSplitter splitter = new LineSplitter(scan);
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                // 先扫描后转发：保证日志先于响应落盘，客户端收到响应即代表记录已完成
                if (scan != null) {
                    splitter.feed(buf, 0, n);
                }
                out.write(buf, 0, n);
                out.flush();
            }
            out.flush();
        } catch (IOException e) {
            // 管道关闭属正常结束路径，不透传错误
        } finally {
            if (out != System.out && out != System.err) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // 忽略关闭异常
                }
            }
        }
    }

    @FunctionalInterface
    private interface LineHandler {
        void handle(String line);
    }

    /** 扫描客户端→服务器方向：捕获 tools/call 请求。 */
    private static void scanRequest(String line) {
        try {
            Matcher m = METHOD.matcher(line);
            if (!m.find() || !"tools/call".equals(m.group(1))) {
                return;
            }
            String id = extractId(line);
            if (id == null) {
                return;
            }
            Matcher tn = TOOL_NAME.matcher(line);
            String tool = tn.find() ? tn.group(1) : "unknown";
            String sqlDigest = null;
            Matcher sq = SQL.matcher(line);
            if (sq.find()) {
                String sql = sq.group(1).replace("\\\"", "\"").replace("\\n", " ");
                sqlDigest = sql.length() > 120 ? sql.substring(0, 120) + "..." : sql;
            }
            PENDING.put(id, new Pending(tool, sqlDigest, System.nanoTime()));
        } catch (RuntimeException ignored) {
            // 解析失败静默降级
        }
    }

    /** 扫描服务器→客户端方向：匹配响应并落日志。 */
    private static void scanResponse(String line) {
        try {
            if (line.indexOf("\"method\"") >= 0) {
                return; // 服务器通知，无 id 对应关系
            }
            String id = extractId(line);
            if (id == null) {
                return;
            }
            Pending p = PENDING.remove(id);
            if (p == null) {
                return; // initialize 等非工具调用响应
            }
            long durMs = (System.nanoTime() - p.startNanos) / 1_000_000;
            boolean error = IS_ERROR.matcher(line).find() || line.contains("\"error\"");
            writeLog(p, durMs, error);
        } catch (RuntimeException ignored) {
            // 解析失败静默降级
        }
    }

    private static String extractId(String line) {
        Matcher m = ID.matcher(line);
        return m.find() ? m.group(1).replace("\"", "") : null;
    }

    private static synchronized void writeLog(Pending p, long durMs, boolean error) {
        if (logWriter == null) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder(256);
            sb.append("{\"ts\":\"").append(Instant.now()).append('"');
            sb.append(",\"tool\":").append(jsonString(p.tool));
            if (p.sqlDigest != null) {
                sb.append(",\"sql\":").append(jsonString(p.sqlDigest));
            }
            sb.append(",\"durMs\":").append(durMs);
            sb.append(",\"error\":").append(error).append('}');
            logWriter.write(sb.toString());
            logWriter.write('\n');
            logWriter.flush();
        } catch (IOException ignored) {
            // 日志失败不影响协议
        }
    }

    /** 极简 JSON 字符串转义。 */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** 把字节块切成完整行交给扫描器，跨块保留残留。 */
    private static final class LineSplitter {
        private final LineHandler handler;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        LineSplitter(LineHandler handler) {
            this.handler = handler;
        }

        void feed(byte[] buf, int off, int len) {
            try {
                for (int i = off; i < off + len; i++) {
                    byte b = buf[i];
                    if (b == '\n') {
                        String line = pending.toString(StandardCharsets.UTF_8).trim();
                        pending.reset();
                        if (!line.isEmpty()) {
                            handler.handle(line);
                        }
                    } else {
                        pending.write(b);
                    }
                }
            } catch (RuntimeException ignored) {
                pending.reset();
            }
        }
    }

    private static final class Pending {
        final String tool;
        final String sqlDigest;
        final long startNanos;

        Pending(String tool, String sqlDigest, long startNanos) {
            this.tool = tool;
            this.sqlDigest = sqlDigest;
            this.startNanos = startNanos;
        }
    }
}

package com.dbmcp.setup;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 连通自检模块：走与生产完全一致的链路（tap 包 toolkit/server），
 * 完成 MCP stdio 握手并调用各库适配器的 ping 工具，返回数据库版本与延迟。
 */
public final class SelfTest {

    private static final int TIMEOUT_SECONDS = 90;
    private static final Gson GSON = new Gson();

    /** 自检结果。 */
    public static final class Result {
        public boolean ok;
        public String detail;
        public String stderrTail;
        public JsonObject fields = new JsonObject();
    }

    private SelfTest() {
    }

    public static Result run(Path baseDir, String dbId, String env, DbAdapter adapter, Map<String, String> envVars, String mcpServer) {
        Result r = new Result();
        PrintStream log = openSelfTestLog(baseDir, dbId, env, mcpServer);
        log.println("=== 自检开始 === " + Instant.now() + " db=" + dbId + " env=" + env + " impl=" + mcpServer);

        try {
            Path toolkit = Installer.toolkitPath(baseDir, dbId, adapter);
            Path tap = baseDir.resolve("tap").resolve(Cfg.TAP_FILE_NAME);
            Path cfg = Installer.configFile(baseDir, dbId, env, adapter);
            log.println("toolkit: " + toolkit + " exists=" + (Files.exists(toolkit) && Files.isDirectory(toolkit)));
            log.println("tap:     " + tap + " exists=" + Files.isRegularFile(tap));
            log.println("config:  " + cfg + " exists=" + Files.isRegularFile(cfg));

            // NODE 链路的服务端入口按实现分派：benborla29 → build/index.js；naganpm → dist/index.js
            boolean nagaImpl = MySqlAdapter.IMPL_NAGA.equals(mcpServer);
            Path serverEntry = nagaImpl
                    ? toolkit.getParent().resolve(MySqlAdapter.nagaDirName()).resolve("dist").resolve("index.js")
                    : toolkit.resolve("build").resolve("index.js");
            if (adapter.runtimeKind() == DbAdapter.RuntimeKind.JAVA_JAR
                    && (!Files.isRegularFile(toolkit) || !Files.isRegularFile(tap) || !Files.isRegularFile(cfg))) {
                r.ok = false;
                r.detail = "运行时或环境配置缺失，请先部署运行时并保存环境配置";
                log.println("FAIL: " + r.detail);
                return r;
            }
            if (adapter.runtimeKind() == DbAdapter.RuntimeKind.NODE
                    && (!Files.isRegularFile(tap) || !Files.isRegularFile(serverEntry))) {
                r.ok = false;
                r.detail = "运行时或环境配置缺失（node 链路需 tap 与 " + serverEntry + "），请先部署";
                log.println("FAIL: " + r.detail);
                return r;
            }

            String java = Installer.resolveJava(baseDir);
            log.println("java:    " + java + " exists=" + Files.isRegularFile(Path.of(java)));

            String javaRel = Path.of(java).getFileName().toString();
            String tapRel = baseDir.relativize(tap).toString();
            String cfgRel = baseDir.relativize(cfg).toString();
            String logRel = baseDir.relativize(Installer.callLog(baseDir, dbId, env, mcpServer)).toString();

            List<String> inner = adapter.buildCommand(baseDir, dbId, env, adapter.requiredToolsFor(mcpServer), mcpServer);
            // inner = [java, -jar, tap, --log, logRel, --, <server...>]
            // 重写日志相对路径并复用 adapter 的 server 段（去掉外层 java/tap/--log/--）
            List<String> server = inner.subList(6, inner.size());
            boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
            boolean node = adapter.runtimeKind() == DbAdapter.RuntimeKind.NODE;
            List<String> cmd = new ArrayList<>();
            cmd.add(javaRel);
            cmd.add("-jar");
            cmd.add(tapRel);
            cmd.add("--log");
            cmd.add(logRel);
            cmd.add("--");
            if (node) {
                // server 段首项是 node 可执行文件（已含相对/绝对路径）；保留原样
                cmd.addAll(relativize(baseDir, server));
            } else {
                cmd.add(javaRel);
                cmd.addAll(relativize(baseDir, server.subList(1, server.size())));
            }

            log.println("CMD (relative): " + String.join(" ", cmd));
            log.println("workDir: " + baseDir);

            Process p = null;
            StringBuilder errBuf = new StringBuilder();
            try {
                final ProcessBuilder pb = new ProcessBuilder(cmd)
                        .directory(baseDir.toFile())
                        .redirectErrorStream(false);
                if (envVars != null && !envVars.isEmpty()) {
                    pb.environment().putAll(envVars);
                }
                final Process proc = pb.start();
                p = proc;
                log.println("子进程已启动, PID=" + proc.pid());

                Thread errReader = new Thread(() -> readAll(proc.getErrorStream(), errBuf), "selftest-stderr");
                errReader.setDaemon(true);
                errReader.start();

                BufferedWriter w = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader rd = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
                LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
                Thread reader = new Thread(() -> {
                    try {
                        String l;
                        while ((l = rd.readLine()) != null) {
                            log.println("  [tap->] " + l);
                            lines.put(l);
                        }
                    } catch (Exception ignored) {
                    }
                }, "selftest-reader");
                reader.setDaemon(true);
                reader.start();

                String initMsg = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"db-mcp-setup\",\"version\":\"0.1\"}}}";
                log.println(">> initialize");
                w.write(initMsg);
                w.write("\n");
                w.flush();
                String initResp = awaitId(lines, "1");
                if (initResp == null) {
                    r.detail = "MCP 握手超时（initialize 无响应）";
                    r.stderrTail = tail(errBuf);
                    log.println("FAIL: " + r.detail);
                    log.println("stderrTail:\n" + r.stderrTail);
                    return r;
                }
                log.println("<< initialize OK: " + initResp.substring(0, Math.min(200, initResp.length())));

                w.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
                w.write("\n");
                w.flush();

                String pingTool = adapter.pingTool();
                JsonObject pingArgs = adapter.pingArguments(mcpServer);
                String pingMsg = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\""
                        + pingTool + "\",\"arguments\":" + GSON.toJson(pingArgs) + "}}";
                log.println(">> " + pingTool);
                w.write(pingMsg);
                w.write("\n");
                w.flush();
                String pingResp = awaitId(lines, "2");
                if (pingResp == null) {
                    r.detail = pingTool + " 超时（数据库连接可能缓慢或不可达）";
                    r.stderrTail = tail(errBuf);
                    log.println("FAIL: " + r.detail);
                    log.println("stderrTail:\n" + r.stderrTail);
                    return r;
                }
                log.println("<< " + pingTool + ": " + pingResp.substring(0, Math.min(500, pingResp.length())));
                adapter.parsePing(pingResp, r);
                r.stderrTail = tail(errBuf);
                log.println((r.ok ? "PASS" : "FAIL") + ": " + r.detail);
                return r;
            } catch (Exception e) {
                r.ok = false;
                r.detail = "自检进程异常：" + e.getMessage();
                log.println("EXCEPTION: " + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace(log);
                if (errBuf != null && errBuf.length() > 0) {
                    r.stderrTail = tail(errBuf);
                    log.println("stderrTail:\n" + r.stderrTail);
                }
                return r;
            } finally {
                if (p != null && p.isAlive()) {
                    log.println("销毁子进程(含派生 toolkit 子进程)...");
                    try {
                        // tap 会派生 toolkit 子 JVM；仅杀 tap 会留下孤儿进程继续持有 jar 锁，
                        // 这里一并强制杀掉整棵进程树。
                        for (ProcessHandle ph : p.descendants().toList()) {
                            ph.destroyForcibly();
                        }
                    } catch (Throwable ignore) {
                    }
                    p.destroyForcibly();
                }
            }
        } catch (Throwable t) {
            // 命令准备阶段（解析 java/相对化路径/buildCommand 等）若抛错，
            // 此前不在任何 catch 内会逃逸导致线程静默死亡、UI 卡在"进行中"。
            // 这里兜底捕获并落盘真实异常，便于定位。
            r.ok = false;
            r.detail = "自检准备阶段异常：" + t.getClass().getName() + "：" + t.getMessage();
            log.println("PREP-EXCEPTION: " + t.getClass().getName() + "：" + t.getMessage());
            t.printStackTrace(log);
            return r;
        } finally {
            log.println("=== 自检结束 ===");
            log.flush();
            log.close();
        }
    }

    /** 把命令段中的绝对路径改为相对 baseDir 的路径（tap 已用工作目录相对路径规避空格问题）。
     *  注意：命令行参数里还有 -DconfigFile=... / -Dtools=... 这类 JVM 属性与 -jar 等 flag，
     *  它们带了等号或 Windows 盘符冒号，不能用 Path.of 解析，需原样保留。 */
    private static List<String> relativize(Path baseDir, List<String> args) {
        List<String> out = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("-")) { // flag / JVM 属性，不是待相对化的文件路径
                out.add(a);
                continue;
            }
            Path p;
            try {
                p = Path.of(a);
            } catch (java.nio.file.InvalidPathException e) {
                out.add(a);
                continue;
            }
            if (p.isAbsolute() && a.startsWith(baseDir.toString())) {
                out.add(baseDir.relativize(p).toString());
            } else {
                out.add(a);
            }
        }
        return out;
    }

    /** 自检日志路径：本次运行的实时日志（每次自检独立，便于前端流式读取）。 */
    public static Path liveLogPath(Path baseDir, String dbId, String env, String mcpServer) {
        Path logDir = baseDir.resolve("logs");
        String name = "selftest-live-" + sanitize(dbId) + "-" + sanitize(env) + "-" + sanitize(mcpServer) + ".log";
        return logDir.resolve(name);
    }

    /** 自检日志写入安装目录 logs/selftest-YYYY-MM-DD.log（累计），同时每次运行独立写一份 live 日志供前端流式展示。 */
    private static PrintStream openSelfTestLog(Path baseDir, String dbId, String env, String mcpServer) {
        try {
            Path logDir = baseDir.resolve("logs");
            Files.createDirectories(logDir);
            String date = java.time.LocalDate.now().toString();
            Path logFile = logDir.resolve("selftest-" + date + ".log");
            Path liveFile = liveLogPath(baseDir, dbId, env, mcpServer);
            // 每次自检前清空 live 文件，保证前端读到的是本次运行的日志
            Files.writeString(liveFile, "", StandardCharsets.UTF_8);
            java.io.OutputStream tee = new TeeOutputStream(
                    new java.io.FileOutputStream(logFile.toFile(), true),
                    new java.io.FileOutputStream(liveFile.toFile(), false));
            return new PrintStream(tee, true, "UTF-8");
        } catch (Exception e) {
            System.err.println("[SelfTest] 无法打开日志: " + e.getMessage());
            return System.err;
        }
    }

    /** 把一份内容同时写入两个输出流（累计日志 + 实时日志）。 */
    private static final class TeeOutputStream extends java.io.OutputStream {
        private final java.io.OutputStream a;
        private final java.io.OutputStream b;
        TeeOutputStream(java.io.OutputStream a, java.io.OutputStream b) {
            this.a = a;
            this.b = b;
        }
        @Override public void write(int c) throws java.io.IOException {
            a.write(c);
            b.write(c);
        }
        @Override public void write(byte[] buf, int off, int len) throws java.io.IOException {
            a.write(buf, off, len);
            b.write(buf, off, len);
        }
        @Override public void flush() throws java.io.IOException {
            a.flush();
            b.flush();
        }
        @Override public void close() throws java.io.IOException {
            a.close();
            b.close();
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "x";
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** 从读线程的行队列中等待包含指定 id 的响应行，超时返回 null。 */
    private static String awaitId(LinkedBlockingQueue<String> lines, String id) {
        String marker = "\"id\":" + id;
        String markerSpaced = "\"id\": " + id;
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String line;
            try {
                long left = deadline - System.currentTimeMillis();
                line = lines.poll(Math.min(500, Math.max(1, left)), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (line == null) {
                continue;
            }
            if (line.contains(marker) || line.contains(markerSpaced)) {
                return line;
            }
        }
        return null;
    }

    /**
     * 逐行读取进程 stderr 并增量写入 errBuf。
     * 关键修复：旧实现先把所有行缓存在局部 List，等流读到 EOF（即子进程退出）才一次性刷入 errBuf。
     * 但 @benborla29/mcp-server-mysql 在启动时直连 MySQL，若目标库 max_connections 耗尽，
     * node 子进程崩溃退出、mcp-tap 却卡在 stdin 上不退出（向导侧管道一直开着），
     * 于是 stderr 流永不关闭、readAll 永远到不了 EOF，超时那一刻 errBuf 始终为空，
     * 真实的 "Too many connections" 错误被彻底吞掉，UI 只看到空 stderrTail + 握手超时。
     * 改为每行到达即增量追加，保证即便 tap 挂死、流不关闭，真实错误也能出现在 stderrTail。
     */
    private static void readAll(java.io.InputStream in, StringBuilder sb) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                synchronized (sb) {
                    sb.append(l);
                    sb.append("\n");
                    // 控制上限，避免长错误输出无限增长（tail() 取末 2000 字符）
                    if (sb.length() > 8000) {
                        sb.delete(0, sb.length() - 4000);
                    }
                }
            }
        } catch (IOException ignored) {
            // 进程结束时管道关闭
        }
    }

    private static String tail(StringBuilder sb) {
        synchronized (sb) {
            String s = sb.toString();
            return s.length() > 2000 ? s.substring(s.length() - 2000) : s;
        }
    }
}

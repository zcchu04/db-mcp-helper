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

    public static Result run(Path baseDir, String dbId, String env, DbAdapter adapter, Map<String, String> envVars) {
        Result r = new Result();
        PrintStream log = openSelfTestLog(baseDir);
        log.println("=== 自检开始 === " + Instant.now() + " db=" + dbId + " env=" + env);

        try {
            Path toolkit = Installer.toolkitPath(baseDir, dbId, adapter);
            Path tap = baseDir.resolve("tap").resolve(Cfg.TAP_FILE_NAME);
            Path cfg = Installer.configFile(baseDir, dbId, env, adapter);
            log.println("toolkit: " + toolkit + " exists=" + Files.isRegularFile(toolkit));
            log.println("tap:     " + tap + " exists=" + Files.isRegularFile(tap));
            log.println("config:  " + cfg + " exists=" + Files.isRegularFile(cfg));

            if (adapter.runtimeKind() == DbAdapter.RuntimeKind.JAVA_JAR
                    && (!Files.isRegularFile(toolkit) || !Files.isRegularFile(tap) || !Files.isRegularFile(cfg))) {
                r.ok = false;
                r.detail = "运行时或环境配置缺失，请先部署运行时并保存环境配置";
                log.println("FAIL: " + r.detail);
                return r;
            }
            if (adapter.runtimeKind() == DbAdapter.RuntimeKind.NODE
                    && (!Files.isRegularFile(tap) || !Files.isRegularFile(toolkit.resolve("build").resolve("index.js")))) {
                r.ok = false;
                r.detail = "运行时或环境配置缺失（node 链路需 tap 与 mysql-mcp-server/build/index.js），请先部署";
                log.println("FAIL: " + r.detail);
                return r;
            }

            String java = Installer.resolveJava(baseDir);
            log.println("java:    " + java + " exists=" + Files.isRegularFile(Path.of(java)));

            String javaRel = Path.of(java).getFileName().toString();
            String tapRel = baseDir.relativize(tap).toString();
            String cfgRel = baseDir.relativize(cfg).toString();
            String logRel = baseDir.relativize(Installer.callLog(baseDir, dbId, env)).toString();

            List<String> inner = adapter.buildCommand(baseDir, dbId, env, adapter.requiredTools());
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

                StringBuilder errBuf = new StringBuilder();
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
                    return r;
                }
                log.println("<< initialize OK: " + initResp.substring(0, Math.min(200, initResp.length())));

                w.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
                w.write("\n");
                w.flush();

                String pingTool = adapter.pingTool();
                JsonObject pingArgs = adapter.pingArguments();
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
                return r;
            } finally {
                if (p != null && p.isAlive()) {
                    log.println("销毁子进程...");
                    p.destroy();
                }
            }
        } finally {
            log.println("=== 自检结束 ===");
            log.flush();
            log.close();
        }
    }

    /** 把命令段中的绝对路径改为相对 baseDir 的路径（tap 已用工作目录相对路径规避空格问题）。 */
    private static List<String> relativize(Path baseDir, List<String> args) {
        List<String> out = new ArrayList<>();
        for (String a : args) {
            Path p = Path.of(a);
            if (p.isAbsolute() && a.startsWith(baseDir.toString())) {
                out.add(baseDir.relativize(p).toString());
            } else {
                out.add(a);
            }
        }
        return out;
    }

    /** 自检日志写入安装目录 logs/selftest-YYYY-MM-DD.log */
    private static PrintStream openSelfTestLog(Path baseDir) {
        try {
            Path logDir = baseDir.resolve("logs");
            Files.createDirectories(logDir);
            String date = java.time.LocalDate.now().toString();
            Path logFile = logDir.resolve("selftest-" + date + ".log");
            return new PrintStream(new java.io.FileOutputStream(logFile.toFile(), true), true, "UTF-8");
        } catch (Exception e) {
            System.err.println("[SelfTest] 无法打开日志: " + e.getMessage());
            return System.err;
        }
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

    private static void readAll(java.io.InputStream in, StringBuilder sb) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String l;
            while ((l = r.readLine()) != null) {
                lines.add(l);
                if (lines.size() > 200) {
                    lines.remove(0);
                }
            }
            synchronized (sb) {
                sb.append(String.join("\n", lines));
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

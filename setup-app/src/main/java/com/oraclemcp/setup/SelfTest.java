package com.oraclemcp.setup;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 连通自检模块：走与生产完全一致的链路（tap 包 toolkit），
 * 完成 MCP stdio 握手并调用 db-ping，返回数据库版本与延迟。
 */
public final class SelfTest {

    private static final int TIMEOUT_SECONDS = 90;
    private static final Pattern FIELD = Pattern.compile("(\\w+)\\s*:\\s*(.+)");

    /** 自检结果。 */
    public static final class Result {
        public boolean ok;
        public String detail;
        public String stderrTail;
        public JsonObject fields = new JsonObject();
    }

    private SelfTest() {
    }

    public static Result run(Path root, String env) {
        Result r = new Result();
        // 打开自检专用日志
        PrintStream log = openSelfTestLog(root);
        log.println("=== 自检开始 === " + Instant.now());

        try {
            Path toolkit = root.resolve(Cfg.TOOLKIT_FILE_NAME);
            Path tap = root.resolve(Cfg.TAP_FILE_NAME);
            Path cfg = Installer.configYaml(root, env);
            log.println("toolkit: " + toolkit + " exists=" + Files.isRegularFile(toolkit));
            log.println("tap:     " + tap + " exists=" + Files.isRegularFile(tap));
            log.println("config:  " + cfg + " exists=" + Files.isRegularFile(cfg));

            if (!Files.isRegularFile(toolkit) || !Files.isRegularFile(tap) || !Files.isRegularFile(cfg)) {
                r.ok = false;
                r.detail = "运行时或环境配置缺失，请先部署运行时并保存环境配置";
                log.println("FAIL: " + r.detail);
                return r;
            }

            String java = Installer.resolveJava(root);
            log.println("java:    " + java + " exists=" + Files.isRegularFile(Path.of(java)));

            // 用引号包裹含空格的路径，确保 JVM -D 参数正确解析
            String cfgPath = cfg.toString();
            String tapPath = tap.toString();
            String toolkitPath = toolkit.toString();
            String logPath = Installer.callLog(root, env).toString();

            List<String> cmd = List.of(
                    java, "-jar", tapPath, "--log", logPath, "--",
                    java, "-DconfigFile=\"" + cfgPath + "\"", "-Dtools=db-ping", "-jar", toolkitPath);
            log.println("CMD: " + String.join(" ", cmd));

            Process p = null;
            try {
                final Process proc = new ProcessBuilder(cmd).redirectErrorStream(false).start();
                p = proc;
                log.println("子进程已启动, PID=" + proc.pid());

                StringBuilder errBuf = new StringBuilder();
                Thread errReader = new Thread(() -> readAll(proc.getErrorStream(), errBuf), "selftest-stderr");
                errReader.setDaemon(true);
                errReader.start();

                BufferedWriter w = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader rd = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
                java.util.concurrent.LinkedBlockingQueue<String> lines = new java.util.concurrent.LinkedBlockingQueue<>();
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

                // 1) initialize
                String initMsg = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"oracle-mcp-setup\",\"version\":\"0.1\"}}}";
                log.println(">> initialize");
                w.write(initMsg);
                w.write("\n");
                w.flush();
                String initResp = awaitId(lines, "1");
                if (initResp == null) {
                    r.detail = "MCP 握手超时（initialize 无响应）";
                    r.stderrTail = tail(errBuf);
                    log.println("FAIL: " + r.detail);
                    log.println("stderr: " + r.stderrTail);
                    return r;
                }
                log.println("<< initialize OK: " + initResp.substring(0, Math.min(200, initResp.length())));

                // 2) initialized 通知
                w.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
                w.write("\n");
                w.flush();

                // 3) db-ping
                log.println(">> db-ping");
                w.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"db-ping\",\"arguments\":{}}}");
                w.write("\n");
                w.flush();
                String pingResp = awaitId(lines, "2");
                if (pingResp == null) {
                    r.detail = "db-ping 超时（数据库连接可能缓慢或不可达）";
                    r.stderrTail = tail(errBuf);
                    log.println("FAIL: " + r.detail);
                    log.println("stderr: " + r.stderrTail);
                    return r;
                }
                log.println("<< db-ping: " + pingResp.substring(0, Math.min(500, pingResp.length())));
                parsePing(pingResp, r);
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

    /** 自检日志写入安装目录 logs/selftest-YYYY-MM-DD.log */
    private static PrintStream openSelfTestLog(Path root) {
        try {
            Path logDir = root.resolve("logs");
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
    private static String awaitId(java.util.concurrent.LinkedBlockingQueue<String> lines, String id) {
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

    private static void parsePing(String resp, Result r) {
        try {
            JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
            if (root.has("error")) {
                r.ok = false;
                r.detail = "db-ping 返回错误：" + root.get("error");
                return;
            }
            JsonObject result = root.getAsJsonObject("result");
            boolean isError = result.has("isError") && result.get("isError").getAsBoolean();
            String text = "";
            if (result.has("content") && result.getAsJsonArray("content").size() > 0) {
                text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
            }
            if (isError) {
                r.ok = false;
                r.detail = text.isEmpty() ? "db-ping 失败" : text;
                return;
            }
            String trimmed = text.trim();
            r.ok = trimmed.startsWith("OK");
            r.detail = trimmed.lines().findFirst().orElse("");
            // 解析 "key: value" 行
            text.lines().forEach(line -> {
                Matcher m = FIELD.matcher(line.trim());
                if (m.matches()) {
                    String k = m.group(1).trim();
                    String v = m.group(2).trim();
                    if (!k.isEmpty() && !v.isEmpty()) {
                        r.fields.addProperty(k, v);
                    }
                }
            });
        } catch (Exception e) {
            r.ok = false;
            r.detail = "无法解析 db-ping 响应：" + e.getMessage();
        }
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

package com.oraclemcp.setup;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
        Path toolkit = root.resolve(Cfg.TOOLKIT_FILE_NAME);
        Path tap = root.resolve(Cfg.TAP_FILE_NAME);
        Path cfg = Installer.configYaml(root, env);
        if (!Files(toolkit) || !Files(tap) || !Files(cfg)) {
            r.ok = false;
            r.detail = "运行时或环境配置缺失，请先部署运行时并保存环境配置";
            return r;
        }
        String java = Installer.resolveJava(root); // 优先安装目录精简运行时，与注册链路一致
        List<String> cmd = List.of(
                java, "-jar", tap.toString(), "--log", Installer.callLog(root, env).toString(), "--",
                java, "-DconfigFile=" + cfg.toString(), "-Dtools=db-ping", "-jar", toolkit.toString());
        Process p = null;
        try {
            final Process proc = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            p = proc;
            StringBuilder errBuf = new StringBuilder();
            Thread errReader = new Thread(() -> readAll(proc.getErrorStream(), errBuf), "selftest-stderr");
            errReader.setDaemon(true);
            errReader.start();

            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader rd = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
            // 独立读线程 + 阻塞队列：不依赖 ready()（Windows pipe 上不可靠）
            java.util.concurrent.LinkedBlockingQueue<String> lines = new java.util.concurrent.LinkedBlockingQueue<>();
            Thread reader = new Thread(() -> {
                try {
                    String l;
                    while ((l = rd.readLine()) != null) {
                        lines.put(l);
                    }
                } catch (Exception ignored) {
                    // 进程结束或中断
                }
            }, "selftest-reader");
            reader.setDaemon(true);
            reader.start();

            // 1) initialize
            w.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"oracle-mcp-setup\",\"version\":\"0.1\"}}}");
            w.write("\n"); // MCP stdio 分隔符固定为 \n，不能用平台相关 newLine（Windows 会写 \r\n）
            w.flush();
            String initResp = awaitId(lines, "1");
            if (initResp == null) {
                r.detail = "MCP 握手超时（initialize 无响应）";
                r.stderrTail = tail(errBuf);
                return r;
            }
            // 2) initialized 通知
            w.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            w.write("\n"); // MCP stdio 分隔符固定为 \n，不能用平台相关 newLine（Windows 会写 \r\n）
            w.flush();
            // 3) db-ping
            w.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"db-ping\",\"arguments\":{}}}");
            w.write("\n"); // MCP stdio 分隔符固定为 \n，不能用平台相关 newLine（Windows 会写 \r\n）
            w.flush();
            String pingResp = awaitId(lines, "2");
            if (pingResp == null) {
                r.detail = "db-ping 超时（数据库连接可能缓慢或不可达）";
                r.stderrTail = tail(errBuf);
                return r;
            }
            parsePing(pingResp, r);
            r.stderrTail = tail(errBuf);
            return r;
        } catch (Exception e) {
            r.ok = false;
            r.detail = "自检进程异常：" + e.getMessage();
            return r;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroy();
            }
        }
    }

    private static boolean Files(Path p) {
        return java.nio.file.Files.isRegularFile(p);
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

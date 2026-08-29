package com.dbmcp.setup;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Oracle 适配器：完整还原原 oracle-mcp-setup 的连接/注册/自检行为，
 * 仅把原本散落在各处的 Oracle 字面量收拢到此处。
 */
public final class OracleAdapter implements DbAdapter {

    private static final Pattern FIELD = Pattern.compile("(\\w+)\\s*:\\s*(.+)");

    @Override
    public String id() {
        return "oracle";
    }

    @Override
    public String displayName() {
        return "Oracle";
    }

    @Override
    public String rootSubdir() {
        return "oracle";
    }

    @Override
    public String toolkitFileName() {
        return "oracle-db-mcp-toolkit-1.0.0.jar";
    }

    @Override
    public String skillDir() {
        return "oracle-db-ops";
    }

    @Override
    public String skillResource() {
        return "skill/oracle/SKILL.md";
    }

    @Override
    public int defaultPort() {
        return 1521;
    }

    @Override
    public List<String> requiredTools() {
        return List.of("read-query", "db-ping");
    }

    @Override
    public List<String> allTools() {
        return List.of("read-query", "db-ping", "table", "explain-plan", "write-query");
    }

    @Override
    public String serverPrefix() {
        return "oracle-";
    }

    @Override
    public RuntimeKind runtimeKind() {
        return RuntimeKind.JAVA_JAR;
    }

    @Override
    public String configFileName() {
        return "config.yaml";
    }

    @Override
    public String buildJdbcUrl(String host, int port, String service) {
        return "jdbc:oracle:thin:@" + host + ":" + port + "/" + service;
    }

    @Override
    public String renderConfig(String env, String url, String user, String password) {
        return """
                # DB MCP Helper 环境配置（由 db-mcp-setup 生成）
                # 修改后需在 AI 平台的连接器管理中对 %s%s 执行 disable→enable 才会重载
                dataSources:
                  %s:
                    url: "%s"
                    user: "%s"
                    password: "%s"
                """.formatted(serverPrefix() + env, env, yamlEscape(url), yamlEscape(user), yamlEscape(password));
    }

    @Override
    public Map<String, String> envVars(String url, String user, String password, int port, String db) {
        return Map.of();
    }

    @Override
    public List<String> buildCommand(Path baseDir, String dbId, String env, List<String> tools) {
        String java = Installer.resolveJava(baseDir);
        return List.of(
                java, "-jar", baseDir.resolve("tap").resolve(Cfg.TAP_FILE_NAME).toString(),
                "--log", Installer.callLog(baseDir, dbId, env).toString(), "--",
                java, "-DconfigFile=" + Installer.configFile(baseDir, dbId, env, this).toString(),
                "-Dtools=" + String.join(",", tools),
                "-jar", Installer.toolkitPath(baseDir, dbId, this).toString());
    }

    @Override
    public String pingTool() {
        return "db-ping";
    }

    @Override
    public JsonObject pingArguments() {
        return new JsonObject();
    }

    @Override
    public void parsePing(String resp, SelfTest.Result r) {
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

    private static String yamlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

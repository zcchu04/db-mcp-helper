package com.dbmcp.setup;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL 适配器：基于主流 Node 版 mysql-mcp-server（community）。
 *
 * <p>与原 Oracle 链路的本质差异（已核实 MySQL 无官方 Java toolkit）：
 * <ul>
 *   <li>连接配置走<b>环境变量</b>（MYSQL_HOST/PORT/USER/PASSWORD/DATABASE），而非 config.yaml；
 *       注册时通过 mcp.json 的 env 块注入，自检时注入进程环境。</li>
 *   <li>运行时为捆绑的 node（baseDir/mysql/runtime/node），而非 jlink JRE。</li>
 *   <li>无 db-ping 工具，自检改用 query 工具执行 {@code SELECT 1}。</li>
 * </ul>
 *
 * <p>注：默认对接的 server 仓库与工具名（query/insert/update/delete）可按实际内置仓库调整，
 * 改动集中在 {@link #allTools()} 与 {@link #envVars(String, String, String, int, String)} 两处即可。
 */
public final class MySqlAdapter implements DbAdapter {

    /** 工具名约定（可随实际内置 server 调整）。 */
    private static final List<String> TOOLS = List.of("query", "insert", "update", "delete");

    @Override
    public String id() {
        return "mysql";
    }

    @Override
    public String displayName() {
        return "MySQL";
    }

    @Override
    public String rootSubdir() {
        return "mysql";
    }

    @Override
    public String toolkitFileName() {
        return "mysql-mcp-server";
    }

    @Override
    public String skillDir() {
        return "mysql-db-ops";
    }

    @Override
    public String skillResource() {
        return "skill/mysql/SKILL.md";
    }

    @Override
    public int defaultPort() {
        return 3306;
    }

    @Override
    public List<String> requiredTools() {
        return List.of("query");
    }

    @Override
    public List<String> allTools() {
        return TOOLS;
    }

    @Override
    public String serverPrefix() {
        return "mysql-";
    }

    @Override
    public RuntimeKind runtimeKind() {
        return RuntimeKind.NODE;
    }

    @Override
    public String configFileName() {
        return ".env";
    }

    @Override
    public String buildJdbcUrl(String host, int port, String db) {
        return "jdbc:mysql://" + host + ":" + port + "/" + db;
    }

    @Override
    public String renderConfig(String env, String url, String user, String password) {
        // 仅作本地留档（server 实际读进程环境变量）；均通过 # 注释呈现，避免被误当文件配置
        StringBuilder sb = new StringBuilder();
        sb.append("# DB MCP Helper 环境配置（由 db-mcp-setup 生成，MySQL 实际以环境变量注入，此文件仅留档）\n");
        sb.append("# 修改后需在 AI 平台的连接器管理中对 ").append(serverPrefix()).append(env).append(" 执行 disable→enable 才会重载\n");
        envVars(url, user, password, defaultPort(), dbFromUrl(url)).forEach((k, v) ->
                sb.append("# ").append(k).append("=").append(v).append("\n"));
        return sb.toString();
    }

    @Override
    public Map<String, String> envVars(String url, String user, String password, int port, String db) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("MYSQL_HOST", hostFromUrl(url));
        env.put("MYSQL_PORT", String.valueOf(port));
        env.put("MYSQL_USER", user);
        env.put("MYSQL_PASSWORD", password);
        env.put("MYSQL_DATABASE", db);
        return env;
    }

    @Override
    public List<String> buildCommand(Path baseDir, String dbId, String env, List<String> tools) {
        String java = Installer.resolveJava(baseDir);
        Path dbDir = Installer.dbDir(baseDir, dbId);
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path nodeExe = dbDir.resolve("runtime").resolve("node").resolve(win ? "node.exe" : "node");
        Path serverEntry = dbDir.resolve("toolkit").resolve(toolkitFileName()).resolve("build").resolve("index.js");
        return List.of(
                java, "-jar", baseDir.resolve("tap").resolve(Cfg.TAP_FILE_NAME).toString(),
                "--log", Installer.callLog(baseDir, dbId, env).toString(), "--",
                nodeExe.toString(), serverEntry.toString());
    }

    @Override
    public String pingTool() {
        return "query";
    }

    @Override
    public JsonObject pingArguments() {
        JsonObject args = new JsonObject();
        args.addProperty("sql", "SELECT 1");
        return args;
    }

    @Override
    public void parsePing(String resp, SelfTest.Result r) {
        try {
            JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
            if (root.has("error")) {
                r.ok = false;
                r.detail = "query 返回错误：" + root.get("error");
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
                r.detail = text.isEmpty() ? "query 失败" : text;
                return;
            }
            r.ok = !text.isBlank();
            r.detail = text.trim().lines().findFirst().orElse("OK").length() > 200
                    ? text.trim().lines().findFirst().orElse("OK").substring(0, 200) + "..."
                    : text.trim().lines().findFirst().orElse("OK");
            r.fields.addProperty("db", "mysql");
        } catch (Exception e) {
            r.ok = false;
            r.detail = "无法解析 query 响应：" + e.getMessage();
        }
    }

    private static String hostFromUrl(String url) {
        if (url == null) {
            return "";
        }
        // jdbc:mysql://host:port/db 或 mysql://host:port/db
        int at = url.lastIndexOf("@");
        String s = at >= 0 ? url.substring(at + 1) : url;
        int slash = s.indexOf("://");
        if (slash >= 0) {
            s = s.substring(slash + 3);
        }
        int colon = s.indexOf(':');
        int q = s.indexOf('/');
        int end = Math.min(colon < 0 ? Integer.MAX_VALUE : colon, q < 0 ? Integer.MAX_VALUE : q);
        return end < 0 ? s : s.substring(0, end);
    }

    private static String dbFromUrl(String url) {
        if (url == null) {
            return "";
        }
        int slash = url.lastIndexOf('/');
        if (slash < 0 || slash == url.length() - 1) {
            return "";
        }
        int q = url.indexOf('?', slash);
        return q < 0 ? url.substring(slash + 1) : url.substring(slash + 1, q);
    }
}

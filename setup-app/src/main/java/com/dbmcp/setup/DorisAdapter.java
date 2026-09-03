package com.dbmcp.setup;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Apache Doris 适配器：Doris 兼容 MySQL 线协议（默认端口 9030），
 * 因此复用 MySQL 的 MCP server toolkit 与 node 运行时，仅在连接参数与展示层做差异化。
 *
 * <p>资源复用通过 {@link #toolkitSourceDbId()} 与 {@link #runtimeSourceDbId()} 返回 {@code "mysql"}
 * 实现——Installer 从 classpath 的 toolkit/mysql/ 与 runtime/mysql/ 取资源，部署到 baseDir/doris/ 下。
 */
public final class DorisAdapter implements DbAdapter {

    private static final List<String> TOOLS_BENBORLA =
            List.of("mysql_query", "mysql_insert", "mysql_update", "mysql_delete");
    private static final List<String> REQUIRED_BENBORLA = List.of("mysql_query");
    private static final List<String> TOOLS_NAGA = List.of(
            "mysql_query", "mysql_insert", "mysql_update", "mysql_delete",
            "mysql_show_tables", "mysql_describe_table", "mysql_show_databases", "mysql_test_connection");
    private static final List<String> REQUIRED_NAGA = List.of("mysql_query");

    @Override
    public String id() {
        return "doris";
    }

    @Override
    public String displayName() {
        return "Apache Doris";
    }

    @Override
    public String rootSubdir() {
        return "doris";
    }

    @Override
    public String toolkitFileName() {
        return "mysql-mcp-server";
    }

    @Override
    public List<String> extraToolkitDirResources() {
        return List.of("toolkit/mysql/" + MySqlAdapter.nagaDirName());
    }

    @Override
    public String toolkitSourceDbId() {
        return "mysql";
    }

    @Override
    public String runtimeSourceDbId() {
        return "mysql";
    }

    @Override
    public String skillDir() {
        return "doris-db-ops";
    }

    @Override
    public String skillResource() {
        return "skill/doris/SKILL.md";
    }

    @Override
    public int defaultPort() {
        return 9030;
    }

    @Override
    public List<String> requiredTools() {
        return REQUIRED_BENBORLA;
    }

    @Override
    public List<String> allTools() {
        return TOOLS_BENBORLA;
    }

    @Override
    public String serverPrefix() {
        return "doris-";
    }

    @Override
    public List<McpServerOption> mcpServerOptions() {
        return List.of(
                new McpServerOption(
                        MySqlAdapter.IMPL_BENBORLA,
                        "@benborla29/mcp-server-mysql (Node)",
                        "单工具 mysql_query 执行任意 SQL；mysql_insert/update/delete 为写能力开关（默认只读）",
                        TOOLS_BENBORLA,
                        REQUIRED_BENBORLA),
                new McpServerOption(
                        MySqlAdapter.IMPL_NAGA,
                        "@naganpm/mysql-mcp-server (Node)",
                        "8 个细粒度工具：增删改查独立工具 + 表结构/库列表/连接测试；mysql_query 仅 SELECT",
                        TOOLS_NAGA,
                        REQUIRED_NAGA));
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
        StringBuilder sb = new StringBuilder();
        sb.append("# DB MCP Helper 环境配置（由 db-mcp-setup 生成 — Doris）\n");
        sb.append("# 修改后需在 AI 平台的连接器管理中对 ").append(serverPrefix()).append(env).append(" 执行 disable→enable 才会重载\n");
        int port = MySqlAdapter.portFromUrl(url);
        envVars(url, user, password, port > 0 ? port : defaultPort(), MySqlAdapter.dbFromUrl(url), List.of())
                .forEach((k, v) -> sb.append(k).append("=").append(v).append("\n"));
        return sb.toString();
    }

    @Override
    public Map<String, String> envVars(String url, String user, String password, int port, String db, List<String> tools) {
        Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put("MYSQL_HOST", MySqlAdapter.hostFromUrl(url));
        env.put("MYSQL_PORT", String.valueOf(port));
        env.put("MYSQL_USER", user);
        env.put("MYSQL_PASSWORD", password);
        env.put("MYSQL_DATABASE", db);
        List<String> ts = tools == null ? List.of() : tools;
        if (ts.contains("mysql_insert")) {
            env.put("ALLOW_INSERT_OPERATION", "true");
        }
        if (ts.contains("mysql_update")) {
            env.put("ALLOW_UPDATE_OPERATION", "true");
        }
        if (ts.contains("mysql_delete")) {
            env.put("ALLOW_DELETE_OPERATION", "true");
        }
        return env;
    }

    @Override
    public List<String> buildCommand(Path baseDir, String dbId, String env, List<String> tools, String mcpServer) {
        String java = Installer.resolveJava(baseDir);
        Path dorisDir = Installer.dbDir(baseDir, dbId);
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path nodeExe = dorisDir.resolve("runtime").resolve("node").resolve(win ? "node.exe" : "node");
        Path serverEntry = MySqlAdapter.IMPL_NAGA.equals(mcpServer)
                ? dorisDir.resolve("toolkit").resolve(MySqlAdapter.nagaDirName()).resolve("dist").resolve("index.js")
                : dorisDir.resolve("toolkit").resolve(toolkitFileName()).resolve("build").resolve("index.js");
        return List.of(
                java, "-jar", baseDir.resolve("tap").resolve(Cfg.TAP_FILE_NAME).toString(),
                "--log", Installer.callLog(baseDir, dbId, env, mcpServer).toString(), "--",
                nodeExe.toString(), serverEntry.toString());
    }

    @Override
    public String pingTool() {
        return "mysql_query";
    }

    @Override
    public JsonObject pingArguments(String mcpServer) {
        JsonObject args = new JsonObject();
        if (MySqlAdapter.IMPL_NAGA.equals(mcpServer)) {
            args.addProperty("query", "SELECT 1");
        } else {
            args.addProperty("sql", "SELECT 1");
        }
        return args;
    }

    @Override
    public void parsePing(String resp, SelfTest.Result r) {
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(resp).getAsJsonObject();
            if (root.has("error")) {
                r.ok = false;
                r.detail = "query 返回错误：" + root.get("error");
                return;
            }
            JsonObject result = root.has("result") ? root.getAsJsonObject("result") : root;
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
            r.ok = true;
            r.detail = "OK — 连通成功（SELECT 1）";
            r.fields.addProperty("db", "doris");
        } catch (Exception e) {
            r.ok = false;
            r.detail = "无法解析 query 响应：" + e.getMessage();
        }
    }
}

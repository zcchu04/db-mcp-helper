package com.dbmcp.setup;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL 适配器：内置两种可选择的 MCP server 实现（配置实例页底部单选）。
 *
 * <p><b>实现一：benborla29 —— @benborla29/mcp-server-mysql 1.x（Node，默认）</b>
 * <ul>
 *   <li>单工具 {@code mysql_query}（万能 SQL 入口），与三个"写能力开关项"
 *       {@code mysql_insert / mysql_update / mysql_delete} 组成勾选清单——
 *       后三项并非真实工具，勾选后在注册/自检时注入对应环境变量
 *       {@code ALLOW_INSERT/UPDATE/DELETE_OPERATION=true}（server 源码默认拒绝写操作）。</li>
 *   <li>连接配置走环境变量（MYSQL_HOST/PORT/USER/PASSWORD/DATABASE），本包实际读取
 *       MYSQL_PASS/MYSQL_DB，由 toolkit 的桥接 shim 转发；运行时为捆绑 node。</li>
 *   <li>无独立 db-ping，自检用 {@code mysql_query} 执行 {@code SELECT 1}。</li>
 * </ul>
 *
 * <p><b>实现二：naganpm —— @naganpm/mysql-mcp-server 2.x（Node，可选）</b>
 * <ul>
 *   <li>8 个细粒度真工具：mysql_query（仅 SELECT）/ mysql_insert / mysql_update / mysql_delete /
 *       mysql_show_tables / mysql_describe_table / mysql_show_databases / mysql_test_connection。</li>
 *   <li>环境变量名（MYSQL_HOST/PORT/USER/PASSWORD/DATABASE）与适配器注入完全一致，无需 shim；
 *       入口 dist/index.js（ESM）。</li>
 * </ul>
 */
public final class MySqlAdapter implements DbAdapter {

    /** 实现一（benborla29）的勾选清单：1 个真实工具 + 3 个写能力开关项。 */
    private static final List<String> TOOLS_BENBORLA =
            List.of("mysql_query", "mysql_insert", "mysql_update", "mysql_delete");
    /** 实现一必选项。 */
    private static final List<String> REQUIRED_BENBORLA = List.of("mysql_query");
    /** 实现二（naganpm）的 8 个真实工具。 */
    private static final List<String> TOOLS_NAGA = List.of(
            "mysql_query", "mysql_insert", "mysql_update", "mysql_delete",
            "mysql_show_tables", "mysql_describe_table", "mysql_show_databases", "mysql_test_connection");
    /** 实现二必选项（自检用 mysql_query）。 */
    private static final List<String> REQUIRED_NAGA = List.of("mysql_query");

    /** 实现一 id（与 mcpServerOptions 首项一致；EnvInfo.mcpServer 为空时也按它处理）。 */
    public static final String IMPL_BENBORLA = "benborla29";
    /** 实现二 id。 */
    public static final String IMPL_NAGA = "naganpm";

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
    public List<String> extraToolkitDirResources() {
        return List.of("toolkit/mysql/" + nagaDirName());
    }

    /** naga 实现的内置目录名（toolkit/mysql/ 下，与 benborla29 的 mysql-mcp-server 并列）。 */
    public static String nagaDirName() {
        return "mysql-naga-mcp-server";
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
        return REQUIRED_BENBORLA;
    }

    @Override
    public List<String> allTools() {
        return TOOLS_BENBORLA;
    }

    @Override
    public String serverPrefix() {
        return "mysql-";
    }

    /**
     * 可选 MCP server 实现（配置实例页底部单选）。工具清单随实现下发，前端按所选实现渲染勾选区。
     */
    @Override
    public List<McpServerOption> mcpServerOptions() {
        return List.of(
                new McpServerOption(
                        IMPL_BENBORLA,
                        "@benborla29/mcp-server-mysql (Node)",
                        "单工具 mysql_query 执行任意 SQL；mysql_insert/update/delete 为写能力开关（默认只读）",
                        TOOLS_BENBORLA,
                        REQUIRED_BENBORLA),
                new McpServerOption(
                        IMPL_NAGA,
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
        sb.append("# DB MCP Helper 环境配置（由 db-mcp-setup 生成）\n");
        sb.append("# 修改后需在 AI 平台的连接器管理中对 ").append(serverPrefix()).append(env).append(" 执行 disable→enable 才会重载\n");
        int port = portFromUrl(url);
        envVars(url, user, password, port > 0 ? port : defaultPort(), dbFromUrl(url), List.of()).forEach((k, v) ->
                sb.append(k).append("=").append(v).append("\n"));
        return sb.toString();
    }

    /**
     * 连接要素 + 写能力开关注入。
     * benborla29 的 server 默认拒绝 INSERT/UPDATE/DELETE（源码 ALLOW_*_OPERATION 默认 false），
     * 勾选对应开关项后在此注入 true 放开；naganpm 的写操作是真工具隔离，注入这些变量无效但无害。
     */
    @Override
    public Map<String, String> envVars(String url, String user, String password, int port, String db, List<String> tools) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("MYSQL_HOST", hostFromUrl(url));
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

    /** 按实现分派 server 入口：benborla29 → 桥接 shim（build/index.js）；naganpm → dist/index.js（ESM）。 */
    @Override
    public List<String> buildCommand(Path baseDir, String dbId, String env, List<String> tools, String mcpServer) {
        String java = Installer.resolveJava(baseDir);
        Path dbDir = Installer.dbDir(baseDir, dbId);
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path nodeExe = dbDir.resolve("runtime").resolve("node").resolve(win ? "node.exe" : "node");
        Path serverEntry = IMPL_NAGA.equals(mcpServer)
                ? dbDir.resolve("toolkit").resolve(nagaDirName()).resolve("dist").resolve("index.js")
                : dbDir.resolve("toolkit").resolve(toolkitFileName()).resolve("build").resolve("index.js");
        return List.of(
                java, "-jar", baseDir.resolve("tap").resolve(Cfg.TAP_FILE_NAME).toString(),
                "--log", Installer.callLog(baseDir, dbId, env, mcpServer).toString(), "--",
                nodeExe.toString(), serverEntry.toString());
    }

    @Override
    public String pingTool() {
        return "mysql_query";
    }

    /** 自检入参按实现分派：benborla29 参数名 sql；naganpm 参数名 query（且仅允许 SELECT）。 */
    @Override
    public JsonObject pingArguments(String mcpServer) {
        JsonObject args = new JsonObject();
        if (IMPL_NAGA.equals(mcpServer)) {
            args.addProperty("query", "SELECT 1");
        } else {
            args.addProperty("sql", "SELECT 1");
        }
        return args;
    }

    @Override
    public void parsePing(String resp, SelfTest.Result r) {
        // 两实现的 mysql_query 工具响应结构一致：
        // 成功 { content:[{type:"text", text: <json rows>}], isError:false }
        // 失败 由框架返回 { error:{ code, message } } 或 { content:[...], isError:true }
        try {
            JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
            if (root.has("error")) {
                r.ok = false;
                r.detail = "query 返回错误：" + root.get("error");
                return;
            }
            // 响应 content 套在 result 之下（与 Oracle 一致），需先解 result 再取 content
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
            // 通过 isError 判定即表示连通与 SELECT 1 执行成功（两实现 mysql_query 返回行集）
            r.ok = true;
            r.detail = "OK — 连通成功（SELECT 1）";
            r.fields.addProperty("db", "mysql");
        } catch (Exception e) {
            r.ok = false;
            r.detail = "无法解析 query 响应：" + e.getMessage();
        }
    }

    static String hostFromUrl(String url) {
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

    static String dbFromUrl(String url) {
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

    static int portFromUrl(String url) {
        if (url == null) {
            return -1;
        }
        int at = url.lastIndexOf("@");
        String s = at >= 0 ? url.substring(at + 1) : url;
        int slash = s.indexOf("://");
        if (slash >= 0) {
            s = s.substring(slash + 3);
        }
        int colon = s.indexOf(':');
        if (colon < 0) {
            return -1;
        }
        int q = s.indexOf('/', colon);
        String portStr = q < 0 ? s.substring(colon + 1) : s.substring(colon + 1, q);
        try {
            return Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

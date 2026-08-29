package com.dbmcp.setup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DB MCP Helper 安装向导入口：JDK 内置 HttpServer 提供向导页与 REST API。
 * 引擎对具体数据库无感知，全部行为由 DbAdapter 驱动。
 *
 * <p>API 一览（JSON）：
 * GET /api/detect · GET /api/adapters · POST /api/deploy · POST /api/env/config ·
 * POST /api/env/parse · POST /api/env/test · POST /api/env/test/poll ·
 * POST /api/env/register · POST /api/env/delete · GET /api/env/log ·
 * GET /api/env/guide · POST /api/skill/deploy · POST /api/skill/sync ·
 * POST /api/skill/targets · GET /api/mcp/targets · POST /api/mcp/register ·
 * POST /api/mcp/unregister · POST /api/reset · POST /api/uninstall
 */
public final class SetupMain {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static HttpServer serverRef;
    private static final java.util.concurrent.ConcurrentHashMap<String, SelfTest.Result> TEST_RESULTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final SelfTest.Result RUNNING_MARKER = new SelfTest.Result();
    static { RUNNING_MARKER.detail = "running"; }

    static boolean NO_BROWSER = false;

    public static void main(String[] args) {
        for (String a : args) {
            if ("--no-browser".equals(a)) NO_BROWSER = true;
        }
        Path logDir = resolveLogDir();
        java.io.PrintStream log = openLog(logDir);
        log.println("=== DB MCP Helper 启动日志 ===");
        log.println("时间: " + Instant.now());
        log.println("Java 版本: " + System.getProperty("java.version"));
        log.println("工作目录: " + System.getProperty("user.dir"));
        log.println("os.name: " + System.getProperty("os.name"));
        log.println("jpackage.app-path: " + System.getProperty("jpackage.app-path", "(未设置)"));
        log.println("命令行参数: " + java.util.Arrays.toString(args));
        log.println();

        try {
            int port = Cfg.port();
            String url = "http://127.0.0.1:" + port;
            log.println("目标端口: " + port);

            if (isOurServerRunning(port)) {
                log.println("检测到 DB MCP Helper 已在运行，直接打开浏览器...");
                openBrowser(url);
                return;
            }

            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/", SetupMain::handle);
            server.start();
            serverRef = server;
            System.out.println("DB MCP Helper 已启动：" + url);
            openBrowser(url);
        } catch (Throwable t) {
            log.println("!!! 启动失败 !!!");
            t.printStackTrace(log);
            t.printStackTrace(System.err);
        } finally {
            log.println("=== 日志结束 ===");
            log.flush();
            log.close();
        }
    }

    private static Path resolveLogDir() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            Path exe = Path.of(appPath);
            Path appDir = Files.isRegularFile(exe) ? exe.getParent() : exe;
            return appDir.resolve("logs");
        }
        return Path.of(System.getProperty("user.home"), ".db-mcp-helper", "logs");
    }

    private static java.io.PrintStream openLog(Path logDir) {
        try {
            Files.createDirectories(logDir);
            String date = java.time.LocalDate.now().toString();
            Path logFile = logDir.resolve("setup-" + date + ".log");
            return new java.io.PrintStream(new java.io.FileOutputStream(logFile.toFile(), true), true, "UTF-8");
        } catch (Exception e) {
            System.err.println("无法打开日志文件: " + e.getMessage());
            return System.err;
        }
    }

    private static boolean isOurServerRunning(int port) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL("http://127.0.0.1:" + port + "/api/detect").openConnection();
            conn.setConnectTimeout(500);
            conn.setReadTimeout(500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if ("/".equals(path) || "/index.html".equals(path)) {
                serveResource(ex, "index.html", "text/html; charset=utf-8");
                return;
            }
            if ("/styles.css".equals(path)) {
                serveResource(ex, "styles.css", "text/css; charset=utf-8");
                return;
            }
            if ("/app.js".equals(path)) {
                serveResource(ex, "app.js", "application/javascript; charset=utf-8");
                return;
            }
            if (!path.startsWith("/api/")) {
                send(ex, 404, err("未知路径"));
                return;
            }
            JsonObject body = ("POST".equals(method) || "PUT".equals(method)) ? readBody(ex) : new JsonObject();
            JsonObject resp = route(path, body, ex.getRequestURI(), ex.getRequestMethod());
            send(ex, 200, resp);
        } catch (Exception e) {
            send(ex, 200, err(e.getMessage() == null ? e.toString() : e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private static JsonObject route(String path, JsonObject body, URI uri, String method) throws IOException {
        switch (path) {
            case "/api/detect":
                return ok(detect());
            case "/api/adapters":
                return ok(adapters());
            case "/api/deploy":
                return ok(deploy(body));
            case "/api/env/config":
                return ok(envConfig(body));
            case "/api/env/parse":
                return ok(envParse(body));
            case "/api/env/test":
                return ok(envTest(body));
            case "/api/env/test/poll":
                return ok(envTestPoll(query(uri, "env")));
            case "/api/env/register":
                return ok(envRegister(body));
            case "/api/env/delete":
                return ok(envDelete(body));
            case "/api/env/log":
                return envLog(uri);
            case "/api/env/guide":
                return envGuide(uri);
            case "/api/skill/deploy":
                return ok(skillDeploy(body));
            case "/api/skill/sync":
                return ok(skillSync());
            case "/api/skill/targets":
                if ("POST".equals(method)) {
                    String action = str(body, "action");
                    if ("add".equals(action)) {
                        return ok(skillAddTarget(body));
                    } else if ("remove".equals(action)) {
                        return ok(skillRemoveTarget(body));
                    }
                }
                return ok(skillListTargets());
            case "/api/mcp/targets":
                return ok(mcpListTargets());
            case "/api/mcp/register":
                return ok(mcpRegister(body));
            case "/api/mcp/unregister":
                return ok(mcpUnregister(body));
            case "/api/mcp/commands":
                return ok(mcpCommands(body));
            case "/api/reset":
                return ok(reset());
            case "/api/reset/preview":
                return ok(resetPreview());
            case "/api/open":
                return ok(openPath(body));
            case "/api/prefs":
                if ("POST".equals(method) || "PUT".equals(method)) {
                    return ok(Prefs.merge(body));
                }
                return ok(Prefs.load());
            case "/api/uninstall":
                return ok(uninstall());
            default:
                return err("未知接口：" + path);
        }
    }

    // ---------- adapters ----------

    private static JsonObject adapters() {
        JsonArray arr = new JsonArray();
        for (DbAdapter a : DbAdapters.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", a.id());
            o.addProperty("displayName", a.displayName());
            o.addProperty("defaultPort", a.defaultPort());
            o.addProperty("serverPrefix", a.serverPrefix());
            o.addProperty("skillDir", a.skillDir());
            o.addProperty("runtimeKind", a.runtimeKind().name());
            JsonArray all = new JsonArray();
            a.allTools().forEach(all::add);
            o.add("allTools", all);
            JsonArray req = new JsonArray();
            a.requiredTools().forEach(req::add);
            o.add("requiredTools", req);
            arr.add(o);
        }
        JsonObject d = new JsonObject();
        d.add("adapters", arr);
        return d;
    }

    // ---------- detect ----------

    private static JsonObject detect() {
        Path root = resolveRoot(null);
        State st = State.load(root);
        JsonObject d = new JsonObject();
        d.addProperty("home", normalizePath(Cfg.home().toString()));
        d.addProperty("root", normalizePath(root.toString()));
        d.addProperty("rootExists", Files.isDirectory(root));
        d.addProperty("tapDeployed", Files.isRegularFile(root.resolve("tap").resolve(Cfg.TAP_FILE_NAME)));
        d.addProperty("javaCmd", normalizePath(Cfg.javaCmd()));
        d.addProperty("mcpJsonPath", normalizePath(Cfg.mcpJsonPath().toString()));
        d.addProperty("qoderPluginMcpJsonPath", normalizePath(Cfg.qoderPluginMcpJsonPath().toString()));
        JsonArray registered = new JsonArray();
        McpJson.serverNames(Cfg.mcpJsonPath()).stream()
                .filter(n -> DbAdapters.all().stream().anyMatch(a -> n.startsWith(a.serverPrefix()))).forEach(registered::add);
        d.add("registeredServers", registered);
        JsonArray qoderPluginRegistered = new JsonArray();
        McpJson.serverNames(Cfg.qoderPluginMcpJsonPath()).stream()
                .filter(n -> DbAdapters.all().stream().anyMatch(a -> n.startsWith(a.serverPrefix()))).forEach(qoderPluginRegistered::add);
        d.add("qoderPluginRegisteredServers", qoderPluginRegistered);
        if (st != null) {
            JsonObject stateJson = GSON.toJsonTree(st).getAsJsonObject();
            if (stateJson.has("root")) {
                stateJson.addProperty("root", normalizePath(stateJson.get("root").getAsString()));
            }
            if (stateJson.has("javaCmd")) {
                stateJson.addProperty("javaCmd", normalizePath(stateJson.get("javaCmd").getAsString()));
            }
            if (stateJson.has("skillTargets")) {
                JsonArray targets = stateJson.getAsJsonArray("skillTargets");
                JsonArray normalized = new JsonArray();
                targets.forEach(e -> normalized.add(normalizePath(e.getAsString())));
                stateJson.add("skillTargets", normalized);
            }
            d.add("state", stateJson);
        }
        return d;
    }

    // ---------- deploy ----------

    private static JsonObject deploy(JsonObject body) throws IOException {
        String dbId = str(body, "dbId");
        DbAdapter adapter = DbAdapters.require(dbId);
        String reqRoot = str(body, "root");
        Path root = reqRoot != null && !reqRoot.isBlank() ? Path.of(reqRoot.trim()) : Cfg.installDir();
        validateInstallRoot(root);
        State st = Installer.deploy(root, dbId, adapter);
        Cfg.writeLastRoot(root);
        JsonObject d = new JsonObject();
        d.addProperty("root", root.toString());
        d.addProperty("dbId", dbId);
        d.add("state", GSON.toJsonTree(st));
        return d;
    }

    // ---------- env config ----------

    private static JsonObject envConfig(JsonObject body) throws IOException {
        Path root = requireRoot();
        String dbId = str(body, "dbId");
        DbAdapter adapter = DbAdapters.require(dbId);
        String env = str(body, "env");
        if (!Installer.validEnvName(env)) {
            throw new IllegalArgumentException("环境编码不合法：仅小写字母/数字/连字符，字母开头，≤32 位");
        }
        String host = str(body, "host");
        String user = str(body, "user");
        String password = str(body, "password");
        String jdbcUrl = str(body, "jdbcUrl");
        int port = body.has("port") && !body.get("port").getAsString().isBlank()
                ? Integer.parseInt(body.get("port").getAsString().trim()) : adapter.defaultPort();
        String service = str(body, "service");
        String database = str(body, "database");
        String paste = str(body, "paste");
        if (paste != null && !paste.isBlank()) {
            java.util.Map<String, String> p = ConfigParser.extract(paste);
            if (isBlank(jdbcUrl) && isBlank(host) && p.containsKey("url")) {
                String[] parts = ConfigParser.splitSimpleUrl(p.get("url"));
                if (parts != null) {
                    host = parts[0];
                    port = Integer.parseInt(parts[1]);
                    if (adapter.id().equals("oracle")) {
                        service = parts[2];
                    } else {
                        database = parts[2];
                    }
                } else {
                    jdbcUrl = p.get("url");
                }
            }
            if (isBlank(user)) {
                user = p.get("username");
            }
            if (isBlank(password)) {
                password = p.get("password");
            }
        }
        String name = adapter.id().equals("oracle")
                ? (service != null ? service : "")
                : (database != null ? database : "");
        String url = jdbcUrl != null && !jdbcUrl.isBlank() ? jdbcUrl.trim() : adapter.buildJdbcUrl(host, port, name);
        if (user == null || user.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }
        List<String> tools = strList(body, "tools");
        if (!tools.containsAll(adapter.requiredTools())) {
            throw new IllegalArgumentException("必选工具缺失：" + String.join(",", adapter.requiredTools()));
        }
        Installer.writeEnvConfig(root, dbId, env, adapter, url, user.trim(), password);

        State st = State.load(root);
        if (st == null) {
            throw new IllegalStateException("未检测到部署状态，请先部署运行时");
        }
        State.EnvInfo info = st.envs.computeIfAbsent(env, k -> new State.EnvInfo());
        info.dbType = adapter.id();
        info.aliases = strList(body, "aliases");
        info.tools = tools;
        info.host = host;
        info.port = port;
        info.database = name;
        info.user = user.trim();
        info.password = password;
        info.url = url;
        st.save(root);

        JsonObject d = new JsonObject();
        d.addProperty("env", env);
        d.addProperty("dbId", dbId);
        d.addProperty("configPath", Installer.configFile(root, dbId, env, adapter).toString());
        d.addProperty("url", url);
        return d;
    }

    // ---------- env parse（粘贴解析） ----------

    private static JsonObject envParse(JsonObject body) {
        String dbId = str(body, "dbId");
        DbAdapter adapter = dbId != null ? DbAdapters.get(dbId) : null;
        java.util.Map<String, String> p = ConfigParser.extract(str(body, "text"));
        JsonObject d = new JsonObject();
        d.addProperty("foundUrl", p.containsKey("url"));
        d.addProperty("foundUser", p.containsKey("username"));
        d.addProperty("foundPassword", p.containsKey("password"));
        String url = p.get("url");
        if (url != null) {
            String[] parts = ConfigParser.splitSimpleUrl(url);
            if (parts != null) {
                d.addProperty("host", parts[0]);
                d.addProperty("port", parts[1]);
                if (adapter != null && adapter.id().equals("oracle")) {
                    d.addProperty("service", parts[2]);
                } else {
                    d.addProperty("database", parts[2]);
                }
            } else {
                d.addProperty("jdbcUrl", url);
            }
        }
        if (p.containsKey("username")) {
            d.addProperty("user", p.get("username"));
        }
        if (p.containsKey("password")) {
            d.addProperty("hasPassword", true);
        }
        d.addProperty("parseable", !p.isEmpty());
        return d;
    }

    // ---------- env test（异步） ----------

    private static JsonObject envTest(JsonObject body) {
        Path root = requireRoot();
        String dbId = str(body, "dbId");
        String env = str(body, "env");
        if (dbId == null || dbId.isBlank() || env == null || env.isBlank()) {
            return err("dbId 与 env 参数不能为空");
        }
        DbAdapter adapter = DbAdapters.require(dbId);
        Map<String, String> envVars = resolveEnvVars(root, dbId, env, adapter);
        TEST_RESULTS.put(env, RUNNING_MARKER);
        new Thread(() -> {
            SelfTest.Result r = SelfTest.run(root, dbId, env, adapter, envVars);
            TEST_RESULTS.put(env, r);
            try {
                State st = State.load(root);
                if (st != null && st.envs.containsKey(env)) {
                    State.LastTest lt = new State.LastTest();
                    lt.ok = r.ok;
                    lt.detail = r.detail;
                    lt.ts = Instant.now().toString();
                    st.envs.get(env).lastTest = lt;
                    st.save(root);
                }
            } catch (Exception ignored) {
            }
        }, "selftest-" + dbId + "-" + env).start();
        JsonObject d = new JsonObject();
        d.addProperty("ok", false);
        d.addProperty("detail", "自检已启动，请稍后刷新查看结果");
        d.addProperty("running", true);
        return d;
    }

    private static Map<String, String> resolveEnvVars(Path root, String dbId, String env, DbAdapter adapter) {
        State st = State.load(root);
        if (st != null && st.envs.containsKey(env)) {
            State.EnvInfo info = st.envs.get(env);
            return adapter.envVars(info.url, info.user, info.password, info.port, info.database);
        }
        return adapter.envVars("", "", "", adapter.defaultPort(), "");
    }

    private static JsonObject envTestPoll(String env) {
        SelfTest.Result r = TEST_RESULTS.get(env);
        JsonObject d = new JsonObject();
        if (r == null) {
            d.addProperty("ok", false);
            d.addProperty("detail", "尚未执行自检");
        } else if (r == RUNNING_MARKER) {
            d.addProperty("ok", false);
            d.addProperty("detail", "自检进行中...");
            d.addProperty("running", true);
        } else {
            d.addProperty("ok", r.ok);
            d.addProperty("detail", r.detail);
            d.add("fields", r.fields);
            if (r.stderrTail != null && !r.stderrTail.isBlank()) {
                d.addProperty("stderrTail", r.stderrTail);
            }
        }
        return d;
    }

    // ---------- env register ----------

    private static JsonObject envRegister(JsonObject body) throws IOException {
        Path root = requireRoot();
        String dbId = str(body, "dbId");
        String env = str(body, "env");
        DbAdapter adapter = DbAdapters.require(dbId);
        State st = State.load(root);
        if (st == null || !st.envs.containsKey(env)) {
            throw new IllegalStateException("环境未配置：" + env);
        }
        List<String> tools = st.envs.get(env).tools;
        List<String> cmd = adapter.buildCommand(root, dbId, env, tools);
        Map<String, String> envVars = resolveEnvVars(root, dbId, env, adapter);
        String serverName = adapter.serverPrefix() + env;
        JsonObject entry = McpJson.register(Cfg.mcpJsonPath(), serverName, cmd, envVars);
        st.envs.get(env).registered = true;
        st.save(root);
        JsonObject d = new JsonObject();
        d.addProperty("serverName", serverName);
        d.addProperty("dbId", dbId);
        d.addProperty("mcpJsonPath", Cfg.mcpJsonPath().toString());
        d.add("entry", entry);
        return d;
    }

    // ---------- env delete ----------

    private static JsonObject envDelete(JsonObject body) throws IOException {
        Path root = requireRoot();
        String dbId = str(body, "dbId");
        String env = str(body, "env");
        DbAdapter adapter = DbAdapters.require(dbId);
        String serverName = adapter.serverPrefix() + env;
        boolean removedFromMcp = McpJson.remove(Cfg.mcpJsonPath(), serverName);
        String trashMsg = Trash.moveToTrash(Installer.envDir(root, dbId, env));
        State st = State.load(root);
        if (st != null) {
            st.envs.remove(env);
            st.save(root);
            for (DbAdapter a : DbAdapters.all()) {
                SkillService.syncMappings(st, a);
            }
        }
        JsonObject d = new JsonObject();
        d.addProperty("removedFromMcp", removedFromMcp);
        d.addProperty("trash", trashMsg);
        return d;
    }

    // ---------- env log ----------

    private static JsonObject envLog(URI uri) throws IOException {
        Path root = requireRoot();
        String dbId = query(uri, "dbId");
        String env = query(uri, "env");
        int limit = 200;
        try {
            String l = query(uri, "limit");
            if (l != null) {
                limit = Math.min(2000, Integer.parseInt(l));
            }
        } catch (NumberFormatException ignored) {
        }
        JsonObject d = new JsonObject();
        d.addProperty("logPath", Installer.callLog(root, dbId, env).toString());
        JsonArray arr = new JsonArray();
        Installer.tailCallLog(root, dbId, env, limit).forEach(arr::add);
        d.add("lines", arr);
        return ok(d);
    }

    // ---------- env guide ----------

    private static JsonObject envGuide(URI uri) throws IOException {
        Path root = requireRoot();
        String dbId = query(uri, "dbId");
        String env = query(uri, "env");
        DbAdapter adapter = DbAdapters.require(dbId);
        State st = State.load(root);
        if (st == null || !st.envs.containsKey(env)) {
            throw new IllegalStateException("环境未配置：" + env);
        }
        return ok(PlatformGuide.guide(root, dbId, env, st.envs.get(env).tools, adapter));
    }

    // ---------- MCP target registration ----------

    private static JsonObject mcpListTargets() {
        JsonArray arr = new JsonArray();
        for (com.dbmcp.mcp.McpTarget t : com.dbmcp.mcp.McpTargets.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", t.id());
            o.addProperty("displayName", t.displayName());
            o.addProperty("describe", t.describe());
            o.addProperty("icon", t.icon());
            o.addProperty("iconClass", t.iconClass());
            o.addProperty("tier", t.tier());
            o.addProperty("writable", t.writable());
            o.addProperty("cliBased", t.cliBased());
            o.addProperty("uiOnly", t.uiOnly());
            o.addProperty("detected", t.detected());
            JsonArray steps = new JsonArray();
            t.uiInstructions().forEach(steps::add);
            o.add("uiInstructions", steps);
            JsonArray paths = new JsonArray();
            t.candidateConfigPaths().forEach(p -> paths.add(normalizePath(p.toString())));
            o.add("candidatePaths", paths);
            Path actual = t.detectActual();
            if (actual != null) o.addProperty("actualPath", normalizePath(actual.toString()));
            try {
                if (actual != null && Files.isRegularFile(actual)) {
                    JsonArray servers = new JsonArray();
                    t.listServers(actual).forEach(servers::add);
                    o.add("existingServers", servers);
                } else {
                    o.add("existingServers", new JsonArray());
                }
            } catch (Exception e) {
                o.addProperty("readError", e.getMessage());
            }
            arr.add(o);
        }
        JsonObject d = new JsonObject();
        d.add("targets", arr);
        return d;
    }

    /** 构造某个 (dbId, env) 实例对应的 MCP server entry + serverName。 */
    private static Object[] mcpEntryFor(String dbId, String env) {
        Path root = requireRoot();
        State st = State.load(root);
        if (st == null || !st.envs.containsKey(env)) {
            throw new IllegalStateException("环境未配置：" + env);
        }
        State.EnvInfo info = st.envs.get(env);
        if (!dbId.equals(info.dbType)) {
            throw new IllegalArgumentException("dbId 与环境不匹配");
        }
        DbAdapter adapter = DbAdapters.require(dbId);
        List<String> cmd = adapter.buildCommand(root, dbId, env, info.tools);
        Map<String, String> envVars = null;
        try { envVars = resolveEnvVars(root, dbId, env, adapter); } catch (Exception ignored) {}
        String serverName = adapter.serverPrefix() + env;
        JsonObject entry = new JsonObject();
        entry.addProperty("command", cmd.get(0));
        JsonArray args = new JsonArray();
        cmd.subList(1, cmd.size()).forEach(args::add);
        entry.add("args", args);
        if (envVars != null && !envVars.isEmpty()) {
            JsonObject envObj = new JsonObject();
            envVars.forEach(envObj::addProperty);
            entry.add("env", envObj);
        }
        return new Object[]{ serverName, entry };
    }

    private static JsonObject mcpRegister(JsonObject body) throws IOException {
        String targetId = str(body, "target");
        String dbId = str(body, "dbId");
        String env = str(body, "env");
        if (targetId == null || dbId == null || env == null) {
            throw new IllegalArgumentException("target/dbId/env 均不能为空");
        }
        com.dbmcp.mcp.McpTarget target = com.dbmcp.mcp.McpTargets.require(targetId);
        if (!target.writable()) {
            throw new IllegalStateException(target.displayName() + " 不支持一键配置，请使用「复制片段」");
        }
        Object[] built = mcpEntryFor(dbId, env);
        String serverName = (String) built[0];
        JsonObject entry = (JsonObject) built[1];
        Path cfg = target.detectActual();
        if (cfg == null) {
            throw new IllegalStateException("无法定位 " + target.displayName() + " 的配置文件路径");
        }
        target.addServer(cfg, serverName, entry);
        JsonObject d = new JsonObject();
        d.addProperty("target", targetId);
        d.addProperty("serverName", serverName);
        d.addProperty("configPath", normalizePath(cfg.toString()));
        d.addProperty("message", "已注册 " + serverName + " 到 " + target.displayName());
        return d;
    }

    private static JsonObject mcpUnregister(JsonObject body) throws IOException {
        String targetId = str(body, "target");
        String dbId = str(body, "dbId");
        String env = str(body, "env");
        com.dbmcp.mcp.McpTarget target = com.dbmcp.mcp.McpTargets.require(targetId);
        DbAdapter adapter = DbAdapters.require(dbId);
        String serverName = adapter.serverPrefix() + env;
        Path cfg = target.detectActual();
        boolean removed = false;
        if (target.cliBased()) {
            // CLI 类无实体文件，直接调 removeServer（内部走 lms mcp remove 等）
            removed = target.removeServer(cfg, serverName);
        } else if (cfg != null && Files.isRegularFile(cfg)) {
            removed = target.removeServer(cfg, serverName);
        }
        JsonObject d = new JsonObject();
        d.addProperty("target", targetId);
        d.addProperty("serverName", serverName);
        d.addProperty("removed", removed);
        d.addProperty("configPath", cfg != null ? normalizePath(cfg.toString()) : null);
        return d;
    }

    private static JsonObject mcpCommands(JsonObject body) {
        String targetId = str(body, "target");
        String dbId = str(body, "dbId");
        String env = str(body, "env");
        if (targetId == null || dbId == null || env == null) {
            throw new IllegalArgumentException("target/dbId/env 均不能为空");
        }
        com.dbmcp.mcp.McpTarget target = com.dbmcp.mcp.McpTargets.require(targetId);
        Object[] built = mcpEntryFor(dbId, env);
        String serverName = (String) built[0];
        JsonObject entry = (JsonObject) built[1];
        JsonObject d = new JsonObject();
        d.addProperty("target", targetId);
        d.addProperty("cliBased", target.cliBased());
        String reg = target.cliRegisterCommand(serverName, entry);
        String unreg = target.cliUnregisterCommand(serverName);
        if (reg != null) d.addProperty("register", reg);
        if (unreg != null) d.addProperty("unregister", unreg);
        return d;
    }

    // ---------- skill ----------

    private static JsonObject skillDeploy(JsonObject body) throws IOException {
        Path root = resolveRoot(null);
        String dbId = str(body, "dbId");
        DbAdapter adapter = DbAdapters.require(dbId);
        State st = State.load(root);
        if (st == null) {
            st = new State();
            st.root = root.toString();
        }
        List<String> targets = strList(body, "targets");
        List<String> deployed = SkillService.deploy(st, targets, adapter);
        st.save(root);
        JsonObject d = new JsonObject();
        JsonArray arr = new JsonArray();
        deployed.forEach(arr::add);
        d.add("deployed", arr);
        return d;
    }

    private static JsonObject skillSync() throws IOException {
        Path root = requireRoot();
        State st = State.load(root);
        if (st == null) {
            throw new IllegalStateException("未检测到部署状态");
        }
        List<String> updated = new ArrayList<>();
        for (DbAdapter a : DbAdapters.all()) {
            updated.addAll(SkillService.syncMappings(st, a));
        }
        JsonObject d = new JsonObject();
        JsonArray arr = new JsonArray();
        updated.forEach(arr::add);
        d.add("updated", arr);
        return d;
    }

    private static JsonObject skillListTargets() throws IOException {
        Path root = resolveRoot(null);
        State st = State.load(root);
        if (st == null) {
            throw new IllegalStateException("未检测到部署状态");
        }
        JsonObject d = new JsonObject();
        JsonArray arr = new JsonArray();
        st.skillTargets.forEach(t -> arr.add(normalizePath(t)));
        d.add("targets", arr);
        return d;
    }

    private static JsonObject skillAddTarget(JsonObject body) throws IOException {
        Path root = requireRoot();
        State st = State.load(root);
        if (st == null) {
            throw new IllegalStateException("未检测到部署状态");
        }
        String target = str(body, "target");
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target 不能为空");
        }
        target = target.trim();
        String dbId = str(body, "dbId");
        DbAdapter adapter = DbAdapters.require(dbId);
        if (!st.skillTargets.contains(target)) {
            st.skillTargets.add(target);
        }
        st.save(root);
        SkillService.deploy(st, List.of(target), adapter);
        JsonObject d = new JsonObject();
        d.addProperty("target", target);
        d.addProperty("added", true);
        return d;
    }

    private static JsonObject skillRemoveTarget(JsonObject body) throws IOException {
        Path root = requireRoot();
        State st = State.load(root);
        if (st == null) {
            throw new IllegalStateException("未检测到部署状态");
        }
        String target = str(body, "target");
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target 不能为空");
        }
        target = target.trim();
        boolean removed = st.skillTargets.remove(target);
        if (removed) {
            st.save(root);
            for (DbAdapter a : DbAdapters.all()) {
                Path skillDir = Path.of(target).resolve(a.skillDir());
                if (Files.isDirectory(skillDir)) {
                    Trash.moveToTrash(skillDir);
                }
            }
        }
        JsonObject d = new JsonObject();
        d.addProperty("target", target);
        d.addProperty("removed", removed);
        return d;
    }

    // ---------- reset / uninstall ----------

    /**
     * Dry-run 预览：不改动任何东西，仅汇总 reset() 将影响的资源清单。
     * 供前端「危险区」弹窗展示，让用户输入关键词前先看一遍会删什么。
     */
    private static JsonObject resetPreview() throws IOException {
        Path root = resolveRoot(null);
        State st = State.load(root);

        JsonArray envList = new JsonArray();
        if (st != null && st.envs != null) {
            for (Map.Entry<String, State.EnvInfo> e : st.envs.entrySet()) {
                JsonObject row = new JsonObject();
                row.addProperty("env", e.getKey());
                row.addProperty("dbId", e.getValue().dbType);
                row.addProperty("registered", e.getValue().registered);
                for (DbAdapter a : DbAdapters.all()) {
                    if (a.id().equals(e.getValue().dbType)) {
                        row.addProperty("serverName", a.serverPrefix() + e.getKey());
                        break;
                    }
                }
                envList.add(row);
            }
        }

        JsonArray mcpServers = new JsonArray();
        for (DbAdapter a : DbAdapters.all()) {
            for (String n : McpJson.serverNames(Cfg.mcpJsonPath())) {
                if (n.startsWith(a.serverPrefix())) mcpServers.add(n);
            }
        }
        JsonArray qoderPluginServers = new JsonArray();
        for (DbAdapter a : DbAdapters.all()) {
            for (String n : McpJson.serverNames(Cfg.qoderPluginMcpJsonPath())) {
                if (n.startsWith(a.serverPrefix())) qoderPluginServers.add(n);
            }
        }

        JsonArray skillDirs = new JsonArray();
        if (st != null && st.skillTargets != null) {
            for (String t : st.skillTargets) {
                for (DbAdapter a : DbAdapters.all()) {
                    Path dir = Path.of(t).resolve(a.skillDir());
                    if (Files.isDirectory(dir)) {
                        skillDirs.add(normalizePath(dir.toString()));
                    }
                }
            }
        }

        JsonObject d = new JsonObject();
        d.addProperty("root", normalizePath(root.toString()));
        d.addProperty("rootExists", Files.exists(root));
        d.add("envs", envList);
        d.add("mcpServersToRemove", mcpServers);
        d.add("qoderPluginServersToRemove", qoderPluginServers);
        d.add("skillDirsToTrash", skillDirs);
        d.addProperty("mcpJsonPath", normalizePath(Cfg.mcpJsonPath().toString()));
        d.addProperty("qoderPluginMcpJsonPath", normalizePath(Cfg.qoderPluginMcpJsonPath().toString()));
        d.addProperty("confirmKeyword", "RESET");
        d.addProperty("note",
                "执行 reset 将：把上述 mcp.json 条目对应 server 移除并自动 .bak.<timestamp> 备份、" +
                        "把 skill 目录移动到系统回收站、把 root 目录整体移动到系统回收站。不卸载 shell 程序本体。");
        return d;
    }

    private static JsonObject reset() throws IOException {
        Path root = resolveRoot(null);
        State st = State.load(root);

        int mcpRemoved = 0;
        for (DbAdapter a : DbAdapters.all()) {
            mcpRemoved += McpJson.removeByPrefix(Cfg.mcpJsonPath(), a.serverPrefix());
        }
        int qoderPluginMcpRemoved = 0;
        for (DbAdapter a : DbAdapters.all()) {
            qoderPluginMcpRemoved += McpJson.removeByPrefix(Cfg.qoderPluginMcpJsonPath(), a.serverPrefix());
        }

        JsonArray skillMsgs = new JsonArray();
        if (st != null) {
            for (String t : st.skillTargets) {
                for (DbAdapter a : DbAdapters.all()) {
                    Path dir = Path.of(t).resolve(a.skillDir());
                    if (Files.isDirectory(dir)) {
                        skillMsgs.add(dir + " → " + Trash.moveToTrash(dir));
                    }
                }
            }
        }

        String rootMsg = Files.exists(root) ? Trash.moveToTrash(root) : "根目录不存在，无需清理";

        JsonObject d = new JsonObject();
        d.addProperty("mcpRemoved", mcpRemoved);
        d.addProperty("mcpJsonPath", Cfg.mcpJsonPath().toString());
        d.add("skillTrashed", skillMsgs);
        d.addProperty("rootTrashed", rootMsg);
        return d;
    }

    private static JsonObject uninstall() throws IOException {
        JsonObject d = reset();

        boolean shortcutRemoved = removeDesktopShortcut();
        d.addProperty("shortcutRemoved", shortcutRemoved);

        String uninstallCmd = Cfg.readUninstallString();
        if (uninstallCmd != null && !uninstallCmd.isBlank()) {
            Path appDir = Cfg.resolveAppDir();
            scheduleInnoUninstall(uninstallCmd, appDir);
            d.addProperty("selfRemoved", "已调起 Inno 卸载器：" + uninstallCmd);
        } else {
            d.addProperty("selfRemoved", "开发模式运行（java -jar），数据已全部清空；安装程序 JAR 请在向导退出后手动删除");
        }
        Thread stopper = new Thread(() -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (serverRef != null) {
                serverRef.stop(1);
            }
            System.exit(0);
        }, "setup-shutdown");
        stopper.setDaemon(true);
        stopper.start();
        return d;
    }

    private static boolean removeDesktopShortcut() {
        try {
            Path desktop = Path.of(System.getProperty("user.home"), "Desktop");
            for (String name : new String[]{"DB MCP Helper.lnk", "DB MCP Helper 安装向导.lnk"}) {
                Path lnk = desktop.resolve(name);
                if (Files.isRegularFile(lnk)) {
                    Trash.moveToTrash(lnk);
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static void scheduleInnoUninstall(String uninstallCmd, Path appDir) {
        try {
            String esc = uninstallCmd.replace("\"", "\\\"");
            new ProcessBuilder("cmd.exe", "/c", "start", "", "/wait", "cmd.exe", "/c",
                    "\"" + esc + "\" /SILENT /SUPPRESSMSGBOXES").start();
        } catch (Exception ignored) {
        }
    }

    // ---------- open / reveal in explorer ----------

    /**
     * 让 OS 打开或定位到某个后端已知的路径。target 枚举，避免前端注入任意路径。
     * mode=open → 用系统默认应用打开（文件不存在自动降级为 reveal 所在目录）
     * mode=reveal → 资源管理器中定位到该文件（选中态）
     */
    private static JsonObject openPath(JsonObject body) throws IOException {
        String target = str(body, "target");
        String mode = str(body, "mode");
        boolean reveal = "reveal".equals(mode);
        Path path = resolveOpenTarget(target, body);
        String msg;
        boolean degraded = false;
        if (!Files.exists(path)) {
            // 目标文件不存在，尝试定位到父目录（父目录不存在就报错）
            Path parent = path.getParent();
            if (parent == null || !Files.exists(parent)) {
                throw new IllegalStateException("路径不存在：" + normalizePath(path.toString()));
            }
            msg = revealInExplorer(parent) + "（原文件尚未生成，已定位到父目录）";
            degraded = true;
        } else if (reveal) {
            msg = revealInExplorer(path);
        } else {
            msg = openWithDefaultApp(path);
        }
        JsonObject d = new JsonObject();
        d.addProperty("target", target);
        d.addProperty("mode", reveal ? "reveal" : (degraded ? "reveal-degraded" : "open"));
        d.addProperty("path", normalizePath(path.toString()));
        d.addProperty("message", msg);
        return d;
    }

    private static Path resolveOpenTarget(String target, JsonObject body) {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target 不能为空");
        }
        switch (target) {
            case "mcp-json":
                return Cfg.mcpJsonPath();
            case "qoder-plugin-mcp":
                return Cfg.qoderPluginMcpJsonPath();
            case "root-dir":
                return resolveRoot(null);
            case "log-dir":
                return resolveLogDir();
            case "prefs-json":
                return Prefs.prefsFile();
            case "env-config-dir": {
                Path root = requireRoot();
                String dbId = str(body, "dbId");
                String env = str(body, "env");
                if (dbId == null || env == null) throw new IllegalArgumentException("需要 dbId + env");
                return Installer.envDir(root, dbId, env);
            }
            case "tap-dir": {
                Path root = requireRoot();
                return root.resolve("tap");
            }
            default:
                throw new IllegalArgumentException("未知 target: " + target);
        }
    }

    private static boolean isWin() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }
    private static boolean isMac() { return System.getProperty("os.name", "").toLowerCase().contains("mac"); }

    private static String openWithDefaultApp(Path path) throws IOException {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(path.toFile());
                return "已用系统默认应用打开：" + normalizePath(path.toString());
            }
        } catch (Exception ignored) { /* fall through to shell */ }
        try {
            if (isWin()) {
                // cmd /c start "" "path"  → 空标题占位，防止 start 把带引号路径当窗口标题
                new ProcessBuilder("cmd.exe", "/c", "start", "", path.toAbsolutePath().toString()).start();
            } else if (isMac()) {
                new ProcessBuilder("open", path.toAbsolutePath().toString()).start();
            } else {
                new ProcessBuilder("xdg-open", path.toAbsolutePath().toString()).start();
            }
            return "已用系统默认应用打开：" + normalizePath(path.toString());
        } catch (Exception e) {
            throw new IOException("无法打开文件：" + e.getMessage());
        }
    }

    private static String revealInExplorer(Path path) throws IOException {
        try {
            if (isWin()) {
                // explorer.exe /select,"C:\path\file.json"  → 引号必须拼进同一条参数
                String arg = "/select,\"" + path.toAbsolutePath() + "\"";
                new ProcessBuilder("explorer.exe", arg).start();
            } else if (isMac()) {
                new ProcessBuilder("open", "-R", path.toAbsolutePath().toString()).start();
            } else {
                // Linux 无标准 reveal，退化成打开父目录
                Path parent = Files.isDirectory(path) ? path : path.getParent();
                new ProcessBuilder("xdg-open", parent.toAbsolutePath().toString()).start();
            }
            return "已在文件管理器中定位：" + normalizePath(path.toString());
        } catch (Exception e) {
            throw new IOException("无法定位文件：" + e.getMessage());
        }
    }

    // ---------- helpers ----------

    private static Path resolveRoot(String requested) {
        if (requested != null && !requested.isBlank()) {
            return Path.of(requested.trim());
        }
        Path lastRoot = Cfg.readLastRoot();
        if (lastRoot != null) {
            return lastRoot;
        }
        State st = State.load(Cfg.defaultRoot());
        if (st != null && st.root != null && !st.root.isBlank()) {
            return Path.of(st.root);
        }
        return Cfg.installDir();
    }

    private static Path requireRoot() {
        Path root = resolveRoot(null);
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("尚未部署运行时（root 不存在：" + root + "）");
        }
        return root;
    }

    private static void validateInstallRoot(Path root) {
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("安装根目录必须是绝对路径：" + root);
        }
        Path normalized = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(normalized);
            Path test = normalized.resolve(".write-test-" + System.nanoTime());
            Files.writeString(test, "ok", StandardCharsets.UTF_8);
            Files.deleteIfExists(test);
        } catch (Exception e) {
            String hint = normalized.startsWith(Path.of("C:\\Program Files").normalize())
                    ? "（若装在 Program Files 且无写权限，请改用安装器「为当前用户安装」或选其他目录）"
                    : "";
            throw new IllegalArgumentException("安装根目录不可写或无法创建：" + normalized + "（" + e.getMessage() + "）" + hint);
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static List<String> strList(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        if (o.has(k) && o.get(k).isJsonArray()) {
            o.getAsJsonArray(k).forEach(e -> {
                String s = e.getAsString();
                if (s != null && !s.isBlank()) {
                    out.add(s.trim());
                }
            });
        }
        return out;
    }

    private static String query(URI uri, String key) {
        String q = uri.getRawQuery();
        if (q == null) {
            return null;
        }
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) {
                return java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static JsonObject readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.isBlank() ? new JsonObject() : GSON.fromJson(text, JsonObject.class);
        }
    }

    private static void serveResource(HttpExchange ex, String resourceName, String contentType) throws IOException {
        try (InputStream in = SetupMain.class.getClassLoader().getResourceAsStream(resourceName)) {
            byte[] body;
            if (in == null) {
                body = ("<!-- " + resourceName + " missing -->").getBytes(StandardCharsets.UTF_8);
            } else {
                body = in.readAllBytes();
            }
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.getResponseHeaders().add("Cache-Control", "no-store, max-age=0");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private static JsonObject ok(JsonObject data) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.add("data", data);
        return o;
    }

    private static JsonObject err(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", msg);
        return o;
    }

    private static void send(HttpExchange ex, int code, JsonObject resp) throws IOException {
        byte[] bytes = GSON.toJson(resp).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void openBrowser(String url) {
        if (NO_BROWSER) return;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                Runtime.getRuntime().exec(new String[]{"rundll32", "url.dll,FileProtocolHandler", url});
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", url});
            } else {
                Runtime.getRuntime().exec(new String[]{"xdg-open", url});
            }
        } catch (Exception ignored) {
            System.out.println("请手动打开浏览器访问：" + url);
        }
    }

    private static String normalizePath(String path) {
        return path == null ? null : path.replace("\\", "/");
    }
}

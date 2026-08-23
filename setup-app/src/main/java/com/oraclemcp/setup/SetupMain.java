package com.oraclemcp.setup;

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

/**
 * Oracle MCP 安装向导入口：JDK 内置 HttpServer 提供向导页与 REST API。
 *
 * <p>API 一览（JSON）：
 * GET /api/detect · POST /api/deploy · POST /api/env/config · POST /api/env/test ·
 * POST /api/env/register · POST /api/env/delete · GET /api/env/log ·
 * POST /api/skill/deploy · POST /api/skill/sync
 */
public final class SetupMain {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    /** 服务实例引用，卸载流程需要主动停机。 */
    private static HttpServer serverRef;

    public static void main(String[] args) throws IOException {
        int port = Cfg.port();
        String url = "http://127.0.0.1:" + port;

        // 检测是否已有 Oracle MCP Setup 实例在运行（通过探测 /api/detect 接口）
        if (isOurServerRunning(port)) {
            System.out.println("检测到 Oracle MCP Setup 已在运行，直接打开浏览器...");
            openBrowser(url);
            return;
        }

        // 启动新实例
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", SetupMain::handle);
        server.start();
        serverRef = server;
        System.out.println("Oracle MCP Setup 已启动：" + url);
        openBrowser(url);
    }

    /** 检测指定端口是否已有我们的服务在运行（通过请求 /api/detect 验证） */
    private static boolean isOurServerRunning(int port) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL("http://127.0.0.1:" + port + "/api/detect").openConnection();
            conn.setConnectTimeout(500);
            conn.setReadTimeout(500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            // 200 且返回 JSON 说明是我们的服务
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
                serveIndex(ex);
                return;
            }
            if (!path.startsWith("/api/")) {
                send(ex, 404, err("未知路径"));
                return;
            }
            JsonObject body = "POST".equals(method) ? readBody(ex) : new JsonObject();
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
            case "/api/deploy":
                return ok(deploy(body));
            case "/api/env/config":
                return ok(envConfig(body));
            case "/api/env/parse":
                return ok(envParse(body));
            case "/api/env/test":
                return ok(envTest(body));
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
            case "/api/reset":
                return ok(reset());
            case "/api/uninstall":
                return ok(uninstall());
            default:
                return err("未知接口：" + path);
        }
    }

    // ---------- detect ----------

    private static JsonObject detect() {
        Path root = resolveRoot(null);
        State st = State.load(root);
        JsonObject d = new JsonObject();
        d.addProperty("home", normalizePath(Cfg.home().toString()));
        d.addProperty("root", normalizePath(root.toString()));
        d.addProperty("rootExists", Files.isDirectory(root));
        d.addProperty("toolkitDeployed", Files.isRegularFile(root.resolve(Cfg.TOOLKIT_FILE_NAME)));
        d.addProperty("tapDeployed", Files.isRegularFile(root.resolve(Cfg.TAP_FILE_NAME)));
        d.addProperty("javaCmd", normalizePath(Cfg.javaCmd()));
        d.addProperty("mcpJsonPath", normalizePath(Cfg.mcpJsonPath().toString()));
        d.addProperty("qoderPluginMcpJsonPath", normalizePath(Cfg.qoderPluginMcpJsonPath().toString()));
        JsonArray registered = new JsonArray();
        McpJson.serverNames(Cfg.mcpJsonPath()).stream().filter(n -> n.startsWith("oracle-")).forEach(registered::add);
        d.add("registeredServers", registered);
        JsonArray qoderPluginRegistered = new JsonArray();
        McpJson.serverNames(Cfg.qoderPluginMcpJsonPath()).stream().filter(n -> n.startsWith("oracle-")).forEach(qoderPluginRegistered::add);
        d.add("qoderPluginRegisteredServers", qoderPluginRegistered);
        if (st != null) {
            // 标准化 state 中的路径
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
        // 安装形态下不再单独询问目录：root 缺省即用安装过程中用户选择的目录（{app}）
        String reqRoot = str(body, "root");
        Path root = reqRoot != null && !reqRoot.isBlank() ? Path.of(reqRoot.trim()) : Cfg.installDir();
        validateInstallRoot(root);
        State st = Installer.deploy(root);
        Cfg.writeLastRoot(root);
        JsonObject d = new JsonObject();
        d.addProperty("root", root.toString());
        d.add("state", GSON.toJsonTree(st));
        return d;
    }

    // ---------- env config ----------

    private static JsonObject envConfig(JsonObject body) throws IOException {
        Path root = requireRoot();
        String env = str(body, "env");
        if (!Installer.validEnvName(env)) {
            throw new IllegalArgumentException("环境编码不合法：仅小写字母/数字/连字符，字母开头，≤32 位");
        }
        String host = str(body, "host");
        String user = str(body, "user");
        String password = str(body, "password");
        String jdbcUrl = str(body, "jdbcUrl");
        int port = body.has("port") && !body.get("port").getAsString().isBlank() ? Integer.parseInt(body.get("port").getAsString().trim()) : 1521;
        String service = str(body, "service");
        // paste 兜底：显式字段为空时从粘贴的配置片段解析补齐
        String paste = str(body, "paste");
        if (paste != null && !paste.isBlank()) {
            java.util.Map<String, String> p = ConfigParser.extract(paste);
            if (isBlank(jdbcUrl) && isBlank(host) && p.containsKey("url")) {
                String[] parts = ConfigParser.splitSimpleUrl(p.get("url"));
                if (parts != null) {
                    host = parts[0];
                    port = Integer.parseInt(parts[1]);
                    service = parts[2];
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
        String url = jdbcUrl != null && !jdbcUrl.isBlank() ? jdbcUrl.trim() : Installer.jdbcUrl(host, port, service);
        if (user == null || user.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }
        List<String> tools = strList(body, "tools");
        if (!tools.contains("read-query") || !tools.contains("db-ping")) {
            throw new IllegalArgumentException("read-query 与 db-ping 为必选工具");
        }
        Installer.writeEnvConfig(root, env, url, user.trim(), password);

        State st = State.load(root);
        if (st == null) {
            throw new IllegalStateException("未检测到部署状态，请先部署运行时");
        }
        State.EnvInfo info = st.envs.computeIfAbsent(env, k -> new State.EnvInfo());
        info.aliases = strList(body, "aliases");
        info.tools = tools;
        st.save(root);

        JsonObject d = new JsonObject();
        d.addProperty("env", env);
        d.addProperty("configPath", Installer.configYaml(root, env).toString());
        d.addProperty("url", url);
        return d;
    }

    // ---------- env parse（粘贴解析） ----------

    /** 从粘贴的配置片段提取 url/username/password，简单形式 URL 进一步拆为 host/port/service。 */
    private static JsonObject envParse(JsonObject body) {
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
                d.addProperty("service", parts[2]);
            } else {
                d.addProperty("jdbcUrl", url); // TNS 等复杂形式整体回填高级选项
            }
        }
        if (p.containsKey("username")) {
            d.addProperty("user", p.get("username"));
        }
        if (p.containsKey("password")) {
            d.addProperty("hasPassword", true); // 密码不回显前端，经 envConfig 的 paste 原样落盘
        }
        d.addProperty("parseable", !p.isEmpty());
        return d;
    }

    // ---------- env test ----------

    private static JsonObject envTest(JsonObject body) throws IOException {
        Path root = requireRoot();
        String env = str(body, "env");
        SelfTest.Result r = SelfTest.run(root, env);
        State st = State.load(root);
        if (st != null && st.envs.containsKey(env)) {
            State.LastTest lt = new State.LastTest();
            lt.ok = r.ok;
            lt.detail = r.detail;
            lt.ts = Instant.now().toString();
            st.envs.get(env).lastTest = lt;
            st.save(root);
        }
        JsonObject d = new JsonObject();
        d.addProperty("ok", r.ok);
        d.addProperty("detail", r.detail);
        d.add("fields", r.fields);
        if (r.stderrTail != null && !r.stderrTail.isBlank()) {
            d.addProperty("stderrTail", r.stderrTail);
        }
        return d;
    }

    // ---------- env register ----------

    private static JsonObject envRegister(JsonObject body) throws IOException {
        Path root = requireRoot();
        String env = str(body, "env");
        State st = State.load(root);
        if (st == null || !st.envs.containsKey(env)) {
            throw new IllegalStateException("环境未配置：" + env);
        }
        List<String> cmd = McpJson.buildCommand(root, env, st.envs.get(env).tools);
        String serverName = "oracle-" + env;
        JsonObject entry = McpJson.register(Cfg.mcpJsonPath(), serverName, cmd);
        st.envs.get(env).registered = true;
        st.save(root);
        JsonObject d = new JsonObject();
        d.addProperty("serverName", serverName);
        d.addProperty("mcpJsonPath", Cfg.mcpJsonPath().toString());
        d.add("entry", entry);
        return d;
    }

    // ---------- env delete ----------

    private static JsonObject envDelete(JsonObject body) throws IOException {
        Path root = requireRoot();
        String env = str(body, "env");
        String serverName = "oracle-" + env;
        boolean removedFromMcp = McpJson.remove(Cfg.mcpJsonPath(), serverName);
        String trashMsg = Trash.moveToTrash(Installer.envDir(root, env));
        State st = State.load(root);
        if (st != null) {
            st.envs.remove(env);
            st.save(root);
            SkillService.syncMappings(st);
        }
        JsonObject d = new JsonObject();
        d.addProperty("removedFromMcp", removedFromMcp);
        d.addProperty("trash", trashMsg);
        return d;
    }

    // ---------- env log ----------

    private static JsonObject envLog(URI uri) throws IOException {
        Path root = requireRoot();
        String env = query(uri, "env");
        int limit = 200;
        try {
            String l = query(uri, "limit");
            if (l != null) {
                limit = Math.min(2000, Integer.parseInt(l));
            }
        } catch (NumberFormatException ignored) {
            // 保持默认 limit
        }
        JsonObject d = new JsonObject();
        d.addProperty("logPath", Installer.callLog(root, env).toString());
        JsonArray arr = new JsonArray();
        Installer.tailCallLog(root, env, limit).forEach(arr::add);
        d.add("lines", arr);
        return ok(d);
    }

    // ---------- env guide（多平台接入指南） ----------

    private static JsonObject envGuide(URI uri) throws IOException {
        Path root = requireRoot();
        String env = query(uri, "env");
        State st = State.load(root);
        if (st == null || !st.envs.containsKey(env)) {
            throw new IllegalStateException("环境未配置：" + env);
        }
        return ok(PlatformGuide.guide(root, env, st.envs.get(env).tools));
    }

    // ---------- skill ----------

    private static JsonObject skillDeploy(JsonObject body) throws IOException {
        Path root = resolveRoot(null);
        State st = State.load(root);
        if (st == null) {
            st = new State();
            st.root = root.toString();
        }
        List<String> targets = strList(body, "targets");
        List<String> deployed = SkillService.deploy(st, targets);
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
        List<String> updated = SkillService.syncMappings(st);
        JsonObject d = new JsonObject();
        JsonArray arr = new JsonArray();
        updated.forEach(arr::add);
        d.add("updated", arr);
        return d;
    }

    // ---------- skill targets 管理 ----------

    /** 列出已部署的 skill 位置 */
    private static JsonObject skillListTargets() throws IOException {
        Path root = requireRoot();
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

    /** 新增 skill 位置 */
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
        if (!st.skillTargets.contains(target)) {
            st.skillTargets.add(target);
            st.save(root);
            // 部署 skill 到新位置
            SkillService.deploy(st, List.of(target));
        }
        JsonObject d = new JsonObject();
        d.addProperty("target", target);
        d.addProperty("added", !st.skillTargets.contains(target));
        return d;
    }

    /** 删除 skill 位置 */
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
            // 删除 skill 目录
            Path skillDir = Path.of(target).resolve(SkillService.SKILL_DIR_NAME);
            if (Files.isDirectory(skillDir)) {
                Trash.moveToTrash(skillDir);
            }
        }
        JsonObject d = new JsonObject();
        d.addProperty("target", target);
        d.addProperty("removed", removed);
        return d;
    }

    // ---------- reset / uninstall ----------

    /**
     * 一键清空：移除 mcp.json 中全部 oracle-* 条目、已部署 Skill 副本、安装根目录（含 state 与环境配置），
     * 全部走系统回收站；安装程序本身保留。清空后等同从未操作过的初始状态。
     */
    private static JsonObject reset() throws IOException {
        // state 必须在删除根目录之前读取（skill 部署位置清单在其中）
        Path root = resolveRoot(null);
        State st = State.load(root);

        int mcpRemoved = McpJson.removeByPrefix(Cfg.mcpJsonPath(), "oracle-");
        int qoderPluginMcpRemoved = McpJson.removeByPrefix(Cfg.qoderPluginMcpJsonPath(), "oracle-");

        JsonArray skillMsgs = new JsonArray();
        if (st != null) {
            for (String t : st.skillTargets) {
                Path dir = Path.of(t).resolve(SkillService.SKILL_DIR_NAME);
                if (Files.isDirectory(dir)) {
                    skillMsgs.add(dir + " → " + Trash.moveToTrash(dir));
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

    /**
     * 一键卸载：先执行一键清空（数据 + mcp.json 条目），再移除安装程序自身。
     * 正式安装形态（Inno Setup）从安装目录的 install-info.json 读取 uninstallString，
     * 调起 Inno 卸载器（unins000.exe）以正规方式移除程序目录与卸载注册项；
     * 开发形态（直接 java -jar）无 Inno 卸载信息，仅清空数据并提示手动删除 JAR。
     */
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
        // 延迟停机，确保本次响应先送达
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

    /** 尽力移除桌面上的安装器快捷方式（Windows .lnk）；不存在返回 false。 */
    private static boolean removeDesktopShortcut() {
        try {
            Path desktop = Path.of(System.getProperty("user.home"), "Desktop");
            for (String name : new String[]{"Oracle MCP Setup.lnk", "Oracle MCP 安装向导.lnk"}) {
                Path lnk = desktop.resolve(name);
                if (Files.isRegularFile(lnk)) {
                    Trash.moveToTrash(lnk);
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 快捷方式清理失败不影响卸载主流程
        }
        return false;
    }

    /**
     * 调起 Inno 卸载器：用 start /wait 以独立进程运行，使其脱离本向导进程树，
     * 在向导退出后仍可继续删除程序目录。uninstallCmd 通常为 unins000.exe 路径。
     */
    private static void scheduleInnoUninstall(String uninstallCmd, Path appDir) {
        try {
            String esc = uninstallCmd.replace("\"", "\\\"");
            new ProcessBuilder("cmd.exe", "/c", "start", "", "/wait", "cmd.exe", "/c",
                    "\"" + esc + "\" /SILENT /SUPPRESSMSGBOXES").start();
        } catch (Exception ignored) {
            // 调起失败不影响已清空的数据；用户可后续从控制面板卸载
        }
    }

    // ---------- helpers ----------

    private static Path resolveRoot(String requested) {
        if (requested != null && !requested.isBlank()) {
            return Path.of(requested.trim());
        }
        // 优先使用上次记录的位置，使安装器自身能记住用户修改过的安装目录
        Path lastRoot = Cfg.readLastRoot();
        if (lastRoot != null) {
            return lastRoot;
        }
        // 兼容旧逻辑：若默认目录下已有 state，直接沿用
        State st = State.load(Cfg.defaultRoot());
        if (st != null && st.root != null && !st.root.isBlank()) {
            return Path.of(st.root);
        }
        // 安装形态：运行时直接释放到安装过程中用户选择的目录（{app}），不再单独询问
        return Cfg.installDir();
    }

    private static Path requireRoot() {
        Path root = resolveRoot(null);
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("尚未部署运行时（root 不存在：" + root + "）");
        }
        return root;
    }

    /**
     * 校验安装根目录：绝对路径、可写。
     * 注意：运行时默认就释放到安装目录（{app}）内，因此不再禁止"位于安装程序自身目录内"——
     * 这是预期行为（与向导同目录，卸载时一并清理）。仅保留绝对路径与可写性校验。
     */
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

    /** 定位安装程序自身所在目录；jpackage 形态用系统属性，开发形态尝试定位运行中的 JAR 目录。 */
    private static Path resolveAppDir() {
        String jp = System.getProperty("jpackage.app-path");
        if (jp != null && !jp.isBlank()) {
            return Path.of(jp).getParent();
        }
        try {
            java.security.CodeSource cs = SetupMain.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path jar = Path.of(cs.getLocation().toURI());
                if (Files.isRegularFile(jar)) {
                    return jar.getParent();
                }
            }
        } catch (Exception ignored) {
            // 开发模式下可能拿不到，忽略
        }
        return null;
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

    private static void serveIndex(HttpExchange ex) throws IOException {
        try (InputStream in = SetupMain.class.getClassLoader().getResourceAsStream("index.html")) {
            byte[] html = in == null ? "<h1>index.html missing</h1>".getBytes(StandardCharsets.UTF_8) : in.readAllBytes();
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, html.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(html);
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
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // 降级到命令行方式
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

    /** 统一路径分隔符为正斜杠，便于前端一致显示。 */
    private static String normalizePath(String path) {
        return path == null ? null : path.replace("\\", "/");
    }
}

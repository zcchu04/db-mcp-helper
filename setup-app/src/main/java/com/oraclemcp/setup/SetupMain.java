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
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", SetupMain::handle);
        server.start();
        serverRef = server;
        String url = "http://127.0.0.1:" + port;
        System.out.println("Oracle MCP Setup 已启动：" + url);
        openBrowser(url);
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
            JsonObject resp = route(path, body, ex.getRequestURI());
            send(ex, 200, resp);
        } catch (Exception e) {
            send(ex, 200, err(e.getMessage() == null ? e.toString() : e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private static JsonObject route(String path, JsonObject body, URI uri) throws IOException {
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
        d.addProperty("home", Cfg.home().toString());
        d.addProperty("root", root.toString());
        d.addProperty("rootExists", Files.isDirectory(root));
        d.addProperty("toolkitDeployed", Files.isRegularFile(root.resolve(Cfg.TOOLKIT_FILE_NAME)));
        d.addProperty("tapDeployed", Files.isRegularFile(root.resolve(Cfg.TAP_FILE_NAME)));
        d.addProperty("javaCmd", Cfg.javaCmd());
        d.addProperty("mcpJsonPath", Cfg.mcpJsonPath().toString());
        JsonArray registered = new JsonArray();
        McpJson.serverNames(Cfg.mcpJsonPath()).stream().filter(n -> n.startsWith("oracle-")).forEach(registered::add);
        d.add("registeredServers", registered);
        if (st != null) {
            d.add("state", GSON.toJsonTree(st));
        }
        return d;
    }

    // ---------- deploy ----------

    private static JsonObject deploy(JsonObject body) throws IOException {
        String reqRoot = str(body, "root");
        Path root = reqRoot != null && !reqRoot.isBlank() ? Path.of(reqRoot.trim()) : resolveRoot(null);
        State st = Installer.deploy(root);
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
     * 一键卸载：先执行一键清空，再移除安装程序自身。
     * jpackage 形态（系统属性 jpackage.app-path 存在）调度退出后把程序目录移入回收站；
     * 开发形态（直接 java -jar）无法自删运行中的 JAR，仅清空数据并提示手动处理。
     */
    private static JsonObject uninstall() throws IOException {
        JsonObject d = reset();

        boolean shortcutRemoved = removeDesktopShortcut();
        d.addProperty("shortcutRemoved", shortcutRemoved);

        String jpackagePath = System.getProperty("jpackage.app-path");
        if (jpackagePath != null && !jpackagePath.isBlank()) {
            Path appDir = Path.of(jpackagePath).getParent();
            scheduleTrashAfterExit(appDir);
            d.addProperty("selfRemoved", "程序目录将在退出后移入回收站：" + appDir);
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

    /** 调度本进程退出后移除目录：优先回收站，不支持时兜底移入父目录 .trash。 */
    private static void scheduleTrashAfterExit(Path target) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String abs = target.toAbsolutePath().toString();
        if (os.contains("win")) {
            if (scheduleViaTaskScheduler(abs)) {
                return; // 任务计划程序脱离进程树执行，不受父进程退出影响
            }
        }
        // 兜底：直接派生延迟进程（常规双击启动场景下可用）
        try {
            String esc = abs.replace("'", "''");
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", trashScriptBody(esc));
            } else {
                pb = new ProcessBuilder("sh", "-c",
                        "sleep 3; gio trash '" + esc + "' 2>/dev/null || { mkdir -p \"$(dirname '" + esc + "')/.trash\" && mv '" + esc + "' \"$(dirname '" + esc + "')/.trash/$(basename '" + esc + "')-$(date +%Y%m%d%H%M%S)\"; }");
            }
            pb.start();
        } catch (Exception ignored) {
            // 自删除失败时数据已清空，仅程序目录残留
        }
    }

    /** Windows：写清理脚本到临时目录，经任务计划程序延迟执行（一次性任务，脚本末尾自删任务与自身）。 */
    private static boolean scheduleViaTaskScheduler(String abs) {
        try {
            String ts = String.valueOf(System.currentTimeMillis());
            Path ps1 = Path.of(System.getProperty("java.io.tmpdir"), "oracle-mcp-cleanup-" + ts + ".ps1");
            String tn = "OracleMCPSetupCleanup" + ts;
            String script = "Start-Sleep -Seconds 2\r\n" + trashScriptBody(abs.replace("'", "''")) + "\r\n"
                    + "& schtasks.exe /Delete /TN '" + tn + "' /F 2>$null\r\n"
                    + "Remove-Item -Force -ErrorAction SilentlyContinue $MyInvocation.MyCommand.Path\r\n";
            java.nio.file.Files.writeString(ps1, script, java.nio.charset.StandardCharsets.UTF_8);
            String runAt = java.time.LocalTime.now().plusSeconds(70).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            // 注意：/Z 与 /SC ONCE 在新版 Windows 会报 EndBoundary XML 错误，故由脚本自删任务
            ProcessBuilder pb = new ProcessBuilder("schtasks.exe", "/Create", "/F", "/TN", tn,
                    "/SC", "ONCE", "/ST", runAt,
                    "/TR", "powershell.exe -NoProfile -ExecutionPolicy Bypass -File \"" + ps1.toAbsolutePath() + "\"");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return p.waitFor() == 0 && !out.toLowerCase().contains("error") && !out.contains("错误");
        } catch (Exception e) {
            return false;
        }
    }

    /** PowerShell 清理主体：回收站优先，失败兜底移入父目录 .trash。 */
    private static String trashScriptBody(String esc) {
        return "try { Add-Type -AssemblyName Microsoft.VisualBasic; "
                + "[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory('" + esc + "', 'OnlyErrorDialogs', 'SendToRecycleBin') } catch {}; "
                + "if (Test-Path '" + esc + "') { "
                + "$fb = Join-Path (Split-Path '" + esc + "') '.trash'; "
                + "New-Item -ItemType Directory -Force -Path $fb | Out-Null; "
                + "Move-Item -Force '" + esc + "' (Join-Path $fb ((Split-Path '" + esc + "' -Leaf) + '-' + (Get-Date -Format yyyyMMddHHmmss))) }";
    }

    // ---------- helpers ----------

    private static Path resolveRoot(String requested) {
        if (requested != null && !requested.isBlank()) {
            return Path.of(requested.trim());
        }
        State st = State.load(Cfg.defaultRoot());
        if (st != null && st.root != null && !st.root.isBlank()) {
            return Path.of(st.root);
        }
        return Cfg.defaultRoot();
    }

    private static Path requireRoot() {
        Path root = resolveRoot(null);
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("尚未部署运行时（root 不存在：" + root + "）");
        }
        return root;
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
}

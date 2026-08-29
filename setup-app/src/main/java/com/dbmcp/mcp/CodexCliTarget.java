package com.dbmcp.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI Codex CLI：{@code ~/.codex/config.toml}（Windows 下 home 即 %USERPROFILE%）。
 *
 * <p>MCP server 以 TOML section 存储：
 * <pre>{@code
 * [mcp_servers.oracle-dev]
 * command = "cmd"
 * args = ["/c", "java", "-jar", "..."]
 *
 * [mcp_servers.oracle-dev.env]
 * KEY = "value"
 * }</pre>
 *
 * <p>项目未引入 TOML 库，这里用文本 + 正则做增删改：
 * 先移除同名旧 section（含 .env 子 section），再在文件末尾追加新 section。
 * TOML 内联数组与 JSON 数组语法兼容，args 直接用 Gson 紧凑序列化输出。
 */
public class CodexCliTarget implements McpTarget {

    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** 匹配 section 头，捕获 server 名（不含 .env 子级的名字部分）。 */
    private static final Pattern SERVER_HEADER = Pattern.compile("(?m)^\\[mcp_servers\\.([^\\].]+)\\]");

    @Override public String id() { return "codex-cli"; }
    @Override public String displayName() { return "Codex CLI"; }
    @Override public String describe() { return "OpenAI 官方 CLI，~/.codex/config.toml 的 [mcp_servers.*] 段"; }
    @Override public boolean writable() { return true; }

    @Override
    public List<Path> candidateConfigPaths() {
        return List.of(Paths.home("~/.codex/config.toml"));
    }

    @Override
    public boolean hasServer(Path cfg, String serverName) throws IOException {
        if (!Files.isRegularFile(cfg)) return false;
        return listServers(cfg).contains(serverName);
    }

    @Override
    public List<String> listServers(Path cfg) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isRegularFile(cfg)) return out;
        String text = Files.readString(cfg, StandardCharsets.UTF_8);
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = SERVER_HEADER.matcher(text);
        while (m.find()) seen.add(m.group(1));
        out.addAll(seen);
        return out;
    }

    @Override
    public void addServer(Path cfg, String serverName, JsonObject entry) throws IOException {
        backupIfPresent(cfg);
        ensureParent(cfg);
        String text = Files.isRegularFile(cfg) ? Files.readString(cfg, StandardCharsets.UTF_8) : "";
        text = removeSection(text, "[mcp_servers." + serverName + "]");
        text = removeSection(text, "[mcp_servers." + serverName + ".env]");
        StringBuilder sb = new StringBuilder(text);
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') sb.append('\n');
        sb.append("[mcp_servers.").append(serverName).append("]\n");
        if (entry.has("command") && !entry.get("command").isJsonNull()) {
            sb.append("command = ").append(tomlString(entry.get("command").getAsString())).append('\n');
        }
        JsonElement argsEl = entry.get("args");
        if (argsEl != null && argsEl.isJsonArray()) {
            List<String> args = new ArrayList<>();
            argsEl.getAsJsonArray().forEach(a -> args.add(a.getAsString()));
            sb.append("args = ").append(GSON.toJson(args)).append('\n');
        }
        JsonElement envEl = entry.get("env");
        if (envEl != null && envEl.isJsonObject() && envEl.getAsJsonObject().size() > 0) {
            sb.append('\n');
            sb.append("[mcp_servers.").append(serverName).append(".env]\n");
            envEl.getAsJsonObject().entrySet().forEach(e ->
                    sb.append(e.getKey()).append(" = ").append(tomlString(e.getValue().getAsString())).append('\n'));
        }
        Files.writeString(cfg, sb.toString(), StandardCharsets.UTF_8);
    }

    @Override
    public boolean removeServer(Path cfg, String serverName) throws IOException {
        if (!Files.isRegularFile(cfg)) return false;
        String text = Files.readString(cfg, StandardCharsets.UTF_8);
        if (!hasServer(cfg, serverName)) return false;
        backupIfPresent(cfg);
        String next = removeSection(text, "[mcp_servers." + serverName + "]");
        next = removeSection(next, "[mcp_servers." + serverName + ".env]");
        Files.writeString(cfg, next, StandardCharsets.UTF_8);
        return true;
    }

    /** 移除以 header 开始、到下一个 section 头（或文件尾）为止的整段。 */
    private String removeSection(String text, String header) {
        Pattern p = Pattern.compile("(?ms)^" + Pattern.quote(header) + "\\s*\\n.*?(?=^\\[|\\z)");
        return p.matcher(text).replaceAll("");
    }

    /** TOML 基本字符串：转义反斜杠与双引号。 */
    private String tomlString(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void ensureParent(Path cfg) throws IOException {
        Path parent = cfg.getParent();
        if (parent != null && !Files.isDirectory(parent)) Files.createDirectories(parent);
    }

    private void backupIfPresent(Path cfg) throws IOException {
        if (!Files.isRegularFile(cfg)) return;
        Path bak = cfg.resolveSibling(cfg.getFileName() + ".bak." + LocalDateTime.now().format(TS));
        Files.copy(cfg, bak, StandardCopyOption.REPLACE_EXISTING);
    }
}

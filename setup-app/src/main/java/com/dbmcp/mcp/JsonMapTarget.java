package com.dbmcp.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 标准 {@code { "mcpServers": { "<name>": { command, args, env } } }} schema 的通用实现。
 * Cursor / Claude Desktop / Windsurf / Trae / Kiro / iFlow / Claude Code / Gemini CLI /
 * 5ire / Msty / Kouzi / Cline / Roo Code 都基于此类构造，只需传入 id/displayName/候选路径。
 *
 * <p>可选参数 {@code rootKey} 覆盖顶层 map 键名（如 Zed 用 {@code context_servers}）。
 */
public class JsonMapTarget implements McpTarget {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final String id;
    private final String name;
    private final String desc;
    private final List<Path> candidates;
    private final String rootKey;

    public JsonMapTarget(String id, String name, String desc, List<Path> candidates) {
        this(id, name, desc, candidates, "mcpServers");
    }

    public JsonMapTarget(String id, String name, String desc, List<Path> candidates, String rootKey) {
        this.id = id;
        this.name = name;
        this.desc = desc;
        this.candidates = candidates;
        this.rootKey = rootKey;
    }

    @Override public String id() { return id; }
    @Override public String displayName() { return name; }
    @Override public String describe() { return desc; }
    @Override public boolean writable() { return true; }
    @Override public List<Path> candidateConfigPaths() { return candidates; }

    @Override
    public boolean hasServer(Path cfg, String serverName) throws IOException {
        if (!Files.isRegularFile(cfg)) return false;
        JsonObject root = readOrEmpty(cfg);
        JsonObject servers = asObject(root.get(rootKey));
        return servers != null && servers.has(serverName);
    }

    @Override
    public List<String> listServers(Path cfg) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isRegularFile(cfg)) return out;
        JsonObject root = readOrEmpty(cfg);
        JsonObject servers = asObject(root.get(rootKey));
        if (servers != null) servers.keySet().forEach(out::add);
        return out;
    }

    @Override
    public void addServer(Path cfg, String serverName, JsonObject entry) throws IOException {
        backupIfPresent(cfg);
        ensureParent(cfg);
        JsonObject root = readOrEmpty(cfg);
        JsonObject servers = asObject(root.get(rootKey));
        if (servers == null) { servers = new JsonObject(); root.add(rootKey, servers); }
        servers.add(serverName, entry);
        Files.writeString(cfg, GSON.toJson(root), StandardCharsets.UTF_8);
    }

    @Override
    public boolean removeServer(Path cfg, String serverName) throws IOException {
        if (!Files.isRegularFile(cfg)) return false;
        backupIfPresent(cfg);
        JsonObject root = readOrEmpty(cfg);
        JsonObject servers = asObject(root.get(rootKey));
        if (servers == null || !servers.has(serverName)) return false;
        servers.remove(serverName);
        Files.writeString(cfg, GSON.toJson(root), StandardCharsets.UTF_8);
        return true;
    }

    private JsonObject readOrEmpty(Path cfg) throws IOException {
        if (!Files.isRegularFile(cfg)) return new JsonObject();
        String text = Files.readString(cfg, StandardCharsets.UTF_8);
        if (text.isBlank()) return new JsonObject();
        try {
            JsonElement el = JsonParser.parseString(text);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            throw new IOException("解析 " + cfg + " 失败：" + e.getMessage() + "（已生成 .bak，请手动检查或恢复）");
        }
    }

    private JsonObject asObject(JsonElement el) {
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
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

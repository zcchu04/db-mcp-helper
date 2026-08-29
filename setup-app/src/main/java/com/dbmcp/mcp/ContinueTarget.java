package com.dbmcp.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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
 * Continue.dev：{@code ~/.continue/config.json}。
 *
 * <p>与标准 map schema 的区别：{@code mcpServers} 是<b>数组</b>，
 * server 名放在每个元素的 {@code name} 字段里：
 * <pre>{@code
 * {
 *   "mcpServers": [
 *     { "name": "oracle-dev", "command": "java", "args": ["-jar", "..."], "env": {...} }
 *   ]
 * }
 * }</pre>
 */
public class ContinueTarget implements McpTarget {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Override public String id() { return "continue"; }
    @Override public String displayName() { return "Continue"; }
    @Override public String describe() { return "开源 AI 编程扩展，mcpServers 为数组（name 在对象内）"; }
    @Override public boolean writable() { return true; }

    @Override
    public List<Path> candidateConfigPaths() {
        return List.of(Paths.home("~/.continue/config.json"));
    }

    @Override
    public boolean hasServer(Path cfg, String serverName) throws IOException {
        if (!Files.isRegularFile(cfg)) return false;
        JsonArray servers = serversOf(readOrEmpty(cfg));
        return indexOf(servers, serverName) >= 0;
    }

    @Override
    public List<String> listServers(Path cfg) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isRegularFile(cfg)) return out;
        JsonArray servers = serversOf(readOrEmpty(cfg));
        for (JsonElement el : servers) {
            if (el != null && el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("name") && !o.get("name").isJsonNull()) out.add(o.get("name").getAsString());
            }
        }
        return out;
    }

    @Override
    public void addServer(Path cfg, String serverName, JsonObject entry) throws IOException {
        backupIfPresent(cfg);
        ensureParent(cfg);
        JsonObject root = readOrEmpty(cfg);
        JsonArray servers = serversOf(root);
        JsonObject item = entry.deepCopy();
        item.addProperty("name", serverName);
        int idx = indexOf(servers, serverName);
        if (idx >= 0) {
            servers.set(idx, item);
        } else {
            servers.add(item);
        }
        root.add("mcpServers", servers);
        Files.writeString(cfg, GSON.toJson(root), StandardCharsets.UTF_8);
    }

    @Override
    public boolean removeServer(Path cfg, String serverName) throws IOException {
        if (!Files.isRegularFile(cfg)) return false;
        backupIfPresent(cfg);
        JsonObject root = readOrEmpty(cfg);
        JsonArray servers = serversOf(root);
        int idx = indexOf(servers, serverName);
        if (idx < 0) return false;
        servers.remove(idx);
        root.add("mcpServers", servers);
        Files.writeString(cfg, GSON.toJson(root), StandardCharsets.UTF_8);
        return true;
    }

    /** 取 root.mcpServers 数组；不存在或类型不对则返回新空数组（不挂到 root 上，由调用方决定）。 */
    private JsonArray serversOf(JsonObject root) {
        JsonElement el = root.get("mcpServers");
        if (el != null && el.isJsonArray()) return el.getAsJsonArray();
        return new JsonArray();
    }

    private int indexOf(JsonArray servers, String serverName) {
        for (int i = 0; i < servers.size(); i++) {
            JsonElement el = servers.get(i);
            if (el == null || !el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (o.has("name") && !o.get("name").isJsonNull()
                    && serverName.equals(o.get("name").getAsString())) {
                return i;
            }
        }
        return -1;
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

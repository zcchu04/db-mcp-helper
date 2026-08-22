package com.oraclemcp.setup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
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
 * QoderWork mcp.json 合并写入模块。
 *
 * <p>语义：解析现有 JSON → 只增改目标 server 键 → 回写；解析失败抛异常由调用方提示手工处理，
 * 绝不盲覆盖。每次写前生成时间戳备份。
 */
public final class McpJson {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private McpJson() {
    }

    /** 读取 mcp.json 中已注册的 server 名列表（文件不存在返回空）。 */
    public static List<String> serverNames(Path mcpJson) {
        List<String> names = new ArrayList<>();
        JsonObject servers = readServers(mcpJson);
        if (servers != null) {
            servers.keySet().forEach(names::add);
        }
        return names;
    }

    /** 注册/更新一个环境的 MCP 条目（tap 包 toolkit 链路），返回写入的条目。 */
    public static JsonObject register(Path mcpJson, String serverName, List<String> command) throws IOException {
        JsonObject root = readRoot(mcpJson);
        JsonObject servers = root.has("mcpServers") && root.get("mcpServers").isJsonObject()
                ? root.getAsJsonObject("mcpServers") : new JsonObject();

        JsonObject entry = new JsonObject();
        entry.addProperty("command", command.get(0));
        JsonArray args = new JsonArray();
        for (int i = 1; i < command.size(); i++) {
            args.add(command.get(i));
        }
        entry.add("args", args);
        entry.addProperty("enabled", true);

        servers.add(serverName, entry);
        root.add("mcpServers", servers);
        writeRoot(mcpJson, root);
        return entry;
    }

    /** 移除一个 server 条目；不存在则无操作。 */
    public static boolean remove(Path mcpJson, String serverName) throws IOException {
        JsonObject root = readRoot(mcpJson);
        if (!root.has("mcpServers") || !root.get("mcpServers").isJsonObject()) {
            return false;
        }
        JsonObject servers = root.getAsJsonObject("mcpServers");
        if (!servers.has(serverName)) {
            return false;
        }
        servers.remove(serverName);
        writeRoot(mcpJson, root);
        return true;
    }

    /** 批量移除指定前缀的 server 条目（一次备份一次写回），返回移除数量。 */
    public static int removeByPrefix(Path mcpJson, String prefix) throws IOException {
        JsonObject root = readRoot(mcpJson);
        if (!root.has("mcpServers") || !root.get("mcpServers").isJsonObject()) {
            return 0;
        }
        JsonObject servers = root.getAsJsonObject("mcpServers");
        List<String> hit = new ArrayList<>();
        for (String k : servers.keySet()) {
            if (k.startsWith(prefix)) {
                hit.add(k);
            }
        }
        if (hit.isEmpty()) {
            return 0;
        }
        hit.forEach(servers::remove);
        writeRoot(mcpJson, root);
        return hit.size();
    }

    /** 组装 tap 链路的完整命令行。 */
    public static List<String> buildCommand(Path root, String env, List<String> tools) {
        String java = Installer.resolveJava(root); // 优先安装目录精简运行时，目标机器零 Java 依赖
        return List.of(
                java, "-jar", root.resolve(Cfg.TAP_FILE_NAME).toString(),
                "--log", Installer.callLog(root, env).toString(), "--",
                java, "-DconfigFile=" + Installer.configYaml(root, env).toString(),
                "-Dtools=" + String.join(",", tools), "-jar", root.resolve(Cfg.TOOLKIT_FILE_NAME).toString());
    }

    private static JsonObject readRoot(Path mcpJson) throws IOException {
        if (!Files.isRegularFile(mcpJson)) {
            return new JsonObject();
        }
        String text = Files.readString(mcpJson, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("mcp.json 解析失败，为避免破坏已有配置已中止：" + e.getMessage());
        }
    }

    private static JsonObject readServers(Path mcpJson) {
        try {
            JsonObject root = readRoot(mcpJson);
            return root.has("mcpServers") && root.get("mcpServers").isJsonObject() ? root.getAsJsonObject("mcpServers") : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeRoot(Path mcpJson, JsonObject root) throws IOException {
        if (Files.isRegularFile(mcpJson)) {
            Path bak = mcpJson.resolveSibling(mcpJson.getFileName() + ".bak-" + LocalDateTime.now().format(TS));
            Files.copy(mcpJson, bak, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.createDirectories(mcpJson.toAbsolutePath().getParent());
        Files.writeString(mcpJson, GSON.toJson(root), StandardCharsets.UTF_8);
    }
}

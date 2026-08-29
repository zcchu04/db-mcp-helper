package com.dbmcp.setup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 多平台接入指南构建器：读取 platforms.json 平台清单，按环境实际注册命令
 * 为各平台生成预填配置模板（mcpServers JSON / Claude CLI 命令 / Codex TOML）。
 */
public final class PlatformGuide {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private PlatformGuide() {
    }

    /** 生成指定环境的各平台接入指南（含预填模板）。 */
    public static JsonObject guide(Path baseDir, String dbId, String env, List<String> tools, DbAdapter adapter) {
        List<String> cmd = adapter.buildCommand(baseDir, dbId, env, tools);
        JsonObject entry = new JsonObject();
        entry.addProperty("command", cmd.get(0));
        JsonArray args = new JsonArray();
        for (int i = 1; i < cmd.size(); i++) {
            args.add(cmd.get(i));
        }
        entry.add("args", args);
        Map<String, String> envVars = adapter.envVars("", "", "", adapter.defaultPort(), "");
        if (!envVars.isEmpty()) {
            JsonObject envObj = new JsonObject();
            envVars.forEach(envObj::addProperty);
            entry.add("env", envObj);
        }

        JsonObject platformsRoot = loadPlatforms();
        JsonArray out = new JsonArray();
        String serverName = adapter.serverPrefix() + env;
        for (var el : platformsRoot.getAsJsonArray("platforms")) {
            JsonObject p = el.getAsJsonObject();
            JsonObject item = p.deepCopy();
            String format = p.get("format").getAsString();
            item.addProperty("template", renderTemplate(format, serverName, entry));
            out.add(item);
        }
        JsonObject d = new JsonObject();
        d.addProperty("serverName", serverName);
        d.add("platforms", out);
        return d;
    }

    private static String renderTemplate(String format, String serverName, JsonObject entry) {
        switch (format) {
            case "claude-cli": {
                return "claude mcp add-json " + serverName + " '" + COMPACT.toJson(entry) + "'";
            }
            case "codex-toml": {
                StringBuilder sb = new StringBuilder();
                sb.append("[mcp_servers.").append(serverName).append("]\n");
                sb.append("command = ").append(toml(entry.get("command").getAsString())).append('\n');
                sb.append("args = [");
                JsonArray args = entry.getAsJsonArray("args");
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(toml(args.get(i).getAsString()));
                }
                sb.append("]");
                if (entry.has("env")) {
                    JsonObject env = entry.getAsJsonObject("env");
                    env.keySet().forEach(k -> sb.append("\n").append(k).append(" = ").append(toml(env.get(k).getAsString())));
                }
                return sb.toString();
            }
            case "mcpServers-json":
            default: {
                JsonObject servers = new JsonObject();
                JsonObject full = entry.deepCopy();
                full.addProperty("enabled", true);
                servers.add(serverName, full);
                JsonObject wrapper = new JsonObject();
                wrapper.add("mcpServers", servers);
                return PRETTY.toJson(wrapper);
            }
        }
    }

    /** TOML 基本字符串转义（反斜杠与双引号）。 */
    private static String toml(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static JsonObject loadPlatforms() {
        try (InputStream in = PlatformGuide.class.getClassLoader().getResourceAsStream("platforms.json")) {
            if (in == null) {
                throw new IllegalStateException("缺少内置资源 platforms.json");
            }
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取 platforms.json 失败：" + e.getMessage(), e);
        }
    }
}

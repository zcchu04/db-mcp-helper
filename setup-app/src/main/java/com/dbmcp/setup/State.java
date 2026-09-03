package com.dbmcp.setup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 安装状态（state.json）：运行时版本、环境清单、Skill 部署位置。
 * 支撑管理台展示、environments.md 同步与幂等判断。跨所有数据库类型共享一份。
 *
 * <p>模型（方案 B：连接与实现分离）：
 * 一个「连接」{@code dbId/env} 拥有连接要素（host/port/database/user/password/url），
 * 其下按 {@code mcpServer} 实现分组 {@link ProviderInfo}（工具清单、注册状态、自检、连接器名）。
 * 同一数据源、同一环境可挂多个 MCP 提供方，各自独立注册为不同连接器、互不覆盖。
 */
public final class State {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public int version = 1;
    public String root;
    public String toolkitVersion;
    public String javaCmd;
    /** 连接键（dbId/env）→ 连接信息（含按 mcpServer 分组的实现列表）。 */
    public Map<String, EnvInfo> envs = new LinkedHashMap<>();
    /** 已部署 skill 的 agent 技能根目录列表（映射文件同步目标）。 */
    public List<String> skillTargets = new ArrayList<>();

    /** 单连接元数据：连接要素 + 按 mcpServer 分组的实现列表。 */
    public static final class EnvInfo {
        public String dbType;            // 数据库类型 id（oracle / mysql）
        public List<String> aliases = new ArrayList<>();

        // --- 连接要素（同一连接的多个实现共享）---
        public String host;
        public int port;
        /** Oracle 为 service name，MySQL 为 database 名。 */
        public String database;
        public String user;
        /** 明文口令仅保存在用户本机 state.json（与 connection.* 同等级别）。 */
        public String password;
        public String url;

        /** mcpServer 实现 id → 该实现的注册 / 工具 / 自检信息（方案 B 核心）。 */
        public Map<String, ProviderInfo> providers = new LinkedHashMap<>();
    }

    /** 单个 MCP 实现（provider）的元数据。 */
    public static final class ProviderInfo {
        public List<String> tools = new ArrayList<>();
        public boolean registered;
        public LastTest lastTest;
        /** 自定义连接器名；null/空 = 默认规则 serverPrefix + env（默认实现）或 + "-" + mcpServer。 */
        public String serverName;
    }

    /** 最近一次自检快照。 */
    public static final class LastTest {
        public boolean ok;
        public String detail;
        public String ts;
    }

    public static State load(Path root) {
        Path f = root.resolve(Cfg.STATE_FILE_NAME);
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            String raw = Files.readString(f, StandardCharsets.UTF_8);
            JsonObject rootObj = GSON.fromJson(raw, JsonObject.class);
            if (rootObj == null || !rootObj.has("envs")) {
                return GSON.fromJson(raw, State.class);
            }
            State s = GSON.fromJson(raw, State.class);
            if (s == null || s.envs == null) {
                return s;
            }
            // 迁移：旧版 EnvInfo 把 mcpServer/registered/lastTest/tools/serverName 放在顶层（单实现）。
            // 新版拆为 providers 映射。逐项判断：若已含非空 providers 映射则视为新版，直接使用。
            JsonObject envsObj = rootObj.getAsJsonObject("envs");
            Map<String, EnvInfo> migrated = new LinkedHashMap<>();
            for (Map.Entry<String, EnvInfo> e : s.envs.entrySet()) {
                String key = e.getKey();
                EnvInfo info = e.getValue();
                JsonObject eo = envsObj.has(key) ? envsObj.getAsJsonObject(key) : null;
                boolean hasProviders = eo != null && eo.has("providers")
                        && eo.get("providers").isJsonObject()
                        && !eo.getAsJsonObject("providers").isEmpty();
                if (hasProviders) {
                    migrated.put(key, info);
                    continue;
                }
                // 旧版：构造默认 provider（取旧 mcpServer，缺省由 dbType 推断首选项）
                ProviderInfo p = new ProviderInfo();
                if (eo != null && eo.has("tools") && eo.get("tools").isJsonArray()) {
                    eo.getAsJsonArray("tools").forEach(t -> p.tools.add(t.getAsString()));
                }
                if (eo != null && eo.has("registered")) {
                    p.registered = eo.get("registered").getAsBoolean();
                }
                if (eo != null && eo.has("lastTest") && eo.get("lastTest").isJsonObject()) {
                    p.lastTest = GSON.fromJson(eo.getAsJsonObject("lastTest"), LastTest.class);
                }
                if (eo != null && eo.has("serverName") && !eo.get("serverName").isJsonNull()) {
                    p.serverName = eo.get("serverName").getAsString();
                }
                String ms = (eo != null && eo.has("mcpServer") && !eo.get("mcpServer").isJsonNull())
                        ? eo.get("mcpServer").getAsString() : null;
                if (ms == null || ms.isBlank()) {
                    ms = defaultMcpServer(root, key, info.dbType);
                }
                info.providers = new LinkedHashMap<>();
                info.providers.put(ms, p);
                migrated.put(key, info);
            }
            s.envs = migrated;
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    /** 旧数据缺 mcpServer 时按 dbType 推断默认实现 id；无法推断返回 "default"。 */
    private static String defaultMcpServer(Path root, String envKey, String dbType) {
        if (dbType != null && !dbType.isBlank()) {
            DbAdapter a = DbAdapters.get(dbType);
            if (a != null && !a.mcpServerOptions().isEmpty()) {
                return a.mcpServerOptions().get(0).id();
            }
        }
        String inferred = inferDbTypeByDir(root, envKey);
        if (inferred != null) {
            DbAdapter a = DbAdapters.get(inferred);
            if (a != null && !a.mcpServerOptions().isEmpty()) {
                return a.mcpServerOptions().get(0).id();
            }
        }
        return "default";
    }

    /** 迁移辅助：仅在旧 key 缺 dbType 时按磁盘 env 目录反推（命中唯一一个适配器才采纳）。 */
    private static String inferDbTypeByDir(Path root, String envCode) {
        try {
            String hit = null;
            for (DbAdapter a : DbAdapters.all()) {
                if (Files.isDirectory(Installer.envDir(root, a.id(), envCode))) {
                    if (hit != null) return null; // 多匹配则弃用，宁可丢也不乱猜
                    hit = a.id();
                }
            }
            return hit;
        } catch (Exception ex) {
            return null;
        }
    }

    public void save(Path root) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve(Cfg.STATE_FILE_NAME), GSON.toJson(this), StandardCharsets.UTF_8);
    }
}

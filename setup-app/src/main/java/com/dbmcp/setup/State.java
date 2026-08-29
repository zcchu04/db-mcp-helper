package com.dbmcp.setup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
 */
public final class State {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public int version = 1;
    public String root;
    public String toolkitVersion;
    public String javaCmd;
    /** 环境编码 → 环境信息（保持插入序，管理台按序展示）。 */
    public Map<String, EnvInfo> envs = new LinkedHashMap<>();
    /** 已部署 skill 的 agent 技能根目录列表（映射文件同步目标）。 */
    public List<String> skillTargets = new ArrayList<>();

    /** 单环境元数据。 */
    public static final class EnvInfo {
        public String dbType;            // 数据库类型 id（oracle / mysql）
        public List<String> aliases = new ArrayList<>();
        public List<String> tools = new ArrayList<>();
        public boolean registered;
        public LastTest lastTest;

        // --- 连接要素：Node 类服务器（MySQL）需要在启动时注入环境变量，故在 state 中留存 ---
        public String host;
        public int port;
        /** Oracle 为 service name，MySQL 为 database 名。 */
        public String database;
        public String user;
        /** 明文口令仅保存在用户本机 state.json（与 config.yaml / .env 同等级别）。 */
        public String password;
        public String url;
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
            return GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), State.class);
        } catch (Exception e) {
            return null;
        }
    }

    public void save(Path root) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve(Cfg.STATE_FILE_NAME), GSON.toJson(this), StandardCharsets.UTF_8);
    }
}

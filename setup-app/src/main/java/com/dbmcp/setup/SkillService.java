package com.dbmcp.setup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Skill 部署模块：静态正文 SKILL.md（各库打包资源）+ 动态环境映射 environments.md（按 state 生成）。
 * 每个数据库类型一个独立 Skill 目录（如 oracle-db-ops / mysql-db-ops），互不影响。
 * 支持多目标目录（多 agent），重复部署为覆盖更新；state 记录全部已部署目标以便后续同步。
 */
public final class SkillService {

    private SkillService() {
    }

    /** 部署到指定 agent 技能根目录列表；返回每个目录的结果描述。 */
    public static List<String> deploy(State st, List<String> targetRoots, DbAdapter adapter) throws IOException {
        String skillMd = readResource(adapter.skillResource());
        String envMd = renderEnvironments(st, adapter);
        List<String> results = new ArrayList<>();
        for (String t : targetRoots) {
            if (t == null || t.isBlank()) {
                continue;
            }
            Path dir = Path.of(t).resolve(adapter.skillDir());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"), skillMd, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("environments.md"), envMd, StandardCharsets.UTF_8);
            results.add(dir.toString());
            if (!st.skillTargets.contains(t)) {
                st.skillTargets.add(t);
            }
        }
        return results;
    }

    /** 仅同步 environments.md 到 state 记录过的全部目标（增删环境后调用）。 */
    public static List<String> syncMappings(State st, DbAdapter adapter) throws IOException {
        String envMd = renderEnvironments(st, adapter);
        List<String> updated = new ArrayList<>();
        for (String t : st.skillTargets) {
            Path dir = Path.of(t).resolve(adapter.skillDir());
            if (Files.isDirectory(dir)) {
                Files.writeString(dir.resolve("environments.md"), envMd, StandardCharsets.UTF_8);
                updated.add(dir.toString());
            }
        }
        return updated;
    }

    /** 按某库环境清单生成映射表：编码 → 连接器 → 权限 → 工具 → 别名。仅含该 dbType 的环境。 */
    public static String renderEnvironments(State st, DbAdapter adapter) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(adapter.displayName()).append(" 环境映射表\n\n");
        sb.append("> 本文件由 DB MCP Helper 生成并同步维护，最后更新：").append(Instant.now()).append("\n");
        sb.append("> 用途：将用户的口语表达（编码、中文叫法、别名）映射到具体的 MCP 连接器。\n");
        sb.append("> 识别规则见对应 SKILL.md「第一步：环境识别」。\n\n");
        if (st.envs.isEmpty()) {
            sb.append("（暂无已配置环境）\n");
            return sb.toString();
        }
        sb.append("| 编码 | 连接器 | 权限 | 工具清单 | 别名 |\n");
        sb.append("|---|---|---|---|---|\n");
        for (Map.Entry<String, State.EnvInfo> e : st.envs.entrySet()) {
            if (!adapter.id().equals(e.getValue().dbType)) {
                continue;
            }
            State.EnvInfo info = e.getValue();
            int slash = e.getKey().indexOf('/');
            String envCode = slash >= 0 ? e.getKey().substring(slash + 1) : e.getKey();
            for (Map.Entry<String, State.ProviderInfo> pe : info.providers.entrySet()) {
                String mcpServer = pe.getKey();
                State.ProviderInfo p = pe.getValue();
                List<String> tools = p.tools;
                boolean writable = adapter.runtimeKind() == DbAdapter.RuntimeKind.JAVA_JAR
                        ? tools.contains("write-query")
                        : tools.stream().anyMatch(t -> !t.equals("query"));
                String serverName = (p.serverName != null && !p.serverName.isBlank())
                        ? p.serverName : adapter.defaultServerName(envCode, mcpServer);
                sb.append("| ").append(e.getKey())
                        .append(" | ").append(serverName)
                        .append(" | ").append(writable ? "读写" : "只读")
                        .append(" | ").append(String.join(", ", tools))
                        .append(" | ").append(info.aliases.isEmpty() ? "—" : String.join("、", info.aliases))
                        .append(" |\n");
            }
        }
        return sb.toString();
    }

    private static String readResource(String name) throws IOException {
        try (InputStream in = SkillService.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("缺少内置资源：" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

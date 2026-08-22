package com.oraclemcp.setup;

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
 * Skill 部署模块：静态正文 SKILL.md（打包资源）+ 动态环境映射 environments.md（按 state 生成）。
 * 支持多目标目录（多 agent），重复部署为覆盖更新；state 记录全部已部署目标以便后续同步。
 */
public final class SkillService {

    public static final String SKILL_DIR_NAME = "oracle-db-ops";

    private SkillService() {
    }

    /** 部署到指定 agent 技能根目录列表；返回每个目录的结果描述。 */
    public static List<String> deploy(State st, List<String> targetRoots) throws IOException {
        String skillMd = readResource("skill/SKILL.md");
        String envMd = renderEnvironments(st);
        List<String> results = new ArrayList<>();
        for (String t : targetRoots) {
            if (t == null || t.isBlank()) {
                continue;
            }
            Path dir = Path.of(t).resolve(SKILL_DIR_NAME);
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
    public static List<String> syncMappings(State st) throws IOException {
        String envMd = renderEnvironments(st);
        List<String> updated = new ArrayList<>();
        for (String t : st.skillTargets) {
            Path dir = Path.of(t).resolve(SKILL_DIR_NAME);
            if (Files.isDirectory(dir)) {
                Files.writeString(dir.resolve("environments.md"), envMd, StandardCharsets.UTF_8);
                updated.add(dir.toString());
            }
        }
        return updated;
    }

    /** 按环境清单生成映射表：编码 → 连接器 → 权限 → 工具 → 别名。 */
    public static String renderEnvironments(State st) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Oracle 环境映射表\n\n");
        sb.append("> 本文件由 Oracle MCP 安装器生成并同步维护，最后更新：").append(Instant.now()).append("\n");
        sb.append("> 用途：将用户的口语表达（编码、中文叫法、别名）映射到具体的 MCP 连接器。\n");
        sb.append("> 识别规则见 SKILL.md「第一步：环境识别」。\n\n");
        if (st.envs.isEmpty()) {
            sb.append("（暂无已配置环境）\n");
            return sb.toString();
        }
        sb.append("| 编码 | 连接器 | 权限 | 工具清单 | 别名 |\n");
        sb.append("|---|---|---|---|---|\n");
        for (Map.Entry<String, State.EnvInfo> e : st.envs.entrySet()) {
            State.EnvInfo info = e.getValue();
            boolean writable = info.tools.contains("write-query");
            sb.append("| ").append(e.getKey())
                    .append(" | oracle-").append(e.getKey())
                    .append(" | ").append(writable ? "读写" : "只读")
                    .append(" | ").append(String.join(", ", info.tools))
                    .append(" | ").append(info.aliases.isEmpty() ? "—" : String.join("、", info.aliases))
                    .append(" |\n");
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

package com.dbmcp.mcp;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;

/**
 * 一个 MCP 客户端 target（Cursor / Claude Desktop / Windsurf / ...）的能力抽象。
 *
 * <p>与 {@code DbAdapter} 对称：DbAdapter 描述"如何为某数据库生成 stdio server 命令"，
 * McpTarget 描述"如何把某个 server 命令登记到某个 AI 客户端的配置文件里"。
 *
 * <p>每个实现负责：
 * <ol>
 *   <li>候选路径解析（跨 Windows/macOS/Linux，允许用户环境差异）</li>
 *   <li>schema 转换（{@code mcpServers} 对象 map / {@code context_servers} 对象 /
 *       {@code mcpServers} 数组 / TOML / YAML / CLI 调用 / UI-only）</li>
 *   <li>原子写入 + .bak.&lt;timestamp&gt; 备份</li>
 * </ol>
 */
public interface McpTarget {

    /** 唯一 id，如 "cursor"。前端与 /api/mcp/register 用它定位。 */
    String id();

    /** UI 显示名，如 "Cursor"。 */
    String displayName();

    /** 单行说明，展示在卡片副标位置。 */
    String describe();

    /** 图标字符（一个 emoji 或 unicode 符号），前端渲染用。 */
    default String icon() { return displayName().substring(0, 1); }

    /** 图标 CSS class（前端预定义），默认 generic。 */
    default String iconClass() { return id(); }

    /** 分组：primary（本机已检测到 QoderWork 类） / common（常见客户端） / manual / pending。 */
    default String tier() { return "common"; }

    /** 该 target 是否支持一键写入。false 表示只能"复制片段"。 */
    boolean writable();

    /** 通过 CLI 而非文件写入（如 LM Studio 的 `lms mcp add`）。 */
    default boolean cliBased() { return false; }

    /** UI-only 客户端（无本地配置文件 / 或写文件不安全），前端应展示操作步骤 + JSON 输入框，隐藏一键注册。 */
    default boolean uiOnly() { return false; }

    /** UI 手动接入步骤列表（有序，前端渲染为 ol）。空表示无 UI 引导。 */
    default java.util.List<String> uiInstructions() { return java.util.Collections.emptyList(); }

    /** CLI 类客户端：返回展示/可执行的注册命令（如 {@code lms mcp add name -- java -jar ...}）。null 表示非 CLI 配置。 */
    default String cliRegisterCommand(String serverName, JsonObject entry) { return null; }

    /** CLI 类客户端：返回展示/可执行的移除命令。null 表示无。 */
    default String cliUnregisterCommand(String serverName) { return null; }

    /** 检测本机候选配置文件路径。返回全部候选（可能都不存在），前端展示用。 */
    List<Path> candidateConfigPaths();

    /** 返回"实际会用哪个路径"——优先已存在的，其次返回第一个可创建的父目录存在的候选。null 表示无法定位。 */
    default Path detectActual() {
        List<Path> cands = candidateConfigPaths();
        for (Path p : cands) if (java.nio.file.Files.isRegularFile(p)) return p;
        for (Path p : cands) {
            Path parent = p.getParent();
            if (parent != null && java.nio.file.Files.isDirectory(parent)) return p;
        }
        return cands.isEmpty() ? null : cands.get(0);
    }

    /** 该 target 在当前机器上是否"已检测到"（配置目录存在）。 */
    default boolean detected() {
        Path p = detectActual();
        if (p == null) return false;
        return java.nio.file.Files.isRegularFile(p)
                || (p.getParent() != null && java.nio.file.Files.isDirectory(p.getParent()));
    }

    /** 判断某 serverName 是否已在配置文件中。 */
    boolean hasServer(Path cfg, String serverName) throws java.io.IOException;

    /** 列出该配置里已有的所有 server 名。 */
    List<String> listServers(Path cfg) throws java.io.IOException;

    /**
     * 注册（或覆盖）一个 server。实现内部负责 .bak 备份 + 原子写。
     * @param cfg 目标配置文件（可能不存在，实现负责创建）
     * @param serverName 例如 "oracle-dev"
     * @param entry MCP server 条目 { command, args, env }
     */
    void addServer(Path cfg, String serverName, JsonObject entry) throws java.io.IOException;

    /** 从配置中移除一个 server。同样 .bak 备份。返回是否真删除了。 */
    boolean removeServer(Path cfg, String serverName) throws java.io.IOException;

    /**
     * 该客户端对应的 agent 技能根目录（用于 Skill 部署），如 {@code ~/.cursor/skills}。
     * 默认由首个候选配置路径的父目录推导（父目录 + "skills"）；少数客户端（claude-code）技能目录
     * 与配置路径不在同一父目录，单独纠正。返回 null 表示无法推导（前端不生成推荐按钮）。
     */
    default Path skillDir() {
        if ("claude-code".equals(id())) {
            return Paths.home("~/.claude/skills");
        }
        List<Path> cands = candidateConfigPaths();
        if (cands == null || cands.isEmpty()) {
            return null;
        }
        Path p = cands.get(0);
        Path parent = p.getParent();
        return parent == null ? null : parent.resolve("skills");
    }
}

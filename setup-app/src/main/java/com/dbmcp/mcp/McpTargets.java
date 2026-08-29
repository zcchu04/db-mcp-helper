package com.dbmcp.mcp;

import java.util.List;

/**
 * MCP 客户端 target 注册表。新增客户端只需在此注册一个实现，
 * 前端通过 /api/mcp/targets 自动获得列表（与 {@code DbAdapters} 对称）。
 */
public final class McpTargets {

    private McpTargets() {}

    private static final List<McpTarget> ALL = build();

    private static List<McpTarget> build() {
        java.util.List<McpTarget> l = new java.util.ArrayList<>();
        // A: 标准 mcpServers JSON 对象
        l.add(new JsonMapTarget("cursor", "Cursor", "本地 IDE，通过 ~/.cursor/mcp.json",
                List.of(Paths.home("~/.cursor/mcp.json"))));
        l.add(new JsonMapTarget("claude", "Claude Desktop",
                "Anthropic 桌面客户端，config 位于 appData 目录",
                List.of(
                        Paths.appData().resolve("Claude").resolve("claude_desktop_config.json"),
                        Paths.appData().resolve("claude-desktop").resolve("claude_desktop_config.json")
                )));
        l.add(new JsonMapTarget("windsurf", "Windsurf", "Codeium 出品，独立 IDE",
                List.of(Paths.home("~/.codeium/windsurf/mcp_config.json"))));
        l.add(new JsonMapTarget("trae", "Trae", "字节跳动 AI IDE",
                List.of(
                        Paths.home("~/.trae/mcp.json"),
                        Paths.appData().resolve("Trae").resolve("User").resolve("mcp.json")
                )));
        l.add(new JsonMapTarget("kiro", "Kiro", "AWS AI IDE",
                List.of(Paths.home("~/.kiro/settings/mcp.json"))));
        l.add(new JsonMapTarget("iflow", "iFlow", "阿里云 AI IDE",
                List.of(Paths.home("~/.iflow/mcp.json"))));
        l.add(new JsonMapTarget("claude-code", "Claude Code",
                "Anthropic 官方 CLI，全局 mcp 配置",
                List.of(Paths.home("~/.claude.json"))));
        l.add(new JsonMapTarget("gemini-cli", "Gemini CLI",
                "Google 官方 CLI",
                List.of(Paths.home("~/.gemini/settings.json"))));
        l.add(new ClineTarget("cline", "Cline (VSCode)",
                "VSCode 扩展，推荐通过扩展 UI 添加；文件写入需 VSCode 未运行",
                List.of(
                        Paths.appData().resolve("Code").resolve("User").resolve("globalStorage")
                                .resolve("saoudrizwan.claude-dev").resolve("settings").resolve("cline_mcp_settings.json")
                )));
        l.add(new RooCodeTarget("roocode", "Roo Code (VSCode)",
                "VSCode 扩展（Cline 分支），推荐通过扩展 UI 添加",
                List.of(
                        Paths.appData().resolve("Code").resolve("User").resolve("globalStorage")
                                .resolve("rooveterinaryinc.roo-cline").resolve("settings").resolve("mcp_settings.json")
                )));
        // B: 特殊 schema
        l.add(new JsonMapTarget("zed", "Zed",
                "Zed 编辑器，字段名为 context_servers",
                List.of(
                        Paths.appData().resolve("zed").resolve("settings.json"),
                        Paths.home("~/.config/zed/settings.json")
                ),
                "context_servers"));
        l.add(new ContinueTarget());
        l.add(new CodexCliTarget());
        // C: CLI / UI
        l.add(new LmStudioTarget());
        l.add(new WorkBuddyTarget());
        l.add(new CcSwitchTarget());
        return java.util.Collections.unmodifiableList(l);
    }

    public static List<McpTarget> all() {
        return ALL;
    }

    public static McpTarget require(String id) {
        return ALL.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知 McpTarget: " + id));
    }
}

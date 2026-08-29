package com.dbmcp.mcp;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * cc-switch（Tauri 桌面 GUI，跨 Claude Code / Codex CLI / Gemini CLI 统一管理 MCP 配置）。
 * 内部用 SQLite 存配置，直接改文件会绕过它的同步机制，因此标记 uiOnly，让用户在它的 GUI 里粘贴。
 */
public class CcSwitchTarget implements McpTarget {

    @Override public String id() { return "cc-switch"; }
    @Override public String displayName() { return "cc-switch"; }
    @Override public String describe() { return "跨 Claude Code / Codex CLI / Gemini CLI 的 MCP 服务商切换器（Tauri GUI）"; }
    @Override public String icon() { return "⇄"; }
    @Override public String iconClass() { return "ccswitch"; }
    @Override public String tier() { return "pending"; }
    @Override public boolean writable() { return false; }
    @Override public boolean cliBased() { return false; }
    @Override public boolean uiOnly() { return true; }
    @Override public List<String> uiInstructions() {
        return List.of(
                "打开 cc-switch 桌面应用",
                "顶部 tab 切到「MCP Servers」",
                "点击右上角「Add」/「+ 新增」按钮",
                "在弹出的 JSON 编辑器里粘贴下方完整片段",
                "保存 → cc-switch 会自动同步到你已配置的 Claude Code / Codex / Gemini CLI"
        );
    }

    @Override public List<Path> candidateConfigPaths() { return List.of(); }
    @Override public Path detectActual() { return null; }
    @Override public boolean detected() { return false; }
    @Override public boolean hasServer(Path cfg, String serverName) { return false; }
    @Override public List<String> listServers(Path cfg) { return List.of(); }
    @Override public void addServer(Path cfg, String serverName, JsonObject entry) throws IOException {
        throw new UnsupportedOperationException("cc-switch 通过其 GUI 管理配置，请使用「复制片段」在应用内粘贴");
    }
    @Override public boolean removeServer(Path cfg, String serverName) throws IOException {
        throw new UnsupportedOperationException("cc-switch 通过其 GUI 管理配置，请在应用内删除");
    }
}

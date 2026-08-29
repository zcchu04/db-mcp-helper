package com.dbmcp.mcp;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 腾讯 CodeBuddy / WorkBuddy：官方未公开 MCP 配置文件路径，暂不支持一键写入。
 * 用户需复制片段后在其 UI（扩展 → 连接器）中手动粘贴。
 */
public class WorkBuddyTarget implements McpTarget {

    @Override public String id() { return "workbuddy"; }
    @Override public String displayName() { return "WorkBuddy"; }
    @Override public String describe() { return "腾讯 CodeBuddy / WorkBuddy，配置路径未公开，复制片段后在其「扩展 → 连接器」中手动添加"; }
    @Override public String icon() { return "W"; }
    @Override public String iconClass() { return "teal"; }
    @Override public String tier() { return "pending"; }
    @Override public boolean writable() { return false; }
    @Override public boolean cliBased() { return false; }
    @Override public boolean uiOnly() { return true; }
    @Override public java.util.List<String> uiInstructions() {
        return java.util.List.of(
                "打开 WorkBuddy 桌面客户端（v5.3+）",
                "主界面左上角侧栏 →「扩展」→「连接器」（侧栏入口名为「专家·技能·连接器」）",
                "点击「添加连接器」，连接类型选择 stdio / 本地命令",
                "「命令」输入框粘贴下方 command 字段值",
                "「参数 / Args」输入框逐行粘贴下方 args 数组内容（若支持 JSON 模式，可直接贴整段片段）",
                "保存并启用该连接器，回到任意会话即可在工具列表中看到 oracle-dev"
        );
    }

    @Override
    public List<Path> candidateConfigPaths() {
        return List.of();
    }

    @Override
    public Path detectActual() {
        return null;
    }

    @Override
    public boolean detected() {
        return false;
    }

    @Override
    public boolean hasServer(Path cfg, String serverName) throws IOException {
        return false;
    }

    @Override
    public List<String> listServers(Path cfg) throws IOException {
        return List.of();
    }

    @Override
    public void addServer(Path cfg, String serverName, JsonObject entry) throws IOException {
        throw new UnsupportedOperationException(
                "WorkBuddy 目前只能通过其 UI 手动添加 MCP server，请参考复制的片段在「扩展 → 连接器」中粘贴");
    }

    @Override
    public boolean removeServer(Path cfg, String serverName) throws IOException {
        throw new UnsupportedOperationException(
                "WorkBuddy 目前只能通过其 UI 手动添加 MCP server，请参考复制的片段在「扩展 → 连接器」中粘贴");
    }
}

package com.dbmcp.mcp;

import java.nio.file.Path;
import java.util.List;

/**
 * Cline（VSCode 扩展）：globalStorage 里的 cline_mcp_settings.json 技术上可写，
 * 但 VSCode 运行时会覆盖回来，因此推荐走扩展 UI 添加。这里保留文件路径作为「高级」入口，
 * 主流程走 UI 步骤。
 */
public class ClineTarget extends JsonMapTarget {

    public ClineTarget(String id, String name, String desc, List<Path> candidates) {
        super(id, name, desc, candidates);
    }

    @Override public boolean uiOnly() { return true; }

    @Override public List<String> uiInstructions() {
        return List.of(
                "打开 VSCode，安装 Cline 扩展（若未装：Ctrl+Shift+X 搜索 \"Cline\"）",
                "点击左侧活动栏的 Cline 图标进入面板",
                "面板右上角点击齿轮 ⚙ → 选「MCP Servers」",
                "点击「Edit Global MCP」，会打开 cline_mcp_settings.json",
                "把下方 JSON 片段贴到 \"mcpServers\": { ... } 里，保存",
                "VSCode 会自动 reload MCP；若没生效，重启 Cline 面板或整个 VSCode 窗口"
        );
    }
}

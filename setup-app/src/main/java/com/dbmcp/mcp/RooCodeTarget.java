package com.dbmcp.mcp;

import java.nio.file.Path;
import java.util.List;

/**
 * Roo Code（VSCode 扩展，Cline 分支）：与 Cline 类似的 UI 添加流程，配置文件路径不同。
 */
public class RooCodeTarget extends ClineTarget {

    public RooCodeTarget(String id, String name, String desc, List<Path> candidates) {
        super(id, name, desc, candidates);
    }

    @Override public List<String> uiInstructions() {
        return List.of(
                "打开 VSCode，安装 Roo Code 扩展（若未装：Ctrl+Shift+X 搜索 \"Roo Code\"）",
                "点击左侧活动栏的 Roo 图标进入面板",
                "面板右上角点击齿轮 ⚙ → 选「MCP Servers」→「Edit Global MCP」",
                "把下方 JSON 片段贴到 \"mcpServers\": { ... } 里，保存",
                "重启 Roo Code 面板或整个 VSCode 窗口生效"
        );
    }
}

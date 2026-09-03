package com.dbmcp.setup;

import java.util.List;

/**
 * 一个数据库类型下可选的 MCP server 实现描述。
 *
 * <p>例如 MySQL 内置两种实现：@benborla29/mcp-server-mysql（单工具万能 SQL + 写能力开关）
 * 与 @naganpm/mysql-mcp-server（8 个细粒度工具）。每种实现自带工具清单，
 * 前端按所选实现渲染工具勾选区，后端按所选实现校验必选工具。
 *
 * @param id            实现唯一标识（存入 state.json 的 EnvInfo.mcpServer）
 * @param displayName   前端展示名
 * @param description   一句话说明（工具集 / 运行时特征）
 * @param allTools      该实现暴露的全部工具/能力项清单（向导中可勾选）
 * @param requiredTools 该实现的必选项（缺这些不允许保存/注册）
 */
public record McpServerOption(
        String id,
        String displayName,
        String description,
        List<String> allTools,
        List<String> requiredTools) {

    /** 兼容旧签名的便捷构造（allTools/requiredTools 为空清单，工具清单沿用适配器级定义）。 */
    public McpServerOption(String id, String displayName, String description) {
        this(id, displayName, description, List.of(), List.of());
    }
}

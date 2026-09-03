package com.dbmcp.setup;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 数据库适配器：把原本写死在 setup-app 中的 Oracle 专属逻辑抽象出来，
 * 使安装引擎对具体数据库类型无感知。新增数据库类型只需新增一个实现并注册到 {@link DbAdapters}。
 *
 * <p>目录模型：{@code baseDir}（安装目录，安装形态即 {app}）下按 {@code id()} 区分
 * 每个数据库的子目录 {@code baseDir/<id>/}，内含该库的 toolkit 与（按需）运行时；
 * 共享的 mcp-tap 与 jlink JRE 放在 baseDir 顶层，供所有库复用。
 */
public interface DbAdapter {

    /** 运行时种类：JAVA_JAR 走 jlink JRE；NODE 走捆绑的 node 运行时。 */
    enum RuntimeKind { JAVA_JAR, NODE }

    /** 稳定标识，如 oracle / mysql，用作目录名、连接器前缀、state 中的 dbType。 */
    String id();

    /** 展示名，如 Oracle / MySQL。 */
    String displayName();

    /** 安装目录下的子目录名（dev 形态也用作 ~/.agent/mcp/<rootSubdir>）。通常等于 id()。 */
    String rootSubdir();

    /** toolkit 文件名（jar 名或预编译 server 目录名），位于 baseDir/<id>/toolkit/ 下。 */
    String toolkitFileName();

    /** 该库对应的 Skill 目录名（每个数据库类型一个，互不影响）。 */
    String skillDir();

    /** 打包资源中该库静态 SKILL.md 的路径（classpath）。 */
    String skillResource();

    /** 默认端口。 */
    int defaultPort();

    /** 必选工具（缺这些工具不允许注册）。 */
    List<String> requiredTools();

    /** 该库暴露的全部工具清单（向导中可勾选）。 */
    List<String> allTools();

    /** 注册到 mcp.json 的连接器名前缀，如 oracle- / mysql-。 */
    String serverPrefix();

    /** 该库可选的 MCP server 实现列表（配置实例页底部单选）。 */
    default List<McpServerOption> mcpServerOptions() {
        return List.of();
    }

    /** 取默认实现 id（options 首项；无选项时 null）。 */
    default String defaultMcpServer() {
        List<McpServerOption> opts = mcpServerOptions();
        return opts.isEmpty() ? null : opts.get(0).id();
    }

    /** 按环境编码与实现 id 计算默认连接器名：默认实现=前缀+env；非默认=前缀+env-实现。 */
    default String defaultServerName(String env, String mcpServer) {
        List<McpServerOption> opts = mcpServerOptions();
        String def = opts.isEmpty() ? null : opts.get(0).id();
        if (def != null && def.equals(mcpServer)) {
            return serverPrefix() + env;
        }
        return serverPrefix() + env + "-" + mcpServer;
    }

    /** 按实现 id 取 allTools；未匹配则回退适配器级 allTools()。 */
    default List<String> allToolsFor(String mcpServer) {
        return mcpServerOptions().stream()
                .filter(o -> o.id().equals(mcpServer))
                .findFirst()
                .filter(o -> !o.allTools().isEmpty())
                .map(McpServerOption::allTools)
                .orElseGet(this::allTools);
    }

    /** 按实现 id 取 requiredTools；未匹配则回退适配器级 requiredTools()。 */
    default List<String> requiredToolsFor(String mcpServer) {
        return mcpServerOptions().stream()
                .filter(o -> o.id().equals(mcpServer))
                .findFirst()
                .filter(o -> !o.requiredTools().isEmpty())
                .map(McpServerOption::requiredTools)
                .orElseGet(this::requiredTools);
    }

    /**
     * 除 toolkitFileName() 外还需解压的内置目录资源（相对 classpath，解压到 baseDir/&lt;id&gt;/toolkit/ 下）。
     * 用于同一数据库类型内置多个 server 实现的场景；默认无。
     */
    default List<String> extraToolkitDirResources() {
        return List.of();
    }

    /** 服务端运行时种类。 */
    RuntimeKind runtimeKind();

    /** 环境连接配置文件名，如 config.yaml / .env。 */
    String configFileName();

    /** 组装 JDBC 风格连接串（仅用于展示与粘贴解析回填；Node server 实际用环境变量）。 */
    String buildJdbcUrl(String host, int port, String db);

    /** 渲染环境连接配置文件内容（env 为环境编码，写进 YAML 的 dataSources key 或仅作注释）。 */
    String renderConfig(String env, String url, String user, String password);

    /** 注册/自检时注入服务端进程的环境变量（Oracle 为空，走 config.yaml）。tools 为该实例勾选的工具/能力项。 */
    Map<String, String> envVars(String url, String user, String password, int port, String db, List<String> tools);

    /**
     * 组装 tap 包裹服务端的完整命令（baseDir 顶层 jlink 的 java 驱动 mcp-tap）。
     * mcpServer 为所选实现 id（null = 默认实现）。
     */
    List<String> buildCommand(Path baseDir, String dbId, String env, List<String> tools, String mcpServer);

    /** 自检使用的工具名（Oracle 为 db-ping；MySQL 为 mysql_query）。 */
    String pingTool();

    /** 自检工具的入参（按实现分派：MySQL 单工具实现为 {"sql":...}，naganpm 为 {"query":...}）。 */
    JsonObject pingArguments(String mcpServer);

    /** 解析自检响应，填充 SelfTest.Result（ok / detail / fields）。 */
    void parsePing(String resp, SelfTest.Result r);
}

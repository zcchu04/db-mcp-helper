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

    /** 服务端运行时种类。 */
    RuntimeKind runtimeKind();

    /** 环境连接配置文件名，如 config.yaml / .env。 */
    String configFileName();

    /** 组装 JDBC 风格连接串（仅用于展示与粘贴解析回填；Node server 实际用环境变量）。 */
    String buildJdbcUrl(String host, int port, String db);

    /** 渲染环境连接配置文件内容（env 为环境编码，写进 YAML 的 dataSources key 或仅作注释）。 */
    String renderConfig(String env, String url, String user, String password);

    /** 注册/自检时注入服务端进程的环境变量（Oracle 为空，走 config.yaml）。 */
    Map<String, String> envVars(String url, String user, String password, int port, String db);

    /** 组装 tap 包裹服务端的完整命令（baseDir 顶层 jlink 的 java 驱动 mcp-tap）。 */
    List<String> buildCommand(Path baseDir, String dbId, String env, List<String> tools);

    /** 自检使用的工具名（Oracle 为 db-ping；MySQL 无 ping 工具则用 query）。 */
    String pingTool();

    /** 自检工具的入参（Oracle db-ping 为空；MySQL query 为 {"sql":"SELECT 1"}）。 */
    JsonObject pingArguments();

    /** 解析自检响应，填充 SelfTest.Result（ok / detail / fields）。 */
    void parsePing(String resp, SelfTest.Result r);
}

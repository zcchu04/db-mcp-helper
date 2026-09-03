package com.dbmcp.setup;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个 MCP 服务实现的安装元数据，持久化到 {@code baseDir/impls/impls.json}。
 *
 * <p>每个 {@code (dbId, serverId)} 对应一条记录。例如 {@code (oracle, builtin-toolkit)}、
 * {@code (mysql, benborla29)}。记录版本、来源、安装时间、入口文件、校验和及历史 bak 版本。
 */
public final class ImplInfo {

    public String version;
    /** 来源类型：builtin / github / uploaded。 */
    public String source;
    public String sourceUrl;
    public String installedAt;
    /** 入口文件名（相对于实现目录），如 oracle-db-mcp-toolkit.jar 或 build/index.js。 */
    public String entryFile;
    /** JAVA_JAR / NODE。 */
    public String runtimeKind;
    public String checksum;
    public List<BakVersion> bakVersions = new ArrayList<>();

    /** 历史 bak 版本条目。 */
    public static final class BakVersion {
        public String version;
        public String bakPath;
        public String createdAt;

        public BakVersion() {
        }

        public BakVersion(String version, String bakPath, String createdAt) {
            this.version = version;
            this.bakPath = bakPath;
            this.createdAt = createdAt;
        }
    }

    public ImplInfo() {
    }

    public ImplInfo(String version, String source, String entryFile, String runtimeKind) {
        this.version = version;
        this.source = source;
        this.entryFile = entryFile;
        this.runtimeKind = runtimeKind;
    }
}

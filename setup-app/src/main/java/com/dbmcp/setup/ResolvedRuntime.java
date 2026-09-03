package com.dbmcp.setup;

import java.nio.file.Path;

/**
 * 运行时解析结果：来源、可执行命令路径、版本号。
 */
public record ResolvedRuntime(
        String source,
        Path binDir,
        String version,
        String executable
) {
    public static final String SOURCE_BUNDLED = "bundled";
    public static final String SOURCE_LOCAL = "local";
    public static final String SOURCE_SYSTEM = "system";

    public boolean available() {
        return executable != null && !executable.isBlank();
    }
}

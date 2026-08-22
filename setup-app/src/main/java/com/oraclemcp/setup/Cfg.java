package com.oraclemcp.setup;

import java.nio.file.Path;

/**
 * 全局配置解析：默认路径 + 系统属性覆盖。
 *
 * <p>覆盖项（开发/测试用）：
 * <ul>
 *   <li>{@code -Dsetup.root} 安装根目录，默认 {@code ~/.agent/mcp/oracle}</li>
 *   <li>{@code -Dsetup.mcpJson} QoderWork mcp.json 路径，默认 {@code ~/.qoderwork/mcp.json}</li>
 *   <li>{@code -Dsetup.toolkitJar} toolkit JAR 来源（文件路径），缺省读打包资源</li>
 *   <li>{@code -Dsetup.tapJar} mcp-tap JAR 来源（文件路径），缺省读打包资源</li>
 *   <li>{@code -Dsetup.javaCmd} 注册到 mcp.json 的 java 可执行文件，默认当前 JVM</li>
 *   <li>{@code -Dsetup.port} 向导端口，默认 8765</li>
 * </ul>
 */
public final class Cfg {

    public static final String TOOLKIT_FILE_NAME = "oracle-db-mcp-toolkit-1.0.0.jar";
    public static final String TAP_FILE_NAME = "mcp-tap.jar";
    public static final String STATE_FILE_NAME = "state.json";

    private Cfg() {
    }

    public static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    /** 安装根目录（可在向导首步修改，修改后持久化到 state）。 */
    public static Path defaultRoot() {
        String v = System.getProperty("setup.root");
        return v != null && !v.isBlank() ? Path.of(v) : home().resolve(".agent").resolve("mcp").resolve("oracle");
    }

    public static Path mcpJsonPath() {
        String v = System.getProperty("setup.mcpJson");
        return v != null && !v.isBlank() ? Path.of(v) : home().resolve(".qoderwork").resolve("mcp.json");
    }

    public static String toolkitJarOverride() {
        return System.getProperty("setup.toolkitJar");
    }

    public static String tapJarOverride() {
        return System.getProperty("setup.tapJar");
    }

    /** 注册到 mcp.json 的 java 命令：优先 -Dsetup.javaCmd；jpackage 形态取应用自带运行时；否则当前 JVM。 */
    public static String javaCmd() {
        String v = System.getProperty("setup.javaCmd");
        if (v != null && !v.isBlank()) {
            return v;
        }
        String jp = System.getProperty("jpackage.app-path");
        if (jp != null && !jp.isBlank()) {
            boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
            Path exe = Path.of(jp).getParent().resolve("runtime").resolve("bin").resolve(win ? "java.exe" : "java");
            if (java.nio.file.Files.isRegularFile(exe)) {
                return exe.toString();
            }
        }
        return ProcessHandle.current().info().command().orElse("java");
    }

    public static int port() {
        try {
            return Integer.getInteger("setup.port", 8765);
        } catch (NumberFormatException e) {
            return 8765;
        }
    }
}

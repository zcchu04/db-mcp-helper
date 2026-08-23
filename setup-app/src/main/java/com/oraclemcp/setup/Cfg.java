package com.oraclemcp.setup;

import java.io.IOException;
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

    /** 记录用户最后选择的安装根目录的标记文件（位于默认根目录下，使安装器自身能记住自定义位置）。 */
    public static Path lastRootMarkerPath() {
        return defaultRoot().resolve(".last-root");
    }

    /** 读取上次使用的安装根目录；无标记或读取失败返回 null。 */
    public static Path readLastRoot() {
        Path f = lastRootMarkerPath();
        if (!java.nio.file.Files.isRegularFile(f)) {
            return null;
        }
        try {
            String s = java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (s.isBlank()) {
                return null;
            }
            return java.nio.file.Path.of(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 写入/更新上次使用的安装根目录标记。 */
    public static void writeLastRoot(Path root) throws IOException {
        Path f = lastRootMarkerPath();
        java.nio.file.Files.createDirectories(f.getParent());
        java.nio.file.Files.writeString(f, root.toAbsolutePath().toString(), java.nio.charset.StandardCharsets.UTF_8);
    }

    public static Path mcpJsonPath() {
        String v = System.getProperty("setup.mcpJson");
        return v != null && !v.isBlank() ? Path.of(v) : home().resolve(".qoderwork").resolve("mcp.json");
    }

    /** IDEA Qoder 插件的 MCP 配置文件路径。 */
    public static Path qoderPluginMcpJsonPath() {
        String v = System.getProperty("setup.qoderPluginMcpJson");
        return v != null && !v.isBlank() ? Path.of(v) : home().resolve(".qoder").resolve("shared_client").resolve("mcp.json");
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

    /**
     * 读取安装时落地到安装目录的 install-info.json（由 Inno Setup 的 [Code] 写入）。
     * 至少包含 uninstallString 字段（Inno 卸载器路径），供向导发起正规卸载。
     * 找不到或损坏返回 null。
     */
    public static String readUninstallString() {
        String path = System.getProperty("setup.installInfo");
        if (path == null || path.isBlank()) {
            Path appDir = resolveAppDir();
            if (appDir == null) {
                return null;
            }
            path = appDir.resolve("install-info.json").toString();
        }
        try {
            String text = java.nio.file.Files.readString(Path.of(path), java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonObject json = GSON_INSTANCE.fromJson(text, com.google.gson.JsonObject.class);
            if (json != null && json.has("uninstallString")) {
                return json.get("uninstallString").getAsString();
            }
        } catch (Exception ignored) {
            // 文件不存在或解析失败均视为无卸载信息
        }
        return null;
    }

    private static final com.google.gson.Gson GSON_INSTANCE = new com.google.gson.Gson();

    /** 安装目录；jpackage 形态取系统属性，开发形态尝试定位运行中的 JAR 目录。 */
    public static Path resolveAppDir() {
        String jp = System.getProperty("jpackage.app-path");
        if (jp != null && !jp.isBlank()) {
            return Path.of(jp).getParent();
        }
        try {
            java.security.CodeSource cs = Cfg.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path jar = Path.of(cs.getLocation().toURI());
                if (java.nio.file.Files.isRegularFile(jar)) {
                    return jar.getParent();
                }
            }
        } catch (Exception ignored) {
            // 开发模式下可能拿不到，忽略
        }
        return null;
    }

    /**
     * 运行时默认部署根目录。
     * 安装形态（Inno/jpackage 安装）：直接用安装过程中用户选择的目录（{app}），
     * 不再单独询问"安装根目录"——运行时与向导程序同目录，卸载时一并清理。
     * 开发形态（java -jar）：回退到 ~/.agent/mcp/oracle 保持兼容。
     */
    public static Path installDir() {
        Path app = resolveAppDir();
        if (app != null) {
            return app;
        }
        return defaultRoot();
    }
}

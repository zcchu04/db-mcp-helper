package com.dbmcp.mcp;

/**
 * 跨平台路径 helper。所有 McpTarget 用它构造候选路径列表。
 */
public final class Paths {

    private Paths() {}

    public static String home() { return System.getProperty("user.home"); }
    public static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }
    public static boolean isMac() { return System.getProperty("os.name", "").toLowerCase().contains("mac"); }

    public static java.nio.file.Path p(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(java.io.File.separatorChar);
            sb.append(parts[i]);
        }
        return java.nio.file.Path.of(sb.toString());
    }

    /** 把 "~" 展开为用户 home 目录。 */
    public static java.nio.file.Path home(String relative) {
        if (relative == null || relative.isEmpty()) return p(home());
        if (relative.startsWith("~/") || relative.startsWith("~\\")) {
            return p(home(), relative.substring(2));
        }
        return p(relative);
    }

    /** Windows %APPDATA% (Roaming) / macOS ~/Library/Application Support / Linux ~/.config。 */
    public static java.nio.file.Path appData() {
        if (isWindows()) {
            String v = System.getenv("APPDATA");
            return v != null && !v.isBlank() ? p(v) : p(home(), "AppData", "Roaming");
        }
        if (isMac()) return p(home(), "Library", "Application Support");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return xdg != null && !xdg.isBlank() ? p(xdg) : p(home(), ".config");
    }

    public static java.nio.file.Path pathFromHomeOrAppData(String appFolder, String fileName) {
        return appData().resolve(appFolder).resolve(fileName);
    }
}

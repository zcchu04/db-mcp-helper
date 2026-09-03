package com.dbmcp.setup;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 运行时环境管理器：解析 Java / Node 运行时的可用路径，支持用户覆盖、bundled、系统全局三级优先级。
 */
public final class RuntimeManager {

    private static final Gson GSON = new Gson();
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final String JAVA_EXE = WIN ? "java.exe" : "java";
    private static final String NODE_EXE = WIN ? "node.exe" : "node";

    private static final Pattern JAVA_VERSION_RE = Pattern.compile("version\\s+\"([^\"]+)\"");
    private static final Pattern NODE_VERSION_RE = Pattern.compile("v(\\d+\\.\\d+\\.\\d+)");

    private RuntimeManager() {
    }

    // ---- 解析 ----

    /**
     * 解析 Java 运行时，优先级：prefs 覆盖 → bundled runtime/ → 系统 JAVA_HOME / PATH。
     */
    public static ResolvedRuntime resolveJava(Path baseDir) {
        JsonObject prefs = Prefs.load();
        String override = readOverride(prefs, "java");
        if (override != null) {
            Path dir = Path.of(override);
            Path exe = findJavaExe(dir);
            if (exe != null) {
                String ver = detectJavaVersion(exe.toString());
                return new ResolvedRuntime(ResolvedRuntime.SOURCE_LOCAL, dir.resolve("bin"), ver, exe.toString());
            }
        }

        Path bundled = Installer.runtimeJava(baseDir);
        if (bundled != null) {
            String ver = detectJavaVersion(bundled.toString());
            return new ResolvedRuntime(ResolvedRuntime.SOURCE_BUNDLED, bundled.getParent(), ver, bundled.toString());
        }

        String sysJava = findSystemJava();
        if (sysJava != null) {
            String ver = detectJavaVersion(sysJava);
            return new ResolvedRuntime(ResolvedRuntime.SOURCE_SYSTEM, null, ver, sysJava);
        }

        return new ResolvedRuntime(ResolvedRuntime.SOURCE_SYSTEM, null, null, null);
    }

    /**
     * 解析 Node 运行时，优先级：prefs 覆盖 → 共享 runtimes/node → 各 db 遗留 runtime/node → 系统 PATH。
     */
    public static ResolvedRuntime resolveNode(Path baseDir) {
        JsonObject prefs = Prefs.load();
        String override = readOverride(prefs, "node");
        if (override != null) {
            Path dir = Path.of(override);
            Path exe = findNodeExe(dir);
            if (exe != null) {
                String ver = detectNodeVersion(exe.toString());
                return new ResolvedRuntime(ResolvedRuntime.SOURCE_LOCAL, dir.resolve("bin"), ver, exe.toString());
            }
        }

        Path sharedNode = baseDir.resolve("runtimes").resolve("node");
        Path exe = findNodeExe(sharedNode);
        if (exe != null) {
            String ver = detectNodeVersion(exe.toString());
            return new ResolvedRuntime(ResolvedRuntime.SOURCE_BUNDLED, sharedNode.resolve("bin"), ver, exe.toString());
        }

        for (DbAdapter a : DbAdapters.all()) {
            if (a.runtimeKind() != DbAdapter.RuntimeKind.NODE) continue;
            Path legacy = Installer.dbDir(baseDir, a.id()).resolve("runtime").resolve("node");
            exe = findNodeExe(legacy);
            if (exe != null) {
                String ver = detectNodeVersion(exe.toString());
                return new ResolvedRuntime(ResolvedRuntime.SOURCE_BUNDLED, legacy.resolve("bin"), ver, exe.toString());
            }
        }

        String sysNode = findSystemNode();
        if (sysNode != null) {
            String ver = detectNodeVersion(sysNode);
            return new ResolvedRuntime(ResolvedRuntime.SOURCE_SYSTEM, null, ver, sysNode);
        }

        return new ResolvedRuntime(ResolvedRuntime.SOURCE_SYSTEM, null, null, null);
    }

    // ---- 用户操作 ----

    /**
     * 设置本地运行时目录，写入 prefs.runtimeOverrides。同时验证目录有效性。
     */
    public static JsonObject setLocalRuntime(String kind, Path localDir) throws IOException {
        if (!Files.isDirectory(localDir)) {
            throw new IOException("目录不存在: " + localDir);
        }
        Path exe = "java".equals(kind) ? findJavaExe(localDir) : findNodeExe(localDir);
        if (exe == null) {
            throw new IOException("指定目录下未找到可用的 " + kind + " 运行时");
        }
        String ver = "java".equals(kind) ? detectJavaVersion(exe.toString()) : detectNodeVersion(exe.toString());

        JsonObject prefs = Prefs.load();
        JsonObject overrides = getOrCreateOverrides(prefs);
        overrides.addProperty(kind, localDir.toAbsolutePath().toString());
        prefs.add("runtimeOverrides", overrides);
        Prefs.save(prefs);

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("kind", kind);
        result.addProperty("path", localDir.toAbsolutePath().toString());
        result.addProperty("executable", exe.toString());
        result.addProperty("version", ver);
        result.addProperty("source", ResolvedRuntime.SOURCE_LOCAL);
        return result;
    }

    /**
     * 清除用户覆盖，恢复使用 bundled / system 运行时。
     */
    public static void resetRuntimeOverride(String kind) throws IOException {
        JsonObject prefs = Prefs.load();
        JsonObject overrides = getOrCreateOverrides(prefs);
        overrides.remove(kind);
        prefs.add("runtimeOverrides", overrides);
        Prefs.save(prefs);
    }

    // ---- 兼容性检测 ----

    /**
     * 检测指定目录下的运行时兼容性：二进制存在性 + 版本号。
     */
    public static JsonObject checkCompatibility(Path runtimeDir, String kind) {
        JsonObject result = new JsonObject();
        if (!Files.isDirectory(runtimeDir)) {
            result.addProperty("compatible", false);
            result.addProperty("error", "目录不存在: " + runtimeDir);
            return result;
        }

        if ("java".equals(kind)) {
            Path exe = findJavaExe(runtimeDir);
            if (exe == null) {
                result.addProperty("compatible", false);
                result.addProperty("error", "未找到 java 可执行文件");
                return result;
            }
            String ver = detectJavaVersion(exe.toString());
            result.addProperty("compatible", ver != null);
            result.addProperty("executable", exe.toString());
            result.addProperty("version", ver);
            if (ver == null) {
                result.addProperty("error", "无法解析 Java 版本");
            }
        } else if ("node".equals(kind)) {
            Path exe = findNodeExe(runtimeDir);
            if (exe == null) {
                result.addProperty("compatible", false);
                result.addProperty("error", "未找到 node 可执行文件");
                return result;
            }
            String ver = detectNodeVersion(exe.toString());
            result.addProperty("compatible", ver != null);
            result.addProperty("executable", exe.toString());
            result.addProperty("version", ver);
            if (ver == null) {
                result.addProperty("error", "无法解析 Node 版本");
            }
        } else {
            result.addProperty("compatible", false);
            result.addProperty("error", "未知运行时类型: " + kind);
        }
        return result;
    }

    // ---- 聚合查询 ----

    /**
     * 列出所有运行时状态（Java + Node），供 /api/runtimes 使用。
     */
    public static JsonObject listRuntimes(Path baseDir) {
        JsonObject result = new JsonObject();

        ResolvedRuntime java = resolveJava(baseDir);
        JsonObject javaObj = new JsonObject();
        javaObj.addProperty("kind", "java");
        javaObj.addProperty("source", java.source());
        javaObj.addProperty("version", java.version());
        javaObj.addProperty("executable", java.executable());
        javaObj.addProperty("available", java.available());
        javaObj.addProperty("override", readOverride(Prefs.load(), "java"));
        result.add("java", javaObj);

        ResolvedRuntime node = resolveNode(baseDir);
        JsonObject nodeObj = new JsonObject();
        nodeObj.addProperty("kind", "node");
        nodeObj.addProperty("source", node.source());
        nodeObj.addProperty("version", node.version());
        nodeObj.addProperty("executable", node.executable());
        nodeObj.addProperty("available", node.available());
        nodeObj.addProperty("override", readOverride(Prefs.load(), "node"));
        result.add("node", nodeObj);

        return result;
    }

    /**
     * 查询指定类型运行时详情，供 /api/runtimes/{kind} 使用。
     */
    public static JsonObject getRuntime(Path baseDir, String kind) {
        ResolvedRuntime rt = "java".equals(kind) ? resolveJava(baseDir) : resolveNode(baseDir);
        JsonObject obj = new JsonObject();
        obj.addProperty("kind", kind);
        obj.addProperty("source", rt.source());
        obj.addProperty("version", rt.version());
        obj.addProperty("executable", rt.executable());
        obj.addProperty("available", rt.available());
        obj.addProperty("override", readOverride(Prefs.load(), kind));
        if (rt.binDir() != null) {
            obj.addProperty("binDir", rt.binDir().toString());
        }
        return obj;
    }

    // ---- 从 GitHub 安装运行时 ----

    /**
     * 从 URL 下载运行时 zip → 解压到 runtimes/{kind}/ → 检测可用性。
     */
    public static JsonObject installRuntimeFromUrl(String kind, String url, Path baseDir) throws Exception {
        if (!"java".equals(kind) && !"node".equals(kind)) {
            throw new IOException("未知运行时类型: " + kind);
        }

        ArtifactDownloader.DownloadProgress progress = new ArtifactDownloader.DownloadProgress();
        Path tempFile;
        try {
            tempFile = ArtifactDownloader.downloadFromUrl(url, progress);
        } catch (Exception e) {
            throw new IOException("下载失败: " + e.getMessage(), e);
        }

        Path targetDir = baseDir.resolve("runtimes").resolve(kind);
        Path tmpDir = Files.createTempDirectory("rt-install-");
        try {
            byte[] zipBytes = Files.readAllBytes(tempFile);
            try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(
                    new java.io.ByteArrayInputStream(zipBytes))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    Path target = tmpDir.resolve(entry.getName()).normalize();
                    if (!target.startsWith(tmpDir)) {
                        throw new IOException("zip 条目路径非法: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(zin, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    zin.closeEntry();
                }
            }

            Path exe = null;
            try (var stream = Files.list(tmpDir)) {
                List<Path> entries = stream.toList();
                if (entries.size() == 1 && Files.isDirectory(entries.get(0))) {
                    Path inner = entries.get(0);
                    exe = "java".equals(kind) ? findJavaExe(inner) : findNodeExe(inner);
                    if (exe != null) {
                        copyTree(inner, targetDir);
                    }
                }
                if (exe == null) {
                    exe = "java".equals(kind) ? findJavaExe(tmpDir) : findNodeExe(tmpDir);
                    if (exe != null) {
                        copyTree(tmpDir, targetDir);
                    }
                }
            }

            if (exe == null) {
                throw new IOException("解压后未找到 " + kind + " 可执行文件");
            }

            String ver = "java".equals(kind) ? detectJavaVersion(exe.toString()) : detectNodeVersion(exe.toString());

            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("kind", kind);
            result.addProperty("version", ver);
            result.addProperty("executable", exe.toString());
            result.addProperty("installedTo", targetDir.toString());
            result.addProperty("source", ResolvedRuntime.SOURCE_BUNDLED);
            return result;
        } finally {
            ImplRegistry.deleteTree(tmpDir);
            Files.deleteIfExists(tempFile);
        }
    }

    private static void copyTree(Path src, Path dest) throws IOException {
        Files.createDirectories(dest);
        try (var stream = Files.walk(src)) {
            stream.forEach(source -> {
                Path relative = src.relativize(source);
                Path target = dest.resolve(relative);
                try {
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    // ---- 内部工具 ----

    private static String readOverride(JsonObject prefs, String kind) {
        if (!prefs.has("runtimeOverrides") || !prefs.get("runtimeOverrides").isJsonObject()) return null;
        JsonObject overrides = prefs.getAsJsonObject("runtimeOverrides");
        if (!overrides.has(kind) || overrides.get(kind).isJsonNull()) return null;
        String v = overrides.get(kind).getAsString();
        return (v != null && !v.isBlank()) ? v : null;
    }

    private static JsonObject getOrCreateOverrides(JsonObject prefs) {
        if (prefs.has("runtimeOverrides") && prefs.get("runtimeOverrides").isJsonObject()) {
            return prefs.getAsJsonObject("runtimeOverrides");
        }
        return new JsonObject();
    }

    private static Path findJavaExe(Path dir) {
        if (dir == null) return null;
        Path binJava = dir.resolve("bin").resolve(JAVA_EXE);
        if (Files.isRegularFile(binJava)) return binJava;
        Path directJava = dir.resolve(JAVA_EXE);
        if (Files.isRegularFile(directJava)) return directJava;
        return null;
    }

    private static Path findNodeExe(Path dir) {
        if (dir == null) return null;
        Path binNode = dir.resolve("bin").resolve(NODE_EXE);
        if (Files.isRegularFile(binNode)) return binNode;
        Path directNode = dir.resolve(NODE_EXE);
        if (Files.isRegularFile(directNode)) return directNode;
        return null;
    }

    private static String findSystemJava() {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            Path exe = findJavaExe(Path.of(javaHome));
            if (exe != null) return exe.toString();
        }
        return findOnPath(JAVA_EXE);
    }

    private static String findSystemNode() {
        return findOnPath(NODE_EXE);
    }

    private static String findOnPath(String exeName) {
        try {
            ProcessBuilder pb = new ProcessBuilder(WIN ? "where" : "which", exeName);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0 || out.isEmpty()) return null;
            String first = out.lines().findFirst().orElse(null);
            return (first != null && Files.isRegularFile(Path.of(first))) ? first : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String detectJavaVersion(String javaExe) {
        return runAndParse(List.of(javaExe, "-version"), JAVA_VERSION_RE);
    }

    private static String detectNodeVersion(String nodeExe) {
        return runAndParse(List.of(nodeExe, "--version"), NODE_VERSION_RE);
    }

    private static String runAndParse(List<String> cmd, Pattern pattern) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            Matcher m = pattern.matcher(out);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }
}

package com.oraclemcp.setup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 部署与环境配置模块：释放共享 JAR、生成环境 config.yaml、维护目录结构。
 *
 * <p>目录结构（root 默认 ~/.agent/mcp/oracle）：
 * <pre>
 * root/
 * ├── oracle-db-mcp-toolkit-1.0.0.jar   共享
 * ├── mcp-tap.jar                       共享
 * ├── state.json
 * └── envs/&lt;env&gt;/config.yaml            每环境独立
 * </pre>
 */
public final class Installer {

    /** 精简运行时目录名（jlink 产物，供 MCP 服务器进程使用，与用户机器 JDK 解耦）。 */
    public static final String RUNTIME_DIR_NAME = "runtime";
    private static final String RUNTIME_ZIP_RESOURCE = "runtime/mcp-runtime.zip";

    private Installer() {
    }

    /** 部署共享 JAR + 精简运行时并初始化 state；幂等（版本一致跳过覆盖逻辑由调用方判断）。 */
    public static State deploy(Path root) throws IOException {
        Files.createDirectories(root);
        extractOrCopy(Cfg.toolkitJarOverride(), "toolkit/" + Cfg.TOOLKIT_FILE_NAME, root.resolve(Cfg.TOOLKIT_FILE_NAME));
        extractOrCopy(Cfg.tapJarOverride(), "tap/" + Cfg.TAP_FILE_NAME, root.resolve(Cfg.TAP_FILE_NAME));
        deployRuntime(root);
        State st = State.load(root);
        if (st == null) {
            st = new State();
        }
        st.root = root.toString();
        st.toolkitVersion = "1.0.0";
        Path rtJava = runtimeJava(root);
        st.javaCmd = rtJava != null ? rtJava.toString() : Cfg.javaCmd();
        st.save(root);
        return st;
    }

    /** 释放精简运行时到 root/runtime；已存在则跳过。支持 -Dsetup.runtimeZip 指向 zip 文件或解压后的目录。 */
    public static void deployRuntime(Path root) throws IOException {
        if (runtimeJava(root) != null) {
            return;
        }
        Path target = root.resolve(RUNTIME_DIR_NAME);
        String override = System.getProperty("setup.runtimeZip");
        if (override != null && !override.isBlank()) {
            Path src = Path.of(override);
            if (Files.isDirectory(src)) {
                copyTree(src, target);
                return;
            }
            unzip(src, target);
            return;
        }
        try (InputStream in = Installer.class.getClassLoader().getResourceAsStream(RUNTIME_ZIP_RESOURCE)) {
            if (in == null) {
                throw new IOException("缺少内置运行时资源 " + RUNTIME_ZIP_RESOURCE + "，且未通过 -Dsetup.runtimeZip 指定");
            }
            unzip(in, target);
        }
    }

    /** 运行时 java 可执行文件；未部署返回 null。 */
    public static Path runtimeJava(Path root) {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path exe = root.resolve(RUNTIME_DIR_NAME).resolve("bin").resolve(win ? "java.exe" : "java");
        return Files.isRegularFile(exe) ? exe : null;
    }

    /** 注册/自检使用的 java：优先安装目录内的精简运行时，其次当前 JVM。 */
    public static String resolveJava(Path root) {
        Path rt = runtimeJava(root);
        return rt != null ? rt.toString() : Cfg.javaCmd();
    }

    private static void unzip(Path zipFile, Path targetDir) throws IOException {
        try (InputStream in = Files.newInputStream(zipFile)) {
            unzip(in, targetDir);
        }
    }

    /** 解压 zip 流到目标目录；zip 内单层根目录（mcp-runtime/）会被剥掉，内容直接落到 targetDir。 */
    private static void unzip(InputStream in, Path targetDir) throws IOException {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(in, StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry e;
            String stripPrefix = null;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (stripPrefix == null) {
                    int slash = name.indexOf('/');
                    stripPrefix = slash > 0 ? name.substring(0, slash + 1) : "";
                }
                if (!stripPrefix.isEmpty() && name.startsWith(stripPrefix)) {
                    name = name.substring(stripPrefix.length());
                }
                if (name.isEmpty()) {
                    continue;
                }
                Path out = targetDir.resolve(name).normalize();
                if (!out.startsWith(targetDir)) {
                    throw new IOException("zip 条目越界：" + e.getName());
                }
                // Compress-Archive 的目录条目以反斜杠结尾，isDirectory() 不识别，须同时按名称判断
                boolean dir = e.isDirectory() || name.endsWith("/");
                if (dir) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                    if (name.startsWith("bin/") && out.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                        try {
                            Files.setPosixFilePermissions(out, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
                        } catch (IOException ignored) {
                            // 权限设置失败不阻断
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.createDirectories(t.getParent());
                    Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 优先使用外部文件路径（开发期），否则从打包资源释放。 */
    private static void extractOrCopy(String overridePath, String resourcePath, Path target) throws IOException {
        if (overridePath != null && !overridePath.isBlank()) {
            Files.copy(Path.of(overridePath), target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        try (InputStream in = Installer.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("缺少内置资源 " + resourcePath + "，且未通过系统属性指定外部 JAR");
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static Path envDir(Path root, String env) {
        return root.resolve("envs").resolve(env);
    }

    public static Path configYaml(Path root, String env) {
        return envDir(root, env).resolve("config.yaml");
    }

    public static Path callLog(Path root, String env) {
        return envDir(root, env).resolve("calllog.jsonl");
    }

    /** 环境编码规范：小写字母开头，仅小写字母/数字/连字符。 */
    public static boolean validEnvName(String env) {
        return env != null && env.matches("^[a-z][a-z0-9-]{0,31}$");
    }

    /** 生成环境连接配置。密码仅本地落盘；值统一双引号包裹并转义。 */
    public static void writeEnvConfig(Path root, String env, String url, String user, String password) throws IOException {
        Path dir = envDir(root, env);
        Files.createDirectories(dir);
        String yaml = """
                # Oracle MCP 环境配置（由 oracle-mcp-setup 生成）
                # 修改后需在 AI 平台的连接器管理中对 oracle-%s 执行 disable→enable 才会重载
                dataSources:
                  %s:
                    url: "%s"
                    user: "%s"
                    password: "%s"
                """.formatted(env, env, yamlEscape(url), yamlEscape(user), yamlEscape(password));
        Files.writeString(configYaml(root, env), yaml, StandardCharsets.UTF_8);
        chmod600IfPosix(configYaml(root, env));
    }

    /** JDBC URL 组装（host/port/service 形式）。 */
    public static String jdbcUrl(String host, int port, String service) {
        return "jdbc:oracle:thin:@" + host + ":" + port + "/" + service;
    }

    private static String yamlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void chmod600IfPosix(Path f) {
        try {
            if (f.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException ignored) {
            // 权限设置失败不阻断
        }
    }

    /** 读取调用日志尾部（最新在前）。 */
    public static List<String> tailCallLog(Path root, String env, int limit) throws IOException {
        Path f = callLog(root, env);
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        int from = Math.max(0, lines.size() - limit);
        List<String> tail = lines.subList(from, lines.size());
        java.util.Collections.reverse(tail);
        return tail;
    }
}

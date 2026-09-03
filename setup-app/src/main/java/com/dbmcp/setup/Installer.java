package com.dbmcp.setup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * 部署与环境配置模块：释放共享 JAR/运行时、生成环境连接配置、维护目录结构。
 *
 * <p>目录结构（baseDir 默认 ~/.agent/mcp；安装形态即 {app}）：
 * <pre>
 * baseDir/
 * ├── runtime/           共享 jlink JRE（驱动 mcp-tap 与 Oracle toolkit）
 * ├── tap/mcp-tap.jar    共享监听代理
 * ├── state.json
 * └── &lt;dbId&gt;/           每数据库类型一个目录
 *     ├── toolkit/&lt;file&gt;
 *     ├── runtime/       （按需）该库服务端运行时
 *     └── instance/&lt;env&gt;/config.*
 * </pre>
 */
public final class Installer {

    /** 共享运行时目录名（jlink 产物，安装器 GUI 与 Oracle toolkit 共用，与用户机器 JDK 解耦）。 */
    public static final String RUNTIME_DIR_NAME = "runtime";
    private static final String RUNTIME_ZIP_RESOURCE = "runtime/runtime.zip";
    private static final String TAP_RESOURCE = "tap/" + Cfg.TAP_FILE_NAME;

    private Installer() {
    }

    /** 部署共享 tap + 共享 jlink 运行时 + 某库 toolkit/运行时，并初始化全局 state；幂等。 */
    public static State deploy(Path baseDir, String dbId, DbAdapter adapter) throws IOException {
        Files.createDirectories(baseDir);
        deployTap(baseDir);
        deployRuntime(baseDir);
        deployToolkit(baseDir, dbId, adapter);
        if (adapter.runtimeKind() == DbAdapter.RuntimeKind.NODE) {
            deployDbRuntime(baseDir, dbId, adapter);
        }
        State st = State.load(baseDir);
        if (st == null) {
            st = new State();
        }
        st.root = baseDir.toString();
        st.toolkitVersion = adapter.toolkitFileName();
        Path rtJava = runtimeJava(baseDir);
        st.javaCmd = rtJava != null ? rtJava.toString() : Cfg.javaCmd();
        st.save(baseDir);
        return st;
    }

    /** 释放共享 mcp-tap 到 baseDir/tap（优先 -Dsetup.tapJar，否则打包资源）。
     *  已存在且非空则跳过：避免覆盖正在被自检 tap 子进程占用的 jar（Windows 文件锁会导致部署失败）。 */
    public static void deployTap(Path baseDir) throws IOException {
        Path dest = baseDir.resolve("tap").resolve(Cfg.TAP_FILE_NAME);
        if (Files.isRegularFile(dest) && Files.size(dest) > 0) {
            return;
        }
        extractOrCopy(Cfg.tapJarOverride(), TAP_RESOURCE, dest);
    }

    /** 释放共享 jlink 运行时到 baseDir/runtime；已存在则跳过。 */
    public static void deployRuntime(Path baseDir) throws IOException {
        if (runtimeJava(baseDir) != null) {
            return;
        }
        Path target = baseDir.resolve(RUNTIME_DIR_NAME);
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

    /** 释放某库 toolkit 到 baseDir/<dbId>/toolkit/<file>（jar 或目录），并解压附加实现目录（如 mysql 的 naganpm）。 */
    public static void deployToolkit(Path baseDir, String dbId, DbAdapter adapter) throws IOException {
        Path dest = toolkitPath(baseDir, dbId, adapter);
        String res = "toolkit/" + dbId + "/" + adapter.toolkitFileName();
        extractOrCopy(null, res, dest);
        for (String extra : adapter.extraToolkitDirResources()) {
            // extra 形如 toolkit/mysql/mysql-naga-mcp-server，解压到 baseDir/<dbId>/toolkit/<basename>
            Path extraDest = dbDir(baseDir, dbId).resolve("toolkit").resolve(extra.substring(extra.lastIndexOf('/') + 1));
            extractOrCopy(null, extra, extraDest);
        }
    }

    /** 释放某库服务端运行时（如 mysql 的 node）到 baseDir/<dbId>/runtime/<name>。 */
    public static void deployDbRuntime(Path baseDir, String dbId, DbAdapter adapter) throws IOException {
        Path dest = dbDir(baseDir, dbId).resolve(RUNTIME_DIR_NAME).resolve("node");
        if (Files.isRegularFile(dest) || Files.isRegularFile(dest.resolve("node.exe")) || Files.isRegularFile(dest.resolve("node"))) {
            return;
        }
        String res = "runtime/" + dbId + "/node";
        extractOrCopy(null, res, dest);
    }

    // ---- 路径解析 ----

    public static Path dbDir(Path baseDir, String dbId) {
        return baseDir.resolve(dbId);
    }

    public static Path toolkitPath(Path baseDir, String dbId, DbAdapter adapter) {
        return dbDir(baseDir, dbId).resolve("toolkit").resolve(adapter.toolkitFileName());
    }

    public static Path envDir(Path baseDir, String dbId, String env) {
        return dbDir(baseDir, dbId).resolve("instance").resolve(env);
    }

    /**
     * 共享连接配置文件（方案 B：连接要素仅落盘一份，位于 instance/&lt;env&gt;/ 下）。
     * 文件名直接沿用适配器声明（Oracle → config.yaml，MySQL → .env），
     * 与历史布局一致，升级后旧实例无需重建。
     */
    public static Path connectionFile(Path baseDir, String dbId, String env, DbAdapter adapter) {
        return envDir(baseDir, dbId, env).resolve(adapter.configFileName());
    }

    /** 兼容别名：Oracle -DconfigFile 指向共享连接文件。 */
    public static Path configFile(Path baseDir, String dbId, String env, DbAdapter adapter) {
        return connectionFile(baseDir, dbId, env, adapter);
    }

    /** 某实现（provider）专属目录：instance/<env>/<mcpServer>/（仅存该实现的 calllog）。 */
    public static Path providerDir(Path baseDir, String dbId, String env, String mcpServer) {
        return envDir(baseDir, dbId, env).resolve(mcpServer);
    }

    public static Path callLog(Path baseDir, String dbId, String env, String mcpServer) {
        return providerDir(baseDir, dbId, env, mcpServer).resolve("calllog.jsonl");
    }

    /**
     * 一次性磁盘布局迁移：旧版把 calllog.jsonl 直接放在 instance/&lt;env&gt;/ 下（假定单实现），
     * 方案 B 后应落到 instance/&lt;env&gt;/&lt;mcpServer&gt;/calllog.jsonl。
     * 幂等：源文件不存在或目标已存在则跳过；历史日志整体归属该连接的第一个实现。
     */
    public static void migrateProviderLayout(Path baseDir, Map<String, State.EnvInfo> envs) {
        if (envs == null || envs.isEmpty()) {
            return;
        }
        for (Map.Entry<String, State.EnvInfo> e : envs.entrySet()) {
            State.EnvInfo info = e.getValue();
            if (info == null || info.providers == null || info.providers.isEmpty()) {
                continue;
            }
            int slash = e.getKey().indexOf('/');
            if (slash < 0) {
                continue;
            }
            String dbId = e.getKey().substring(0, slash);
            String env = e.getKey().substring(slash + 1);
            if (DbAdapters.get(dbId) == null) {
                continue;
            }
            Path legacy = envDir(baseDir, dbId, env).resolve("calllog.jsonl");
            if (!Files.isRegularFile(legacy)) {
                continue;
            }
            String first = info.providers.keySet().iterator().next();
            Path target = callLog(baseDir, dbId, env, first);
            try {
                Files.createDirectories(target.getParent());
                if (Files.isRegularFile(target)) {
                    // 目标已存在（已迁移过）：源文件只剩残留，尽力清理
                    Files.deleteIfExists(legacy);
                    continue;
                }
                try {
                    Files.move(legacy, target);
                } catch (IOException lockOrCrossDevice) {
                    // 运行中的 tap 进程可能持有句柄：退化为复制，历史日志不丢
                    Files.copy(legacy, target, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        Files.deleteIfExists(legacy);
                    } catch (IOException ignored2) {
                    }
                }
            } catch (IOException ignored) {
            }
        }
    }

    /** 共享运行时 java 可执行文件；未部署返回 null。 */
    public static Path runtimeJava(Path baseDir) {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path exe = baseDir.resolve(RUNTIME_DIR_NAME).resolve("bin").resolve(win ? "java.exe" : "java");
        return Files.isRegularFile(exe) ? exe : null;
    }

    /** 注册/自检使用的 java：优先安装目录内的共享运行时，其次当前 JVM。 */
    public static String resolveJava(Path baseDir) {
        Path rt = runtimeJava(baseDir);
        return rt != null ? rt.toString() : Cfg.javaCmd();
    }

    private static void unzip(Path zipFile, Path targetDir) throws IOException {
        try (InputStream in = Files.newInputStream(zipFile)) {
            unzip(in, targetDir);
        }
    }

    /** 解压 zip 流到目标目录；zip 内单层根目录（runtime/）会被剥掉，内容直接落到 targetDir。 */
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

    /** 优先使用外部文件路径（开发期），否则从打包资源释放（文件或目录）。 */
    private static void extractOrCopy(String overridePath, String resourcePath, Path target) throws IOException {
        if (overridePath != null && !overridePath.isBlank()) {
            Files.copy(Path.of(overridePath), target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        // 目录型资源优先（以 "/" 结尾标记）。注意：ClassLoader.getResourceAsStream 对目录条目
        // 可能返回 0 字节流，若先按单文件处理会把目录写成空文件并提前返回，导致目录型资源
        // （mysql server / node 运行时）解压为空文件。因此先判定目录再回退单文件。
        String dirRes = resourcePath.endsWith("/") ? resourcePath : resourcePath + "/";
        java.net.URL dirUrl = Installer.class.getClassLoader().getResource(dirRes);
        if (dirUrl != null && "jar".equals(dirUrl.getProtocol())) {
            copyJarDirResource(dirRes, target);
            return;
        }
        if (dirUrl != null && "file".equals(dirUrl.getProtocol())) {
            try {
                copyTree(Path.of(dirUrl.toURI()), target);
            } catch (java.net.URISyntaxException e) {
                throw new IOException("资源目录 URL 非法：" + dirUrl, e);
            }
            return;
        }
        // 单文件资源
        try (InputStream in = Installer.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in != null) {
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
        }
        throw new IOException("缺少内置资源 " + resourcePath + "（或 " + dirRes + "），且未通过系统属性指定外部文件");
    }

    /** 从 jar 内复制某个目录前缀下的所有条目到目标目录。 */
    private static void copyJarDirResource(String dirRes, Path target) throws IOException {
        java.util.Enumeration<java.net.URL> urls = Installer.class.getClassLoader().getResources(dirRes);
        boolean any = false;
        while (urls.hasMoreElements()) {
            java.net.URL url = urls.nextElement();
            if (!"jar".equals(url.getProtocol())) {
                continue;
            }
            any = true;
            String jarPath = url.getPath();
            int bang = jarPath.indexOf("!");
            if (bang < 0) {
                continue;
            }
            Path jar = Path.of(java.net.URI.create(jarPath.substring(0, bang)));
            String prefix = jarPath.substring(bang + 2);
            if (!prefix.endsWith("/")) {
                prefix += "/";
            }
            try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
                for (java.util.jar.JarEntry je : jf.stream().toList()) {
                    if (je.isDirectory() || !je.getName().startsWith(prefix)) {
                        continue;
                    }
                    String rel = je.getName().substring(prefix.length());
                    if (rel.isEmpty()) {
                        continue;
                    }
                    Path out = target.resolve(rel).normalize();
                    if (!out.startsWith(target)) {
                        continue;
                    }
                    try (InputStream in = jf.getInputStream(je)) {
                        Files.createDirectories(out.getParent());
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        if (!any) {
            throw new IOException("内置目录资源不存在：" + dirRes);
        }
    }

    /** 环境编码规范：小写字母开头，仅小写字母/数字/连字符。 */
    public static boolean validEnvName(String env) {
        return env != null && env.matches("^[a-z][a-z0-9-]{0,31}$");
    }

    /** 生成共享连接配置 + 确保该实现目录存在。密码仅本地落盘一份；adapter 决定文件名与内容。 */
    public static void writeEnvConfig(Path baseDir, String dbId, String env, String mcpServer, DbAdapter adapter,
                                      String url, String user, String password) throws IOException {
        Path dir = envDir(baseDir, dbId, env);
        Files.createDirectories(dir);
        String content = adapter.renderConfig(env, url, user, password);
        Path file = connectionFile(baseDir, dbId, env, adapter);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        chmod600IfPosix(file);
        // 该实现专属目录（仅承载 calllog），提前创建避免自检首次写日志时竞态
        Files.createDirectories(providerDir(baseDir, dbId, env, mcpServer));
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
    public static List<String> tailCallLog(Path baseDir, String dbId, String env, String mcpServer, int limit) throws IOException {
        Path f = callLog(baseDir, dbId, env, mcpServer);
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

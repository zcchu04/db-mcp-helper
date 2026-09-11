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
 * <p>目录结构（v0.3；baseDir 默认 ~/.agent/mcp；安装形态即 {app}）：
 * <pre>
 * baseDir/
 * ├── runtimes/java/       共享 jlink JRE（驱动 mcp-tap 与 Oracle toolkit）
 * ├── runtimes/node/       共享 Node（驱动 MySQL / Doris 等 NODE 实现）
 * ├── impls/               各实现入口（impls.json + &lt;dbId&gt;/&lt;serverId&gt;/）
 * ├── bak/                 实现历史备份
 * ├── tap/mcp-tap.jar      共享监听代理
 * ├── state.json
 * └── &lt;dbId&gt;/instance/&lt;env&gt;/&lt;mcpServer&gt;/config.*
 * </pre>
 *
 * <p>v0.2 旧布局（runtime/、&lt;dbId&gt;/toolkit/、&lt;dbId&gt;/runtime/node）由 {@link Migrator}
 * 在首次启动时一次性迁到上述新位置，迁移完成写入 state.migratedToV3 标记。
 */
public final class Installer {

    /** v0.3 共享运行时根目录名（runtimes/java、runtimes/node）。 */
    public static final String RUNTIMES_DIR = "runtimes";
    /** v0.3 实现根目录名（impls/&lt;dbId&gt;/&lt;serverId&gt;/）。 */
    public static final String IMPLS_DIR = "impls";
    /** v0.2 旧共享运行时目录名；仅用于迁移检测与兼容回退。 */
    public static final String LEGACY_RUNTIME_DIR_NAME = "runtime";
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

    /** 释放共享 jlink 运行时到 baseDir/runtimes/java；已存在则跳过。 */
    public static void deployRuntime(Path baseDir) throws IOException {
        if (runtimeJava(baseDir) != null) {
            return;
        }
        Path target = baseDir.resolve(RUNTIMES_DIR).resolve("java");
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

    /**
     * 释放某库内置实现到 baseDir/impls/&lt;dbId&gt;/&lt;serverId&gt;/——每个 McpServerOption 一份自己的资源。
     *
     * <p>内容戳 {@link #BUILTIN_STAMP_FILE} 记录"资源路径 + 安装包指纹"：一致则跳过（避免重复解压
     * 数千个 node_modules 文件），不一致则整体重解压。旧版把同一个 toolkit 解压进所有实现目录
     * （naganpm 目录里装的其实是 benborla29），戳机制同时修复这类历史错装。
     * 入口 shim 体积小且承载随版本发布的修复，每次部署都覆盖（见 {@link #overlayEntryShim}）。
     */
    public static void deployToolkit(Path baseDir, String dbId, DbAdapter adapter) throws IOException {
        ImplRegistry reg = ImplRegistry.load(baseDir);
        for (McpServerOption opt : adapter.mcpServerOptions()) {
            ImplInfo info = reg.get(dbId, opt.id());
            if (info != null && !"builtin".equals(info.source)) {
                // 用户自行安装的实现（URL/GitHub 升级）不被内置资源覆盖
                continue;
            }
            String res = adapter.toolkitResourceFor(opt);
            Path dest = ImplRegistry.implDir(baseDir, dbId, opt.id());
            Path stampFile = dest.resolve(BUILTIN_STAMP_FILE);
            String stamp = res + "@" + packageStamp();
            if (!stamp.equals(readStamp(stampFile))) {
                if (resourceExists(res)) {
                    ImplRegistry.deleteTree(dest);
                    extractOrCopy(null, res, dest);
                    Files.createDirectories(dest);
                    Files.writeString(stampFile, stamp, StandardCharsets.UTF_8);
                } else if (!isDeployed(dest)) {
                    throw new IOException("缺少内置资源 " + res + "（或 " + res + "/），且未通过系统属性指定外部文件");
                } else {
                    System.out.println("[WARN] 缺少内置资源 " + res + "，保留现有实现目录 " + dest);
                }
            }
            overlayEntryShim(res, dest);
        }
    }

    /** 内置实现目录的内容戳文件名（值为 {@code <资源路径>@<安装包指纹>}）。 */
    public static final String BUILTIN_STAMP_FILE = ".dbmcp-builtin";

    /** 安装包指纹：本 jar（dev 形态为 classes 目录）的最后修改时间，随每次构建/覆盖变化。 */
    private static String packageStamp() {
        try {
            java.security.CodeSource src = Installer.class.getProtectionDomain().getCodeSource();
            if (src != null && src.getLocation() != null) {
                Path p = Path.of(src.getLocation().toURI());
                if (Files.exists(p)) {
                    return String.valueOf(Files.getLastModifiedTime(p).toMillis());
                }
            }
        } catch (Exception ignored) {
        }
        return "dev";
    }

    private static String readStamp(Path stampFile) {
        try {
            return Files.isRegularFile(stampFile) ? Files.readString(stampFile, StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** 资源是否存在（目录型或单文件型）。删除旧目录前的前置校验，避免资源缺失时误删。 */
    private static boolean resourceExists(String resourcePath) {
        ClassLoader cl = Installer.class.getClassLoader();
        String dirRes = resourcePath.endsWith("/") ? resourcePath : resourcePath + "/";
        return cl.getResource(dirRes) != null || cl.getResource(resourcePath) != null;
    }

    /**
     * 入口 shim（{@code <资源>/build/index.js}）每次部署都覆盖：它体积小，却承载必须随版本落地的修复
     * （如 Doris 的 MySQL 协议兼容补丁）。资源里没有该文件（Java JAR 型 toolkit）时静默跳过。
     */
    private static void overlayEntryShim(String resourceDir, Path dest) throws IOException {
        String shim = resourceDir.endsWith("/") ? resourceDir + "build/index.js" : resourceDir + "/build/index.js";
        try (InputStream in = Installer.class.getClassLoader().getResourceAsStream(shim)) {
            if (in == null) {
                return;
            }
            Path out = dest.resolve("build").resolve("index.js");
            Files.createDirectories(out.getParent());
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 目标是否已就绪：非空普通文件，或非空目录（目录型 toolkit 如 mysql 的 node_modules）。 */
    public static boolean isDeployed(Path target) {
        if (Files.isRegularFile(target)) {
            try {
                return Files.size(target) > 0;
            } catch (IOException e) {
                return false;
            }
        }
        if (Files.isDirectory(target)) {
            try (java.util.stream.Stream<Path> s = Files.list(target)) {
                return s.findFirst().isPresent();
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    /** 释放某库服务端运行时（如 mysql 的 node）到 baseDir/runtimes/node/（v0.3 共享）。 */
    public static void deployDbRuntime(Path baseDir, String dbId, DbAdapter adapter) throws IOException {
        Path dest = baseDir.resolve(RUNTIMES_DIR).resolve("node");
        if (Files.isRegularFile(dest) || Files.isRegularFile(dest.resolve("node.exe")) || Files.isRegularFile(dest.resolve("node"))) {
            return;
        }
        String res = "runtime/" + adapter.runtimeSourceDbId() + "/node";
        extractOrCopy(null, res, dest);
    }

    // ---- 路径解析 ----

    public static Path dbDir(Path baseDir, String dbId) {
        return baseDir.resolve(dbId);
    }

    public static Path toolkitPath(Path baseDir, String dbId, DbAdapter adapter) {
        String serverId = adapter.mcpServerOptions().isEmpty() ? "default" : adapter.mcpServerOptions().get(0).id();
        return ImplRegistry.implDir(baseDir, dbId, serverId);
    }

    public static Path envDir(Path baseDir, String dbId, String env) {
        return dbDir(baseDir, dbId).resolve("instance").resolve(env);
    }

    /**
     * 每实现（provider）专属连接配置文件，位于 instance/&lt;env&gt;/&lt;mcpServer&gt;/ 下。
     * 文件名直接沿用适配器声明（Oracle → config.yaml，MySQL → .env）。
     */
    public static Path connectionFile(Path baseDir, String dbId, String env, String mcpServer, DbAdapter adapter) {
        return providerDir(baseDir, dbId, env, mcpServer).resolve(adapter.configFileName());
    }

    /** 兼容别名：Oracle -DconfigFile 指向该实现专属连接文件。 */
    public static Path configFile(Path baseDir, String dbId, String env, String mcpServer, DbAdapter adapter) {
        return connectionFile(baseDir, dbId, env, mcpServer, adapter);
    }

    /** 某实现（provider）专属目录：instance/<env>/<mcpServer>/（仅存该实现的 calllog）。 */
    public static Path providerDir(Path baseDir, String dbId, String env, String mcpServer) {
        return envDir(baseDir, dbId, env).resolve(mcpServer);
    }

    public static Path callLog(Path baseDir, String dbId, String env, String mcpServer) {
        return providerDir(baseDir, dbId, env, mcpServer).resolve("calllog.jsonl");
    }

    /**
     * 一次性磁盘布局迁移：旧版把 calllog.jsonl / 连接配置（config.yaml、.env）直接放在
     * instance/&lt;env&gt;/ 下（假定单实现），方案 B 后应落到 instance/&lt;env&gt;/&lt;mcpServer&gt;/ 内。
     * 幂等：源文件不存在或目标已存在则跳过；历史日志与配置整体归属该连接的第一个实现。
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
            DbAdapter adapter = DbAdapters.get(dbId);
            if (adapter == null) {
                continue;
            }
            Path legacyDir = envDir(baseDir, dbId, env);
            String first = info.providers.keySet().iterator().next();
            moveLegacy(legacyDir.resolve("calllog.jsonl"), callLog(baseDir, dbId, env, first));
            String cfgName = adapter.configFileName();
            moveLegacy(legacyDir.resolve(cfgName), providerDir(baseDir, dbId, env, first).resolve(cfgName));
        }
    }

    /** 旧位置 → 新位置；目标已存在则视为已迁移，仅清理源残留。 */
    private static void moveLegacy(Path legacy, Path target) {
        if (!Files.isRegularFile(legacy)) {
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            if (Files.isRegularFile(target)) {
                Files.deleteIfExists(legacy);
                return;
            }
            try {
                Files.move(legacy, target);
            } catch (IOException lockOrCrossDevice) {
                // 运行中的 tap 进程可能持有句柄：退化为复制，内容不丢
                Files.copy(legacy, target, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.deleteIfExists(legacy);
                } catch (IOException ignored2) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    /** 共享运行时 java 可执行文件；优先检查 v0.3 路径 runtimes/java/，回退 v0.2 路径 runtime/（迁移前兼容）。 */
    public static Path runtimeJava(Path baseDir) {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        String exeName = win ? "java.exe" : "java";
        Path exe = baseDir.resolve(RUNTIMES_DIR).resolve("java").resolve("bin").resolve(exeName);
        if (Files.isRegularFile(exe)) return exe;
        Path legacy = baseDir.resolve(LEGACY_RUNTIME_DIR_NAME).resolve("bin").resolve(exeName);
        return Files.isRegularFile(legacy) ? legacy : null;
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

    /** 生成该实现专属连接配置 + 确保该实现目录存在。密码仅本地落盘一份；adapter 决定文件名与内容。 */
    public static void writeEnvConfig(Path baseDir, String dbId, String env, String mcpServer, DbAdapter adapter,
                                      String url, String user, String password) throws IOException {
        Path dir = providerDir(baseDir, dbId, env, mcpServer);
        Files.createDirectories(dir);
        String content = adapter.renderConfig(env, url, user, password);
        Path file = connectionFile(baseDir, dbId, env, mcpServer, adapter);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        chmod600IfPosix(file);
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

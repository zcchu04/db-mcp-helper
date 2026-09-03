package com.dbmcp.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * v0.2 → v0.3 目录布局迁移：
 *
 * <pre>
 * v0.2:
 *   baseDir/runtime/              → 共享 JRE
 *   baseDir/&lt;dbId&gt;/toolkit/&lt;file&gt; → 实现入口
 *   baseDir/&lt;dbId&gt;/runtime/node   → 数据库服务端 Node 运行时
 *
 * v0.3:
 *   baseDir/runtimes/java/        → 共享 JRE
 *   baseDir/runtimes/node/        → 共享 Node
 *   baseDir/impls/&lt;dbId&gt;/&lt;serverId&gt;/ → 实现入口
 *   baseDir/impls/impls.json      → 实现元数据
 *   baseDir/bak/                  → 实现历史备份
 * </pre>
 *
 * <p>迁移幂等：通过 state.json 的 {@code migratedToV3} 标记 + 目标路径存在性双重判断。
 * 物理文件复制完成后才写入标记，中途失败重启会重试（目标已存在则跳过）。
 */
public final class Migrator {

    private Migrator() {
    }

    /**
     * 入口：按需执行 v0.2 → v0.3 迁移。无 state.json（全新安装）或已迁移则直接返回。
     */
    public static void migrateV3(Path baseDir) throws IOException {
        if (!Files.isDirectory(baseDir)) {
            return;
        }
        State st = State.load(baseDir);
        if (st == null) {
            return;
        }
        if (st.migratedToV3) {
            return;
        }
        if (!hasLegacyLayout(baseDir)) {
            st.migratedToV3 = true;
            st.save(baseDir);
            return;
        }

        migrateJavaRuntime(baseDir);
        migrateNodeRuntime(baseDir);
        migrateImpls(baseDir);

        st.migratedToV3 = true;
        st.save(baseDir);
    }

    /** 任一旧布局路径存在即需迁移。 */
    private static boolean hasLegacyLayout(Path baseDir) {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path legacyJava = baseDir.resolve(Installer.LEGACY_RUNTIME_DIR_NAME)
                .resolve("bin").resolve(win ? "java.exe" : "java");
        if (Files.isRegularFile(legacyJava)) {
            return true;
        }
        for (DbAdapter a : DbAdapters.all()) {
            Path legacyToolkit = Installer.dbDir(baseDir, a.id()).resolve("toolkit").resolve(a.toolkitFileName());
            if (Installer.isDeployed(legacyToolkit)) {
                return true;
            }
            Path legacyNode = Installer.dbDir(baseDir, a.id()).resolve(Installer.LEGACY_RUNTIME_DIR_NAME).resolve("node");
            if (Installer.isDeployed(legacyNode)) {
                return true;
            }
        }
        return false;
    }

    /** baseDir/runtime/ → baseDir/runtimes/java/。 */
    private static void migrateJavaRuntime(Path baseDir) throws IOException {
        Path legacy = baseDir.resolve(Installer.LEGACY_RUNTIME_DIR_NAME);
        Path target = baseDir.resolve(Installer.RUNTIMES_DIR).resolve("java");
        if (!Files.isDirectory(legacy)) {
            return;
        }
        if (Files.isDirectory(target) && Installer.isDeployed(target)) {
            return;
        }
        Files.createDirectories(target.getParent());
        copyTree(legacy, target);
    }

    /** 各 baseDir/<dbId>/runtime/node → baseDir/runtimes/node/（共享；首个存在的适配器写入即可）。 */
    private static void migrateNodeRuntime(Path baseDir) throws IOException {
        Path target = baseDir.resolve(Installer.RUNTIMES_DIR).resolve("node");
        if (Files.isDirectory(target) && Installer.isDeployed(target)) {
            return;
        }
        for (DbAdapter a : DbAdapters.all()) {
            if (a.runtimeKind() != DbAdapter.RuntimeKind.NODE) {
                continue;
            }
            Path legacy = Installer.dbDir(baseDir, a.id()).resolve(Installer.LEGACY_RUNTIME_DIR_NAME).resolve("node");
            if (Installer.isDeployed(legacy)) {
                Files.createDirectories(target.getParent());
                copyTree(legacy, target);
                return;
            }
        }
    }

    /** 各 baseDir/<dbId>/toolkit/<file> → baseDir/impls/<dbId>/<serverId>/。 */
    private static void migrateImpls(Path baseDir) throws IOException {
        for (DbAdapter adapter : DbAdapters.all()) {
            String dbId = adapter.id();
            for (McpServerOption opt : adapter.mcpServerOptions()) {
                String serverId = opt.id();
                Path legacy = Installer.dbDir(baseDir, dbId).resolve("toolkit").resolve(adapter.toolkitFileName());
                Path target = ImplRegistry.implDir(baseDir, dbId, serverId);
                if (!Installer.isDeployed(legacy)) {
                    continue;
                }
                if (Files.isDirectory(target) && Installer.isDeployed(target)) {
                    continue;
                }
                Files.createDirectories(target);
                copyTree(legacy, target);
            }
            for (String extra : adapter.extraToolkitDirResources()) {
                String name = extra.substring(extra.lastIndexOf('/') + 1);
                Path legacyExtra = Installer.dbDir(baseDir, dbId).resolve("toolkit").resolve(name);
                if (!Installer.isDeployed(legacyExtra)) {
                    continue;
                }
                String primary = adapter.mcpServerOptions().isEmpty() ? "default" : adapter.mcpServerOptions().get(0).id();
                Path targetExtra = ImplRegistry.implDir(baseDir, dbId, primary).resolve(name);
                if (Installer.isDeployed(targetExtra)) {
                    continue;
                }
                Files.createDirectories(targetExtra.getParent());
                copyTree(legacyExtra, targetExtra);
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
                    Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}

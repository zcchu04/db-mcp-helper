package com.dbmcp.setup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务实现注册中心：管理 {@code impls.json} 元数据，提供 bak 备份与恢复。
 *
 * <p>磁盘布局（v0.3 新增）：
 * <pre>
 * baseDir/
 * ├── impls/impls.json          ← 本类管理的元数据
 * ├── bak/&lt;dbId&gt;/&lt;serverId&gt;/&lt;version&gt;_&lt;timestamp&gt;/  ← 历史版本备份
 * ...
 * </pre>
 *
 * <p>首次启动时从现有 toolkit 文件推断并生成 impls.json，使老用户无感升级。
 */
public final class ImplRegistry {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String IMPLS_DIR = "impls";
    private static final String IMPLS_FILE = "impls.json";
    static final String BAK_DIR = "bak";
    private static final int DEFAULT_KEEP_BAK = 3;

    private final Path baseDir;
    /** dbId → (serverId → ImplInfo)。 */
    private Map<String, Map<String, ImplInfo>> impls = new LinkedHashMap<>();

    public ImplRegistry(Path baseDir) {
        this.baseDir = baseDir;
    }

    // ---- 路径 ----

    static Path implsFile(Path baseDir) {
        return baseDir.resolve(IMPLS_DIR).resolve(IMPLS_FILE);
    }

    /** 某实现的当前安装目录：v0.3 统一落到 baseDir/impls/&lt;dbId&gt;/&lt;serverId>/。 */
    public static Path implDir(Path baseDir, String dbId, String serverId) {
        return baseDir.resolve(Installer.IMPLS_DIR).resolve(dbId).resolve(serverId);
    }

    /** bak 根目录。 */
    public static Path bakRoot(Path baseDir) {
        return baseDir.resolve(BAK_DIR);
    }

    // ---- load / save ----

    /** 加载 impls.json；不存在返回空注册表（不报错）。 */
    public static ImplRegistry load(Path baseDir) {
        ImplRegistry reg = new ImplRegistry(baseDir);
        Path f = implsFile(baseDir);
        if (!Files.isRegularFile(f)) {
            return reg;
        }
        try {
            String raw = Files.readString(f, StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(raw, JsonObject.class);
            if (root == null) {
                return reg;
            }
            for (var dbEntry : root.entrySet()) {
                String dbId = dbEntry.getKey();
                JsonObject dbObj = dbEntry.getValue().isJsonObject() ? dbEntry.getValue().getAsJsonObject() : null;
                if (dbObj == null) continue;
                Map<String, ImplInfo> dbMap = new LinkedHashMap<>();
                for (var srvEntry : dbObj.entrySet()) {
                    String serverId = srvEntry.getKey();
                    ImplInfo info = GSON.fromJson(srvEntry.getValue(), ImplInfo.class);
                    if (info != null) {
                        dbMap.put(serverId, info);
                    }
                }
                if (!dbMap.isEmpty()) {
                    reg.impls.put(dbId, dbMap);
                }
            }
        } catch (Exception e) {
            // 损坏的 impls.json 不阻断启动，下次 deploy 会重建
        }
        return reg;
    }

    public void save() throws IOException {
        Path f = implsFile(baseDir);
        Files.createDirectories(f.getParent());
        Files.writeString(f, GSON.toJson(impls), StandardCharsets.UTF_8);
    }

    // ---- 查询 ----

    public ImplInfo get(String dbId, String serverId) {
        Map<String, ImplInfo> dbMap = impls.get(dbId);
        return dbMap == null ? null : dbMap.get(serverId);
    }

    public Map<String, Map<String, ImplInfo>> listAll() {
        return impls;
    }

    public Map<String, ImplInfo> forDb(String dbId) {
        return impls.getOrDefault(dbId, Map.of());
    }

    // ---- 注册 / 更新 ----

    public void register(String dbId, String serverId, ImplInfo info) {
        impls.computeIfAbsent(dbId, k -> new LinkedHashMap<>()).put(serverId, info);
    }

    public void remove(String dbId, String serverId) {
        Map<String, ImplInfo> dbMap = impls.get(dbId);
        if (dbMap != null) {
            dbMap.remove(serverId);
            if (dbMap.isEmpty()) {
                impls.remove(dbId);
            }
        }
    }

    // ---- 首次启动推断 ----

    /**
     * 从磁盘现有 toolkit 文件推断 impls.json。仅在 impls.json 不存在或某 (dbId, serverId) 缺记录时补充。
     * 遍历所有已注册适配器及其 mcpServerOptions，检查 toolkit 是否已部署。
     */
    public void inferFromDisk() {
        for (DbAdapter adapter : DbAdapters.all()) {
            String dbId = adapter.id();
            for (McpServerOption opt : adapter.mcpServerOptions()) {
                String serverId = opt.id();
                if (get(dbId, serverId) != null) {
                    continue;
                }
                Path dir = implDir(baseDir, dbId, serverId);
                if (!Installer.isDeployed(dir)) {
                    continue;
                }
                ImplInfo info = new ImplInfo();
                info.version = "builtin";
                info.source = "builtin";
                info.installedAt = Instant.now().toString();
                info.entryFile = resolveEntryFile(adapter, serverId);
                info.runtimeKind = adapter.runtimeKind().name();
                register(dbId, serverId, info);
            }
        }
    }

    /** 推断入口文件名。 */
    private static String resolveEntryFile(DbAdapter adapter, String serverId) {
        if (adapter.runtimeKind() == DbAdapter.RuntimeKind.JAVA_JAR) {
            return adapter.toolkitFileName();
        }
        // NODE 实现：按 serverId 推断
        if (adapter instanceof MySqlAdapter && MySqlAdapter.IMPL_NAGA.equals(serverId)) {
            return "dist/index.js";
        }
        return "build/index.js";
    }

    // ---- bak 备份与恢复 ----

    /**
     * 备份当前实现到 {@code bak/<dbId>/<serverId>/<version>_<timestamp>/}。
     * 返回 bak 路径；当前实现不存在则返回 null。
     */
    public Path bakImpl(String dbId, String serverId) throws IOException {
        ImplInfo current = get(dbId, serverId);
        Path dir = implDir(baseDir, dbId, serverId);
        if (!Installer.isDeployed(dir)) {
            return null;
        }
        String version = current != null ? current.version : "unknown";
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(Instant.now().atZone(java.time.ZoneId.systemDefault()));
        String bakName = version + "_" + ts;
        Path bakPath = bakRoot(baseDir).resolve(dbId).resolve(serverId).resolve(bakName);
        copyTree(dir, bakPath);

        ImplInfo.BakVersion bv = new ImplInfo.BakVersion(version, bakPath.toString(), Instant.now().toString());
        if (current != null) {
            current.bakVersions.add(bv);
            pruneBak(dbId, serverId, DEFAULT_KEEP_BAK);
            save();
        }
        return bakPath;
    }

    /**
     * 从最近一个 bak 版本恢复。恢复后删除该 bak 条目（已回到那个版本）。
     * 无可用 bak 返回 false。
     */
    public boolean restoreBak(String dbId, String serverId) throws IOException {
        ImplInfo current = get(dbId, serverId);
        if (current == null || current.bakVersions.isEmpty()) {
            return false;
        }
        ImplInfo.BakVersion last = current.bakVersions.remove(current.bakVersions.size() - 1);
        Path bakPath = Path.of(last.bakPath);
        if (!Files.isDirectory(bakPath)) {
            save();
            return false;
        }
        Path dir = implDir(baseDir, dbId, serverId);
        deleteTree(dir);
        copyTree(bakPath, dir);
        current.version = last.version;
        current.source = "restored";
        current.installedAt = Instant.now().toString();
        save();
        return true;
    }

    /** 保留最近 keepCount 个 bak 版本，删除更早的。 */
    public void pruneBak(String dbId, String serverId, int keepCount) {
        ImplInfo current = get(dbId, serverId);
        if (current == null || current.bakVersions.size() <= keepCount) {
            return;
        }
        List<ImplInfo.BakVersion> bvs = current.bakVersions;
        while (bvs.size() > keepCount) {
            ImplInfo.BakVersion old = bvs.remove(0);
            try {
                deleteTree(Path.of(old.bakPath));
            } catch (IOException ignored) {
            }
        }
    }

    /** 列出某实现的所有可回滚 bak 版本。 */
    public List<ImplInfo.BakVersion> listBakVersions(String dbId, String serverId) {
        ImplInfo info = get(dbId, serverId);
        return info == null ? List.of() : List.copyOf(info.bakVersions);
    }

    // ---- 文件操作 ----

    static void copyTree(Path src, Path dst) throws IOException {
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

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}

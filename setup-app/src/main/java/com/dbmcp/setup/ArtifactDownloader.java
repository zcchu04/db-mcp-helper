package com.dbmcp.setup;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * GitHub Release 产物下载器：查询最新 Release、列出产物、流式下载、SHA-256 校验、从 URL 安装实现。
 */
public final class ArtifactDownloader {

    private static final String GITHUB_REPO = "your-org/db-mcp-helper";
    private static final String GITHUB_API = "https://api.github.com/repos/" + GITHUB_REPO;
    private static final Gson GSON = new Gson();

    private ArtifactDownloader() {}

    // ---- 数据模型 ----

    public record ReleaseInfo(String tagName, String name, String publishedAt, List<ArtifactEntry> artifacts) {}

    public record ArtifactEntry(String name, String type, String platform, long size, String downloadUrl, String sha256) {}

    /** 下载进度快照（供轮询）。 */
    public static final class DownloadProgress {
        public volatile long downloaded;
        public volatile long total;
        public volatile boolean done;
        public volatile String error;
    }

    // ---- GitHub Release 查询 ----

    public static ReleaseInfo fetchLatestRelease() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_API + "/releases/latest"))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "DB-MCP-Helper")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("GitHub API 返回 " + resp.statusCode() + ": " + resp.body());
        }
        JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
        String tag = json.has("tag_name") ? json.get("tag_name").getAsString() : "unknown";
        String name = json.has("name") ? json.get("name").getAsString() : "";
        String pub = json.has("published_at") ? json.get("published_at").getAsString() : "";
        List<ArtifactEntry> artifacts = new ArrayList<>();
        JsonArray assets = json.has("assets") ? json.getAsJsonArray("assets") : new JsonArray();
        for (JsonElement el : assets) {
            JsonObject a = el.getAsJsonObject();
            String assetName = a.get("name").getAsString();
            long size = a.get("size").getAsLong();
            String url = a.get("browser_download_url").getAsString();
            artifacts.add(new ArtifactEntry(assetName, classifyAsset(assetName), detectPlatform(assetName), size, url, ""));
        }
        return new ReleaseInfo(tag, name, pub, artifacts);
    }

    /** 按文件名推断产物类型。 */
    static String classifyAsset(String name) {
        String n = name.toLowerCase();
        if (n.contains("runtime-jre") || n.contains("runtime/jre") || n.matches(".*jre.*\\.zip")) return "runtime-jre";
        if (n.contains("runtime-node") || n.contains("runtime/node") || n.matches(".*node.*\\.zip")) return "runtime-node";
        if (n.contains("full")) return "full";
        if (n.contains("slim")) return "slim";
        if (n.contains("impl-") || n.contains("toolkit")) return "impl";
        if (n.contains("helper") || n.endsWith(".exe") || n.endsWith(".msi")) return "helper";
        return "other";
    }

    /** 从文件名推断平台。 */
    static String detectPlatform(String name) {
        String n = name.toLowerCase();
        if (n.contains("win") || n.contains("windows")) return "win-x64";
        if (n.contains("mac") || n.contains("darwin")) {
            return n.contains("arm") || n.contains("aarch") ? "mac-arm64" : "mac-x64";
        }
        if (n.contains("linux")) {
            return n.contains("arm") || n.contains("aarch") ? "linux-arm64" : "linux-x64";
        }
        return "any";
    }

    // ---- 下载 ----

    /** 流式下载产物到临时文件。 */
    public static Path download(ArtifactEntry entry, DownloadProgress progress) throws IOException, InterruptedException {
        Path tempFile = Files.createTempFile("mcp-dl-", "-" + entry.name());
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(entry.downloadUrl()))
                .header("User-Agent", "DB-MCP-Helper")
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            Files.deleteIfExists(tempFile);
            throw new IOException("下载失败，HTTP " + resp.statusCode());
        }
        long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (progress != null) progress.total = total;
        try (InputStream in = resp.body();
             var out = Files.newOutputStream(tempFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (progress != null) progress.downloaded += n;
            }
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
        if (progress != null) progress.done = true;
        return tempFile;
    }

    /** 从任意 URL 下载文件到临时路径。 */
    public static Path downloadFromUrl(String url, DownloadProgress progress) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "DB-MCP-Helper")
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("下载失败，HTTP " + resp.statusCode());
        }
        Path tempFile = Files.createTempFile("mcp-dl-url-", ".zip");
        long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (progress != null) progress.total = total;
        try (InputStream in = resp.body();
             var out = Files.newOutputStream(tempFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (progress != null) progress.downloaded += n;
            }
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
        if (progress != null) progress.done = true;
        return tempFile;
    }

    // ---- SHA-256 校验 ----

    public static boolean verify(Path file, String expectedSha256) throws IOException {
        if (expectedSha256 == null || expectedSha256.isBlank()) return true;
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        String actual = hexEncode(md.digest());
        return actual.equalsIgnoreCase(expectedSha256);
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ---- 从 URL 安装实现 ----

    /**
     * 从 URL 下载 zip → bak 旧版本 → 解压 → 注册。
     * 与 {@code SetupMain.implsUpload} 流程一致，区别在于来源是 URL 而非 base64。
     */
    public static JsonObject installFromUrl(String dbId, String serverId, String url, String version, Path root) throws Exception {
        DbAdapter adapter = DbAdapters.require(dbId);
        ImplRegistry reg = ImplRegistry.load(root);
        Path dir = ImplRegistry.implDir(root, dbId, serverId);

        DownloadProgress progress = new DownloadProgress();
        Path tempFile;
        try {
            tempFile = downloadFromUrl(url, progress);
        } catch (Exception e) {
            throw new IOException("下载失败: " + e.getMessage(), e);
        }

        Path tmpDir = Files.createTempDirectory("impl-install-");
        try {
            byte[] zipBytes = Files.readAllBytes(tempFile);

            reg.bakImpl(dbId, serverId);
            extractZip(zipBytes, tmpDir);

            ImplRegistry.deleteTree(dir);
            Files.createDirectories(dir.getParent());

            try (var stream = Files.list(tmpDir)) {
                List<Path> entries = stream.toList();
                if (entries.size() == 1 && Files.isRegularFile(entries.get(0))) {
                    Files.copy(entries.get(0), dir, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    ImplRegistry.copyTree(tmpDir, dir);
                }
            }

            if (version == null || version.isBlank()) {
                version = "from-url";
            }

            ImplInfo info = reg.get(dbId, serverId);
            if (info == null) info = new ImplInfo();
            info.version = version;
            info.source = "github";
            info.sourceUrl = url;
            info.installedAt = Instant.now().toString();
            info.runtimeKind = adapter.runtimeKind().name();
            info.entryFile = resolveEntryFileForInstall(adapter, dir);
            reg.register(dbId, serverId, info);
            reg.save();

            JsonObject d = new JsonObject();
            d.addProperty("dbId", dbId);
            d.addProperty("serverId", serverId);
            d.addProperty("version", version);
            d.addProperty("source", "github");
            return d;
        } finally {
            ImplRegistry.deleteTree(tmpDir);
            Files.deleteIfExists(tempFile);
        }
    }

    private static String resolveEntryFileForInstall(DbAdapter adapter, Path dir) {
        if (Files.isRegularFile(dir)) return dir.getFileName().toString();
        if (Files.isDirectory(dir)) {
            Path manifest = dir.resolve("manifest.json");
            if (Files.isRegularFile(manifest)) {
                try {
                    JsonObject m = GSON.fromJson(Files.readString(manifest), JsonObject.class);
                    if (m != null && m.has("entryFile")) return m.get("entryFile").getAsString();
                } catch (Exception ignored) {}
            }
        }
        if (adapter.runtimeKind() == DbAdapter.RuntimeKind.JAVA_JAR) {
            return adapter.toolkitFileName();
        }
        if (Files.isDirectory(dir)) {
            if (Files.isRegularFile(dir.resolve("build/index.js"))) return "build/index.js";
            if (Files.isRegularFile(dir.resolve("dist/index.js"))) return "dist/index.js";
            if (Files.isRegularFile(dir.resolve("index.js"))) return "index.js";
        }
        return adapter.toolkitFileName();
    }

    private static void extractZip(byte[] zipBytes, Path destDir) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Path target = destDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(destDir)) {
                    throw new IOException("zip 条目路径非法：" + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zin, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zin.closeEntry();
            }
        }
    }
}

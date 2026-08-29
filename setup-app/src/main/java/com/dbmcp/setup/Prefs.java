package com.dbmcp.setup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 首选项持久化：主题、侧栏折叠、最近访问实例、setup 完成标记等，
 * 落到 {@code <defaultRoot>/prefs.json}（不受 reset 影响，除非用户显式清空）。
 *
 * <p>字段（全部可选，缺失即默认）：
 * <pre>
 * {
 *   "theme": "light" | "dark" | "system",   // 默认 "system"
 *   "sidebarCollapsed": false,               // 默认 false
 *   "lastEnv": "oracle/uat",                 // 默认 null（{dbId}/{env} 复合 key）
 *   "setupCompleted": false                  // 首次向导完成标记，默认 false
 * }
 * </pre>
 */
public final class Prefs {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    public static final String FILE_NAME = "prefs.json";

    private Prefs() {
    }

    public static Path prefsFile() {
        return Cfg.defaultRoot().resolve(FILE_NAME);
    }

    /** 读取 prefs；文件不存在或解析失败返回一份默认对象（不抛异常）。 */
    public static synchronized JsonObject load() {
        JsonObject def = defaults();
        Path f = prefsFile();
        if (!Files.isRegularFile(f)) {
            return def;
        }
        try {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            JsonObject parsed = GSON.fromJson(text, JsonObject.class);
            if (parsed == null) return def;
            // merge: 已有字段覆盖默认
            for (String k : parsed.keySet()) {
                def.add(k, parsed.get(k));
            }
            return def;
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 部分更新（PATCH 语义）：body 里给了什么字段就覆盖什么，未给的字段保持原值。
     * 返回合并后的完整对象。
     */
    public static synchronized JsonObject merge(JsonObject body) throws IOException {
        JsonObject cur = load();
        if (body != null) {
            for (String k : body.keySet()) {
                cur.add(k, body.get(k));
            }
        }
        save(cur);
        return cur;
    }

    /** 全量覆盖写入。 */
    public static synchronized void save(JsonObject data) throws IOException {
        Path f = prefsFile();
        Files.createDirectories(f.getParent());
        Files.writeString(f, GSON.toJson(data), StandardCharsets.UTF_8);
    }

    private static JsonObject defaults() {
        JsonObject o = new JsonObject();
        o.addProperty("theme", "system");
        o.addProperty("sidebarCollapsed", false);
        o.addProperty("setupCompleted", false);
        return o;
    }
}

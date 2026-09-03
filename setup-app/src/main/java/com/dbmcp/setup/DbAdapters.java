package com.dbmcp.setup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库适配器注册表。新增数据库类型只需在此注册一个实现，
 * 引擎其余部分（向导、注册、自检、Skill）自动适配。
 */
public final class DbAdapters {

    private static final Map<String, DbAdapter> MAP = new LinkedHashMap<>();

    static {
        register(new OracleAdapter());
        register(new MySqlAdapter());
        register(new DorisAdapter());
    }

    private DbAdapters() {
    }

    public static void register(DbAdapter adapter) {
        MAP.put(adapter.id(), adapter);
    }

    public static DbAdapter get(String id) {
        return MAP.get(id);
    }

    public static DbAdapter require(String id) {
        DbAdapter a = MAP.get(id);
        if (a == null) {
            throw new IllegalArgumentException("不支持的数据库类型：" + id);
        }
        return a;
    }

    public static List<DbAdapter> all() {
        return new ArrayList<>(MAP.values());
    }
}

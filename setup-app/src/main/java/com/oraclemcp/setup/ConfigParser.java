package com.oraclemcp.setup;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 宽松连接配置解析器：从任意文本片段（Spring YAML、properties 等）中只提取期望的键值，忽略其余内容。
 *
 * <p>兼容键名（大小写不敏感，支持 spring.datasource.xxx 这类带前缀的点分键）：
 * url / jdbc-url / jdbcUrl / jdbc_url；username / user / user-name / user_name；password / pwd。
 * 值支持单双引号包裹，行内注释（引号外的 #）自动剥离。
 */
public final class ConfigParser {

    /** key: value 或 key=value 行。 */
    private static final Pattern LINE = Pattern.compile("^\\s*([A-Za-z][\\w.-]*)\\s*[:=]\\s*(.*?)\\s*$");
    /** 简单形式 JDBC URL：jdbc:oracle:thin:@[//]host:port(/|:)service，@ 后斜杠 0-2 个均兼容。 */
    private static final Pattern SIMPLE_URL = Pattern.compile("^jdbc:oracle:thin:@/{0,2}([^:/?]+):(\\d+)[/:]([^?;\\s]+).*$");

    private ConfigParser() {
    }

    /**
     * 从文本中提取 url / username / password。
     *
     * @param text 任意配置片段，可为 null
     * @return 命中的键值（最多三个键），未命中返回空 Map
     */
    public static Map<String, String> extract(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.split("\\r?\\n")) {
            String line = raw;
            int hash = indexOfComment(line);
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String key = m.group(1).toLowerCase();
            int dot = key.lastIndexOf('.'); // 支持 spring.datasource.url 这类点分键，取末段匹配
            if (dot >= 0) {
                key = key.substring(dot + 1);
            }
            String val = unquote(m.group(2).trim());
            if (val.isEmpty()) {
                continue;
            }
            switch (key) {
                case "url":
                case "jdbc-url":
                case "jdbcurl":
                case "jdbc_url":
                    out.putIfAbsent("url", val);
                    break;
                case "username":
                case "user":
                case "user-name":
                case "user_name":
                    out.putIfAbsent("username", val);
                    break;
                case "password":
                case "pwd":
                    out.putIfAbsent("password", val);
                    break;
                default:
                    // 非关注键一律忽略
            }
        }
        return out;
    }

    /**
     * 拆分简单形式 JDBC URL 为 [host, port, service]；TNS 等复杂形式返回 null（调用方整体作为 URL 使用）。
     */
    public static String[] splitSimpleUrl(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = SIMPLE_URL.matcher(url.trim());
        return m.matches() ? new String[]{m.group(1), m.group(2), m.group(3)} : null;
    }

    /** 定位引号外的行内注释起点（# 前须为空白），无注释返回 -1。 */
    private static int indexOfComment(String line) {
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble && i > 0 && Character.isWhitespace(line.charAt(i - 1))) {
                return i;
            }
        }
        return -1;
    }

    /** 剥离成对包裹引号；双引号还原 \" 与 \\，单引号还原 ''。 */
    private static String unquote(String s) {
        if (s.length() >= 2) {
            char f = s.charAt(0), l = s.charAt(s.length() - 1);
            if ((f == '"' && l == '"') || (f == '\'' && l == '\'')) {
                String inner = s.substring(1, s.length() - 1);
                return f == '"' ? inner.replace("\\\"", "\"").replace("\\\\", "\\") : inner.replace("''", "'");
            }
        }
        return s;
    }
}

package com.dbmcp.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * LM Studio：无配置文件可写，通过官方 CLI {@code lms mcp add/remove/list} 注册。
 *
 * <p>所有操作防御式包裹：若本机没有 lms（未安装 / 未启用 CLI），
 * detected() 为 false，读写操作要么安全降级（list/has 返回空），要么抛出带提示的 IOException。
 */
public class LmStudioTarget implements McpTarget {

    /** lms 可用性缓存（进程级，探测一次即可）。 */
    private static volatile Boolean lmsAvailable;

    @Override public String id() { return "lmstudio"; }
    @Override public String displayName() { return "LM Studio"; }
    @Override public String describe() { return "通过 lms mcp add 命令行注册（需已安装 lms CLI）"; }
    @Override public boolean cliBased() { return true; }
    @Override public boolean writable() { return detected(); }
    @Override public String icon() { return "S"; }

    @Override
    public String cliRegisterCommand(String serverName, JsonObject entry) {
        StringBuilder sb = new StringBuilder("lms mcp add ").append(shellQuote(serverName)).append(" -- ");
        if (entry.has("command") && !entry.get("command").isJsonNull()) {
            sb.append(shellQuote(entry.get("command").getAsString()));
        }
        JsonElement argsEl = entry.get("args");
        if (argsEl != null && argsEl.isJsonArray()) {
            for (JsonElement a : argsEl.getAsJsonArray()) {
                sb.append(' ').append(shellQuote(a.getAsString()));
            }
        }
        return sb.toString();
    }

    @Override
    public String cliUnregisterCommand(String serverName) {
        return "lms mcp remove " + shellQuote(serverName);
    }

    /** 含空格/特殊字符时用双引号包裹，让命令可直接复制到终端执行。 */
    private static String shellQuote(String v) {
        if (v == null) return "\"\"";
        if (v.matches("[A-Za-z0-9_./:\\-]+")) return v;
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override
    public List<Path> candidateConfigPaths() {
        // 不落盘，仅为满足接口；实际注册走 lms CLI
        return List.of(Paths.home("~/.lmstudio/mcp"));
    }

    @Override
    public Path detectActual() {
        return detected() ? Paths.home("~/.lmstudio/mcp") : null;
    }

    @Override
    public boolean detected() {
        Boolean v = lmsAvailable;
        if (v == null) {
            Res r = run(List.of("--version"));
            v = !r.missing;
            lmsAvailable = v;
        }
        return v;
    }

    @Override
    public boolean hasServer(Path cfg, String serverName) throws IOException {
        for (String line : listServers(cfg)) {
            if (line.equals(serverName) || line.contains("\"" + serverName + "\"")
                    || line.matches("(?s).*\\b" + java.util.regex.Pattern.quote(serverName) + "\\b.*")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> listServers(Path cfg) throws IOException {
        List<String> out = new ArrayList<>();
        Res r = run(List.of("mcp", "list"));
        if (r.missing) return out; // lms 不在 PATH：安全返回空
        // 输出格式随版本变化，尽量抽取每行中的 server 名（取第一个 token，去掉序号/符号）
        for (String raw : r.out.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String name = line.replaceAll("^[\\d\\W_]+", "").trim();
            if (!name.isEmpty()) {
                int sp = name.indexOf(' ');
                out.add(sp > 0 ? name.substring(0, sp) : name);
            }
        }
        return out;
    }

    @Override
    public void addServer(Path cfg, String serverName, JsonObject entry) throws IOException {
        List<String> args = new ArrayList<>();
        args.add("mcp");
        args.add("add");
        args.add(serverName);
        args.add("--");
        if (entry.has("command") && !entry.get("command").isJsonNull()) {
            args.add(entry.get("command").getAsString());
        }
        JsonElement argsEl = entry.get("args");
        if (argsEl != null && argsEl.isJsonArray()) {
            for (JsonElement a : argsEl.getAsJsonArray()) args.add(a.getAsString());
        }
        Res r = run(args);
        if (r.missing) {
            throw new IOException("未检测到 lms 命令，请先安装 LM Studio 并启用 lms CLI（在 LM Studio 中 Develop → Install lms CLI）");
        }
        if (r.code != 0) {
            throw new IOException("lms mcp add " + serverName + " 失败（退出码 " + r.code + "）：" + tail(r.err.isEmpty() ? r.out : r.err));
        }
    }

    @Override
    public boolean removeServer(Path cfg, String serverName) throws IOException {
        Res r = run(List.of("mcp", "remove", serverName));
        if (r.missing) {
            throw new IOException("未检测到 lms 命令，无法移除 " + serverName);
        }
        return r.code == 0;
    }

    // ---------- 进程调用 ----------

    private static final class Res {
        final int code;
        final String out;
        final String err;
        final boolean missing;

        Res(int code, String out, String err, boolean missing) {
            this.code = code;
            this.out = out;
            this.err = err;
            this.missing = missing;
        }

        static Res miss() { return new Res(-1, "", "", true); }
    }

    /** 先直接执行 lms；Windows 下若失败再退到 cmd /c lms（lms 可能是 .cmd 包装）。 */
    private Res run(List<String> args) {
        Res r = attempt(concat(List.of("lms"), args));
        if (!r.missing) return r;
        if (Paths.isWindows()) {
            r = attempt(concat(List.of("cmd", "/c", "lms"), args));
        }
        return r;
    }

    private List<String> concat(List<String> base, List<String> rest) {
        List<String> l = new ArrayList<>(base);
        l.addAll(rest);
        return l;
    }

    private Res attempt(List<String> fullCmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(fullCmd);
            Process p = pb.start();
            String out = readAll(p.getInputStream());
            String err = readAll(p.getErrorStream());
            int code = p.waitFor();
            return new Res(code, out, err, false);
        } catch (IOException e) {
            return Res.miss();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Res.miss();
        }
    }

    private String readAll(InputStream in) {
        try (ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            in.transferTo(buf);
            return buf.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private String tail(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > 500 ? t.substring(t.length() - 500) : t;
    }
}

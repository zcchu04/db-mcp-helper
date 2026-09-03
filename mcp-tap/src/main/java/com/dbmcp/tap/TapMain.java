package com.dbmcp.tap;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * MCP stdio 监听代理（tap）。
 *
 * <p>职责：在 AI 客户端与真实 MCP 服务器之间做<b>字节级透传</b>，同时异步记录原始报文日志。
 *
 * <p>设计原则：
 * <ul>
 *   <li>字节转发路径零阻塞——pump 只做 read→write，不做任何正则匹配或字符串构造。</li>
 *   <li>行分割（纯字节扫描）仍在转发前同步完成，但完整行提交到线程池异步写日志。</li>
 *   <li>无论日志线程发生什么（异常、OOM、慢盘），都不影响协议通信链路。</li>
 * </ul>
 *
 * <p>用法：{@code java -jar mcp-tap.jar --log <日志文件> -- <目标命令...>}
 */
public final class TapMain {

    private static final ExecutorService LOG_POOL = new ThreadPoolExecutor(
            1, 2, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1024),
            r -> {
                Thread t = new Thread(r, "tap-log");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    private static volatile Writer logWriter;

    private TapMain() {
    }

    public static void main(String[] args) throws Exception {
        int split = -1;
        String logFile = null;
        for (int i = 0; i < args.length; i++) {
            if ("--log".equals(args[i]) && i + 1 < args.length) {
                logFile = args[++i];
            } else if ("--".equals(args[i])) {
                split = i;
                break;
            }
        }
        if (split < 0 || split + 1 >= args.length) {
            System.err.println("[mcp-tap] usage: java -jar mcp-tap.jar [--log <file>] -- <command...>");
            System.exit(2);
        }
        if (logFile != null) {
            try {
                logWriter = new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("[mcp-tap] cannot open log file: " + e.getMessage());
            }
        }

        String[] cmd = new String[args.length - split - 1];
        System.arraycopy(args, split + 1, cmd, 0, cmd.length);
        System.err.println("[mcp-tap] child CMD: " + String.join(" ", cmd));

        Process child;
        try {
            child = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            System.err.println("[mcp-tap] child started, PID=" + child.pid());
        } catch (Exception e) {
            System.err.println("[mcp-tap] failed to start child: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(3);
            return;
        }

        Thread outPump = pump(child.getInputStream(), System.out, true, "out");
        Thread errPump = pump(child.getErrorStream(), System.err, false, "err");
        pumpBlocking(System.in, child.getOutputStream(), true, "in");

        int code = child.waitFor();
        outPump.join(3000);
        errPump.join(3000);
        LOG_POOL.shutdown();
        if (logWriter != null) {
            try {
                logWriter.close();
            } catch (IOException ignored) {
            }
        }
        System.exit(code);
    }

    private static Thread pump(InputStream in, OutputStream out, boolean log, String name) {
        Thread t = new Thread(() -> pumpBlocking(in, out, log, name), "tap-" + name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * 字节泵：纯 read→write 搬运，转发路径上零正则、零字符串分配。
     * 行分割（纯字节扫描）仅在启用日志时执行，完整行异步提交线程池。
     */
    private static void pumpBlocking(InputStream in, OutputStream out, boolean log, String name) {
        LineSplitter splitter = log && logWriter != null ? new LineSplitter(name) : null;
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
                if (splitter != null) {
                    splitter.feed(buf, 0, n);
                }
            }
            out.flush();
        } catch (IOException e) {
            // 管道关闭属正常结束路径
        } finally {
            if (out != System.out && out != System.err) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 按 \n 切行的纯字节扫描器，无正则。完整行异步提交日志线程池。 */
    private static final class LineSplitter {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final String direction;

        LineSplitter(String direction) {
            this.direction = direction;
        }

        void feed(byte[] buf, int off, int len) {
            try {
                for (int i = off; i < off + len; i++) {
                    if (buf[i] == '\n') {
                        String line = pending.toString(StandardCharsets.UTF_8).trim();
                        pending.reset();
                        if (!line.isEmpty()) {
                            submitLog(direction, line);
                        }
                    } else {
                        pending.write(buf[i]);
                    }
                }
            } catch (RuntimeException ignored) {
                pending.reset();
            }
        }
    }

    /** 将完整行异步提交日志线程池，队列满时直接丢弃，绝不阻塞转发。 */
    private static void submitLog(String direction, String line) {
        LOG_POOL.submit(() -> writeLog(direction, line));
    }

    private static synchronized void writeLog(String direction, String line) {
        if (logWriter == null) {
            return;
        }
        try {
            logWriter.write("{\"ts\":\"");
            logWriter.write(Instant.now().toString());
            logWriter.write("\",\"dir\":\"");
            logWriter.write(direction);
            logWriter.write("\",\"msg\":");
            logWriter.write(jsonString(line));
            logWriter.write("}\n");
            logWriter.flush();
        } catch (IOException ignored) {
        }
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}

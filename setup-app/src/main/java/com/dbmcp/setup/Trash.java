package com.dbmcp.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 安全删除：目录一律移入系统回收站，绝不物理删除。
 * Windows 走 PowerShell SendToRecycleBin；Linux 优先 gio；兜底移入 ~/.local/share/Trash 或安装根下的 .trash。
 */
public final class Trash {

    private Trash() {
    }

    public static String moveToTrash(Path target) throws IOException {
        if (!Files.exists(target)) {
            return "目标不存在，无需删除";
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                return windows(target);
            }
            return unix(target);
        } catch (Exception e) {
            // 兜底：移入 .trash 保留。目标本身是安装根时落点取其父目录，避免移入自身
            Path base = target.equals(Cfg.defaultRoot()) && target.getParent() != null
                    ? target.getParent() : Cfg.defaultRoot();
            Path fallback = base.resolve(".trash");
            Files.createDirectories(fallback);
            Path dest = fallback.resolve(target.getFileName() + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
            try {
                // shell 操作失败后可能有瞬时句柄残留，移动做有限重试
                IOException last = null;
                for (int i = 0; i < 3; i++) {
                    try {
                        Files.move(target, dest);
                        return "已移入 " + dest;
                    } catch (IOException mv) {
                        last = mv;
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                throw new IOException("回收站移动失败（" + e + "），兜底移动也失败：" + last, last);
            } catch (IOException outer) {
                throw outer;
            }
        }
    }

    private static String windows(Path target) throws Exception {
        String abs = target.toAbsolutePath().toString().replace("'", "''");
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                "Add-Type -AssemblyName Microsoft.VisualBasic; [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory('"
                        + abs + "', 'OnlyErrorDialogs', 'SendToRecycleBin')");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0 || Files.exists(target)) {
            throw new IOException("回收站移动失败：" + out);
        }
        return "已移入 Windows 回收站";
    }

    private static String unix(Path target) throws Exception {
        ProcessBuilder gio = new ProcessBuilder("gio", "trash", target.toString());
        gio.redirectErrorStream(true);
        Process p = gio.start();
        if (p.waitFor() == 0 && !Files.exists(target)) {
            return "已移入系统回收站（gio）";
        }
        Path trashDir = Path.of(System.getProperty("user.home"), ".local", "share", "Trash", "files");
        Files.createDirectories(trashDir);
        Path dest = trashDir.resolve(target.getFileName() + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        Files.move(target, dest);
        return "已移入 " + dest;
    }
}

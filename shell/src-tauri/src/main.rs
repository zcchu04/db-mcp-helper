#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::fs::OpenOptions;
use std::io::Write as _;
use std::net::TcpStream;
use std::path::{Path, PathBuf};
use std::process::{Child, Command};
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};

#[cfg(windows)]
use std::os::windows::process::CommandExt;

const PORT: u16 = 8765;
const JAR: &str = "db-mcp-setup.jar";
const NO_BROWSER: &str = "--no-browser";

/// Windows CreateProcess 标志：阻止子进程（java.exe 是 console 子系统）分配新控制台，
/// 消除桌面快捷方式启动时一闪而过的黑框。
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

/// 持有 Java 后端子进程句柄，让 Tauri 在 RunEvent::Exit 时可以 kill 掉它。
/// 用 `Mutex<Option<Child>>` 提供内部可变性：`Child::kill` 需要 `&mut`，
/// 但 tauri managed-state 只发共享引用。
struct Backend(Mutex<Option<Child>>);

// ─── 启动日志 ────────────────────────────────────────────────────────────
// SetupMain 失败或 WebView 起不来时，GUI 子系统的 eprintln! 输出会丢进黑洞，
// 只能靠往用户数据目录写文件才能诊断。日志文件位置：
//   Windows: %LOCALAPPDATA%\DB MCP Helper\logs\tauri-startup.log
//   macOS:   ~/Library/Application Support/DB MCP Helper/logs/tauri-startup.log
//   Linux:   $XDG_STATE_HOME\... 或 ~/.local/state/DB MCP Helper/logs/tauri-startup.log
fn log_path() -> Option<PathBuf> {
    let base: Option<PathBuf> = if cfg!(windows) {
        std::env::var_os("LOCALAPPDATA").map(PathBuf::from)
    } else if cfg!(target_os = "macos") {
        std::env::var_os("HOME").map(|h| PathBuf::from(h).join("Library/Application Support"))
    } else {
        std::env::var_os("XDG_STATE_HOME")
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local/state")))
    };
    base.map(|b| b.join("DB MCP Helper").join("logs").join("tauri-startup.log"))
}

fn log_line(msg: &str) {
    let ts = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    let line = format!("[{ts}] {msg}\n");
    if let Some(p) = log_path() {
        if let Some(dir) = p.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&p) {
            let _ = f.write_all(line.as_bytes());
        }
    }
    // 同时输出到 stderr（开发模式 tauri dev 里能看到；GUI 子系统下会丢，无所谓）
    eprint!("{line}");
}

// ─── Java 解析 ───────────────────────────────────────────────────────────
/// 该 `java` 是否是含 jdk.httpserver 的 JDK（SetupMain 依赖 com.sun.net.httpserver）。
fn has_httpserver(java: &str) -> bool {
    if let Ok(out) = Command::new(java).args(["--list-modules"]).output() {
        let s = String::from_utf8_lossy(&out.stdout);
        return s.lines().any(|l| l.starts_with("jdk.httpserver@"));
    }
    false
}

/// 该 `java` 版本 >= 17（后端 target 17）。
fn java_ge17(java: &str) -> bool {
    if let Ok(out) = Command::new(java).arg("-version").output() {
        let s = String::from_utf8_lossy(&out.stderr);
        for line in s.lines() {
            let line = line.trim();
            if line.starts_with("version") {
                if let Some(v) = line.split('"').nth(1) {
                    if let Some(major) = v.split('.').next() {
                        if let Ok(m) = major.parse::<i32>() {
                            return m >= 17;
                        }
                    }
                }
            }
        }
    }
    false
}

/// Windows 上把 `java.exe` 换成同目录的 `javaw.exe`（GUI 子系统的 Java 启动器），
/// 从源头避免黑框。找不到 javaw.exe 时原样返回（后面还有 CREATE_NO_WINDOW 兜底）。
/// 其它平台原样返回。
#[cfg(windows)]
fn prefer_javaw(java: String) -> String {
    let p = Path::new(&java);
    if let Some(parent) = p.parent() {
        let javaw = parent.join("javaw.exe");
        if javaw.exists() {
            return javaw.to_string_lossy().into_owned();
        }
    }
    java
}

#[cfg(not(windows))]
fn prefer_javaw(java: String) -> String {
    java
}

/// 解析要用的 Java 启动器，优先级：
///   1. `$JAVA_HOME/bin/java`（必须 JDK 17+ 且含 jdk.httpserver）
///   2. `PATH` 上的 `java`（同上）
///   3. 打包在安装目录里的 `bundle/runtime/bin/java[.exe]` 兜底
/// Windows 上每一档都会再尝试替换成 `javaw.exe`。
fn resolve_java(res_dir: &Path) -> Option<String> {
    if let Ok(home) = std::env::var("JAVA_HOME") {
        let p = format!("{}/bin/java", home.replace('\\', "/"));
        if Path::new(&p).exists() && has_httpserver(&p) && java_ge17(&p) {
            let picked = prefer_javaw(p.clone());
            log_line(&format!("java: JAVA_HOME hit ({} → {})", p, picked));
            return Some(picked);
        }
    }
    if Command::new("java").arg("-version").output().map(|o| o.status.success()).unwrap_or(false) {
        if has_httpserver("java") && java_ge17("java") {
            // PATH 上的裸 "java" 字符串无法定位到具体文件，交给 CREATE_NO_WINDOW 兜底
            log_line("java: PATH hit");
            return Some("java".to_string());
        }
    }
    for name in ["java.exe", "java"] {
        let bundled = res_dir.join("bundle").join("runtime").join("bin").join(name);
        if bundled.exists() {
            let raw = bundled.to_string_lossy().into_owned();
            let picked = prefer_javaw(raw.clone());
            log_line(&format!("java: bundled runtime hit ({} → {})", raw, picked));
            return Some(picked);
        }
    }
    log_line("java: none of JAVA_HOME / PATH / bundled runtime usable; falling back to `java`");
    None
}

/// Spawn Java 后端，附带 `--no-browser` 让 SetupMain 不弹系统浏览器（UI 走 Tauri WebView）。
/// 失败时返回 io::Error 让上层记日志并展示窗口（不让整个 Tauri app 崩）。
fn spawn_backend(java: &str, jar: &Path, cwd: &Path) -> std::io::Result<Child> {
    let mut cmd = Command::new(java);
    cmd.arg("-jar").arg(jar).arg(NO_BROWSER).current_dir(cwd);
    #[cfg(windows)]
    cmd.creation_flags(CREATE_NO_WINDOW);
    let child = cmd.spawn()?;
    log_line(&format!("backend spawned: java={java} jar={:?} pid={}", jar, child.id()));
    Ok(child)
}

/// 轮询直到 TCP 端口能连上（或超时）。返回 true=就绪。
fn wait_for_backend(port: u16) -> bool {
    for i in 0..150 {
        if TcpStream::connect(("127.0.0.1", port)).is_ok() {
            log_line(&format!("backend accepting TCP on {port} ({} polls)", i + 1));
            return true;
        }
        thread::sleep(Duration::from_millis(200));
    }
    log_line(&format!("backend NOT accepting TCP on {port} after 30s"));
    false
}

fn main() {
    log_line("=== db-mcp-helper (tauri shell) starting ===");

    let app = tauri::Builder::default()
        .setup(|app| {
            let res_dir = app.path().resource_dir().expect("resource_dir unavailable");
            let bundle_dir = res_dir.join("bundle");
            let jar = bundle_dir.join(JAR);
            log_line(&format!("resource_dir = {res_dir:?}"));
            log_line(&format!("jar = {jar:?} exists = {}", jar.exists()));
            let bundled_rt = bundle_dir.join("runtime").join("bin");
            log_line(&format!("bundled runtime bin = {bundled_rt:?} exists = {}", bundled_rt.exists()));

            let java = resolve_java(&res_dir).unwrap_or_else(|| "java".to_string());

            // 先 spawn 后端，失败也不阻断窗口显示（让用户至少看到 WebView，不至于"点了没反应"）
            let child_opt: Option<Child> = match spawn_backend(&java, &jar, &bundle_dir) {
                Ok(c) => Some(c),
                Err(e) => {
                    log_line(&format!("spawn backend FAILED: {e}"));
                    None
                }
            };
            let ready = wait_for_backend(PORT);
            if !ready {
                log_line("webview will attempt to load backend URL anyway (may show ERR)");
            }

            // 无论后端 ready 与否都建窗，避免用户看不到任何反馈
            let url: url::Url = format!("http://127.0.0.1:{PORT}").parse().expect("invalid url");
            let _win = WebviewWindowBuilder::new(app, "main", WebviewUrl::External(url))
                .title("DB MCP Helper")
                .inner_size(1100.0, 760.0)
                .resizable(true)
                .visible(true)
                .center()
                .build()
                .map_err(|e| -> Box<dyn std::error::Error> {
                    let msg = format!("failed to build main window: {e}");
                    log_line(&msg);
                    msg.into()
                })?;
            log_line("webview window built OK (visible=true, centered)");

            if let Some(c) = child_opt {
                app.manage(Backend(Mutex::new(Some(c))));
            } else {
                app.manage(Backend(Mutex::new(None)));
            }
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building DB MCP Helper");

    app.run(|app_handle, event| {
        if let tauri::RunEvent::Exit = event {
            if let Some(b) = app_handle.try_state::<Backend>() {
                if let Ok(mut guard) = b.0.lock() {
                    if let Some(mut child) = guard.take() {
                        let _ = child.kill();
                        log_line("backend killed on shell exit");
                    }
                }
            }
        }
    });
}

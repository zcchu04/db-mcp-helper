#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::net::TcpStream;
use std::path::Path;
use std::process::{Child, Command};
use std::sync::Mutex;
use std::thread;
use std::time::Duration;

use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};

const PORT: u16 = 8765;
const JAR: &str = "db-mcp-setup.jar";
const NO_BROWSER: &str = "--no-browser";

/// Owns the spawned Java backend process so we can terminate it on app exit.
/// Uses `Mutex<Option<Child>>` because `Child::kill` needs `&mut` but Tauri
/// managed-state only hands out shared references.
struct Backend(Mutex<Option<Child>>);

/// True if this `java` is a JDK that exposes the `jdk.httpserver` module
/// (our setup backend depends on `com.sun.net.httpserver.HttpServer`).
fn has_httpserver(java: &str) -> bool {
    if let Ok(out) = Command::new(java).args(["--list-modules"]).output() {
        let s = String::from_utf8_lossy(&out.stdout);
        return s.lines().any(|l| l.starts_with("jdk.httpserver@"));
    }
    false
}

/// True if this `java` is JDK 17 or newer (the backend is built for Java 17).
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

/// Resolve the Java launcher to use, in priority order:
///   1. `$JAVA_HOME/bin/java` (must be JDK 17+ with jdk.httpserver)
///   2. `java` found on PATH (same requirement)
///   3. bundled JRE shipped next to the jar (`bundle/runtime/bin/java[.exe]`)
fn resolve_java(res_dir: &Path) -> Option<String> {
    if let Ok(home) = std::env::var("JAVA_HOME") {
        let p = format!("{}/bin/java", home.replace('\\', "/"));
        if Path::new(&p).exists() && has_httpserver(&p) && java_ge17(&p) {
            return Some(p);
        }
    }
    if Command::new("java").arg("-version").output().map(|o| o.status.success()).unwrap_or(false) {
        if has_httpserver("java") && java_ge17("java") {
            return Some("java".to_string());
        }
    }
    for name in ["java.exe", "java"] {
        let bundled = res_dir.join("bundle").join("runtime").join("bin").join(name);
        if bundled.exists() {
            return Some(bundled.to_string_lossy().to_string());
        }
    }
    None
}

/// Spawn the backend jar with `--no-browser` so it only serves the HTTP API
/// and never pops a system browser (the Tauri WebView is the UI).
fn spawn_backend(java: &str, jar: &Path, cwd: &Path) -> Child {
    Command::new(java)
        .arg("-jar")
        .arg(jar)
        .arg(NO_BROWSER)
        .current_dir(cwd)
        .spawn()
        .expect("failed to start DB MCP Helper backend")
}

/// Block until the backend accepts TCP connections on the port (or give up).
fn wait_for_backend(port: u16) {
    for _ in 0..150 {
        if TcpStream::connect(("127.0.0.1", port)).is_ok() {
            return;
        }
        thread::sleep(Duration::from_millis(200));
    }
    eprintln!("[db-mcp-helper] backend did not come up on port {port} in time");
}

fn main() {
    let app = tauri::Builder::default()
        .setup(|app| {
            let res_dir = app
                .path()
                .resource_dir()
                .expect("resource_dir unavailable");
            let bundle_dir = res_dir.join("bundle");
            let jar = bundle_dir.join(JAR);

            if !jar.exists() {
                eprintln!("[db-mcp-helper] backend jar not found: {:?}", jar);
            }

            let java = resolve_java(&res_dir).unwrap_or_else(|| {
                eprintln!("[db-mcp-helper] no suitable Java (JDK 17+ with jdk.httpserver) and no bundled runtime");
                "java".to_string()
            });

            let child = spawn_backend(&java, &jar, &bundle_dir);
            wait_for_backend(PORT);

            let url: url::Url = format!("http://127.0.0.1:{PORT}").parse().expect("invalid url");
            WebviewWindowBuilder::new(app, "main", WebviewUrl::External(url))
                .title("DB MCP Helper")
                .inner_size(1100.0, 760.0)
                .resizable(true)
                .build()
                .expect("failed to build main window");

            app.manage(Backend(Mutex::new(Some(child))));
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
                    }
                }
            }
        }
    });
}

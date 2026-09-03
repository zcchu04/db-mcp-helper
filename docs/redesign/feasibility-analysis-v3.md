# DB MCP Helper v0.3 可行性分析文档

> 版本：v0.3.0-draft | 日期：2026-09-03

---

## 一、技术可行性

### 1.1 ImplRegistry — MCP 服务实现热更新

| 维度 | 评估 | 说明 |
|------|------|------|
| **文件替换** | ✅ 完全可行 | 现有 `Installer.deployToolkit()` 已实现 toolkit 目录写入，只需增加 bak → 解压 → 覆盖三步流程 |
| **zip 解压** | ✅ 完全可行 | `Installer.unzip()` 已实现完整的 zip 解压逻辑（含根目录剥离、POSIX 权限设置），可直接复用 |
| **bak 备份** | ✅ 完全可行 | 已有 `Trash.java` 做回收站删除、`McpJson.java` 做 `.bak-timestamp` 备份，模式成熟 |
| **上传解压** | ✅ 完全可行 | JDK HttpServer 支持 multipart 解析（需引入轻量库或手写 parser），Java 17 内置 `java.util.zip` |
| **版本管理** | ✅ 完全可行 | `impls.json` 纯 JSON 文件，Gson 序列化，与现有 `state.json` 模式一致 |
| **路径穿越防护** | ✅ 完全可行 | zip entry name 校验 `../` 和绝对路径，标准安全措施 |

**风险点**：
- Windows 上替换正在被 JVM 加载的 JAR 会文件锁冲突 → 解决：替换前检查进程占用，提示用户先停止实例
- 大文件上传（Node 项目含 node_modules 可能 100MB+）→ 解决：流式上传 + 临时文件 + 进度回调

### 1.2 RuntimeManager — 运行时环境管理

| 维度 | 评估 | 说明 |
|------|------|------|
| **本地运行时检测** | ✅ 完全可行 | `java -version` / `node --version` 解析版本，`--list-modules` 检查 Java 模块，标准操作 |
| **兼容性判定** | ✅ 完全可行 | 版本比较用 `Runtime.Version.parse()`（Java 17+），Node 用 semver 比较 |
| **运行时切换** | ✅ 可行，有复杂度 | 需要修改 `Cfg.java` 的解析链和 `Installer.resolveJava()` 的优先级链，改动集中可控 |
| **prefs.json 扩展** | ✅ 完全可行 | 增加 `runtimeOverrides` 字段，与现有 prefs 模式一致 |
| **jlink 运行时独立打包** | ✅ 完全可行 | CI 已有 jlink 步骤，只需将产物单独上传 |
| **Node 运行时独立打包** | ✅ 完全可行 | 直接打包 Node.js 官方二进制分发 |

**风险点**：
- 系统 Java 可能缺少 `jdk.httpserver` 模块 → 解决：检测失败时明确提示并引导下载 bundled JRE
- 用户指定的本地 Node 版本过低 → 解决：前置检测 + 明确错误信息 + 引导下载
- macOS/Linux 上权限问题 → 解决：`deployRuntime()` 已有 POSIX chmod +x 逻辑

### 1.3 ArtifactDownloader — GitHub Release 下载

| 维度 | 评估 | 说明 |
|------|------|------|
| **GitHub API 调用** | ✅ 完全可行 | `java.net.http.HttpClient`（JDK 17 内置）调用 REST API，无需额外依赖 |
| **大文件下载** | ✅ 完全可行 | `HttpClient` 支持流式 body 写入文件，可配合 `Range` header 断点续传 |
| **SHA-256 校验** | ✅ 完全可行 | `MessageDigest.getInstance("SHA-256")`，JDK 内置 |
| **进度回调** | ✅ 完全可行 | 流式读取 `InputStream` 时累计字节数，通过回调接口通知前端 |
| **断点续传** | ⚠️ 可行但复杂 | GitHub Release assets 支持 `Range` header，但需处理临时文件管理和重试逻辑 |
| **代理支持** | ✅ 完全可行 | `HttpClient` 支持 `ProxySelector`，可读取系统代理设置 |

**风险点**：
- 国内网络访问 GitHub 可能不稳定 → 解决：支持配置镜像源 / 代理；提供手动上传作为替代方案
- Release API 有 rate limit（未认证 60次/h）→ 解决：缓存 Release 信息，减少查询频率
- 下载中断后临时文件残留 → 解决：下载到 `.tmp` 文件，完成后 rename；启动时清理残留 `.tmp`

### 1.4 CI 产物分层

| 维度 | 评估 | 说明 |
|------|------|------|
| **GitHub Actions matrix** | ✅ 完全可行 | 现有 workflow 已使用 matrix，扩展维度即可 |
| **多产物上传** | ✅ 完全可行 | `actions/upload-artifact` + `softprops/action-gh-release` 支持多文件 |
| **构建时间** | ⚠️ 会增加 | 分层产物意味着更多 job，但可并行执行，预计总时间增加 30-50% |
| **存储成本** | ⚠️ 会增加 | GitHub Release 无硬限制但建议关注，每个 tag 约 300MB 产物 |
| **manifest 规范** | ✅ 完全可行 | 纯 JSON 文件，构建脚本生成 |

**风险点**：
- CI 构建矩阵膨胀 → 解决：分层 job 并行执行，不串行；非 tag push 可只构建 slim
- 产物命名冲突 → 解决：严格命名规范 `{type}-{platform}-{version}`

---

## 二、向后兼容性分析

### 2.1 目录结构迁移

| 变更 | 兼容策略 | 风险 |
|------|---------|------|
| `toolkit/` → `impls/` | 创建符号链接 `toolkit/ → ../../impls/` | Windows 符号链接需管理员权限 → 回退：用 `.last-root` 记录 + 路径重写 |
| `runtime/` → `runtimes/jre/` | 创建符号链接 `runtime/ → runtimes/jre/` | 同上 |
| `<dbId>/runtime/node` → `runtimes/node/` | 符号链接 | 同上 |
| `state.json` 格式 | 不变，新增字段有默认值 | 无风险 |
| `mcp.json` 注册 | 不变，command 路径不变 | 无风险（如果符号链接工作） |

### 2.2 Windows 符号链接替代方案

Windows 非管理员用户无法创建符号链接。替代方案：

**方案 A：目录联接 (Junction)**
- `mklink /J` 不需要管理员权限
- Java 17 `Files.createSymbolicLink()` 在 Windows 上实际创建 junction
- 验证：`java.nio.file.Files.createSymbolicLink()` 在非管理员下是否可用

**方案 B：路径重写**
- 不创建符号链接，直接修改 `buildCommand()` 中的路径引用
- `Installer.toolkitPath()` 返回 `impls/` 下的新路径
- 优点：无文件系统依赖；缺点：需要确保所有路径引用点都更新

**推荐方案 B**：更健壮、无平台差异、无权限问题。保留方案 A 作为可选优化。

### 2.3 老用户升级路径

```
v0.2 用户升级 v0.3:
  1. 安装新版 Helper（slim 包 or 全量包）
  2. 首次启动检测旧布局
  3. 自动迁移：
     - 复制 toolkit 到 impls/
     - 复制 runtime 到 runtimes/
     - 生成 impls.json
     - 更新路径引用
  4. 旧目录保留（不删除），用户确认无问题后手动清理
```

---

## 三、性能影响分析

### 3.1 启动性能

| 操作 | 当前 | 优化后 | 影响 |
|------|------|--------|------|
| Helper 启动 | 读 state.json + 检查文件 | +读 impls.json + 读 prefs.json runtimeOverrides | < 5ms，可忽略 |
| 实例启动命令 | 直接拼 command | 多一步 resolveJava/resolveNode | < 50ms（含版本检测），可缓存 |

### 3.2 部署性能

| 操作 | 当前 | 优化后 | 影响 |
|------|------|--------|------|
| 首次 deploy | 解压 runtime.zip + toolkit | 同左（全量包）| 无变化 |
| 更新实现 | 不支持 | bak + 解压 + 覆盖 | 取决于实现大小，Oracle ~5MB < 2s，MySQL ~50MB < 10s |
| 回滚 | 不支持 | 删除当前 + 恢复 bak | < 2s |

### 3.3 磁盘占用

| 场景 | 当前 | 优化后 |
|------|------|--------|
| 最小安装（slim + 按需下载） | ~15MB helper | ~15MB helper + 按需下载 |
| 全量安装 | ~150MB | ~150MB + bak 空间（约 2x 单次实现大小）|
| bak 累积 | 无 | 每实现最多 3 版本，自动清理 |

---

## 四、依赖与约束分析

### 4.1 新增依赖

| 依赖 | 用途 | 必要性 | 替代方案 |
|------|------|--------|---------|
| 无新增 Maven 依赖 | — | — | — |
| GitHub REST API | Release 查询/下载 | 必须 | 可退化为手动 URL 输入 |

所有功能均可用 JDK 17 内置 API 实现：
- HTTP: `java.net.http.HttpClient`
- JSON: 已有 Gson 2.11.0
- ZIP: `java.util.zip.ZipInputStream`
- Hash: `java.security.MessageDigest`
- 文件: `java.nio.file.Files`

### 4.2 外部约束

| 约束 | 影响 | 应对 |
|------|------|------|
| GitHub API rate limit | 未认证 60次/h | 缓存 + ETag + 可选 token |
| GitHub 下载带宽 | 大文件可能慢 | 断点续传 + 进度显示 |
| Windows 文件锁 | 替换运行中的 JAR | 检测 + 提示停止 |
| 企业网络代理 | 无法直连 GitHub | 代理配置 + 手动上传 |

---

## 五、可行性结论

| 模块 | 可行性 | 复杂度 | 建议 |
|------|--------|--------|------|
| ImplRegistry | ✅ 高 | 中 | 核心功能，优先实施 |
| RuntimeManager | ✅ 高 | 中 | 与 ImplRegistry 并行实施 |
| ArtifactDownloader | ✅ 高 | 中低 | 依赖 GitHub API，可独立实施 |
| CI 产物分层 | ✅ 高 | 低 | 纯 CI 配置，可快速实施 |
| 目录迁移 | ✅ 高 | 中 | 需仔细处理 Windows 兼容 |
| 前端 UI | ✅ 高 | 中 | 两个新页面 + 向导增强 |

**总体评估**：所有模块技术可行，无硬性技术障碍。主要复杂度在于：
1. 目录迁移的跨平台兼容（推荐路径重写方案规避符号链接问题）
2. 运行时检测的边界情况（系统 Java 缺模块、Node 版本碎片）
3. CI 矩阵膨胀的构建时间控制

**建议**：分三期实施，先核心（ImplRegistry + 前端管理页），再扩展（RuntimeManager + ArtifactDownloader），最后优化（CI 分层 + 迁移工具）。

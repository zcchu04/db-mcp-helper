# DB MCP Helper 架构优化设计文档

> 版本：v0.3.0-draft | 日期：2026-09-03

---

## 一、现状概述

### 1.1 当前架构

```
┌─────────────────────────────────────────────────────────┐
│  shell (Tauri Desktop)                                   │
│  ├── setup-app (fat JAR, 内嵌 HttpServer :8765)         │
│  │   ├── DbAdapter 体系 (Oracle / MySQL / Doris)        │
│  │   ├── Installer (deploy / reset / uninstall)         │
│  │   ├── McpTarget (13+ AI 客户端注册)                  │
│  │   └── SelfTest (MCP stdio 全链路自检)                │
│  ├── mcp-tap (stdio 代理 + JSONL 审计)                  │
│  └── resources/                                         │
│      ├── toolkit/oracle/  → oracle-db-mcp-toolkit.jar   │
│      ├── toolkit/mysql/   → benborla29/ + naga/         │
│      ├── tap/mcp-tap.jar                                │
│      ├── runtime/runtime.zip (jlink JRE)                │
│      └── runtime/mysql/node.zip                         │
└─────────────────────────────────────────────────────────┘
```

### 1.2 现存痛点

| 问题 | 影响 |
|------|------|
| MCP 服务实现随安装包硬编码 | 更新实现必须重新发布整个安装包 |
| 运行时环境(jlink JRE / Node)捆绑分发 | 包体大、用户无法复用已有运行时 |
| 无实现版本管理 | 用户不知道当前版本、无法回滚 |
| 单一全量产物 | CI 只产出一种包，无法按需选择 |
| 部署即覆盖、无备份 | 更新出问题只能手动恢复 |

---

## 二、目标架构

### 2.1 设计原则

1. **实现与安装包解耦** — MCP 服务实现作为可热更新的独立资源，不随 Helper 本体发布
2. **运行时可选** — 优先使用本地已有运行时，不足时按需下载
3. **产物分层** — CI 按组合维度产出多种包，用户按需取用
4. **安全回滚** — 任何替换操作都先 bak 备份，支持一键还原
5. **向后兼容** — 全量包保持开箱即用，老用户无感升级

### 2.2 目标架构图

```
┌──────────────────────────────────────────────────────────────────┐
│                    DB MCP Helper (Tauri Shell)                    │
│                                                                   │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │                  setup-app (核心引擎)                      │    │
│  │                                                           │    │
│  │  ┌─────────────┐  ┌──────────────┐  ┌────────────────┐  │    │
│  │  │ Adapter 体系 │  │ ImplRegistry │  │ RuntimeManager │  │    │
│  │  │ (不变)       │  │ (新增)        │  │ (新增)          │  │    │
│  │  └──────┬──────┘  └──────┬───────┘  └───────┬────────┘  │    │
│  │         │                │                   │            │    │
│  │  ┌──────┴────────────────┴───────────────────┴────────┐  │    │
│  │  │              Installer (增强)                        │  │    │
│  │  │  deploy / deployImpl / deployRuntime / bak / restore│  │    │
│  │  └────────────────────────────────────────────────────┘  │    │
│  │                                                           │    │
│  │  ┌────────────────────────────────────────────────────┐  │    │
│  │  │           ArtifactDownloader (新增)                  │  │    │
│  │  │  GitHub Release 下载 / 本地上传 / 校验 / 解压       │  │    │
│  │  └────────────────────────────────────────────────────┘  │    │
│  └──────────────────────────────────────────────────────────┘    │
│                                                                   │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │                    前端 UI (增强)                          │    │
│  │  ┌──────────────┐  ┌──────────────┐  ┌───────────────┐  │    │
│  │  │ 实现管理页    │  │ 运行时配置页  │  │ 向导 Step1    │  │    │
│  │  │ (新增)        │  │ (新增)        │  │ (增强检测)     │  │    │
│  │  └──────────────┘  └──────────────┘  └───────────────┘  │    │
│  └──────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────┘

         │                          │
         ▼                          ▼
┌─────────────────┐     ┌──────────────────────────────┐
│  GitHub Release  │     │   本地文件系统                 │
│  (分类型产物)     │     │   ~/.agent/mcp/               │
│                  │     │   ├── impls/    (实现仓库)     │
│  ├ full-*.zip    │     │   │   ├── oracle/             │
│  ├ slim-*.zip    │     │   │   │   └── builtin-toolkit/│
│  ├ runtime-*.zip │     │   │   ├── mysql/              │
│  └ helper-*.exe  │     │   │   │   ├── benborla29/     │
│                  │     │   │   │   └── naganpm/        │
└─────────────────┘     │   ├── runtimes/ (运行时仓库)   │
                         │   │   ├── jre/                │
                         │   │   └── node/               │
                         │   ├── runtime/  (共享 JRE)     │
                         │   ├── tap/                    │
                         │   └── <dbId>/                 │
                         │       ├── toolkit/            │
                         │       └── runtime/            │
                         └──────────────────────────────┘
```

---

## 三、核心模块设计

### 3.1 ImplRegistry — MCP 服务实现注册中心

#### 3.1.1 数据模型

```java
// impls.json — 位于 baseDir/impls/impls.json
{
  "oracle": {
    "builtin-toolkit": {
      "version": "1.3.0",
      "source": "builtin",           // builtin | github | uploaded
      "sourceUrl": null,
      "installedAt": "2026-09-01T10:00:00Z",
      "entryFile": "oracle-db-mcp-toolkit.jar",
      "runtimeKind": "JAVA_JAR",
      "checksum": "sha256:abc123...",
      "bakVersions": [                // 保留最近 3 个历史版本
        { "version": "1.2.0", "bakPath": "bak/oracle/builtin-toolkit/1.2.0/" }
      ]
    }
  },
  "mysql": {
    "benborla29": {
      "version": "0.8.1",
      "source": "builtin",
      "entryFile": "build/index.js",
      "runtimeKind": "NODE",
      ...
    },
    "naganpm": { ... }
  }
}
```

#### 3.1.2 核心接口

```java
public class ImplRegistry {

    /** 查询某实现的当前安装状态 */
    ImplInfo get(String dbId, String serverId);

    /** 列出所有已注册的实现 */
    Map<String, Map<String, ImplInfo>> listAll();

    /** 从 GitHub Release URL 下载并安装/更新实现 */
    void installFromUrl(String dbId, String serverId, String url, ProgressCallback cb);

    /** 从用户上传的压缩包安装/替换实现 */
    void installFromUpload(String dbId, String serverId, Path uploadFile, ProgressCallback cb);

    /** 回滚到上一个 bak 版本 */
    void rollback(String dbId, String serverId);

    /** 清理过期的 bak 版本（保留最近 N 个） */
    void pruneBak(String dbId, String serverId, int keepCount);
}
```

#### 3.1.3 安装/替换流程

```
用户上传 / GitHub 下载
        │
        ▼
  ┌─────────────┐     否     ┌──────────────┐
  │ 目标目录存在? ├──────────→│ 直接安装      │──→ 写入 impls.json
  └──────┬──────┘            └──────────────┘
         │ 是
         ▼
  ┌─────────────┐
  │ bak 当前版本 │  →  bak/<dbId>/<serverId>/<version>_<timestamp>/
  └──────┬──────┘
         ▼
  ┌─────────────┐
  │ 解压/覆盖    │  →  如果是 zip/tar.gz 先解压
  └──────┬──────┘
         ▼
  ┌─────────────┐
  │ 校验入口文件 │  →  检查 entryFile 存在且非空
  └──────┬──────┘
         ▼
  ┌─────────────┐
  │ 更新 impls   │  →  写入版本、来源、时间戳、checksum
  └──────┬──────┘
         ▼
  ┌─────────────┐
  │ 清理旧 bak   │  →  保留最近 3 个版本
  └─────────────┘
```

### 3.2 RuntimeManager — 运行时环境管理器

#### 3.2.1 运行时类型

| 类型 | 用途 | 当前来源 | 目标来源 |
|------|------|---------|---------|
| jlink JRE | Helper 自身 + Oracle JAVA_JAR 实现 | runtime.zip 内置 | 内置 / 本地选择 / GitHub 下载 |
| Node.js | MySQL / Doris NODE 实现 | runtime/mysql/node.zip | 内置 / 本地选择 / GitHub 下载 |

#### 3.2.2 运行时解析优先级

```
1. 用户显式指定（prefs.json 中 runtimeOverrides）
2. 已部署的 bundled runtime（baseDir/runtime/ 或 baseDir/<dbId>/runtime/）
3. 系统全局运行时（JAVA_HOME / PATH 中的 node）
4. 从 GitHub Release 按需下载
```

#### 3.2.3 核心接口

```java
public class RuntimeManager {

    /** 解析可用的 Java 运行时 */
    ResolvedRuntime resolveJava(RuntimeRequirement req);

    /** 解析可用的 Node 运行时 */
    ResolvedRuntime resolveNode(RuntimeRequirement req);

    /** 用户选择本地运行时目录 */
    void setLocalRuntime(String kind, Path localDir);

    /** 从 GitHub 下载运行时 */
    void downloadRuntime(String kind, ProgressCallback cb);

    /** 兼容性检测：版本、架构、必要模块 */
    CompatResult checkCompatibility(Path runtimeDir, RuntimeKind kind);

    /** 清除用户覆盖，恢复使用 bundled runtime */
    void resetRuntimeOverride(String kind);
}

public record RuntimeRequirement(
    String minVersion,       // 最低版本要求
    String requiredModules,  // Java: "jdk.httpserver" 等
    String arch              // "x64" | "arm64"
) {}

public record ResolvedRuntime(
    String source,           // "bundled" | "local" | "system" | "downloaded"
    Path binDir,
    String version,
    String javaCmdOrNodeCmd  // 完整可执行路径
) {}
```

#### 3.2.4 兼容性检测逻辑

```
Java 运行时检测:
  ├── bin/java (或 bin/java.exe) 存在?
  ├── 版本号 >= minVersion?          → java -version 解析
  ├── 包含必要模块?                   → java --list-modules 检查
  └── 架构匹配?                       → java -XshowSettings:properties

Node 运行时检测:
  ├── bin/node (或 bin/node.exe) 存在?
  ├── 版本号 >= minVersion?          → node --version 解析
  ├── npm/npx 可用?                  → 检查 bin/ 目录
  └── 架构匹配?
```

### 3.3 ArtifactDownloader — 产物下载器

#### 3.3.1 GitHub Release 集成

```java
public class ArtifactDownloader {

    /** 查询最新 Release 信息（不下载） */
    ReleaseInfo fetchLatestRelease();

    /** 列出所有可用产物及其类型 */
    List<ArtifactEntry> listArtifacts(ReleaseInfo release);

    /** 下载指定产物到临时目录，支持进度回调和断点续传 */
    Path download(ArtifactEntry entry, ProgressCallback cb);

    /** 校验下载完整性（SHA-256） */
    boolean verify(Path file, String expectedSha256);
}

public record ArtifactEntry(
    String name,           // "db-mcp-runtime-jre-win-x64.zip"
    String type,           // "full" | "slim" | "runtime-jre" | "runtime-node" | "helper"
    String platform,       // "win-x64" | "mac-arm64" | "linux-x64"
    long size,
    String downloadUrl,
    String sha256
) {}
```

#### 3.3.2 下载流程

```
用户触发下载
     │
     ▼
GET /repos/{owner}/{repo}/releases/latest
     │
     ▼
解析 assets 列表 → 按 type + platform 过滤
     │
     ▼
显示可选列表（大小、类型、平台）
     │
     ▼
用户选择 → 开始下载（HttpURLConnection, 支持 Range 断点续传）
     │
     ▼
下载到 temp 目录 → SHA-256 校验
     │
     ▼
解压到目标目录 → 更新 impls.json 或 runtime 注册
```

### 3.4 Installer 增强

#### 3.4.1 新增方法

```java
public class Installer {

    // === 实现管理 ===

    /** 部署实现（带 bak 备份） */
    void deployImpl(String dbId, String serverId, Adapter adapter, Path source);

    /** 从 zip 上传安装实现 */
    void installImplFromZip(String dbId, String serverId, Path zipFile);

    /** 回滚实现到上一版本 */
    void rollbackImpl(String dbId, String serverId);

    // === 运行时管理 ===

    /** 部署运行时（带版本标记） */
    void deployRuntime(String kind, Path source);

    /** 使用本地运行时 */
    void linkLocalRuntime(String kind, Path localDir);

    // === bak 管理 ===

    /** 备份当前实现 */
    Path bakImpl(String dbId, String serverId);

    /** 从 bak 恢复 */
    void restoreBak(Path bakDir);
}
```

#### 3.4.2 目录结构变更

```
~/.agent/mcp/
├── impls/                          ← 新增：实现仓库
│   ├── impls.json                  ← 实现注册表
│   ├── oracle/
│   │   └── builtin-toolkit/        ← 当前版本
│   │       └── oracle-db-mcp-toolkit.jar
│   └── mysql/
│       ├── benborla29/
│       │   ├── build/index.js
│       │   └── node_modules/
│       └── naganpm/
│           └── dist/index.js
├── bak/                            ← 新增：备份仓库
│   ├── oracle/builtin-toolkit/
│   │   └── 1.2.0_20260901-100000/
│   └── mysql/benborla29/
│       └── 0.7.0_20260815-140000/
├── runtimes/                       ← 新增：运行时仓库
│   ├── jre/                        ← 共享 JRE（替代原 runtime/）
│   │   ├── version.json            ← { "version": "17.0.12", "source": "bundled" }
│   │   └── bin/java ...
│   └── node/                       ← 共享 Node（替代原 <dbId>/runtime/node）
│       ├── version.json
│       └── bin/node ...
├── runtime/                        ← 兼容：软链到 runtimes/jre/
├── tap/
├── <dbId>/
│   ├── toolkit/ → ../../impls/<dbId>/   ← 兼容软链（或直接引用）
│   └── instance/
├── state.json
└── prefs.json                      ← 增加 runtimeOverrides 字段
```

---

## 四、CI 产物分层设计

### 4.1 产物矩阵

| 产物类型 | 命名模式 | 包含内容 | 典型大小 |
|---------|---------|---------|---------|
| **全量包** | `db-mcp-helper-full-{platform}-{version}.zip` | Helper + tap + 所有实现 + 所有运行时 | ~150MB |
| **精简包** | `db-mcp-helper-slim-{platform}-{version}.zip` | Helper + tap，无实现无运行时 | ~15MB |
| **JRE 运行时包** | `db-mcp-runtime-jre-{platform}-{version}.zip` | jlink JRE | ~45MB |
| **Node 运行时包** | `db-mcp-runtime-node-{platform}-{version}.zip` | Node.js runtime | ~30MB |
| **Oracle 实现包** | `db-mcp-impl-oracle-toolkit-{version}.zip` | oracle-db-mcp-toolkit | ~5MB |
| **MySQL 实现包** | `db-mcp-impl-mysql-{version}.zip` | benborla29 + naganpm | ~10MB |
| **桌面安装包** | `db-mcp-helper-{platform}-{version}.exe/.dmg/.deb` | Tauri 安装包（含 slim） | ~20MB |

### 4.2 GitHub Actions 工作流

```yaml
# .github/workflows/build.yml (重构)

name: Build

on:
  push:
    tags: ['v*']
  workflow_dispatch:

jobs:
  # ─── Phase 1: 构建核心组件 ───
  build-core:
    runs-on: ubuntu-latest
    steps:
      - build mcp-tap.jar
      - build setup-app fat JAR
      - upload: mcp-tap.jar, db-mcp-setup.jar

  # ─── Phase 2: 构建各实现包 ───
  build-impls:
    needs: build-core
    strategy:
      matrix:
        impl: [oracle-toolkit, mysql-all]
    steps:
      - build/download MCP server implementation
      - package as zip with version manifest
      - upload artifact

  # ─── Phase 3: 构建运行时包 ───
  build-runtimes:
    needs: build-core
    strategy:
      matrix:
        include:
          - runtime: jre
            platforms: [win-x64, mac-arm64, linux-x64]
          - runtime: node
            platforms: [win-x64, mac-arm64, linux-x64]
    steps:
      - jlink / download Node
      - package as zip with version manifest
      - upload artifact

  # ─── Phase 4: 组装分层产物 ───
  assemble:
    needs: [build-core, build-impls, build-runtimes]
    strategy:
      matrix:
        platform: [win-x64, mac-arm64, linux-x64]
    steps:
      - download all artifacts
      - assemble "full" package (core + all impls + all runtimes)
      - assemble "slim" package (core only)
      - upload: full, slim

  # ─── Phase 5: 桌面安装包 ───
  desktop:
    needs: assemble
    strategy:
      matrix:
        platform: [windows, macos, ubuntu]
    steps:
      - use slim package for Tauri bundle
      - tauri build → .exe / .dmg / .deb

  # ─── Phase 6: 发布 ───
  release:
    needs: [assemble, build-impls, build-runtimes, desktop]
    if: startsWith(github.ref, 'refs/tags/')
    steps:
      - create GitHub Release
      - upload all artifacts with categorized names
      - generate checksums.txt
```

### 4.3 产物 manifest 规范

每个产物 zip 内含 `manifest.json`：

```json
{
  "type": "impl-oracle-toolkit",
  "version": "1.3.0",
  "helperVersion": "0.3.0",
  "platform": "any",
  "runtimeKind": "JAVA_JAR",
  "dbId": "oracle",
  "serverId": "builtin-toolkit",
  "entryFile": "oracle-db-mcp-toolkit.jar",
  "sha256": "abc123...",
  "createdAt": "2026-09-03T12:00:00Z"
}
```

---

## 五、前端 UI 设计

### 5.1 新增页面：实现管理

路由：`#/implementations`

```
┌─────────────────────────────────────────────────────────┐
│  MCP 服务实现管理                                         │
│                                                           │
│  ┌─ Oracle ──────────────────────────────────────────┐   │
│  │  ┌─ builtin-toolkit ──────────────────────────┐   │   │
│  │  │  版本: 1.3.0  来源: 内置  安装: 2026-09-01  │   │   │
│  │  │  [检查更新]  [从URL安装]  [上传替换]  [回滚]  │   │   │
│  │  └────────────────────────────────────────────┘   │   │
│  └───────────────────────────────────────────────────┘   │
│                                                           │
│  ┌─ MySQL ───────────────────────────────────────────┐   │
│  │  ┌─ benborla29 ───────────────────────────────┐   │   │
│  │  │  版本: 0.8.1  来源: 内置  安装: 2026-09-01  │   │   │
│  │  │  [检查更新]  [从URL安装]  [上传替换]  [回滚]  │   │   │
│  │  └────────────────────────────────────────────┘   │   │
│  │  ┌─ naganpm ──────────────────────────────────┐   │   │
│  │  │  版本: 2.1.0  来源: GitHub  安装: 2026-09-02│   │   │
│  │  │  [检查更新]  [从URL安装]  [上传替换]  [回滚]  │   │   │
│  │  └────────────────────────────────────────────┘   │   │
│  └───────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### 5.2 新增页面：运行时配置

路由：`#/runtime-config`（或合并到现有 `#/runtime` 页面）

```
┌─────────────────────────────────────────────────────────┐
│  运行时环境配置                                           │
│                                                           │
│  ┌─ Java 运行时 (JRE) ───────────────────────────────┐   │
│  │  当前: bundled JRE 17.0.12 (baseDir/runtime/)     │   │
│  │  状态: ✓ 兼容                                      │   │
│  │  [选择本地目录]  [从GitHub下载]  [恢复默认]         │   │
│  └───────────────────────────────────────────────────┘   │
│                                                           │
│  ┌─ Node 运行时 ─────────────────────────────────────┐   │
│  │  当前: 系统 Node v20.11.0 (/usr/local/bin/node)   │   │
│  │  状态: ✓ 兼容 (>= 18.0.0)                         │   │
│  │  [选择本地目录]  [从GitHub下载]  [恢复默认]         │   │
│  └───────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### 5.3 向导 Step 1 增强

在现有实现卡片上增加状态标记：

- 未安装：灰色边框 + "需下载" 标签
- 已安装 + 有更新：蓝色边框 + "v1.3.0 → v1.4.0 可更新" 标签
- 已安装 + 最新：绿色勾选标记
- 无运行时：提示 "需要 Node 运行时" + 一键安装按钮

---

## 六、REST API 设计

### 6.1 实现管理 API

```
GET    /api/impls                        → 列出所有实现及状态
GET    /api/impls/{dbId}/{serverId}      → 查询单个实现详情
POST   /api/impls/{dbId}/{serverId}/install-url
         body: { "url": "..." }          → 从 URL 下载安装
POST   /api/impls/{dbId}/{serverId}/upload
         multipart: file                 → 上传压缩包安装
POST   /api/impls/{dbId}/{serverId}/rollback
                                         → 回滚到上一版本
GET    /api/impls/{dbId}/{serverId}/bak-versions
                                         → 列出可回滚的 bak 版本
DELETE /api/impls/{dbId}/{serverId}/bak-versions/{version}
                                         → 删除指定 bak 版本
```

### 6.2 运行时管理 API

```
GET    /api/runtimes                     → 列出所有运行时及状态
GET    /api/runtimes/{kind}              → 查询指定运行时详情
POST   /api/runtimes/{kind}/set-local
         body: { "path": "..." }         → 设置本地运行时路径
POST   /api/runtimes/{kind}/download
         body: { "url": "..." }          → 从 URL 下载运行时
POST   /api/runtimes/{kind}/reset        → 恢复默认运行时
GET    /api/runtimes/{kind}/check
         query: path=...                 → 兼容性检测
```

### 6.3 产物查询 API

```
GET    /api/releases/latest              → 查询最新 Release 信息
GET    /api/releases/latest/artifacts    → 列出可用产物
```

---

## 七、数据迁移策略

### 7.1 从 v0.2 → v0.3 迁移

首次启动时自动执行（幂等）：

```
1. 检测 baseDir 是否存在旧布局
2. 迁移 toolkit 文件到 impls/ 目录
   - baseDir/oracle/toolkit/*.jar → baseDir/impls/oracle/builtin-toolkit/
   - baseDir/mysql/toolkit/* → baseDir/impls/mysql/benborla29/ 和 naganpm/
3. 迁移 runtime 到 runtimes/ 目录
   - baseDir/runtime/ → baseDir/runtimes/jre/
   - baseDir/mysql/runtime/node/ → baseDir/runtimes/node/
4. 创建兼容软链
   - baseDir/oracle/toolkit → ../../impls/oracle/
   - baseDir/runtime → runtimes/jre/
5. 生成 impls.json（从现有文件推断版本信息）
6. 写入迁移标记到 state.json: "migratedToV3": true
```

---

## 八、安全考量

| 风险 | 缓解措施 |
|------|---------|
| 上传恶意 zip | 解压前检查路径穿越（`../`）；限制解压大小上限；校验文件类型 |
| GitHub 下载被劫持 | SHA-256 校验；仅信任固定 owner/repo |
| bak 目录占满磁盘 | 自动清理策略：每个实现最多保留 3 个 bak 版本 |
| 本地运行时注入 | 兼容性检测 + 版本签名校验（可选） |
| 运行时覆盖导致服务中断 | 覆盖前检查是否有正在运行的实例；提示用户确认 |

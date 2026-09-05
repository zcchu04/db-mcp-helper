# CI 构建产物说明

本文档说明 GitHub Actions CI 流水线产出的所有包及其用途。

---

## 一、版本定义

| | 简版 (slim) | 标准版 (standard) | 全量版 (full) |
|---|---|---|---|
| 核心 JAR（setup-app + mcp-tap） | 包含 | 包含 | 包含 |
| JRE 运行时（jlink 裁剪） | 不包含 | 包含 | 包含 |
| 数据库实现包（mcp-impl） | 不包含 | 不包含 | 包含 |
| 数据库实现包运行时 | 不包含 | 不包含 | 包含 |
| 适用场景 | 开发机、已有 Java 17+ | 普通用户、无 Java 环境 | 需要开箱即用数据库支持 |

> **当前 CI 状态**：`standard` 对应 workflow 中的 `full` variant（仅含 core + JRE）。真正的 `full`（全量）还需包含数据库实现包及其运行时，待后续 CI 完善。

---

## 二、为什么只有 Oracle 实现包

`build-impls` job 的 matrix 目前只配置了 Oracle：

```yaml
matrix:
  include:
    - db: oracle
      serverId: builtin-toolkit
      jarPattern: "oracle-db-mcp-toolkit-*.jar"
```

MySQL 和 Doris 的实现包尚未加入 CI matrix。Doris 兼容 MySQL 协议，可复用 MySQL 的实现包，因此未来实际只需新增 MySQL 一个条目。

---

## 三、CI 流水线总览

```
build-core    ──→ core-jars (db-mcp-setup.jar + mcp-tap.jar)
                     │
build-runtime ──→ jre-runtime-{platform} (jlink 裁剪的私有 JRE，3 平台)
                     │
build-impls   ──→ impl-oracle (Oracle toolkit JAR)
                     │
assemble      ──→ 合并 core + runtime + impls → standard-package zip (仅 Windows)
                     │
desktop       ──→ Tauri 安装程序（3 平台 × 2 变体 = 6 个组合）
                     │
release       ──→ 打 tag 时汇总所有产物，生成 GitHub Release
```

---

## 四、构建产物清单（按用途分类）

### 4.1 安装程序（Installer）

由 `desktop` job 通过 Tauri 构建，每个安装程序内嵌 `bundle/` 目录资源。

| CI 产物名 | 文件格式 | 说明 |
|---|---|---|
| `installer-win-x64-slim` | `.exe` (NSIS) + `.msi` | Windows 简版安装程序 |
| `installer-win-x64-full` | `.exe` (NSIS) + `.msi` | Windows 标准版安装程序（内嵌 JRE） |
| `installer-macos-x64-slim` | `.dmg` | macOS 简版安装程序 |
| `installer-macos-x64-full` | `.dmg` | macOS 标准版安装程序（内嵌 JRE） |
| `installer-linux-x64-slim` | `.deb` + `.AppImage` | Linux 简版安装程序 |
| `installer-linux-x64-full` | `.deb` | Linux 标准版安装程序（内嵌 JRE） |

> Linux 标准版仅产出 `.deb`，跳过 AppImage 以节省 CI 磁盘空间。

### 4.2 运行时（Runtime）

| CI 产物名 | 文件名 | 说明 |
|---|---|---|
| `runtime-jre-win-x64` | `db-mcp-runtime-jre-win-x64-{version}.zip` | Windows JRE 运行时 |
| `runtime-jre-macos-x64` | `db-mcp-runtime-jre-macos-x64-{version}.zip` | macOS JRE 运行时 |
| `runtime-jre-linux-x64` | `db-mcp-runtime-jre-linux-x64-{version}.zip` | Linux JRE 运行时 |

### 4.3 数据库实现包（MCP Implementation）

| CI 产物名 | 文件名 | 说明 |
|---|---|---|
| `impl-oracle` | `db-mcp-impl-oracle-toolkit-{version}.zip` | Oracle 数据库 MCP 工具包 |

> MySQL / Doris 实现包待后续加入 CI。

### 4.4 组合分发包

| CI 产物名 | 文件名 | 说明 |
|---|---|---|
| `slim-package` | `db-mcp-helper-slim-win-x64-{version}.zip` | 简版 zip（仅 core，仅 Windows） |
| `full-package` | `db-mcp-helper-full-win-x64-{version}.zip` | 标准版 zip（core + JRE + impls，仅 Windows） |

### 4.5 中间产物（CI 内部流转，不对外发布）

| 产物名 | 内容 | 保留期 |
|---|---|---|
| `core-jars` | `db-mcp-setup.jar` + `mcp-tap.jar`（平铺） | 7 天 |
| `jre-runtime-{platform}` | 原始 `runtime/` 目录（供 desktop job 使用） | 3 天 |

---

## 五、ZIP 包内容详解

### 5.1 简版 ZIP（`db-mcp-helper-slim-win-x64-{version}.zip`）

```
db-mcp-setup.jar          # 核心应用 JAR（fat JAR，包含所有依赖）
tap/
  mcp-tap.jar             # MCP Tap 模块（协议适配层）
manifest.json             # 元信息：type=slim, version, platform, createdAt
```

### 5.2 标准版 ZIP（`db-mcp-helper-full-win-x64-{version}.zip`）

```
db-mcp-setup.jar                  # 核心应用 JAR
tap/
  mcp-tap.jar                     # MCP Tap 模块
runtime/
  bin/java.exe                    # jlink 裁剪的 Java 运行时
  bin/javaw.exe                   # 无控制台窗口的 Java 启动器
  lib/                            # JRE 核心库
  conf/                           # JRE 配置
  legal/                          # 开源协议声明
impls/
  oracle/
    builtin-toolkit/
      oracle-db-mcp-toolkit.jar   # Oracle 数据库实现
manifest.json                     # 元信息：type=full, includes=[core, impls, runtime-jre]
```

### 5.3 JRE 运行时 ZIP（`db-mcp-runtime-jre-{platform}-{version}.zip`）

```
runtime/
  bin/                            # java / javaw 可执行文件
  lib/                            # JRE 库文件
  conf/                           # 配置
  legal/                          # 协议
manifest.json                     # 元信息：type=runtime-jre, runtimeKind=JAVA_JAR
```

JRE 通过 `jdeps` 分析依赖 + `jlink` 构建，包含模块：
`java.base, java.logging, java.xml, java.desktop, java.instrument, java.management, java.naming, java.net.http, java.rmi, java.sql, jdk.net, jdk.security.jgss, jdk.httpserver`

### 5.4 Oracle 实现包（`db-mcp-impl-oracle-toolkit-{version}.zip`）

```
builtin-toolkit/
  oracle-db-mcp-toolkit.jar       # Oracle 数据库 MCP 工具包
manifest.json                     # 元信息：type=impl-oracle-toolkit, dbId=oracle
```

部署位置：`impls/oracle/builtin-toolkit/`

---

## 六、安装程序格式说明

### 6.1 Windows

#### NSIS（`.exe`）

- 向导式安装界面（许可协议 → 选择目录 → 安装 → 完成）
- 自动创建开始菜单快捷方式和桌面图标
- 自带卸载程序
- **推荐大多数用户使用**

#### MSI（`.msi`）

- 基于 Windows Installer 服务
- 支持静默安装（`msiexec /i package.msi /qn`）
- 支持组策略（GPO）批量部署、SCCM / Intune 分发
- **推荐企业 IT 部门使用**

| | NSIS (.exe) | MSI (.msi) |
|---|---|---|
| 安装体验 | 自定义向导界面 | Windows 标准界面 |
| 静默安装 | `/S` | `/qn` |
| 企业部署 | 不友好 | 友好（GPO/SCCM） |
| 卸载方式 | 自带 uninstaller | Windows 安装服务管理 |

### 6.2 macOS（`.dmg`）

- 双击挂载虚拟磁盘，拖拽 `.app` 到 `/Applications` 安装
- 卸载：删除 `.app` 即可

### 6.3 Linux

#### Debian 包（`.deb`）

- `dpkg -i package.deb` 或 `apt install ./package.deb` 安装
- 二进制安装到 `/usr/bin/`，桌面入口安装到 `/usr/share/applications/`
- 通过 `apt remove` 卸载
- 适用于 Debian / Ubuntu 及衍生发行版

#### AppImage（`.AppImage`）

- 单文件免安装，`chmod +x` 后直接运行
- 基于 FUSE 挂载 squashfs，不需要 root 权限
- 适用于非 Debian 系发行版
- **仅简版产出**，标准版为节省 CI 磁盘空间跳过

---

## 七、安装程序内嵌资源

### 简版安装程序

```
bundle/
  db-mcp-setup.jar          # 核心应用
  tap/
    mcp-tap.jar             # MCP Tap 模块
```

### 标准版安装程序

```
bundle/
  db-mcp-setup.jar          # 核心应用
  tap/
    mcp-tap.jar             # MCP Tap 模块
  runtime/                  # jlink 裁剪的 JRE
    bin/
    lib/
    conf/
    ...
```

> **注意**：当前安装程序不包含数据库实现包（desktop job 不依赖 build-impls）。全量版安装程序需后续 CI 改造，将 impl 包及其运行时一并嵌入。

### Java 运行时解析顺序

应用启动时按以下优先级查找 Java：

1. `$JAVA_HOME/bin/java`（需 JDK 17+ 且包含 `jdk.httpserver` 模块）
2. `$PATH` 上的 `java`（同样要求）
3. 内置 `bundle/runtime/bin/java`（仅标准版/全量版有此目录）

Windows 上优先使用 `javaw.exe` 以避免弹出控制台窗口。

---

## 八、Release 完整产物

推送 `v*` tag 时，`release` job 汇总所有产物生成 GitHub Release：

```
# 组合分发包
db-mcp-helper-slim-win-x64-{version}.zip
db-mcp-helper-full-win-x64-{version}.zip

# JRE 运行时
db-mcp-runtime-jre-win-x64-{version}.zip
db-mcp-runtime-jre-macos-x64-{version}.zip
db-mcp-runtime-jre-linux-x64-{version}.zip

# 数据库实现包
db-mcp-impl-oracle-toolkit-{version}.zip

# Windows 安装程序
DB MCP Helper_{version}_x64-setup.exe        # NSIS 简版
DB MCP Helper_{version}_x64_en-US.msi        # MSI 简版
DB MCP Helper_{version}_x64-setup.exe        # NSIS 标准版
DB MCP Helper_{version}_x64_en-US.msi        # MSI 标准版

# macOS 安装程序
DB MCP Helper_{version}_x64.dmg              # 简版
DB MCP Helper_{version}_x64.dmg              # 标准版

# Linux 安装程序
db-mcp-helper_{version}_amd64.deb            # 简版
db-mcp-helper_{version}_amd64.AppImage       # 简版
db-mcp-helper_{version}_amd64.deb            # 标准版

# 校验
checksums.txt                                 # SHA-256 校验和
```

---

## 九、快速选择指南

| 你的情况 | 推荐下载 |
|---|---|
| Windows 普通用户 | `installer-win-x64-full` 的 `.exe` |
| Windows 企业批量部署 | `installer-win-x64-full` 的 `.msi` |
| macOS 用户 | `installer-macos-x64-full` 的 `.dmg` |
| Ubuntu/Debian 用户 | `installer-linux-x64-full` 的 `.deb` |
| 其他 Linux 发行版 | `installer-linux-x64-slim` 的 `.AppImage` + 单独下载 JRE |
| 已有 Java 17+ 的开发者 | `slim-package` zip |
| 需要 Oracle 支持 | 额外下载 `impl-oracle`，放入 `impls/oracle/builtin-toolkit/` |

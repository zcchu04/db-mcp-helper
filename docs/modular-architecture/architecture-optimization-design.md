# DB MCP Helper — 模块化分发架构优化设计文档

> 配套文档：`feasibility-analysis.md`（可行性分析）、`implementation-plan.md`（实施计划）。
> 本文承接《MCP 服务实现管理设计方案》：把"MCP 服务实现"从硬编码升级为可管理组件之后，进一步把"MCP 服务的运行时环境"也提升为同级可管理组件，并在 GitHub Actions 产出多类型产物。

---

## 1. 背景与目标

当前工程（`build.yml` / `stage-resources.*` / `Installer`）把"helper 本体、MCP 服务实现、MCP 服务运行时"三者**耦合**进一个全量包：

- `build.yml` 经 Tauri 打出**单一全量产物**（helper + Oracle toolkit + jlink 运行时 + mcp-tap），MySQL 为可选、当前 CI 未纳入。
- Oracle toolkit 与 helper 向导**共用同一个 jlink JRE**（`dist/runtime` → `shell/bundle/runtime` 兜底 JDK），`Installer.resolveJava` 用这一份 JRE 既启动向导也启动 Oracle toolkit。
- MySQL server 的 Node 运行时（`NODE_RUNTIME_ZIP` → `runtime/mysql/node`）虽已可独立打包，但未被当成一等组件管理。

目标：把安装器从"单体全量包"重构为**模块化分发**——一个恒定 `helper core` + 可插拔的 `实现(implementation)` 与 `运行时(runtime)`，二者都能：

1. 在 GitHub Actions **分别打包**为不同产物类型（全量 / 不含实现不含运行时 / 含实现不含运行时 / 独立运行时包）；
2. 在已安装系统中**独立管理**：列出、从源更新、上传替换（实现，前方案已设计）、本地选择并校验兼容版本、从本项目 Release 下载独立运行时包。

---

## 2. 当前架构回顾（代码为证）

| 关注点 | 现状 | 位置 |
|---|---|---|
| 打包入口 | Tauri 桌面壳，单一全量产物 | `build.yml:1-186`（Tauri build `--bundles nsis/dmg/deb`） |
| 资源内嵌 | 按 5 个源拷贝：TOOLKIT_SRC/TAP_JAR/RUNTIME_ZIP 必选，MYSQL_TOOLKIT_SRC/NODE_RUNTIME_ZIP 可选 | `stage-resources.cmd:10-13,30-55`、`stage-resources.sh:11-13,27-51` |
| 实现落地 | `deployToolkit` 把 `toolkit/<dbId>/<file>` 释放到安装目录 | `Installer.java:92-101` |
| 运行时落地 | `deployRuntime`（共享 jlink JRE）→ `baseDir/runtime/`；`deployDbRuntime`（Node）→ `<dbId>/runtime/node` | `Installer.java:68-111` |
| 运行时解析 | `runtimeJava` / `resolveJava` 优先返回 `baseDir/runtime/bin/java`，否则回退系统 JVM | `Installer.java:151-161` |
| 多实现声明 | `DbAdapter.mcpServerOptions()` 仅含工具/选择信息，**无 artifact 与运行时映射** | `DbAdapter.java:52-54` |
| 前端运行时页 | 侧栏已有 `data-route="runtime"`（"Skill 与运行时"），可挂载运行时管理 UI | `index.html:38` |

**核心耦合点**：`OracleAdapter.buildCommand`（`OracleAdapter.java:114-122`）直接用 `resolveJava(baseDir)` 启动 Oracle toolkit，即 toolkit 复用 helper 的 jlink JRE；二者在物理上是同一份。这恰是用户所说"mcp 服务的运行时环境，非本 helper 的运行时"需要解耦之处。

---

## 3. 目标架构：三层模块化模型

```
┌──────────────────────────────────────────────────────────────┐
│  HELPER CORE（恒定，必装）                                      │
│  Tauri 壳 + SetupMain(wizard) + mcp-tap + helper 自身运行时     │
│  （仅用于启动向导/代理；不含任何 MCP 服务实现与服务器运行时）     │
└───────────────┬───────────────────────────┬───────────────────┘
                │ 引用                         │ 引用
        ┌───────▼────────┐            ┌───────▼────────┐
        │ IMPLEMENTATION  │            │   RUNTIME       │
        │  MCP 服务实现    │            │  MCP 服务运行时 │
        │ (per dbId/impl)  │            │ (per 运行时类型) │
        │  oracle jar      │            │  jre(Oracle)    │
        │  mysql benborla  │            │  node(MySQL)    │
        │  mysql naga      │            │                 │
        └─────────────────┘            └─────────────────┘
```

- **HELPER CORE**：始终包含，且**不再内含**任何实现/服务器运行时（或仅含"兜底"最小启动 JRE）。
- **IMPLEMENTATION**：每个 `dbId/impl` 一个制品（jar 或目录），承接前方案的管理能力（列出/源更新/上传替换/备份回滚）。
- **RUNTIME**：按"运行时类型"组织，与实现解耦：
  - `jre`：Oracle toolkit 运行所需的 JRE（≥17，含 `jdk.httpserver`）。
  - `node`：MySQL server 运行所需的 Node（≥18，naganpm 为 ESM）。

**关键解耦决策**：`helper.core` 与 `oracle.runtime(jre)` 在**逻辑上是两个槽位**，物理上 v1 可共用同一份 jlink JRE（helper 用其启动向导，Oracle toolkit 复用其执行 jar）。这样"helper-only"包仍自带启动 JRE，而"独立 jre 运行时包"只是同一 JRE 的另一种分发形态，避免体积翻倍。"独立运行时包"的意义在于：用户可**单独下载/替换**服务器运行时（升级 JRE、换 Node 版本）而无需重装 helper。

---

## 4. 组件模型与声明

### 4.1 适配器元数据扩展

在 `DbAdapter` 上新增两个概念（不改动既有工具/选择逻辑）：

```java
/** 每个实现 = 一个可管理制品 */
public final class ImplMeta {
    String id;            // 如 builtin-toolkit / benborla29 / naganpm
    String displayName;
    String artifact;      // 相对 <dbId>/toolkit/ 的路径
    String artifactType;  // "jar" | "dir"
    String version;
    String sourceUrl;     // 可选：实现更新源
    String runtime;       // 该实现依赖的运行时类型："jre" | "node"（关联 4.2）
}

/** 每种运行时类型 = 一个可管理运行时 */
public final class RuntimeMeta {
    String id;            // "jre" | "node"
    String kind;          // "jre" | "node"
    String minVersion;    // "17" / "18"
    List<String> modules; // jre 需含模块，如 ["jdk.httpserver"]
    String sourceUrl;     // 可选：独立运行时包下载源
    boolean bundled;      // 全量包是否内含
}
```

各适配器声明（例）：
- `OracleAdapter`：`impls=[builtin-toolkit→oracle-db-mcp-toolkit-1.0.0.jar/jar]`，`runtime="jre"`；`RuntimeMeta{jre,17,[jdk.httpserver]}`。
- `MySqlAdapter`：`impls=[benborla29→mysql-mcp-server/dir, naganpm→mysql-naga-mcp-server/dir]`，`runtime="node"`；`RuntimeMeta{node,18,[]}`。

### 4.2 状态模型扩展（`State`）

```java
public Map<String, ImplState> impls = new LinkedHashMap<>();   // key=dbId/implId
public Map<String, RuntimeState> runtimes = new LinkedHashMap<>(); // key=runtimeId
// ImplState: version, updatedAt, sourceUrl, backups[]
// RuntimeState: kind, version, source("bundled"|"local"|"downloaded"),
//              path, updatedAt, compatible(bool)
```

### 4.3 部署路径解析

- 实现制品：`Installer.toolkitPath`（已有）→ `<dbId>/toolkit/<artifact>`。
- 运行时：`baseDir/runtime/<runtimeId>/`（新增 `Installer.runtimePath(baseDir, runtimeId)`），替代当前 `baseDir/runtime/`（jre）与 `<dbId>/runtime/node`（node）的分散位置，统一按 runtimeId 收纳。
- `buildCommand` 改造：`OracleAdapter` 改为 `Installer.runtimePath(baseDir,"jre")/bin/java -jar toolkit...`；`MySqlAdapter` 改为 `Installer.runtimePath(baseDir,"node")/node ...`。`resolveJava` 退化为"仅 helper 自身启动用"。

---

## 5. GitHub Actions 多类型产物矩阵（`build.yml` 改造）

### 5.1 构建阶段拆分

把 `build.yml` 拆为"通用构建"与"按变体打包"两层：

1. **通用构建（一次）**：checkout oracle/mcp → 构建 toolkit jar；构建 mcp-tap；构建 jlink 运行时 `dist/runtime`+`runtime.zip`；构建 fat jar `db-mcp-setup.jar`；（可选）构建 MySQL toolkit + Node 运行时。产出中间件到 `dist/`。
2. **按变体打包（矩阵）**：对每个 `variant` 设置 `stage` 标志，调用 `stage-resources` 控制包含项，再 `tauri build`，上传带变体后缀的产物。

### 5.2 变体定义

| variant | 包含 helper core | 含实现 | 含服务器运行时 | 产物名后缀 |
|---|---|---|---|---|
| `full` | ✓ | ✓（oracle + mysql 可选） | ✓（jre + node） | 无（默认全量） |
| `helper-only` | ✓ | ✗ | ✗ | `-core` |
| `helper-impls` | ✓ | ✓ | ✗ | `-impls` |
| `runtime-jre` | ✗（独立包） | ✗ | jre 单独 | `runtime-jre` |
| `runtime-node` | ✗（独立包） | ✗ | node 单独 | `runtime-node` |

> 说明：`full` 与当前产物一致，保证向后兼容；其余四种为新增。独立运行时包（`runtime-jre/node`）是**纯运行时 zip**，不含 helper，供已装系统"下载安装运行时"使用。

### 5.3 `stage-resources` 参数化

在现有 5 个源变量基础上，新增"包含开关"让 `helper-only` / `helper-impls` 成立：

- `INCLUDE_ORACLE_TOOLKIT`（当前强制，改为可关；关闭时 Oracle 实现需用户后续下载/上传，对应前方案）
- `INCLUDE_MYSQL_TOOLKIT`、`INCLUDE_JRE_RUNTIME`、`INCLUDE_NODE_RUNTIME`

`stage-resources.*` 据此决定是否拷贝对应源；`build.yml` 用 `env:` 矩阵把开关透传。

### 5.4 产物上传与 Release

- `upload-artifact` 按 `name: DB-MCP-Helper-<OS>-<variant>` 上传。
- Release job 除三平台全量外，额外发布 `runtime-jre` / `runtime-node` 两个独立 zip，供"从 Release 下载运行时"功能消费。

---

## 6. 运行时环境配置 / 安装逻辑

新增 `RuntimeManager`（或并入 `Installer`）：

### 6.1 本地运行时选择与版本校验

```
RuntimeManager.detectLocal(runtimeId, path):
  kind = meta(runtimeId).kind
  if kind=="jre":  exec <path>/bin/java -version → 解析 "17.x"; 校验 meta.modules ⊆ java --list-modules
  if kind=="node": exec <path>/node --version → 解析 "v18.x"
  return {compatible, actualVersion, issues[]}
```

校验规则来自 `RuntimeMeta.minVersion` / `modules`，**不信任用户输入路径**，仅执行 `--version` 与 `--list-modules`（只读、低危）。

### 6.2 从 Release 下载独立运行时包

```
RuntimeManager.installFromRelease(runtimeId, packageUrl):
  校验 packageUrl 属本项目 GitHub（白名单 host：github.com/<owner>/<repo>/releases）
  HTTP GET → 临时文件 → 校验扩展名/内容 → 解压到 baseDir/runtime/<runtimeId>/
  写 state.runtimes[runtimeId] = {source:"downloaded", path, version, compatible:true}
```

复用 `Installer.unzip` 的"剥顶层目录 + 防穿越"逻辑（`Installer.java:163-207`）。

### 6.3 选择解析（构建命令时用）

`buildCommand` 调用 `RuntimeManager.resolve(runtimeId)` → 返回最终可执行路径，优先级：
1. 用户显式本地选择（state 中 `source:"local"`）；
2. 从 Release 下载的（`source:"downloaded"`）；
3. 全量包自带的（`source:"bundled"`）；
4. 系统 PATH 上的兼容版本（可选，需校验）。

### 6.4 前端

扩展侧栏 `runtime`（Skill 与运行时）页（`index.html:38`）：每个运行时类型一张卡，展示当前来源/版本/兼容状态，提供"选择本地目录""从 Release 下载""校验"操作；与"实现管理"页共享同一套列表/上传/备份交互。

---

## 7. 部署后目录布局（目标）

```
baseDir/
├── runtime/                  # 服务器运行时（按 runtimeId 收纳，替代旧 runtime/ + <dbId>/runtime/node）
│   ├── jre/bin/java ...       # oracle.runtime（全量包自带 / 下载 / 本地）
│   └── node/node[.exe] ...    # mysql.runtime
├── tap/mcp-tap.jar
├── db-mcp-setup.jar          # helper core（Tauri 壳内）
├── state.json                # 含 impls[] + runtimes[]
└── <dbId>/
    ├── toolkit/<artifact>    # 实现（前方案管理对象）
    └── instance/<env>/...
```

---

## 8. 与"实现管理"方案的关系

本方案是前一方案的**平行扩展**：实现与运行时是**同一套组件管理范式**的两个实例。

| 维度 | 实现（IMPLEMENTATION） | 运行时（RUNTIME） |
|---|---|---|
| 声明 | `ImplMeta`（artifact/type/version/sourceUrl） | `RuntimeMeta`（kind/min/modules/sourceUrl） |
| 状态 | `state.impls[]` | `state.runtimes[]` |
| 管理 API | `/api/impls/*`（列表/更新/上传/备份/回滚） | `/api/runtimes/*`（同上 + 校验） |
| 产物 | 内嵌于 full/helper-impls；可源更新/上传 | 内嵌于 full；独立 `runtime-*` 包；可源下载/本地选择 |
| 替换前 | `.bak.<ts>` 备份 | 同左 |

二者共用 `ImplManager`/`RuntimeManager` 的"备份→取包→校验→替换→写状态→失败回滚"骨架，统一在 `SetupMain` 注册路由，在前端统一交互风格。

---

## 9. 未决点（需确认）

1. **helper.core 是否自带最小启动 JRE**：若自带，则 `helper-only` 仍可离线启动向导（推荐）；若不带，则要求用户机器有 JRE 17+。
2. **独立运行时包是否也跨三平台**：是（每平台各自 jre/node 包）；包名带平台标记。
3. **MySQL 在 CI 是否默认纳入 full**：当前 `build.yml` 未设 `MYSQL_TOOLKIT_SRC/NODE_RUNTIME_ZIP`，full 仅含 Oracle。建议 full 同时含 MySQL 两实现 + node 运行时。

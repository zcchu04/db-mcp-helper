# DB MCP Helper — 模块化分发架构实施计划

> 配套：`architecture-optimization-design.md`（设计）、`feasibility-analysis.md`（可行性）。
> 约定：所有路径相对仓库根；改动以"新增/修改"标注；验证命令在本地 Windows（Git Bash / PowerShell）与 CI 双轨。

---

## 0. 总览与里程碑

```
P0 元数据与部署解耦 ──► P1 GitHub Actions 多类型产物 ──► P2 运行时配置/安装（本地+下载） ──► P3 版本兼容强制与收尾
```

| 阶段 | 目标 | 退出标准 |
|---|---|---|
| P0 | 适配器声明 ImplMeta/RuntimeMeta；Installer 按 runtimeId 落地；状态扩字段 | 本地构建可部署"实现+运行时"到新布局，向导自测通过 |
| P1 | `build.yml` 变体矩阵产出 full/core/helper-impls/runtime-jre/runtime-node | CI 三平台 × 5 变体产物可下载；Release 含独立运行时包 |
| P2 | 前端"实现管理"+"运行时管理"；本地选择+版本校验；从 Release 下载 | 向导内可管理实现与运行时，含备份/回滚/校验 |
| P3 | 版本兼容强制、双链路治理、文档与回滚 | 全功能可用，安全警示到位，旧包兼容 |

---

## P0 — 元数据与部署解耦（后端先行）

### P0.1 新增元数据类
- **新增** `setup-app/.../setup/ImplMeta.java`：`id, displayName, artifact, artifactType("jar"|"dir"), version, sourceUrl, runtime`。
- **新增** `setup-app/.../setup/RuntimeMeta.java`：`id, kind("jre"|"node"), minVersion, modules[], sourceUrl, bundled`。
- **修改** `DbAdapter.java`：在 `McpServerOption` 增加 `implMeta`/`runtime` 关联；增加 `List<RuntimeMeta> runtimeMetas()` 默认空。
- **修改** `OracleAdapter.java`：构造 `ImplMeta(builtin-toolkit, oracle-db-mcp-toolkit-1.0.0.jar, jar, 1.0.0, <sourceUrl>, "jre")`；`runtimeMetas()=[RuntimeMeta("jre","jre","17",["jdk.httpserver"],<jreSrc>,true)]`。
- **修改** `MySqlAdapter.java`：两个 `ImplMeta`（benborla29→mysql-mcp-server/dir，naganpm→mysql-naga-mcp-server/dir），`runtime="node"`；`runtimeMetas()=[RuntimeMeta("node","node","18",[],<nodeSrc>,true)]`。

### P0.2 Installer 运行时路径与部署
- **修改** `Installer.java`：
  - 新增 `runtimePath(Path baseDir, String runtimeId)` → `baseDir/runtime/<runtimeId>`（统一收纳，替代旧 `baseDir/runtime/` 与 `<dbId>/runtime/node` 分散）。
  - 新增 `deployServerRuntime(baseDir, runtimeId, adapter)`：从资源 `runtime/<runtimeId>` 释放到 `runtimePath`（复用 `extractOrCopy`）。
  - `deployRuntime`（共享 JRE）保留作 helper 自身启动用，改名清晰为 `deployHelperRuntime` 避免歧义。
  - `deployDbRuntime` 改为调用 `deployServerRuntime(baseDir,"node",adapter)`。
  - `buildCommand` 两适配器改为引用 `Installer.runtimePath(baseDir, meta.runtime)` 而非 `resolveJava`（见 P0.3）。
- **修改** `State.java`：新增 `impls` / `runtimes` 段与 `ImplState`/`RuntimeState`/`Backup` 内部类（保持 `load` 迁移兼容：缺失新段返回 null 安全）。

### P0.3 适配器 buildCommand 改造
- **修改** `OracleAdapter.java:114-122`：`java` 取自 `Installer.runtimePath(baseDir,"jre")/bin/java`（full 下即自带 JRE；partial 下取自用户下载/本地）。
- **修改** `MySqlAdapter.java:189`：`nodeExe` 取自 `Installer.runtimePath(baseDir,"node")/node[.exe]`。

### P0.4 验证
- 本地：`mvn package -DskipTests -pl setup-app`；启动向导 → 部署 Oracle → 确认 `baseDir/runtime/jre/bin/java` 启动 toolkit；部署 MySQL → 确认 `baseDir/runtime/node/node` 启动 server。
- 回归：旧布局 `baseDir/runtime/`（helper JRE）仍存在且向导可启动（P0 不破坏 helper 自身运行）。

---

## P1 — GitHub Actions 多类型产物

### P1.1 `stage-resources.*` 加包含开关
- **修改** `stage-resources.cmd` / `stage-resources.sh`：新增 `INCLUDE_ORACLE_TOOLKIT` / `INCLUDE_MYSQL_TOOLKIT` / `INCLUDE_JRE_RUNTIME` / `INCLUDE_NODE_RUNTIME`（默认开；关闭时跳过对应拷贝，且 Oracle toolkit 缺失不再 `exit 1` 而是 `WARN`）。

### P1.2 `build.yml` 变体矩阵
- **修改** `.github/workflows/build.yml`：
  - `matrix.include` 增加 `variant` 维度：`full / core / helper-impls / runtime-jre / runtime-node`，并带 `includes` 标志（如 `oracle:true, mysql:true, jre:true, node:true` 按变体设 false）。
  - "Stage resources" 步用 `env:` 透传包含开关。
  - Tauri 构建步：`if: variant != runtime-jre && variant != runtime-node`（独立运行时包跳过 Tauri）。
  - 独立运行时包步（新增）：`if: variant==runtime-jre`，`zip dist/runtime` → 上传 `runtime-jre-<os>.zip`；`runtime-node` 同理。
  - 上传步：`name: DB-MCP-Helper-${{matrix.os}}-${{matrix.variant}}`。
  - Release 步 `files:` 追加 `release-files/*runtime-jre*`、`*runtime-node*`。

### P1.3 验证
- 推送 tag `v*` → Release 含：`DB MCP Helper_*_x64-setup.exe`（full）、`*-core`、`*-helper-impls`、独立 `runtime-jre`/`runtime-node` zip。
- 本地：`act` 或手动跑 `build.yml` 单 variant，确认 partial 包不含 toolkit/运行时（解包核对 `shell/bundle`）。

---

## P2 — 运行时配置 / 安装（前端 + 管理面）

### P2.1 后端管理 API
- **新增** `setup-app/.../setup/RuntimeManager.java`：`detectLocal(runtimeId,path)`、`installFromRelease(runtimeId,url)`、`resolve(runtimeId)`、`listBackups`/`restore`，骨架复用前"实现管理"方案的备份→取包→校验→替换→写状态→失败回滚。
- **修改** `SetupMain.java`：注册 `/api/impls/*`（前方案）+ `/api/runtimes/*`（list/update/upload/restore/backups/detect）；`adapters()` 序列化补 `ImplMeta`/`RuntimeMeta`。
- **修改** `Installer.unzip`：确认可被 RuntimeManager 复用（已防穿越）。

### P2.2 前端
- **修改** `index.html`：侧栏 `runtime`（Skill 与运行时）保留；新增 `impls`（实现管理）路由项（沿用既有导航样式）。
- **修改** `app.js`：`renderMain` 分支 + `renderImpls()`（前方案）+ `renderRuntimes()`：每运行时类型卡含 当前来源/版本/兼容徽标、"选择本地目录""从 Release 下载""校验"按钮；上传/更新/回滚交互与实现管理共用组件。

### P2.3 验证
- 向导内：Oracle 实现显示"未部署"时引导上传/从源更新；运行时页选择本地 JDK17 → 显示兼容；下载 `runtime-jre` → 部署后 `baseDir/runtime/jre` 更新，state.runtimes 记录来源。
- 替换前自动 `.bak.<ts>`；回滚可用。

---

## P3 — 版本兼容强制与收尾

### P3.1 版本兼容强制
- **修改** `RuntimeManager.resolve`：若检测到的运行时不满足 `RuntimeMeta.minVersion/modules`，在 `buildCommand` 前拦截并返回明确错误（替代 P2 的仅警告），向导提示"运行时不兼容，请升级/重选"。
- **修改** `OracleAdapter`/`MySqlAdapter`：在 `pingArguments`/自检前断言运行时兼容。

### P3.2 双链路治理（必做）
- **决策**：以 `build.yml`（Tauri/CI）为唯一真相源。
- **修改** `package-windows.ps1`：头部注释明确"本地全量调试用，不等价 CI 变体"；或在本期直接废弃并删除（需用户确认，避免误用产生不一致包）。
- 同步 `README.md`：更新"产物类型"与"从 Release 获取运行时"说明。

### P3.3 文档与安全
- **修改** `docs/modular-architecture/*.md`：随实现更新（设计/可行性/计划三件套保持同步）。
- **修改** 向导 UI：上传/下载前展示安全警示（仅可信来源）。
- **新增** `docs/modular-architecture/CHANGELOG.md`（可选）：记录变体命名与兼容矩阵。

### P3.4 验证
- 全量 + 各 partial 安装后向导可启动、可实现/运行时管理完整可用。
- 故意选不兼容 Node（如 v14）→ 自检拦截并提示。
- 回滚 `.bak` 生效；状态持久化正确（重启向导后保持）。

---

## 文件改动总清单

| 文件 | 阶段 | 动作 |
|---|---|---|
| `setup-app/.../setup/ImplMeta.java` | P0 | 新增 |
| `setup-app/.../setup/RuntimeMeta.java` | P0 | 新增 |
| `setup-app/.../setup/RuntimeManager.java` | P2 | 新增 |
| `DbAdapter.java` | P0 | 修改（元数据接口） |
| `OracleAdapter.java` | P0/P3 | 修改（ImplMeta + buildCommand + 兼容断言） |
| `MySqlAdapter.java` | P0/P3 | 修改（ImplMeta + buildCommand + 兼容断言） |
| `Installer.java` | P0 | 修改（runtimePath/deployServerRuntime/路径统一） |
| `State.java` | P0 | 修改（impls/runtimes 段） |
| `SetupMain.java` | P2 | 修改（API + 序列化） |
| `stage-resources.cmd` / `stage-resources.sh` | P1 | 修改（包含开关） |
| `.github/workflows/build.yml` | P1 | 修改（变体矩阵 + 独立运行时包 + Release） |
| `index.html` | P2 | 修改（impls 路由） |
| `app.js` | P2 | 修改（renderImpls/renderRuntimes） |
| `package-windows.ps1` | P3 | 修改/废弃（双链路治理） |
| `README.md` | P3 | 修改（产物说明） |

---

## 回滚策略

- 每阶段可独立回滚：P0/P2 改动集中在新增类与适配器，不影响现有 full 包（旧 `baseDir/runtime/` 保留）。
- P1 若 CI 矩阵失败，可先合 `full` 单变体，其余变体后续补；不影响已发布 full。
- 双链路治理（P3）若暂不能废弃 `package-windows.ps1`，先加注释隔离，不阻断 CI。
- 实现/运行时替换均带 `.bak.<ts>`，失败时自动回滚，用户可手动恢复。

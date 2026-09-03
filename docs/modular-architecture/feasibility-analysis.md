# DB MCP Helper — 模块化分发架构可行性分析

> 配套：`architecture-optimization-design.md`（设计）、`implementation-plan.md`（计划）。
> 结论先行：**可行**，建议分四期落地；最大风险是"双打包链路并存"与"多产物带来的用户认知负担"，均可通过收敛与默认推荐规避。

---

## 1. 已具备的基础（降低工作量）

| 能力 | 现状 | 证据 | 复用方式 |
|---|---|---|---|
| 多实现声明 | `DbAdapter.mcpServerOptions()` 已支持每库多实现 | `DbAdapter.java:52-54`、`MySqlAdapter.java:115-129` | 扩展 `ImplMeta` 即可 |
| 资源分源拷贝 | 5 个源变量，MySQL/Node 已可选 | `stage-resources.cmd:30-55`、`stage-resources.sh:27-51` | 加"包含开关"即支持全量/部分 |
| 解压与防穿越 | `Installer.unzip` 剥顶层目录 + 越界断言 | `Installer.java:163-207` | 运行时包/实现包复用同一逻辑 |
| 运行时解析 | `runtimeJava`/`resolveJava` 已抽象 | `Installer.java:151-161` | 改为按 runtimeId 解析 |
| 状态模型 | `State` 已可扩展（含迁移逻辑） | `State.java:72-130` | 增 `impls`/`runtimes` 段 |
| 前端运行时页 | 侧栏已有 `runtime` 路由 | `index.html:38` | 挂载运行时管理 UI |
| CI 打包 | Tauri 三平台 + Release | `build.yml` | 加 `variants` 矩阵即可 |

**结论**：核心积木已存在，主要是"解耦 + 参数化 + 新增管理面"，非从零搭建。

---

## 2. 需新增的能力（增量）

1. 适配器元数据：`ImplMeta` + `RuntimeMeta`（含 runtime 依赖关联）。
2. `RuntimeManager`：本地检测（版本/模块校验）、从 Release 下载、解析选择。
3. `build.yml` 变体矩阵：通用构建 + 按 variant 打包/上传；独立运行时 zip 产出。
4. 前端"实现管理"+"运行时管理"两页（共用组件管理交互）。
5. `buildCommand` 改造：实现 → 其依赖的运行时（按 runtimeId 路径）。

---

## 3. 关键技术可行性

### 3.1 JRE 耦合解耦（中风险，可控）
当前 Oracle toolkit 直接复用 helper 的 jlink JRE（`OracleAdapter.java:114-122`）。解耦为"两个逻辑槽位、物理可共用"：
- v1 物理上仍共用同一 JRE（helper 启动向导、toolkit 复用），仅逻辑上区分 `helper.runtime` 与 `oracle.runtime(jre)`；
- `runtime-jre` 独立包 = 同一 JRE 的另一种分发，用户可单独升级/替换 Oracle 的 JRE 而不动 helper。
- **避免体积翻倍**：`full` 仅含 1 份 JRE；`helper-impls` 含 helper 启动 JRE，Oracle 运行时由用户按需下载 `runtime-jre`。
- 可行性：高。`buildCommand` 改为 `Installer.runtimePath(baseDir,"jre")`，该路径在 full 下指向自带 JRE，在 partial 下指向用户下载/本地选择的 JRE。

### 3.2 版本/模块兼容性检测（中风险）
- JRE：`<jre>/bin/java -version` 解析主版本 ≥17；`java --list-modules | find jdk.httpserver` 校验模块。均为只读探针，安全。
- Node：`<node>/node --version` 解析 ≥18。naganpm 为 ESM 需 Node 18+，benborla29 16+ 即可，按 `RuntimeMeta.minVersion` 判定。
- 风险：跨平台版本字符串格式差异（如 `openjdk 17.0.9`、`java version "17"`、Adoptium/Temurin 前缀）。缓解：用正则取首个整数主版本 + 已知厂商前缀白名单；检测失败给"未知，请手动确认"而非硬拒。

### 3.3 Tauri 多变体打包策略（中风险）
- 同一 `shell/bundle` 内容决定产物内容；按 variant 设置 `stage-resources` 开关后重跑 `tauri build` 即可产出不同包。
- 独立运行时包（`runtime-jre/node`）**无需 Tauri 构建**，直接 `zip` 对应目录上传，成本极低。
- 风险：每次 `tauri build` 耗时数分钟；矩阵变体数 = 3 平台 × 5 variant = 15 次构建，CI 时间显著增长。缓解：
  - `core`/`helper-impls`/`full` 三者的差异只在 `shell/bundle` 内容，`tauri build` 可并行（matrix `os` × `variant`）；
  - 或改为"先构建一个 full bundle，再用脚本裁剪成 partial"（解包→删实现/运行时→重包），缩短 CI，但实现复杂、跨平台解包/重包易碎，不推荐首版。
- 可行性：高（接受 CI 时长增长，或用较大 runner / 缓存）。

### 3.4 双打包链路并存（高风险，须决策）
工程里**存在两条打包线**：
- `build.yml`：Tauri 桌面壳（CI 实际使用的产出）。
- `package-windows.ps1`：jpackage + Inno Setup（本地 Windows 打包，早期会话重构过）。

多类型产物矩阵若只加在 `build.yml`，`package-windows.ps1` 会与之失同步，形成技术债。
**决策建议**：
- 短期：多类型矩阵仅在 `build.yml` 实现（CI 唯一真相源）；`package-windows.ps1` 标注"仅本地全量调试用，不等价于 CI 变体"。
- 长期：废弃 `package-windows.ps1` 或让其调用 Tauri 流程，消除双链路。

### 3.5 产物存储与留存（低风险）
- GitHub Actions `upload-artifact` 默认 90 天留存，适合"CI 内下载安装"中转；但前端"从 Release 下载运行时"应指向 **GitHub Release 资产**（长期），而非 artifact（过期）。
- Release job 已存在（`build.yml:188-237`），扩展其 `files:` 把 `runtime-jre/node` zip 一并发布即可。
- 风险：独立运行时包体积（jre ≈ 30–50MB，node ≈ 30MB）使 Release 体积上升。缓解：运行时包按需发布，不强制随每 tag。

### 3.6 跨平台一致性（低风险）
路径解析全部走 `Installer.runtimePath`/`toolkitPath`（已有 `baseDir.resolve` + OS 判断，如 `Installer.java:151-155` 的 `win?java.exe:java`），前端仅传 runtimeId，不拼路径。Windows 文件锁问题沿用前方案"先备份 move，失败给明确提示"。

---

## 4. 风险与缓解汇总

| 风险 | 等级 | 缓解 |
|---|---|---|
| 双打包链路失同步 | 高 | CI 为唯一真相源；标注/废弃 `package-windows.ps1` |
| 多产物用户认知负担 | 中 | 默认推荐 `full`；partial 明确标注"需另行获取实现/运行时"；向导内引导下载 |
| CI 时长增长（15 构建） | 中 | 独立运行时包免 Tauri 构建；matrix 并行；缓存依赖 |
| 版本检测跨平台误判 | 中 | 宽松解析 + 手动确认兜底；只警告不硬拒 |
| 下载运行时/实现 = 执行不可信代码 | 高(安全) | 见 §5 |
| Windows 文件锁导致替换失败 | 中 | 备份先于替换；失败回滚 + 明确提示 disable 连接器 |

---

## 5. 安全评估（重点）

被管理/下载的"实现"与"运行时"最终都会被**直接执行**（`java -jar` 或 `node ...`）。这是本架构最敏感的信任边界：

1. **来源白名单**：从 Release 下载时，`packageUrl` 必须落在 `github.com/<owner>/<repo>/releases` 下（host + 路径白名单），拒绝任意 URL；实现的上传同理仅接受可信本地文件。
2. **传输完整性**：GitHub Release 资产走 HTTPS；实现上传为本地文件，不联网。可选：Release 资产附带 `sha256` 校验（在 `RuntimeMeta`/`ImplMeta` 声明 expected hash），下载后校验。
3. **明确警示**：向导在"上传替换""从源下载"前强制展示："该组件将被直接执行，仅使用可信来源"。
4. **沙箱/权限**：运行时尚无额外沙箱；属已知接受范围（与当前全量包行为一致），但应在文档中声明。

---

## 6. 可行性结论

- **技术上可行**：所有积木已具备，新增量为"元数据 + 管理面 + CI 矩阵"，无不可逾越障碍。
- **主要成本**在 CI 时长与双链路治理，均有明确缓解。
- **安全**是头等约束，靠来源白名单 + 警示兜底。
- **建议**：采用分阶段实施（见 `implementation-plan.md`），先打通"元数据 + 局部部署 + 单变体"，再扩到全矩阵与下载/本地选择。

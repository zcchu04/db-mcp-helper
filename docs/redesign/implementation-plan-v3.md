# DB MCP Helper v0.3 实施计划文档

> 版本：v0.3.0-draft | 日期：2026-09-03

---

## 一、实施总览

### 1.1 分期策略

```
Phase 1 (核心能力)          Phase 2 (下载与运行时)        Phase 3 (CI与迁移)
─────────────────────       ─────────────────────        ──────────────────
• ImplRegistry 数据模型      • ArtifactDownloader         • CI 产物分层
• 实现管理 REST API          • 运行时管理 REST API         • 目录迁移工具
• 上传替换 + bak 备份        • 本地运行时选择               • 老用户自动迁移
• 前端：实现管理页            • 前端：运行时配置页            • 向导 Step1 状态标记
• 前端：上传/回滚交互         • 前端：从 GitHub 下载         • 兼容性符号链接(可选)

预计: 5-7 个工作日            预计: 4-5 个工作日             预计: 3-4 个工作日
```

### 1.2 里程碑

| 里程碑 | 目标 | 验收标准 |
|--------|------|---------|
| M1 | ImplRegistry 核心 | impls.json 读写、bak/restore 单测通过 |
| M2 | 实现管理 API + UI | 上传 zip 替换实现 → bak 备份 → 回滚 全流程通 |
| M3 | ArtifactDownloader | 从 GitHub Release 下载产物 + SHA-256 校验 |
| M4 | RuntimeManager | 本地运行时选择 + 兼容性检测 + 切换 |
| M5 | CI 分层产物 | GitHub Actions 产出 full/slim/runtime/impl 包 |
| M6 | 迁移 + 集成 | v0.2 → v0.3 自动迁移，全量包开箱即用 |

---

## 二、Phase 1：实现管理核心（5-7 工作日）

### 2.1 Task 1.1 — ImplRegistry 数据模型与持久化

**涉及文件**：
- `setup-app/src/main/java/com/dbmcp/setup/ImplRegistry.java` (新增)
- `setup-app/src/main/java/com/dbmcp/setup/ImplInfo.java` (新增)

**工作内容**：
1. 定义 `ImplInfo` record：version, source, sourceUrl, installedAt, entryFile, runtimeKind, checksum, bakVersions
2. 实现 `ImplRegistry` 类：
   - `load()` / `save()` — 读写 `baseDir/impls/impls.json`
   - `get(dbId, serverId)` / `listAll()` — 查询接口
   - `register(dbId, serverId, ImplInfo)` — 注册/更新
3. 初始化逻辑：首次启动时从现有 toolkit 文件推断并生成 impls.json

**验收**：单测覆盖 load/save/register/get

---

### 2.2 Task 1.2 — bak 备份与恢复

**涉及文件**：
- `setup-app/src/main/java/com/dbmcp/setup/ImplRegistry.java` (扩展)
- `setup-app/src/main/java/com/dbmcp/setup/Installer.java` (扩展)

**工作内容**：
1. `bakImpl(dbId, serverId)` 方法：
   - 读取当前 ImplInfo 版本
   - 复制 `impls/<dbId>/<serverId>/` → `bak/<dbId>/<serverId>/<version>_<timestamp>/`
   - 记录到 ImplInfo.bakVersions
2. `restoreBak(dbId, serverId)` 方法：
   - 取 bakVersions 最后一个条目
   - 删除当前目录
   - 复制 bak 目录回 impls/
   - 更新 impls.json
3. `pruneBak(dbId, serverId, keepCount)` 方法：
   - 保留最近 N 个 bak 版本，删除更早的

**验收**：单测覆盖 bak → restore 循环、prune 清理

---

### 2.3 Task 1.3 — 上传替换实现

**涉及文件**：
- `setup-app/src/main/java/com/dbmcp/setup/Installer.java` (新增 `installImplFromZip`)
- `setup-app/src/main/java/com/dbmcp/setup/SetupMain.java` (新增 API handler)
- `setup-app/src/main/resources/app.js` (前端上传逻辑)

**工作内容**：
1. REST API `POST /api/impls/{dbId}/{serverId}/upload`：
   - 接收 multipart file upload
   - 校验文件类型（.zip / .tar.gz / .jar）
   - 调用 `bakImpl()` 备份当前版本
   - 解压到 `impls/<dbId>/<serverId>/`
   - 校验 entryFile 存在
   - 更新 impls.json
2. 前端上传交互：
   - 文件选择器（限制 .zip, .jar, .tar.gz）
   - 上传进度条
   - 成功/失败 toast

**验收**：上传 zip → bak 旧版本 → 解压新版本 → impls.json 更新 → 实例启动使用新版本

---

### 2.4 Task 1.4 — 前端：实现管理页

**涉及文件**：
- `setup-app/src/main/resources/app.js` (新增 `pageImplementations()`)
- `setup-app/src/main/resources/styles.css` (新增样式)
- `setup-app/src/main/resources/index.html` (新增侧栏入口)

**工作内容**：
1. 侧栏新增 "MCP 服务实现" 导航项
2. 实现管理页布局：
   - 按数据源分组，每组下列出各实现
   - 每个实现卡片：版本、来源、安装时间、工具列表
   - 操作按钮：[从URL安装] [上传替换] [回滚] [删除bak]
3. 对话框：
   - "从URL安装"：输入 GitHub Release URL 或直接 asset URL
   - "上传替换"：文件选择 + 确认
   - "回滚"：选择目标版本 + 确认
4. 路由：`#/implementations`

**验收**：页面渲染正确、上传/回滚操作可用、状态实时更新

---

## 三、Phase 2：下载与运行时（4-5 工作日）

### 3.1 Task 2.1 — ArtifactDownloader

**涉及文件**：
- `setup-app/src/main/java/com/dbmcp/setup/ArtifactDownloader.java` (新增)
- `setup-app/src/main/java/com/dbmcp/setup/SetupMain.java` (新增 API handler)

**工作内容**：
1. `fetchLatestRelease()` — GET GitHub API，解析 tag_name / assets
2. `listArtifacts(release)` — 按 type + platform 分类 assets
3. `download(entry, callback)` — 流式下载到 temp 目录
4. `verify(file, sha256)` — SHA-256 校验
5. REST API:
   - `GET /api/releases/latest` — 返回 Release 信息
   - `GET /api/releases/latest/artifacts` — 返回产物列表
6. 从 URL 安装实现：
   - `POST /api/impls/{dbId}/{serverId}/install-url`
   - 下载 → 校验 → bak → 解压 → 注册

**验收**：能查询到最新 Release、列出产物、下载并校验

---

### 3.2 Task 2.2 — RuntimeManager

**涉及文件**：
- `setup-app/src/main/java/com/dbmcp/setup/RuntimeManager.java` (新增)
- `setup-app/src/main/java/com/dbmcp/setup/Cfg.java` (修改解析链)
- `setup-app/src/main/java/com/dbmcp/setup/Installer.java` (修改 resolveJava)
- `setup-app/src/main/java/com/dbmcp/setup/Prefs.java` (扩展 runtimeOverrides)

**工作内容**：
1. `resolveJava(req)` 优先级链：
   - prefs.runtimeOverrides.java → bundled runtimes/jre → system JAVA_HOME → PATH java
2. `resolveNode(req)` 优先级链：
   - prefs.runtimeOverrides.node → bundled runtimes/node → system PATH node
3. `checkCompatibility(dir, kind)` — 版本检测 + 模块检测
4. `setLocalRuntime(kind, path)` — 写入 prefs + 验证
5. `resetRuntimeOverride(kind)` — 清除 prefs 覆盖
6. 修改 `Installer.resolveJava()` 调用 RuntimeManager
7. 修改 `DbAdapter.buildCommand()` 使用 RuntimeManager 解析的路径

**验收**：
- 默认使用 bundled runtime（与当前行为一致）
- 设置本地覆盖后使用本地 runtime
- 兼容性检测正确报告版本/模块/架构

---

### 3.3 Task 2.3 — 前端：运行时配置页

**涉及文件**：
- `setup-app/src/main/resources/app.js` (新增/扩展 `pageRuntime()`)
- `setup-app/src/main/resources/styles.css` (新增样式)

**工作内容**：
1. 扩展现有 `#/runtime` 页面或新建 `#/runtime-config`
2. 运行时卡片：
   - 当前来源、版本、兼容性状态
   - [选择本地目录] — 文件选择器（目录）
   - [从GitHub下载] — 选择产物 → 下载 → 安装
   - [恢复默认] — 清除覆盖
3. 兼容性检测结果展示：
   - 版本 ✓/✗
   - 必要模块 ✓/✗
   - 架构 ✓/✗

**验收**：选择本地 Node 目录 → 检测兼容 → 切换成功 → 实例使用新 runtime

---

### 3.4 Task 2.4 — 从 GitHub 下载实现/运行时

**涉及文件**：
- `setup-app/src/main/resources/app.js` (集成下载 UI)
- `setup-app/src/main/java/com/dbmcp/setup/SetupMain.java` (进度推送)

**工作内容**：
1. 实现管理页 "检查更新" 按钮：
   - 查询 GitHub Release → 比对版本 → 提示可更新
2. "从GitHub下载" 流程：
   - 列出匹配当前 dbId/serverId 的产物
   - 用户选择 → 下载（带进度条）→ 自动安装
3. 运行时页 "从GitHub下载" 流程：
   - 列出匹配当前平台 + kind 的运行时产物
   - 用户选择 → 下载 → 解压 → 注册

**验收**：端到端从 GitHub 下载并安装实现/运行时

---

## 四、Phase 3：CI 与迁移（3-4 工作日）

### 4.1 Task 3.1 — GitHub Actions 产物分层

**涉及文件**：
- `.github/workflows/build.yml` (重构)
- `stage-resources.sh` / `stage-resources.cmd` (调整)

**工作内容**：
1. 拆分构建 job：
   - `build-core`：mcp-tap + setup-app
   - `build-impls`：各实现包（oracle-toolkit, mysql-all）
   - `build-runtimes`：各运行时包（jre, node）× 平台
   - `assemble`：组装 full / slim
   - `desktop`：Tauri 安装包（使用 slim）
2. 每个产物生成 `manifest.json`
3. Release job 上传所有产物 + `checksums.txt`

**验收**：
- tag push 触发完整构建
- Release 页面包含所有类型产物
- 每个产物 manifest.json 内容正确

---

### 4.2 Task 3.2 — 目录迁移工具

**涉及文件**：
- `setup-app/src/main/java/com/dbmcp/setup/Migrator.java` (新增)
- `setup-app/src/main/java/com/dbmcp/setup/SetupMain.java` (启动时检测)

**工作内容**：
1. `Migrator.needsMigration(baseDir)` — 检测旧布局
2. `Migrator.migrate(baseDir)` — 执行迁移：
   - 创建 `impls/` 目录结构
   - 复制 toolkit 文件到 `impls/`
   - 创建 `runtimes/` 目录结构
   - 复制 runtime 到 `runtimes/`
   - 生成 `impls.json`
   - 写入 `state.json` 迁移标记
3. 路径重写：
   - 修改 `Installer.toolkitPath()` → 指向 `impls/`
   - 修改 `Installer.dbRuntimePath()` → 指向 `runtimes/`
   - 确保 `buildCommand()` 使用新路径
4. 启动时自动检测并执行（幂等）

**验收**：
- v0.2 目录结构 → 自动迁移 → 所有实例正常启动
- 重复执行不产生副作用（幂等）
- 迁移日志完整

---

### 4.3 Task 3.3 — 向导 Step1 状态增强

**涉及文件**：
- `setup-app/src/main/resources/app.js` (修改 `setupStep1()`)

**工作内容**：
1. 每个 impl-card 查询 ImplRegistry 状态
2. 状态标记：
   - 未安装：灰色虚线边框 + "需下载" 标签
   - 有更新：蓝色脉冲边框 + "v1.3.0 → v1.4.0" 标签
   - 已最新：绿色 ✓ 角标
   - 缺运行时：橙色警告 + "需要 Node" 提示
3. 选择未安装的实现时：
   - "下一步" 按钮文案改为 "下载并安装"
   - Step 2 自动触发下载流程

**验收**：状态标记正确、未安装实现可一键下载安装

---

## 五、文件变更清单

### 5.1 新增文件

| 文件 | 模块 | 说明 |
|------|------|------|
| `ImplRegistry.java` | setup-app | 实现注册中心 |
| `ImplInfo.java` | setup-app | 实现信息 record |
| `RuntimeManager.java` | setup-app | 运行时管理器 |
| `ResolvedRuntime.java` | setup-app | 运行时解析结果 record |
| `ArtifactDownloader.java` | setup-app | GitHub 产物下载器 |
| `Migrator.java` | setup-app | v0.2→v0.3 迁移工具 |

### 5.2 修改文件

| 文件 | 变更范围 |
|------|---------|
| `Installer.java` | 新增 deployImpl/installImplFromZip/rollbackImpl；修改 toolkitPath/dbRuntimePath |
| `SetupMain.java` | 新增 REST API handler（impls/runtimes/releases）|
| `Cfg.java` | 修改 Java 解析链接入 RuntimeManager |
| `Prefs.java` | 新增 runtimeOverrides 字段 |
| `State.java` | 新增 migratedToV3 标记 |
| `app.js` | 新增实现管理页、运行时配置页、向导增强 |
| `styles.css` | 新增实现管理/运行时配置样式 |
| `index.html` | 新增侧栏导航项 |
| `build.yml` | 重构为分层产物构建 |
| `stage-resources.sh/cmd` | 调整资源暂存路径 |

---

## 六、测试策略

### 6.1 单元测试

| 测试类 | 覆盖 |
|--------|------|
| `ImplRegistryTest` | load/save/register/get/pruneBak |
| `RuntimeManagerTest` | resolveJava/resolveNode/checkCompatibility |
| `ArtifactDownloaderTest` | listArtifacts/verify (mock HTTP) |
| `MigratorTest` | needsMigration/migrate/idempotency |

### 6.2 集成测试

| 场景 | 步骤 |
|------|------|
| 上传替换全流程 | 上传 zip → bak 验证 → 新版本生效 → 回滚 → 旧版本恢复 |
| 运行时切换 | 设置本地 Node → 兼容性检测 → 实例启动使用新 Node |
| 迁移 | 构造 v0.2 目录 → 启动 → 验证 v0.3 布局 + 实例正常 |
| 全量包开箱即用 | 解压全量包 → 配置实例 → 自检通过 |
| 精简包按需下载 | 解压 slim 包 → 选择实现 → 下载 → 安装 → 自检通过 |

### 6.3 手动验证

- [ ] Windows 10/11 全量包安装 → 配置 Oracle 实例 → 自检通过
- [ ] Windows 10/11 slim 包安装 → 下载实现 → 下载运行时 → 配置 → 自检通过
- [ ] v0.2 升级 v0.3 → 自动迁移 → 已有实例不受影响
- [ ] 上传自定义实现 zip → 替换 → 回滚
- [ ] 选择本地 Node 运行时 → 实例正常启动

---

## 七、风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| Windows 文件锁导致替换 JAR 失败 | 高 | 中 | 替换前检测占用进程，提示用户停止实例 |
| GitHub API rate limit | 中 | 低 | 缓存 Release 信息（15min TTL）+ 支持手动 URL |
| 大文件上传超时 | 中 | 中 | 流式处理 + 增大超时 + 分片上传（未来） |
| 迁移中断（断电/崩溃） | 低 | 高 | 先写临时目录，完成后原子 rename |
| 系统 Java 缺 jdk.httpserver | 中 | 中 | 检测后明确提示 + 引导下载 bundled JRE |
| CI 构建时间过长 | 中 | 低 | 分层 job 并行 + 非 tag 只构建 slim |

---

## 八、时间线

```
Week 1:
  Day 1-2: Task 1.1 ImplRegistry 数据模型
  Day 2-3: Task 1.2 bak 备份与恢复
  Day 3-5: Task 1.3 上传替换实现
  Day 5-7: Task 1.4 前端实现管理页

Week 2:
  Day 1-2: Task 2.1 ArtifactDownloader
  Day 2-3: Task 2.2 RuntimeManager
  Day 3-4: Task 2.3 前端运行时配置页
  Day 5:   Task 2.4 GitHub 下载集成

Week 3:
  Day 1-2: Task 3.1 CI 产物分层
  Day 2-3: Task 3.2 目录迁移工具
  Day 4:   Task 3.3 向导 Step1 状态增强
  Day 5:   集成测试 + 手动验证 + 修复
```

**总计**：约 12-16 个工作日（3-4 周）

# DB MCP Helper — 产品规格说明书（SPEC）

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.0 |
| 更新时间 | 2026-08-29 |
| 上游文档 | `PRD.md` |
| 参考实现 | `prototype-redesign.html` |
| 后端契约 | `setup-app` @ `be976a8` |

本文档面向工程师与设计师，给出 Redesign 版本的**完整页面结构、组件规格、数据契约、状态机与交互细节**，可直接照此编码。

---

## 1. 全局架构

### 1.1 前端运行时

- 单页应用（SPA），URL hash 路由（`#/instances` / `#/instances/:code` / `#/runtime` / ...）
- 无框架依赖，纯原生 JS + Web Components（或 Alpine.js 极轻量做数据绑定）
- 主题通过 `<html data-theme="light|dark">` 切换，所有颜色走 CSS 变量
- 命令面板（⌘K）作为全局 overlay，独立组件

### 1.2 应用外壳（Chrome）

```
┌────────────────────────────────────────────────────────────┐
│ Topbar (h=48px, sticky)                                    │
│  Logo  ·  Breadcrumb/Title  ·  Search(⌘K)  ·  Theme  ·  ? │
├────────┬───────────────────────────────────────────────────┤
│ Sider  │ Main Content                                      │
│ (w=    │ (max-width 1440px, padding 24px, overflow-y auto) │
│  240px │                                                   │
│  col-  │                                                   │
│  lapse │                                                   │
│  →64px)│                                                   │
└────────┴───────────────────────────────────────────────────┘
     Slide-over (right, w=560px) overlays Main, doesn't push.
     Toast (top-right, stacked). Modal (centered, max-w 640px).
```

### 1.3 一级路由清单

| Route | 页面 | 落地条件 | 侧栏选中 |
| --- | --- | --- | --- |
| `#/welcome` | 空态欢迎 + 首次引导入口 | `state.envs` 为空且未点过「开始接入」 | — |
| `#/setup` | 首次三步向导 | 用户点「快速接入」或首次装机 | — |
| `#/overview` | 总览 Dashboard | 默认落地（有实例时） | 总览 |
| `#/instances` | 实例列表 | 主操作页 | 实例 |
| `#/instances/:dbId/:env` | 实例详情（Slide-over 打开列表页 + URL 同步） | 点卡片 | 实例 |
| `#/runtime` | Skill 与运行时 | 侧栏入口 | Skill 与运行时 |
| `#/diagnostics` | 排障（探测快照 / mcp 双视图 / 指南） | 侧栏入口 | 排障 |
| `#/system` | 系统操作（危险区 / 偏好 / 关于） | 侧栏入口 | 系统 |

### 1.4 全局状态

```js
// AppState (in-memory, 不持久化到 localStorage)
{
  detect: { home, root, tapDeployed, javaCmd, mcpJsonPath, qoderPluginMcpJsonPath, registeredServers, qoderPluginRegisteredServers, state },
  adapters: [{ id, displayName, defaultPort, serverPrefix, skillDir, runtimeKind, allTools, requiredTools }],
  ui: {
    theme: 'light'|'dark'|'system',
    sidebarCollapsed: false,
    currentRoute: 'instances',
    slideOver: null | { type: 'instance', dbId, env },
    commandPaletteOpen: false,
    filters: { dbType: 'all', env: 'all', perm: 'all', q: '' }
  }
}
```

**约定**：
- 所有 UI 状态只存内存，不用 `localStorage`（Tauri WebView 里也不安全）
- 用户偏好（主题 / 语言）通过后端 `state` JSON 持久化（新增 `ui` 节点，见 §5.5）
- `detect` / `adapters` 在 boot 时一次性拉取；后续按操作结果增量刷新 `detect`

---

## 2. 设计系统

### 2.1 色彩

| Token | Light | Dark | 用途 |
| --- | --- | --- | --- |
| `--bg-canvas` | `#f8fafc` | `#0b1220` | 页面背景 |
| `--bg-elevated` | `#ffffff` | `#111827` | 卡片、面板 |
| `--bg-inset` | `#f1f5f9` | `#0f172a` | 输入框底、代码块底 |
| `--bg-hover` | `#eff6ff` | `#1e293b` | hover 行 |
| `--border` | `#e2e8f0` | `#1f2937` | 分割线 |
| `--text-primary` | `#0f172a` | `#f8fafc` | 主文本 |
| `--text-secondary` | `#475569` | `#94a3b8` | 副文本 |
| `--text-muted` | `#94a3b8` | `#64748b` | 提示 |
| `--brand` | `#2563eb` | `#3b82f6` | 主品牌 / 主按钮 |
| `--brand-hover` | `#1d4ed8` | `#60a5fa` | 主按钮 hover |
| `--success` | `#059669` | `#10b981` | 自检通过 |
| `--warning` | `#d97706` | `#f59e0b` | 读写权限 / 提醒 |
| `--danger` | `#dc2626` | `#ef4444` | 危险 / 失败 |
| `--info` | `#0891b2` | `#06b6d4` | 提示条 |

### 2.2 字体

- UI：`Inter / -apple-system / "Segoe UI" / "Microsoft YaHei"` — 12/13/14/16/20/24
- 代码：`"JetBrains Mono" / Consolas / Menlo / monospace` — 12/13
- 层级：`h1(24/1.2) / h2(20/1.3) / h3(16/1.4) / body(13/1.6) / caption(12/1.5)`

### 2.3 间距 / 圆角 / 阴影

- 间距基数 4px：`s1=4 s2=8 s3=12 s4=16 s5=24 s6=32 s7=48`
- 圆角：`r-sm=6 r-md=10 r-lg=14 r-pill=999`
- 阴影：
  - `sh-card`: `0 1px 2px rgba(15,23,42,.04), 0 1px 3px rgba(15,23,42,.06)`
  - `sh-pop`: `0 8px 24px rgba(15,23,42,.10)`（Slide-over / Modal）

### 2.4 图标

- 全部走内联 SVG（Lucide 24×24 line 风格），`currentColor`
- 语义图标：`home / database / puzzle / stethoscope / settings / info / plus / play / refresh / trash / check / x / alert-triangle / code / eye / eye-off / command / chevron-right / chevron-down / copy / external-link / filter / search / more-horizontal`
- 数据库类型图标：`oracle` 用字母 O 圆形；`mysql` 用海豚轮廓简化；均 20×20

### 2.5 组件规范（Design Tokens for Components）

| 组件 | 高度 | padding | 字号 | 备注 |
| --- | --- | --- | --- | --- |
| Button (primary/secondary/ghost/danger) | 36 / 32 / 28 (sm) | 0 14px | 13 | 左侧 icon 8px gap；hover 有 60ms 过渡；禁用 opacity .5 |
| Input / Select | 36 | 0 10px | 13 | 聚焦 `outline 2px var(--brand)/40%`；错误态红边 |
| Card | — | 20 | — | `sh-card`，圆角 `r-md`，header 与 body 之间 `s4` gap |
| Badge | 20 | 0 8 | 11.5 | 圆角 `r-pill`；`ok / warn / no / info / neutral` |
| Table | row 40 / header 32 | 8px 12px | 13 | hover 行 `--bg-hover`；斑马可选 |
| Toast | 44 | 12 16 | 13 | 右上角堆叠，3.5s 消失，最多 3 |
| Slide-over | — | — | — | 从右侧滑入，宽 560px（`max-w 92vw`），带遮罩 |
| Modal | — | — | — | 居中，`max-w 640px`，`sh-pop` |
| Chip (tag) | 22 | 0 8 | 12 | 可带 `×` 删除 |
| Segmented Control | 32 | 0 12 | 12.5 | 用于筛选 / tab |

---

## 3. 页面详细规格

### 3.1 `#/welcome` 空态欢迎页

**目的**：让用户在进入向导之前，明确知道这个工具能干什么、需要装什么、依赖是否满足。

**布局**：
```
┌───────────────────────────────────────────────────────┐
│  Hero：Logo + 一句话定位 + 3 个能力亮点（图标+短语） │
├───────────────────────────────────────────────────────┤
│  环境检测卡（自动运行）                                │
│   ✓ Java 17.0.7 (found at C:/...)                     │
│   ✓ 磁盘剩余 240 GB                                    │
│   ✓ QoderWork mcp.json 存在                            │
│   ⚠ IDEA Qoder 插件 mcp.json 未找到（可忽略）         │
├───────────────────────────────────────────────────────┤
│  支持的数据源（卡片墙，来自 /api/adapters）             │
│   [Oracle] 默认端口 1521 · 5 工具 · JAVA_JAR          │
│   [MySQL]  默认端口 3306 · 4 工具 · NODE              │
├───────────────────────────────────────────────────────┤
│  主按钮：[快速接入] [先看看手册]                        │
└───────────────────────────────────────────────────────┘
```

**状态**：
- 加载中：检测项 skeleton；
- 全部通过：主按钮 `primary`；
- 有失败项：主按钮 `disabled`，失败行右侧显示「如何解决」链接。

**动作**：
- `[快速接入]` → 跳 `#/setup?step=1&dbId=oracle`
- `[先看看手册]` → 打开 `#/diagnostics?tab=guide`

### 3.2 `#/setup` 首次三步向导

**顶部**：Stepper 组件（3 步，当前步 `brand`，完成步 `success` + `✓`）。

#### Step 1：选类型 + 部署运行时

- 网格卡片：每张卡片显示 `displayName`、`id`、`runtimeKind`、`serverPrefix`、`defaultPort`、工具数、勾选态；单库可选（本步）
- 底部：目标目录（只读，来自 `detect.root`，可点击修改），当前 javaCmd 展示
- CTA：`[部署运行时并继续]`
- 后端：`POST /api/deploy {dbId}`；成功后拉 `detect`，进入 Step 2
- 进度：按钮切「部署中…」+ 环形进度条（模拟 0→100%，实际以 fetch resolve 为准）

#### Step 2：建第一个实例

- 左右分栏
  - 左：主表单（实例标识 / 别名 chips / 主机 / 端口 / service / user / password / jdbcUrl 高级）
  - 右：粘贴解析区（textarea + 解析并回填） + 权限勾选（工具清单 + 只读/读写预设）
- 底部：`[上一步]` `[保存并自检]`
- 后端：可选 `POST /api/env/parse`；主提交 `POST /api/env/config` 后 `POST /api/env/test` + `poll`
- 自检通过自动进入 Step 3；失败停留在 Step 2 但保留已填内容

#### Step 3：一键完成（注册 + Skill）

- 两个 checkbox 组：
  - **注册到 mcp.json**（默认勾选）：展示 `serverName = serverPrefix + env` + 完整 entry JSON
  - **部署 Skill**（默认勾选）：目标目录多选（QoderWork + .agents/skills 双默认，其他 4 平台可选） + 自定义目录
- CTA：`[完成所有配置]`
- 后端：`POST /api/env/register` → `POST /api/skill/deploy {dbId, targets}`（并行或串行均可，前端聚合）
- 完成页：绿色成功卡，展示下一步（「重启 QoderWork 使连接器生效」提示 + `[进入管理台]`）

### 3.3 `#/overview` 总览 Dashboard

**目的**：给有一堆实例的老用户一屏看到健康度。

**布局**：
```
┌───────────────────────────────────────────────────┐
│  顶部指标条：4 个数字卡片（实例总数 / 未注册 /    │
│              自检异常 / 最近 24h 调用数）         │
├──────────────┬────────────────────────────────────┤
│  左：健康列表│  右：快速动作 / 最近活动           │
│  （异常优先）│                                    │
└──────────────┴────────────────────────────────────┘
```

**数据源**：`detect.state.envs`（客户端聚合） + `detect.registeredServers`。

**动作**：
- 点异常行 → Slide-over 打开实例详情
- 快速动作卡：`添加实例 / 一键自检全部 / 打开 mcp.json / 查看日志`

### 3.4 `#/instances` 实例列表（核心页）

**筛选栏**：
- 搜索框（按 code / alias / host / user 模糊匹配）
- Segmented：库类型（All / Oracle / MySQL / …）
- Segmented：权限（All / 只读 / 读写）
- Segmented：状态（All / 未注册 / 自检异常 / 正常）
- 右侧：`视图` 切换（卡片墙 / 表格） + `[+ 添加实例]`

**卡片视图**（默认）：
```
┌─────────────────────────────────────┐
│ ◐ Oracle  ● prod  生产, 线上   [⋯] │  ← 头部：dbType icon + env code + alias chips + kebab
│ ▸ host:1521 · user=appuser          │  ← 副行：连接摘要（密码永不明文）
│ [只读] [已注册] [✓ 正常 · 2h前]     │  ← 徽章行
│ [自检] [打开日志] [注册]            │  ← 快捷动作（hover 显现更详细的）
└─────────────────────────────────────┘
```

**表格视图**：列 `状态灯 / dbType / env code / 别名 / host:port / 权限 / 注册 / 最近自检 / 操作`。

**空态**：无实例时展示 `[+ 添加第一个实例]` 大按钮 + 「或从备份导入」次按钮（P2）。

### 3.5 Slide-over 实例详情

**打开方式**：点卡片 or URL `#/instances/:dbId/:env`

**结构**：
- Header：`<dbType icon> <env code>` + alias chips + `[×]`
- Tabs：`概览 / 权限 / 日志 / 高级`
- **概览**：kv 表（host / port / service / user / url） + 密钥（密码框 + `显示/隐藏/复制/修改`） + `[一键自检]` + `[注册/重新注册]` + 最近自检详情
- **权限**：工具 checklist（继承向导中的分组：查询类 / 元数据类 / 写操作类），底部预设 `[只读] [读写]`；改动后按钮 `[应用并重注册]`
- **日志**：见 §3.5.1
- **高级**：JSON 视图（state.envs[env] 完整原文，可编辑） + `[从其他实例复制配置]` + `[删除实例]`

**删除流**：
1. 点 `[删除]` → Modal 显示将删除的 mcp 条目名 + 目录路径 + Skill 同步影响列表
2. 输入框要求输入 env code 完全一致才能激活 `[确认删除]`
3. 后端 `POST /api/env/delete`

**§3.5.1 日志面板**（Tab 内）：
- 过滤器：时间范围（最近 100 / 500 / 自定义）、工具下拉、成功/失败 chip、SQL 关键字
- 表格行：`时间 · 工具 · 耗时 · 状态 · SQL（可展开）`
- 底部：`[加载更多]` + `[导出 CSV]`（前端生成）
- 数据源：`GET /api/env/log?env&dbId&limit` 前端按过滤器二次过滤

### 3.6 `#/runtime` Skill 与运行时

三段式布局：
1. **运行时状态卡**
   - tap: `已部署 ✓` / `未部署`（`detect.tapDeployed`）
   - 各 dbType toolkit：路径 + 版本 + `[重装]`
   - 根目录 + javaCmd + `[打开目录]`
2. **Skill 目标目录管理**
   - 已部署位置表格：路径 · 状态（存在 / 缺失） · `[删除]`
   - 顶部 `[+ 添加位置]` 三模式 segmented（AI 平台 / 目录选择 / 手动输入）
   - `[全量重部署]` / `[仅同步映射]` 两个批量按钮
3. **Skill 内容预览**
   - 左：当前 dbType 的 SKILL.md 静态正文（read-only 高亮渲染）
   - 右：environments.md 映射表（由 `state.envs` 动态生成的实例→连接器名→dbType 三元组）

### 3.7 `#/diagnostics` 排障

三 Tab：
- **环境探测**：kv 展示 `detect` 全量字段 + 每项右侧 `[复制]` + 底部 `[重新检测]`
- **mcp.json 双视图**：左右分栏 `~/.qoderwork/mcp.json` vs `~/.qoder/shared_client/mcp.json`；高亮 DB MCP 相关条目；顶部 `[Diff]` 按钮显示两边差异
- **接入指南**：选实例 → 选平台 chip → 展示模板 + `[复制]` + `[打开设置页]`（走 deeplink）

### 3.8 `#/system` 系统操作

三卡片：
1. **偏好**
   - 主题 segmented（浅色 / 深色 / 跟随系统）
   - 语言 segmented（中 / 英）
   - 自检超时（数字输入，默认 100s）
   - 首次向导开关（`已完成后是否可重放`）
2. **危险操作**
   - **一键清空**：`[预览影响]` → 展开影响清单（将删除 X 个 mcp 条目、Y 个 Skill 副本、根目录 → 回收站、日志 → 回收站）→ 输入 `RESET` → `[执行]`
   - **一键卸载**：同上，输入 `UNINSTALL`；额外提示「本向导将退出，桌面快捷方式将移除」
3. **关于**
   - 版本 / 提交 hash（构建时注入 `__BUILD_SHA__`）
   - 依赖许可证
   - `[检查更新]` `[反馈问题]` `[开源仓库]`

---

## 4. 交互细节

### 4.1 键盘导航

- `Tab` 遍历 focusable
- `Enter / Space` 触发按钮
- `Esc` 关闭 Slide-over / Modal / 命令面板
- `⌘K` / `Ctrl+K` 命令面板
- `⌘,` / `Ctrl+,` 打开偏好
- `⌘N` / `Ctrl+N` 添加实例
- `?` 打开快捷键帮助
- 列表页 `↑↓` 移动选中，`Enter` 打开详情

### 4.2 命令面板

输入即时搜索，覆盖：
- 路由跳转（`总览 / 实例 / 运行时 / 排障 / 系统 / 添加实例`）
- 实例 quick jump（按 code + alias 模糊匹配）
- 动作（`自检 <env> / 注册 <env> / 打开日志 <env>`）
- 主题切换（`切换深色 / 切换浅色`）

分组显示，键盘 `↑↓` 移动，`Enter` 执行。

### 4.3 Toast vs Inline 错误

- **成功反馈**：Toast（3.5s 消失）
- **表单字段错误**：Inline，input 下方红字 + 红边
- **API 全局错误**：Toast 显示一行；如含 `stderrTail` 则展开为 Modal，附 `[复制错误详情]` `[打开日志]` 两个动作
- **危险操作错误**：Modal 内嵌 result 区域，不弹 Toast（避免用户错过）

### 4.4 加载态

- **首屏**：skeleton 卡片 3 张
- **异步按钮**：按钮内 spinner + 文案变化（`自检 → 自检中… (12s)`）
- **轮询任务**：poll 期间按钮 disabled，右侧显示 elapsed 秒
- **长任务无进度**：文案「预计 X 秒」+ 「后台运行，可切换页面」+ 右上角进度指示器（chip）

### 4.5 空态设计

每个列表页/面板独立空态：
- **未部署运行时**：插图 + 一句说明 + `[开始首次接入]`
- **无实例**：一句说明 + `[+ 添加实例]` `[导入配置(P2)]`
- **无日志**：一句说明 + `[立即自检产生数据]`
- **无 Skill 位置**：一句说明 + `[+ 添加位置]`

---

## 5. 数据契约（API Request/Response Schema）

**约定**：所有响应外层为 `{ ok: true, data: {...} }` 或 `{ ok: false, error: "msg" }`。

### 5.1 `GET /api/detect` （不变）

```json
{
  "home": "C:/Users/chuzh",
  "root": "D:/.../DB MCP Helper",
  "rootExists": true,
  "tapDeployed": true,
  "javaCmd": "C:/Develop.../jdk-17/bin/java.exe",
  "mcpJsonPath": "C:/Users/chuzh/.qoderwork/mcp.json",
  "qoderPluginMcpJsonPath": "C:/Users/chuzh/.qoder/shared_client/mcp.json",
  "registeredServers": ["oracle-uat", "mysql-dev"],
  "qoderPluginRegisteredServers": [],
  "state": {
    "root": "...",
    "javaCmd": "...",
    "skillTargets": [".../.qoderwork/skills", ".../.agents/skills"],
    "envs": {
      "uat": {
        "dbType": "oracle",
        "aliases": ["UAT","验收"],
        "tools": ["read-query","db-ping"],
        "host": "10.x.x.x",
        "port": 1521,
        "database": "SALESPORTALUAT",
        "user": "app",
        "password": "***",  // 明文存后端，前端永不回显
        "url": "jdbc:oracle:thin:@...",
        "registered": true,
        "lastTest": { "ok": true, "detail": "OK 12.2.0.1", "ts": "2026-08-29T10:00:00Z" }
      }
    }
  }
}
```

### 5.2 `GET /api/adapters` （不变）

### 5.3 `POST /api/deploy {dbId, root?}` （不变）

### 5.4 `POST /api/env/config {...}` （不变，字段与前端一致）

### 5.5 【新增】`POST /api/prefs` — 保存 UI 偏好

**Redesign 需要新增**，或退化为直接嵌入 `state` JSON。

```
Req: { prefs: { theme: "light|dark|system", lang: "zh|en", selfTestTimeoutMs: 100000, setupCompleted: true } }
Res: { ok: true }
```

若不改后端，Redesign 首版可先跳过持久化，内存生效即可。

### 5.6 【建议新增】`POST /api/reset/preview` — dry-run

```
Req: {}
Res: {
  mcpEntries: ["oracle-uat", "mysql-dev"],
  mcpJsonPath: "...",
  qoderPluginMcpEntries: [],
  skillPaths: [".../qoderwork/skills/oracle-db-ops", ...],
  rootPath: "...",
  rootSizeMB: 240,
  logPaths: [".../logs/call-oracle-uat.log", ...]
}
```

Redesign 首版可前端组合 `detect` + `skillTargets` 近似模拟。

### 5.7 其他端点（保持现状）

`/api/env/parse` / `/api/env/test` / `/api/env/test/poll` / `/api/env/register` / `/api/env/delete` / `/api/env/log` / `/api/env/guide` / `/api/skill/deploy` / `/api/skill/sync` / `/api/skill/targets` / `/api/reset` / `/api/uninstall`。

---

## 6. 组件规格

### 6.1 `<m-button variant="primary|secondary|ghost|danger" size="md|sm">`

- variant=primary：`--brand` 底，白字，hover `--brand-hover`
- variant=secondary：`--bg-elevated` 底，`--brand` 字色，边框 `--border`
- variant=ghost：透明底，`--brand` 字色，无边框，hover `--bg-hover`
- variant=danger：`--danger` 底，白字

禁用：`opacity:.5; cursor:not-allowed`。带 `icon` 插槽左/右。

### 6.2 `<m-input type="text|password|number">`

- 左侧 icon / 右侧 actions 插槽（如 eye toggle、copy）
- 聚焦 `outline: 2px solid rgba(37,99,235,.4); outline-offset:1px`
- 错误态：`border-color: var(--danger); background: rgba(220,38,38,.04)` + 下方红字

### 6.3 `<m-card>` `<m-card header="..." actions="...">`

Header 行：`h3` + 右侧 actions 插槽；分隔线可选；body padding `s5`。

### 6.4 `<m-badge tone="ok|warn|no|info|neutral">`

Tone 对应色彩（§2.1 语义色）。

### 6.5 `<m-table>` `<m-tr selectable>`

- 支持 hover 行、行点击、行右键菜单（kebab 按钮等价）
- 列宽固定/弹性混用；操作列右对齐

### 6.6 `<m-slide-over>`

- 打开：`transform: translateX(0)`；关闭：`translateX(100%)`；`transition 220ms cubic-bezier(.4,0,.2,1)`
- 遮罩 `rgba(15,23,42,.35)`，点击关闭（危险编辑时禁用点击关闭）

### 6.7 `<m-modal>`

- 遮罩同上；`role="dialog"` `aria-modal="true"` `aria-labelledby`
- 关闭按钮 `Esc`

### 6.8 `<m-tabs>`

- 下划线指示条；键盘 `←→` 切换；`role="tablist"`

### 6.9 `<m-command-palette>`

- `position: fixed; top: 20vh; left: 50%; translateX(-50%); width: 640px; max-width: 92vw`
- 输入框 + 分组结果列表 + 底部快捷键提示条

### 6.10 `<m-stepper steps="..." current="n">`

横向步骤条：完成步骤 `✓`；当前 `brand` 圆点 + 描边；未来 `neutral`。

### 6.11 `<m-instance-card>`

- 见 §3.4
- `role="article"` `aria-labelledby`
- hover：轻微 `transform: translateY(-1px)` + `sh-pop`

### 6.12 `<m-password-field>`

- 内置 eye toggle
- 永不回显后端返回的明文密码；修改流程走「覆盖式保存」

---

## 7. 状态机

### 7.1 实例生命周期

```
                  ┌────────────────────┐
   create draft   │   draft (未保存)   │
   ─────────────► └────────┬───────────┘
                            │ /env/config ok
                            ▼
                  ┌────────────────────┐
       test run   │  configured (未自检)│
       ─────────► └────────┬───────────┘
                            │ /env/test poll ok
                            ▼
                  ┌────────────────────┐
   /env/register  │  ready (自检通过)  │
   ─────────────► └────────┬───────────┘
                            │ mcp.json entry added
                            ▼
                  ┌────────────────────┐
   /env/test fail │  registered (可用) │
   ◄───────────── └────────┬───────────┘
                            │ /env/delete
                            ▼
                  ┌────────────────────┐
                  │   trashed (回收站) │
                  └────────────────────┘
```

UI 徽章映射：`draft=no`, `configured=neutral`, `ready=info`, `registered=ok`, `error=no`。

### 7.2 首次向导状态

```
welcome → (点开始) setup:step1 → setup:step2 → setup:step3 → instances
    │                    │           │            │
    │                    │(deploy fail) stay+error msg
    │                    │           │(config/test fail) stay
    │                    │           │            │(register/skill fail) stay
    └─(skip setup)──► instances (with setup banner at top)
```

`setupCompleted: true` 持久化在 `state.ui`。

### 7.3 危险操作状态

```
idle → previewing (加载影响清单) → ready (用户输入正确关键字) → running → done
                                    ↑                             │
                                    │                             ▼
                                    └───── (输入错误 / 取消) ←── error
```

---

## 8. 错误处理

### 8.1 错误来源分层

- **网络失败**（fetch reject）：Toast「无法连接向导后端」+ `[重试]` + `[打开诊断]`
- **后端 ok:false**：Toast 显示 error 字段；若 error 含 `\n` 或 `at com.` 判定为 stack，改用 Modal 展示
- **前端校验失败**：Inline 字段下方

### 8.2 兜底

- 启动期 `boot()` 若 `detect` 或 `adapters` 失败，渲染 `<m-boot-error>`：显示 fetch 状态码 + 手动打开的 URL 复制按钮 + `[重试]`
- `poll` 超过 timeout：Toast 「自检超时（>100s）」+ `[打开日志]`

---

## 9. 可访问性（A11y）清单

- 所有交互控件有语义标签：`button` / `a[href]` / `input` / `select`
- Icon-only 按钮有 `aria-label`
- 表单 `label` 与 `input` 通过 `for/id` 绑定
- 焦点可见：`:focus-visible { outline: 2px solid var(--brand); outline-offset:2px }`
- Modal / Slide-over 焦点陷阱：Tab 循环在浮层内
- 状态徽章附文字（不能仅靠颜色）
- `prefers-reduced-motion: reduce` 时禁用 Slide-over / Toast 动画

---

## 10. 迁移与兼容

### 10.1 数据兼容

- 后端 `state` JSON 结构不变，Redesign 前端只读取
- 新增字段（`ui`）走向后兼容：读不到时用默认值
- 旧版本 `detect` 无 `qoderPluginRegisteredServers` 字段时，前端 `[] ||` 兜底

### 10.2 URL 兼容

- 旧版无 hash；新版 `#/` 前缀；老用户从桌面图标启动 → 前端自动重定向 `#/welcome` 或 `#/instances`
- 保留 `?setup=1` query 支持快捷唤起向导（后续可用于「帮助中心 → 打开向导」链接）

### 10.3 回退窗口

- Redesign 首版发布后保留 `?classic=1` 参数进入旧版 UI（同一 jar 内，仅 CSS/JS 分支）一个大版本；然后彻底移除

---

## 11. 交付物清单

| 文件 | 说明 |
| --- | --- |
| `docs/redesign/PRD.md` | 本文档的上游 |
| `docs/redesign/SPEC.md` | 本文档 |
| `docs/redesign/prototype-redesign.html` | 高保真可点击原型（含 mock 数据） |
| `setup-app/src/main/resources/index.html` | 正式版替换目标（原型验证通过后合并） |
| `setup-app/src/main/resources/app.js`（可选） | 若单文件超 3000 行则拆分，仍随 jar 打包 |
| `setup-app/src/main/resources/styles.css`（可选） | 同上 |

---

## 12. 未决问题（需产品/用户确认）

- [ ] 是否需要「批量操作」：一次注册 / 自检 / 删除多个实例
- [ ] 是否提供「主题自定义」（primary color 换色）
- [ ] 权限预设除「只读 / 读写」外是否加「运维 / DBA」
- [ ] 是否引入 `@tanstack/virtual` 类库做虚拟列表（当实例数 > 500）
- [ ] 命令面板是否作为 P0（本 SPEC 假定 P0）
- [ ] 「导出/导入配置」（P2）具体范围：只导出 mcp 条目 / 含 state.envs 密码 / 含 Skill 副本

---

**文档结束。原型见 `prototype-redesign.html`。**

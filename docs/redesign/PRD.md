# DB MCP Helper — 产品需求文档（PRD）

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.0（Redesign Kickoff） |
| 更新时间 | 2026-08-29 |
| 关联代码 | `oracle-mcp-install-tools / setup-app`（当前 HEAD `be976a8`） |
| 关联交付 | `SPEC.md`（详细规格）、`prototype-redesign.html`（高保真原型） |
| 产品负责人 | 用户（chuzhuchao1） |
| 一句话定位 | 一款面向 AI 开发者的一站式本地数据库 MCP 服务器安装/注册/自检/Skill 部署管理工具，让「把数据库接入到 Cursor / Claude / QoderWork 等 AI 客户端」这件事在 3 分钟内可视化完成。 |

---

## 1. 背景与问题

### 1.1 业务背景

AI 编码助手（QoderWork / Cursor / Claude Code / Gemini CLI / Codex CLI 等）通过 MCP（Model Context Protocol）协议接入外部工具已成为 2026 年的事实标准。数据库 MCP Server 是其中最常用的一类：让 AI 可以直接查询、维护数据库。但当前用户接入一个数据库 MCP 需要手工完成下列步骤：

1. 下载数据库 MCP Toolkit（Oracle 版是 jar + JRE，MySQL 版是 Node 包）
2. 写连接配置文件（Oracle `config.yaml` / MySQL `.env`）
3. 写 MCP 客户端配置（`~/.qoderwork/mcp.json`、`~/.qoder/shared_client/mcp.json`）
4. 装 Skill（可选，用于让 AI 遵循数据库安全规范）
5. 手工重启客户端使连接器生效
6. 自检连通性

多环境（dev / test / uat / prod）+ 多数据库类型（Oracle / MySQL / PG / ...）组合下，配置维护成本呈笛卡尔积增长；且密码明文散落在多份文件中，容易误传到 Git。

### 1.2 现版本已解决的问题

`setup-app` v0.2.0（当前 `be976a8`）已经把上述流程做成了一个本地 Web 向导（Tauri 桌面壳 + JDK HttpServer 后端 + DbAdapter 抽象层），支持 Oracle / MySQL 双适配器、tap 监听代理、Skill 多副本部署、mcp.json 自动合并写入。

### 1.3 现版本仍存在的问题

| 分类 | 具体问题 |
| --- | --- |
| 界面/交互 | 首屏向导 6 步冗长；管理台/向导两套渲染路径复用同一 `#app`，存在双份卡片同时呈现（wizard + danger 未清）；术语混用（"实例"/"环境"/"连接器"/"数据库类型"）；权限勾选是扁平 checkbox，看不到"这个实例到底开放了哪些工具"的清晰摘要 |
| 信息密度 | "配置概览"页与首页"运行信息"卡片内容重叠；"Skill 部署位置"在首页卡片 + 独立页面出现两次；"危险区"作为通用组件被塞进 wizard 每一步 |
| 状态反馈 | 自检是异步的但 UI 只有 `poll` 期间的按钮 loading；错误提示统一 toast，长错误堆栈无处安放；tap 调用日志是纯文本 pre，无法按工具 / 时间 / 成功状态过滤 |
| 数据库类型切换 | 顶部下拉框切换后所有页面强制重渲染，用户已填的实例信息未保留；跨库类型对比不便（多库共存场景下无法在一屏看到全部实例） |
| 首次体验 | 装机 → 打开 → 立即进入 wizard 第 1 步，无「先看能力再决定要不要部署」的空态；「部署运行时」按钮无进度反馈，大 jar 释放期间界面像卡死 |
| 危险操作 | 一键清空 / 一键卸载 只有 `confirm()`，无输入实例名二次确认；无 dry-run |
| 品牌与观感 | 深紫 header + 灰底白卡，视觉偏 2015；菜单 6 项无分组，无键盘导航；emoji 做图标不适合桌面产品 |

---

## 2. 目标用户与使用场景

### 2.1 目标用户

| 角色 | 特征 | 使用频次 |
| --- | --- | --- |
| **主力**：AI 应用开发者 | 需要让 AI 客户端连自己的 Oracle / MySQL 数据库做查询、代码调试；熟悉 mcp.json 但讨厌手工维护多份配置 | 首次装机 + 每次新增实例 |
| **次要**：DBA / 后端 TL | 给团队搭好统一的 AI 数据库接入基线；重点关注权限与审计 | 每周 |
| **边缘**：技术负责人 | 只是想看看这个工具能干什么，暂时不装 | 一次性 |

### 2.2 核心场景

| 场景 ID | 触发 | 目标 | 现在的痛点 |
| --- | --- | --- | --- |
| S1 首次装机 | 双击桌面图标 | 3 步内完成 Oracle UAT 实例接入，看到 QoderWork 里 `oracle-uat` 连接器可用 | 6 步向导 + 中途无进度 + "运行根目录"等术语不解释 |
| S2 新增实例（同库类型） | 管理台点「+ 添加实例」 | 复制现有实例配置改几个字段快速接入 | 表单在向导第 2 步里，无「从已有实例复制」入口 |
| S3 新增实例（换库类型） | 顶部切到 MySQL | 与 Oracle 完全隔离的一整套实例，界面结构一致 | 切换后 CUR_ROUTE 保留但状态被清空；跨库不共存 |
| S4 排查连接不通 | AI 报"oracle-uat 未响应" | 打开实例详情 → 一键自检 → 看调用日志 → 复制连接串 | 三步跳转：管理台→自检→日志，日志是纯文本 |
| S5 权限收紧 | UAT 上线要收掉 write-query | 进入实例 → 权限卡片 → 取消勾选 write-query → 重注册 | 权限在向导第 2 步里，管理台看不到；改权限要重跑整个向导 |
| S6 Skill 部署到新平台 | 团队新装了 Cursor | 添加 Skill 部署位置（选 Cursor） | 三种添加模式（AI 选择 / 目录选择器 / 手动输入）散落在两页 |
| S7 换机器 / 备份 | 装机环境重建 | 导出/导入配置 | 无（新需求） |
| S8 卸载 | 不想用了 | 一键清空保留程序 / 一键卸载 | 只有 confirm，无二次输入校验；无 dry-run |

### 2.3 非场景（明确不做）

- 不做 SQL 编辑器 / 数据浏览（不是产品定位，交给 AI 客户端）
- 不做多用户 / 权限体系（本地单机工具）
- 不做云端配置同步
- 不做数据库本身的部署 / 运维
- 不接管除 mcp.json 外的其他 AI 客户端配置格式（但会给出接入指南）

---

## 3. 产品目标与成功指标

### 3.1 定性目标

- 让「接入一个数据库实例」这件事的心智模型从 6 步收敛到 3 步（**连接 → 授权 → 注册**）。
- 让「管理多个数据库 × 多环境」这件事在**同一屏可见**，不用切下拉框。
- 让「改权限 / 看日志 / 排障」这类高频运维动作不用回到向导，在实例详情里就能完成。
- 让「危险操作」的破坏半径**在确认前完全可见**（会删哪些文件 / 会改哪些 mcp.json 条目）。

### 3.2 定量指标

| 指标 | 基线（当前版） | 目标（Redesign 后） |
| --- | --- | --- |
| 首次装机 → 看到第一个可用实例（点击次数） | 12+ | ≤ 6 |
| 新增一个同库类型实例（点击次数） | 24（走完 wizard 2/3/4/5） | ≤ 8 |
| 修改一个实例的权限（点击次数） | 24（重跑 wizard） | ≤ 3 |
| 卸载彻底清理（回滚率） | 未知 | 支持 dry-run，误删率 < 1% |
| 页面视觉一致性（组件复用率） | 低（wizard / console 双套） | 高（一套 Card / Table / Form 组件覆盖 100%） |

### 3.3 反 KPI（不做的事）

- 不追求"炫技动画"，加载动画仅在有真实进度时呈现。
- 不追求 100% 单页化；实例详情、Skill 编辑等大表单允许独立视图。

---

## 4. 功能范围（Redesign 版本必须覆盖的当前能力）

### 4.1 后端能力清单（已存在，Redesign 只做前端重构）

| 分类 | API | 用途 |
| --- | --- | --- |
| 探测 | `GET /api/detect` | 返回 home / root / tapDeployed / javaCmd / mcpJsonPath / qoderPluginMcpJsonPath / registeredServers / qoderPluginRegisteredServers / state |
| 元数据 | `GET /api/adapters` | 返回所有 DbAdapter 的 id / displayName / defaultPort / serverPrefix / skillDir / runtimeKind / allTools / requiredTools |
| 部署 | `POST /api/deploy` | 释放指定 dbId 的运行时（jar + jlink JRE / node）与 tap 代理到 root |
| 实例配置 | `POST /api/env/config` | 写单实例的连接配置文件（Oracle config.yaml / MySQL .env） + 更新 state.envs |
| 粘贴解析 | `POST /api/env/parse` | 宽松解析 Spring YAML / properties 片段，返回 host/port/service/database/user/hasPassword |
| 自检 | `POST /api/env/test` | 异步触发（返回 running=true，通过 poll 拿结果） |
| 自检轮询 | `GET /api/env/test/poll?env=` | 拿最新结果 |
| 注册 | `POST /api/env/register` | 合并写入 mcp.json，自动备份 |
| 删除实例 | `POST /api/env/delete` | 移除 mcp.json 条目 + 实例目录进回收站 + 同步 Skill 映射 |
| 调用日志 | `GET /api/env/log?env&dbId&limit` | 返回 tap 旁路记录的最后 N 条 |
| 接入指南 | `GET /api/env/guide?env&dbId` | 返回各 AI 平台的接入模板与 deeplink |
| Skill 部署 | `POST /api/skill/deploy` | 把 SKILL.md + environments.md 部署到 targets |
| Skill 同步 | `POST /api/skill/sync` | 只更新 environments.md 映射表，不重写 SKILL.md |
| Skill 位置管理 | `GET/POST /api/skill/targets` | list / add / remove |
| 一键清空 | `POST /api/reset` | 移走 mcp 条目 / Skill / 根目录（回收站） |
| 一键卸载 | `POST /api/uninstall` | 清空 + 卸载自身 |

### 4.2 前端功能清单（Redesign 必须支持）

按功能域拆分为 5 组共 22 项。所有项都是当前已有能力的重新组织，不新增后端逻辑。

**A. 首次引导（Onboarding）**
- A1 空态欢迎页：说明能力矩阵、依赖检测（Java 17+ / 磁盘空间 / mcp.json 是否存在）、开始按钮
- A2 三步向导：选数据库类型 → 建第一个实例（连接 + 权限 + 别名） → 注册 & Skill 部署（一键组合）
- A3 向导进度持久化：中途关窗口再开，回到上次步骤

**B. 实例管理（Instance Hub）**
- B1 实例列表：所有 dbId × env 的实例统一陈列，可按库类型 / 环境 / 权限过滤
- B2 实例卡片视图（默认）：每张卡片显示 dbType、env code、别名 tag、权限徽章（只读/读写）、最近自检状态、注册状态
- B3 实例详情侧滑面板（Slide-over）：不跳页即可查看/编辑
- B4 新增实例：独立表单页，支持"从已有实例复制"
- B5 编辑实例：改密码 / 端口 / URL 等，改后引导重新注册
- B6 权限编辑：细粒度勾选工具集 + 两个预设（只读 / 读写），改后引导重新注册
- B7 别名管理：chips 增删，输入回车即添加，展示 DEFAULT_ALIASES 建议
- B8 删除实例：二次确认，展示会移除的 mcp 条目名、进回收站的目录路径
- B9 一键自检：详情页 & 列表页快捷按钮，实时进度
- B10 一键注册 / 重新注册：显示将写入 mcp.json 的完整 entry JSON

**C. 排障与观测（Troubleshooting）**
- C1 实例调用日志：按时间倒序，支持按工具 / 成功状态 / SQL 关键字过滤，导出为 CSV
- C2 环境探测快照：显示 home / root / javaCmd / mcpJsonPath / qoderPluginMcpJsonPath 及可复制
- C3 双 mcp.json 合并视图：QoderWork + IDEA Qoder 插件的已注册服务器列表并排
- C4 AI 平台接入指南：per-instance 生成模板 + deeplink 到对应平台设置页

**D. Skill 与运行时（Runtime & Skill）**
- D1 Skill 部署位置管理：统一入口（列表 + 三种添加模式），支持批量部署
- D2 Skill 内容预览：SKILL.md 静态正文 + environments.md 映射表实时预览
- D3 运行时状态：显示 tap 是否部署、jar / node 版本、可"重装运行时"
- D4 重新部署 Skill 映射：一键触发 syncMappings

**E. 系统操作（System）**
- E1 一键清空（dry-run）：预览要删除的资源清单 → 输入 "RESET" 二次确认
- E2 一键卸载（dry-run）：预览要删除的资源 + 卸载器路径 → 输入 "UNINSTALL" 二次确认
- E3 偏好设置：主题（浅/深/跟随系统）、语言（中/英，仅 UI 文案，实例数据不变）、自检超时时间
- E4 关于：版本号 / 提交 hash / 开源许可 / 检查更新链接

### 4.3 明确移除 / 合并的旧功能

| 旧功能 | 处置 | 理由 |
| --- | --- | --- |
| 顶部下拉切换数据库类型 | 改为「按库类型分组」列表 + 筛选器 | 用户实际会同时维护多库，切换反而隐藏信息 |
| 侧栏「Skill 部署位置」独立页 + 管理台里的同名卡片 | 合并到「Skill 与运行时」单一页 | 双份组件是 bug 源 |
| 危险区在每个 wizard 步骤都渲染 | 独立到「系统操作」页 | 首次流程中放"清空/卸载"是心智污染 |
| 配置概览页 | 拆到「环境探测」+「运行时状态」 | 与首页重复 |
| 6 步 wizard（部署→加实例→自检→注册→Skill→完成） | 收敛到 3 步（选类型→建实例→一键完成注册+Skill） | 自检是建实例的自动子动作，不应独立一步；注册+Skill 可并行做一步 |

---

## 5. 非功能需求

| 维度 | 要求 |
| --- | --- |
| 性能 | 首屏 TTI ≤ 300ms；管理台实例数 ≤ 100 时列表滚动 60fps；异步任务（自检、部署）响应时间 < 100ms，实际耗时通过 poll 展示 |
| 兼容 | Windows 10+ / macOS 12+ / Ubuntu 22.04+；Tauri WebView（WebView2 / WKWebView / WebKitGTK） |
| 键盘导航 | 主操作支持 Tab / Enter / Esc；命令面板（⌘K / Ctrl+K）覆盖所有一级路由 |
| 无障碍 | 遵循 WCAG 2.1 AA：色彩对比 ≥ 4.5:1，控件有 focus 环，语义标签 |
| 本地化 | 中文为主，UI 文案预留 i18n key；实例数据 / 日志 / 错误详情按后端原样展示 |
| 视觉 | 深浅双主题；不依赖 emoji 做图标（改用内联 SVG icon set） |
| 安全 | 密码字段永不明文回显；mcp.json 写入前自动备份到 `<path>.bak.<timestamp>`；所有删除走系统回收站 |
| 可靠 | 后端启动失败时 WebView 显示诊断页 + tail 最近 50 行日志；前端 fetch 有 15s 超时 + 重试提示 |
| 可回滚 | 每次 mcp.json 写入前生成 diff 预览；提供"回滚上一次变更" |

---

## 6. 交互与信息架构（高层）

### 6.1 一级导航（IA）

```
┌─────────────────────────────────────────────────────────────┐
│ 顶栏：Logo | 面包屑/搜索 | 主题切换 | 命令面板 | 用户/帮助  │
├─────────────────────────────────────────────────────────────┤
│ 侧栏（可折叠）：                                              │
│   🏠 总览（Overview）                                       │
│   🗄  实例（Instances）—— 默认落地页                        │
│   ⚙  Skill 与运行时（Runtime & Skill）                     │
│   🧭 排障（Diagnostics）                                    │
│   🔧 系统（System）—— 含危险区                              │
│   ℹ  关于（About）                                          │
├─────────────────────────────────────────────────────────────┤
│ 主内容区：根据路由渲染                                        │
│ 详情侧滑面板（Slide-over）：编辑实例、看日志                │
└─────────────────────────────────────────────────────────────┘
```

### 6.2 首次体验流（Onboarding Flow）

```
装机 → 打开 → 空态欢迎页
                 │
                 ├─ [检测环境] ─ Java 17 ✓ 磁盘 ✓ mcp.json ✓
                 │
                 ├─ [开始快速接入] → 三步向导
                 │      Step 1 选类型 + Step 2 建实例（合并同页） + Step 3 一键完成（注册+Skill）
                 │
                 └─ [稍后再说] → 管理台（引导横幅常驻，可关闭）
```

### 6.3 日常管理主流程

```
实例列表（卡片墙，支持筛选 / 搜索 / 排序）
   │
   ├─ 点卡片 → 右侧 Slide-over（详情 + 编辑 + 自检 + 日志 + 权限）
   ├─ 点 [+ 添加实例] → 全屏表单（支持从其他实例复制）
   └─ 长按/右键 → 快捷菜单（注册 / 自检 / 复制配置 / 删除）
```

---

## 7. 优先级与里程碑

| 优先级 | 范围 | 里程碑 |
| --- | --- | --- |
| **P0 Must**（不做不发布） | A1/A2/A3 首次向导；B1–B11 实例管理全套；D1 Skill 位置统一；E1/E2 危险操作 dry-run；顶部搜索 + 命令面板（覆盖所有一级路由 + 实例 quick jump） | v0.3.0 alpha |
| **P1 Should**（首版尽量带） | C1 日志过滤器；C3 双 mcp 合并视图；B5 权限 diff 预览；主题切换 | v0.3.0 beta |
| **P2 Could**（有则更好） | 场景 7 导出/导入配置；键盘优先模式；Skill 内容 diff | v0.4 |
| **P3 Won't**（本次不做） | 云同步、SQL 编辑器、多语言 UI、主题自定义 | — |

### 7.1 里程碑拆解

- M1（本文档）：PRD / SPEC / 原型评审通过
- M2：前端重构（`index.html` 拆分）+ 后端契约扩展（增加 dry-run / export / preference 端点）
- M3：Tauri 打包 + 三平台 CI 回归
- M4：灰度用户反馈 → v0.3.0 正式版

---

## 8. 依赖与风险

### 8.1 依赖

- 后端 `SetupMain` 保持现有 24 个 API 契约稳定（Redesign 期间只加不改）
- Tauri v2.11+ 桌面壳（已在 `be976a8` 中稳定运行）
- 至少 3 套内嵌 SVG icon（Lucide / Tabler 之一，24×24 line）
- 组件库：推荐 Tailwind + Headless UI 或 shadcn-vue（不引入 React/Vue 大依赖，保持 index.html 单文件可运行）

### 8.2 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| 前端重构期间发现后端契约缺失字段 | 阻塞 | M2 前必须先跑一遍 SPEC 中「数据契约」章节的字段核对 |
| 首次向导 3 步合并后，用户跳过实例配置直接注册 | 高 | Step 3 强制检查 envs 非空，否则禁用按钮 + 引导回 Step 2 |
| 危险操作 dry-run 需要新增后端接口 | 中 | Redesign 首版可前端组合 `detect` + 现有 `reset` 逻辑模拟 dry-run；正式版补 `/api/reset/preview` |
| 单文件 HTML 膨胀到 > 3000 行难以维护 | 中 | 原型阶段允许；正式版建议拆分为 `index.html` + `app.js` + `styles.css` 三文件，仍走 jar 内嵌 |
| 深色主题适配成本 | 低 | 通过 CSS `prefers-color-scheme` + `data-theme` 变量集中管理 |
| 顶部下拉切库类型行为变更破坏用户习惯 | 低 | 老用户可通过设置打开"经典模式"（保留旧 IA）一个过渡版本 |

---

## 9. 验收标准（Definition of Done）

Redesign 版本必须同时满足以下才能视为「可发布」：

1. **功能对齐**：SPEC.md 中「API 契约」章节列出的 24 个端点均有对应 UI 入口；不存在无法触达的旧功能。
2. **首屏时间**：管理台首屏（有 20 个实例）可交互 ≤ 500ms（本机 Chrome / Tauri WebView 各测 3 次取平均）。
3. **首次装机**：全新环境（无 `~/.db-mcp-helper`、`~/.qoderwork/mcp.json`）从零开始到 `oracle-uat` 连接器出现在 mcp.json 中，鼠标点击次数 ≤ 6。
4. **回归**：现有 CI 三平台构建全绿；Tauri 打包产物在 Windows / macOS / Linux 各自可安装可启动。
5. **黑框零出现**：桌面图标启动全过程无 cmd 窗口闪现（已被 `be976a8` 修复，Redesign 不得回退）。
6. **无 UI 破损**：无同时渲染两个 route 内容；无 `TypeError: xxx is not a function`；无 "无可用数据库适配器" 空态出现在有适配器时。
7. **可访问性**：键盘 Tab 能覆盖所有一级操作；`prefers-reduced-motion` 生效。
8. **危险操作**：一键清空 / 一键卸载 必须走 dry-run + 关键字二次确认；测试用例覆盖「取消」路径不留残留。

---

## 10. 附录

### 10.1 现有术语 → Redesign 术语映射

| 现版本 | Redesign 版本 | 说明 |
| --- | --- | --- |
| 环境 / env | 实例（Instance） | 一个 dbType 下的一个连接配置就是一个实例 |
| 环境编码 / env code | 实例标识（instance code） | `uat` / `prod` 这类短名，用于拼连接器名 |
| 实例别名 / aliases | 口语别名 | AI 识别用户口语（"生产库"→ prod）的映射 |
| 数据库类型 / dbId | 数据源类型 | Oracle / MySQL |
| 运行时 | 运行时（不变） | tap 代理 + 库类型 toolkit |
| 注册 / register | 注册连接器 | 写入 mcp.json 的过程 |
| 危险区 | 系统 → 危险操作 | 归入系统页 |
| Skill 部署位置 | Skill 目标目录 | 更贴近文件系统语义 |

### 10.2 待用户确认事项

- [ ] 是否引入 i18n 框架，还是仅中文化？
- [ ] 是否需要"经典模式"过渡？
- [ ] P2 中的「导出/导入配置」是否提到 P1？
- [ ] 命令面板（⌘K）是否作为 P0？

---

**文档结束。下一步请阅读 `SPEC.md` 了解具体页面/组件/交互规格，`prototype-redesign.html` 是可点击高保真原型。**

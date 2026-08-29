# macOS / Linux 验证清单

Windows 全场景回归已在 M4 用隔离根跑过全绿。mac/Linux 平台在 CI 构建出产物后，按下述清单在真机上逐项验证；如发现问题，参考 [README.md](../README.md) 与 [平台限制](../README.md#已知限制) 处理。

## 通用前置条件

- macOS：双击 `.dmg` 挂载后把 `DB MCP Helper` 拖入「应用程序」文件夹；首次启动如被 Gatekeeper 拦截，在「系统设置 → 隐私与安全性」里点「仍要打开」
- Linux：`.deb` 用 `sudo dpkg -i db-mcp-helper_1.0.0-1_amd64.deb`；app-image 解压后给 `DB MCP Helper` 加可执行位：`chmod +x "DB MCP Helper/DB MCP Helper"`

## 一、启动与向导

- [ ] 启动后浏览器自动打开 `http://127.0.0.1:8765/`（macOS/Linux 默认同 Windows）
- [ ] 向导首屏可选数据库类型（Oracle / MySQL），「部署运行时」默认根目录为 `~/.agent/mcp`，可改
- [ ] 点击「开始部署」后，根目录出现 `oracle-db-mcp-toolkit-1.0.0.jar`（Oracle）/ Node 运行时（MySQL）、`mcp-tap.jar`、`runtime/`、`state.json`，并按 `<dbId>/` 分目录
- [ ] `runtime/bin/java`（Linux）或 `runtime/bin/java`（mac）可执行，运行 `-version` 输出 17.x

## 二、加环境 + 自检 + 注册

- [ ] 添加一个真实环境（粘贴 Spring 配置片段或手工填写 host/port/service 或 database/user/password）
- [ ] 「保存并自检」返回 `ok:true`，数据库版本/延迟/用户名正确显示
- [ ] 「注册」后 `~/.qoderwork/mcp.json` 出现 `<dbId>-<env>` 条目（如 `oracle-uat` / `mysql-prod`），`command` 指向安装目录内的运行时
- [ ] 重启 QoderWork（或对该连接器关闭再开启）后，在对话里说「在 <env> 跑 db-ping」能成功
- [ ] 该环境的 `calllog.jsonl` 出现调用条目

## 三、多环境与权限隔离

- [ ] 添加第二个只读环境（不勾写工具），注册后其工具集不含写工具
- [ ] 在只读环境上让 AI 跑写工具 → 报「工具不存在」
- [ ] 删除第一个环境后，mcp.json 条目消失、环境目录进回收站、Skill 映射表同步移除

## 四、接入指南

- [ ] 管理台某环境行点「接入指南」，平台列表出现：QoderWork / Claude Code / Cursor / Codex CLI / Gemini CLI / 其他
- [ ] 切换每个平台，模板预填的命令/JSON/TOML 中包含真实的连接参数与运行时路径
- [ ] 「复制配置模板」按钮可用，粘贴到对应客户端后能注册成功
- [ ] QoderWork 平台下「打开配置页」能跳转到 `qoder-work://connectors`

## 五、一键清空 / 卸载

- [ ] 一键清空：mcp.json 中的 DB MCP 连接器条目消失、根目录进系统回收站、部署过的 Skill 副本也被同步移走
- [ ] 一键卸载：数据清空后程序目录在几秒内被移入回收站（macOS 用 `osascript` + Finder Trash；Linux 用 `gio trash` 或兜底 mv）
- [ ] 卸载后浏览器自动关闭（HTTP 服务已停）

## 六、Skill 同步

- [ ] Skill 部署到 `~/.qoderwork/skills` 后，在 QoderWork 对话里说「查 Oracle xxx」能触发 `oracle-db-ops` 技能；MySQL 实例触发 `mysql-db-ops`
- [ ] 新增环境后，管理台「同步 Skill 映射」让所有部署副本的 `environments.md` 反映最新环境清单

## macOS 专属

- [ ] Dock 里的应用图标正常；右键「退出」能终止 HTTP 服务
- [ ] 卸载时如 `osascript` 方式回收失败，兜底 mv 到 `~/.agent/mcp/.trash` 成功

## Linux 专属

- [ ] `.desktop` 文件已注册到应用菜单（如 jpackage `--linux-menu-group` 配置生效）
- [ ] `gio trash` 可用环境下进回收站；不可用环境兜底 mv 到父目录 `.trash`
- [ ] 卸载时 schtasks 不可用（仅 Windows），应走直接 `sh -c` 延迟脚本

## 问题上报

发现任何一项与清单不符，请在仓库 Issue 中附上：
1. 平台与版本（`uname -a` 或 `sw_vers`）
2. `~/.agent/mcp/state.json`
3. 失败步骤对应的浏览器开发者工具控制台日志
4. 如为卸载问题，提供 `~/.agent/mcp/.trash` 或回收站内容

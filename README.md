# DB MCP Helper — 多数据库 MCP 可视化安装器

一站式安装器，把**多种数据库**的官方 MCP Server 部署为 AI 客户端（QoderWork / Claude Code / Cursor / Codex / Gemini CLI 等）可用的 MCP 连接器，并自带可视化向导管理多个数据库类型、多个环境。

> 当前内置适配器：**Oracle**（Java Toolkit + 自带精简 JRE）与 **MySQL**（Node MCP Server + 捆绑便携 Node 运行时）。新增数据库类型只需实现一个 `DbAdapter` 并在 `DbAdapters` 注册，向导与安装器自动适配。

## 特性

- **适配器驱动、引擎无感知**：所有数据库差异（连接配置格式、运行时、工具集、连接器前缀）收敛到 `DbAdapter`；向导按 `/api/adapters` 动态渲染，新增库零侵入
- **按库分目录**：安装目录内每个数据库类型一个目录（`<dbId>/`，如 `oracle/`、`mysql/`），互不干扰，可独立增删
- **每库一个 Skill**：`oracle-db-ops` / `mysql-db-ops` 等，由对应适配器声明 `skillDir`，随实例清单自动同步 `environments.md`
- **目标机器零依赖**：安装包自带精简运行时（Oracle 走 jlink JRE，含 `jdk.httpserver` 驱动向导内嵌 HTTP 服务；MySQL 走捆绑 Node），与用户机器环境解耦
- **运行时自适应**：启动器优先复用系统已装 JDK 17（含 `jdk.httpserver` 模块），未满足时回退到安装目录内捆绑的精简 JRE，避免重复占用磁盘
- **多环境隔离**：一环境一 MCP 实例，物理隔离、权限独立（例如 prod 只读、uat 可读写）
- **可视化向导**：浏览器自动打开的向导 + 管理台，覆盖部署、配置、自检、注册、Skill 部署全流程
- **调用历史审计**：`mcp-tap` 旁路监听代理记录每次工具调用（时间、工具、耗时、成败、SQL 摘要）
- **多平台接入指南**：管理台为每个 AI 平台预填注册模板（mcpServers JSON / Claude CLI / Codex TOML），一键复制 + QoderWork 一键打开配置页
- **粘贴解析配置**：支持把 Spring YAML/properties 片段整段粘进来，宽松解析只取 url/username/password，其余键一律忽略
- **一键清空/卸载**：数据全部移入回收站（可找回），卸载模式下程序目录经延迟自删除
- **跨平台**：GitHub Actions 三平台 CI 产出 Windows `.exe` / macOS `.dmg` / Linux `.deb` + app-image

## 工程结构

```
db-mcp-install-tools/
├── pom.xml                          多模块父工程（groupId com.dbmcp）
├── mcp-tap/                         stdio 透传监听代理（零依赖）
├── setup-app/                       向导应用（JDK HttpServer + 单文件 HTML 前端 + Gson）
│   └── src/main/resources/          SKILL.md、platforms.json、index.html 已内置；toolkit/runtime 打包期生成
├── stage-resources.sh / .cmd        资源内嵌脚本（把各库 toolkit + tap + runtime 复制进 setup-app 资源）
├── package-windows.ps1              Windows 打包（jlink + jpackage + Inno Setup）
├── installer/db-mcp.iss             Inno Setup 安装脚本
└── docs/                            各平台验证清单等文档
```

### 运行时目录布局

部署后数据目录默认 `~/.agent/mcp`（安装形态即 `{app}`）：

```
baseDir/
├── runtime/           共享 jlink JRE（驱动 mcp-tap 与 Oracle toolkit）
├── tap/mcp-tap.jar    共享监听代理
├── state.json
└── <dbId>/            每数据库类型一个目录（oracle/、mysql/…）
    ├── toolkit/<file> 该库的服务端（jar 或目录）
    ├── runtime/node   （按需）该库服务端运行时（如 MySQL 的 Node）
    └── instance/<env>/config.*  每环境的连接配置
```

## 安装使用

### 普通用户

到仓库 Releases 下载对应平台的安装包（Windows `.exe` / macOS `.dmg` / Linux `.deb` 或 app-image 解压即用），双击启动即可。向导会自动打开浏览器；首屏可选数据库类型，所有数据写入 `~/.agent/mcp/<dbId>/`（可在向导首屏修改安装根目录）。

### 从源码构建

**前置条件**：JDK 17+、Maven 3.6+；Windows 出 `.exe` 需额外安装 [Inno Setup 6](https://jrsoftware.org/isdl.php)。

```bash
# 1. 构建 Oracle 官方 MCP Toolkit（按需要）
git clone --depth 1 --filter=blob:none --sparse https://github.com/oracle/mcp.git
cd mcp && git sparse-checkout set src/oracle-db-mcp-java-toolkit && cd ..
cd mcp/src/oracle-db-mcp-java-toolkit && mvn clean package -DskipTests -q && cd ../../../..

# 2. 构建 mcp-tap
mvn package -DskipTests -q -pl mcp-tap

# 3. jlink 共享 MCP 运行时（按需调整模块集）
MODS=$(jdeps --ignore-missing-deps --print-module-deps \
  mcp/src/oracle-db-mcp-java-toolkit/target/oracle-db-mcp-toolkit-1.0.0.jar)
ALL="java.base,java.logging,java.xml,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.rmi,java.sql,jdk.net,jdk.security.jgss,jdk.httpserver,$MODS"
ALL=$(printf "%s" "$ALL" | tr ',' '\n' | sort -u | tr '\n' ',' | sed 's/,$//')
mkdir -p dist && jlink --add-modules "$ALL" --output dist/runtime \
  --strip-debug --no-header-files --no-man-pages --compress 2
cd dist && (command -v zip >/dev/null 2>&1 && zip -qr runtime.zip runtime \
  || powershell -Command "Compress-Archive -Path runtime -DestinationPath runtime.zip -Force") && cd ..

# 4. （可选）MySQL 支持：准备 MySQL MCP Server 目录与便携 Node 运行时
#    MYSQL_TOOLKIT：构建你选用的 MySQL MCP Server，得到目录或可执行文件
#    NODE_RUNTIME_ZIP：下载 Node 便携版并打包为 zip（内部根目录随意，脚本会提取到 runtime/mysql/node）

# 5. 资源内嵌 + 构建 setup-app
export TOOLKIT_SRC="$(pwd)/mcp/src/oracle-db-mcp-java-toolkit/target/oracle-db-mcp-toolkit-1.0.0.jar"
# export MYSQL_TOOLKIT_SRC="$(pwd)/path/to/mysql-mcp-server"   # 可选
# export NODE_RUNTIME_ZIP="$(pwd)/dist/node-runtime.zip"        # 可选
./stage-resources.sh
mvn package -DskipTests -q -pl setup-app

# 6. 打包（Windows 默认 exe；-InnoSetup 需先安装）
pwsh package-windows.ps1            # 产物 dist\pkg\DB MCP Helper-1.0.0.exe
```

> 不提供 MySQL 资源时，安装器仍可构建并运行，仅 MySQL 适配器在部署阶段会提示缺少 toolkit/Node 运行时；Oracle 路径完全不受影响（零回归）。

## 多环境管理

每个数据库环境独立成一个 MCP 实例：

- **物理隔离**：`<dbId>-<env>` 连接器（如 `oracle-uat`、`mysql-prod`），工具名带环境前缀
- **权限独立**：按环境勾选工具集（prod 不勾写工具即为只读）
- **别名映射**：`environments.md` 维护编码→连接器→别名的映射，AI 通过口语（"生产"/"UAT"/"测试"）访问时自动路由；歧义或未命中强制反问

## 接入其他 AI 平台

QoderWork 由安装器自动写入 `~/.qoderwork/mcp.json`。其他平台通过管理台的「接入指南」页复制预填好的配置模板（mcpServers JSON / Claude CLI 命令 / Codex TOML 片段）。

## 卸载与回滚

- **一键清空**：删除安装器写入的所有数据（运行时、环境配置、mcp.json 中的 DB MCP 连接器条目、已部署的 Skill 副本），全部移入系统回收站
- **一键卸载**：在清空基础上延迟自删除程序目录（规避进程树终止的副作用）

## 已知限制

- 未提供 MySQL toolkit / Node 运行时时，MySQL 适配器仅占位，部署阶段会提示缺资源
- macOS 安装包未签名，首次打开需在「系统设置→隐私与安全性」中允许；如需正式签名需 Apple Developer 账号
- Linux deb 安装器未配置 APT 仓库；当前走 artifact 直装
- 调用历史仅在 MCP 进程长驻时可靠记录；AI 客户端侧的极短会话可能在响应前关闭进程导致个别调用漏记

## 许可证

安装器本体与 `mcp-tap` 按本项目仓库许可证发布。捆绑的数据库官方 MCP Server 遵循各自上游许可证（如 Oracle Universal Permissive License）。

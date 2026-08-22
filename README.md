# Oracle MCP Setup — 可视化 Oracle 数据库 MCP 安装器

一站式安装器，把 Oracle 数据库的官方 MCP Toolkit 部署为 AI 客户端（QoderWork / Claude Code / Cursor / Codex / Gemini CLI 等）可用的 MCP 服务器，并自带可视化向导管理多个数据库环境。

## 特性

- **目标机器零 Java 依赖**：安装包自带 jlink 精简 JRE（MCP 链路用 12 模块约 43M；安装器自身 4 模块约 40M）
- **多环境隔离**：一环境一 MCP 实例，物理隔离、权限独立（例如 prod 只读、uat 可读写）
- **可视化向导**：浏览器自动打开的向导 + 管理台，覆盖部署、配置、自检、注册、Skill 部署全流程
- **调用历史审计**：`mcp-tap` 旁路监听代理记录每次工具调用（时间、工具、耗时、成败、SQL 摘要）
- **多平台接入指南**：管理台为每个 AI 平台预填注册模板（mcpServers JSON / Claude CLI / Codex TOML），一键复制 + QoderWork 一键打开配置页
- **粘贴解析配置**：支持把 Spring YAML/properties 片段整段粘进来，宽松解析只取 url/username/password，其余键一律忽略
- **一键清空/卸载**：数据全部移入回收站（可找回），卸载模式下程序目录经任务计划程序延迟自删除
- **跨平台**：GitHub Actions 三平台 CI 产出 Windows `.exe` / macOS `.dmg` / Linux `.deb` + app-image

## 工程结构

```
oracle-mcp-install-tools/
├── pom.xml                          多模块父工程
├── mcp-tap/                         stdio 透传监听代理（零依赖）
├── setup-app/                       向导应用（JDK HttpServer + 单文件 HTML 前端 + Gson）
│   └── src/main/resources/          SKILL.md、platforms.json 已内置；其余资源打包期生成
├── stage-resources.sh / .cmd        资源内嵌脚本（把 toolkit + tap + runtime 复制进 setup-app 资源）
├── .github/workflows/build.yml      三平台 CI
└── docs/                            mac/Linux 验证清单等文档
```

## 安装使用

### 普通用户

到仓库 Releases 下载对应平台的安装包（Windows `.exe` / macOS `.dmg` / Linux `.deb` 或 app-image 解压即用），双击启动即可。向导会自动打开浏览器；所有数据写入 `~/.agent/mcp/oracle/`（可在向导首屏修改）。

### 从源码构建

**前置条件**：JDK 17+、Maven 3.6+；Windows 出 `.exe` 需额外安装 [WiX Toolset 3.x](https://wixtoolset.org/)（`winget install --id FireGiant.WiXToolset` 或 `choco install wixtoolset`）。

```bash
# 1. 先克隆 Oracle 官方 MCP 仓库并构建 toolkit
git clone --depth 1 --filter=blob:none --sparse https://github.com/oracle/mcp.git
cd mcp && git sparse-checkout set src/oracle-db-mcp-java-toolkit && cd ..
cd mcp/src/oracle-db-mcp-java-toolkit && mvn clean package -DskipTests -q && cd ../../../..

# 2. 构建 mcp-tap
mvn package -DskipTests -q -pl mcp-tap

# 3. jlink MCP 运行时（按需调整模块集）
MODS=$(jdeps --ignore-missing-deps --print-module-deps \
  mcp/src/oracle-db-mcp-java-toolkit/target/oracle-db-mcp-toolkit-1.0.0.jar)
ALL="java.base,java.logging,java.xml,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.rmi,java.sql,jdk.net,jdk.security.jgss,$MODS"
ALL=$(printf "%s" "$ALL" | tr ',' '\n' | sort -u | tr '\n' ',' | sed 's/,$//')
mkdir -p dist && jlink --add-modules "$ALL" --output dist/mcp-runtime \
  --strip-debug --no-header-files --no-man-pages --compress 2
cd dist && (command -v zip >/dev/null 2>&1 && zip -qr mcp-runtime.zip mcp-runtime \
  || powershell -Command "Compress-Archive -Path mcp-runtime -DestinationPath mcp-runtime.zip -Force") && cd ..

# 4. jlink 安装器自身运行时
jlink --add-modules java.base,java.desktop,java.logging,jdk.httpserver \
  --output dist/app-runtime --strip-debug --no-header-files --no-man-pages --compress 2

# 5. 资源内嵌 + 构建 setup-app
export TOOLKIT_SRC="$(pwd)/mcp/src/oracle-db-mcp-java-toolkit/target/oracle-db-mcp-toolkit-1.0.0.jar"
./stage-resources.sh
mvn package -DskipTests -q -pl setup-app

# 6. 复制到 staging 目录
mkdir -p dist/app-staging
cp setup-app/target/oracle-mcp-setup.jar dist/app-staging/

# 7. jpackage（按平台选择）
# Windows（需 WiX）：
jpackage --type exe --name "Oracle MCP Setup" --app-version 0.1.0 \
  --vendor oraclemcp --input dist/app-staging \
  --main-jar oracle-mcp-setup.jar --main-class com.oraclemcp.setup.SetupMain \
  --runtime-image dist/app-runtime --dest dist/pkg \
  --win-menu --win-shortcut
# macOS：jpackage --type dmg ...（详见 workflow）
# Linux：jpackage --type deb ... + --type app-image ...
```

打包完成后，产物位于 `dist/pkg/`。

## 多环境管理

每个数据库环境独立成一个 MCP 实例：

- **物理隔离**：`oracle-<env>` 连接器，工具名带环境前缀
- **权限独立**：`-Dtools` 按环境勾选（prod 不勾 `write-query` 即为只读）
- **别名映射**：`environments.md` 维护编码→连接器→别名的映射，AI 通过口语（"生产"/"UAT"/"测试"）访问时自动路由；歧义或未命中强制反问

## 接入其他 AI 平台

QoderWork 由安装器自动写入 `~/.qoderwork/mcp.json`。其他平台通过管理台的「接入指南」页复制预填好的配置模板（mcpServers JSON / Claude CLI 命令 / Codex TOML 片段）。详见 [`docs/platforms.md`](docs/platforms.md)（待补）。

## 卸载与回滚

- **一键清空**：删除安装器写入的所有数据（运行时、环境配置、mcp.json 中的 oracle-* 条目、已部署的 Skill 副本），全部移入系统回收站
- **一键卸载**：在清空基础上经任务计划程序延迟自删除程序目录（规避进程树终止的副作用）

## 已知限制

- WiX Toolset 未安装时只能产出 Windows app-image 便携目录（双击 exe 即可运行，仅缺安装向导）
- macOS 安装包未签名，首次打开需在「系统设置→隐私与安全性」中允许；如需正式签名需 Apple Developer 账号
- Linux deb 安装器未配置 APT 仓库；当前走 artifact 直装
- 调用历史仅在 MCP 进程长驻时可靠记录；AI 客户端侧的极短会话可能在响应前关闭进程导致个别调用漏记

## 许可证

安装器本体与 `mcp-tap` 按本项目仓库许可证发布。捆绑的 Oracle MCP Toolkit 遵循 Oracle Universal Permissive License（详见其仓库）。

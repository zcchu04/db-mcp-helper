---
name: oracle-db-ops
description: Oracle 数据库安全操作规范。通过 oracle-* MCP 连接器执行 SQL 查询、表结构探查、行数统计、执行计划分析与受控写操作。当用户提到查 Oracle、查库、查表、表结构、跑 SQL、统计数据、执行计划、数据库连通性，或提及 uat/prod/生产/测试等环境名要求取数时使用。
version: 1.0.0
---

# Oracle 数据库操作规范

适用于通过 `oracle-*` 前缀 MCP 连接器操作 Oracle 数据库的所有场景。核心原则：**环境优先、只读默认、写操作强确认**。

## 第一步：环境识别（每次必做）

本 skill 目录下的 `environments.md` 记录所有已配置环境：编码 → 连接器名 → 权限 → 别名。执行任何数据库操作前先读取该文件，将用户表达映射到具体连接器：

1. 用户明确说出环境编码或别名，且唯一命中 → 直接使用对应 `oracle-<env>` 连接器
2. 别名歧义（如"测试"同时命中 test 与 uat）→ 反问用户确认，禁止猜测
3. 用户未提及环境或无法映射 → 反问用户，禁止默认假设任何环境
4. 跨环境对比数据时，每条结果必须标注来源环境

## 工具语义与选型

各 `oracle-<env>` 连接器的可用工具以 `environments.md` 权限列为准：

- `db-ping`：连通性与延迟检查，返回版本/用户/schema。会话开始或怀疑连接异常时先跑
- `read-query`：执行 SELECT 返回 JSON 行数据，引擎层强制仅 SELECT。取数首选
- `table`：表管理（列表/结构描述等）。探索表结构用它
- `explain-plan`：执行计划分析。大查询前先评估
- `write-query`：DML/DDL。高危工具，见安全红线

选型口诀：探索用 table，取数用 read-query，诊断用 explain-plan，写操作用 write-query。

## 标准工作流

1. `db-ping` 确认目标环境连通
2. 用 `table` 或字典视图（all_tables / all_tab_columns / all_users）探索结构
3. 构造查询；涉及大表先 `explain-plan` 评估
4. 需要写操作时，走安全红线流程后再执行

## 安全红线

- `write-query` 执行前必须：展示完整 SQL + 说明影响范围（目标表、预估行数）+ 获得用户明确确认
- DROP / TRUNCATE / 无 WHERE 的 UPDATE / DELETE：主动提示不可逆，需用户逐字确认
- 目标环境没有 `write-query` 工具（只读环境）：写请求应引导用户改用有写权限的环境，禁止任何变通绕过
- 禁止在对话中回显数据库密码；引用配置内容一律脱敏

## 大表防护

- COUNT(*) 或全表扫描前，先评估量级（explain-plan 或字典视图）
- 大表禁止 SELECT *，必须限定列并带 WHERE / 行数上限
- 字典视图的 num_rows 可能未收集统计而为空，此时再实时 COUNT

## 故障排查

- 修改 config.yaml 后，须在 QoderWork 连接器中对该连接器 disable→enable 才会重载配置
- 配置中 `${VAR}` 占位符缺系统环境变量会导致 MCP 服务启动失败
- ORA 速查：ORA-12541 无监听（主机/端口错或服务未起）· ORA-01017 账密错误 · ORA-12170 连接超时（网络/防火墙）· ORA-00942 表不存在（检查 schema 前缀与对象权限）

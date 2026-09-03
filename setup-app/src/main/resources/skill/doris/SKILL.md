---
name: doris-db-ops
description: Apache Doris 数据库安全操作规范。通过 doris-* MCP 连接器执行 SQL 查询、表结构探查与受控写操作。当用户提到查 Doris、查库、查表、表结构、跑 SQL、统计数据、数据库连通性，或提及 uat/prod/生产/测试等环境名要求取数，以及呼唤 小D/小d 时使用。
version: 1.0.0
aliases:
  - 小D
  - 小d
---

# Apache Doris 数据库操作规范

适用于通过 `doris-*` 前缀 MCP 连接器操作 Apache Doris（MPP / OLAP）数据库的所有场景。核心原则：**环境优先、只读默认、写操作强确认**。

> Doris 兼容 MySQL 协议（默认端口 9030），连接配置以环境变量（MYSQL_HOST/PORT/USER/PASSWORD/DATABASE）注入，由 DB MCP Helper 在注册时写入各 AI 客户端的 mcp.json `env` 块；修改环境后需在对应客户端对 `doris-<env>` 执行关闭再开启。

## 唤醒词

- `小D`、`小d`

听到这些称呼时，仍须先按「环境识别」流程确认目标环境，再执行操作。

## 第一步：环境识别（每次必做）

本 skill 目录下的 `environments.md` 记录所有已配置环境：编码 → 连接器名 → 权限 → 别名。执行任何数据库操作前先读取该文件，将用户表达映射到具体连接器：

1. 用户明确说出环境编码或别名，且唯一命中 → 直接使用对应 `doris-<env>` 连接器
2. 别名歧义（如"测试"同时命中 test 与 uat）→ 反问用户确认，禁止猜测
3. 用户未提及环境或无法映射 → 反问用户，禁止默认假设任何环境
4. 跨环境对比数据时，每条结果必须标注来源环境

## 工具语义与选型

各 `doris-<env>` 连接器的可用工具以 `environments.md` 权限列为准：

- `query`：执行 SELECT（及 SHOW/DESCRIBE）返回结果。取数首选；引擎层强制仅读
- `insert`：INSERT。高危工具，见安全红线
- `update`：UPDATE。高危工具，见安全红线
- `delete`：DELETE。高危工具，见安全红线

选型口诀：取数用 query，写操作用 insert/update/delete。

## 标准工作流

1. 用 `query` 跑 `SELECT 1` 或 `SHOW TABLES` 确认目标环境连通
2. 用 `query` + `DESCRIBE <表>` / `information_schema` 探索结构
3. 构造查询，涉及大表先估算量级（EXPLAIN）
4. 需要写操作时，走安全红线流程后再执行

## Doris 注意事项

- Doris 为 MPP 列式存储引擎，大宽表查询性能优异，但单次查询返回行数可能极大，务必带 LIMIT
- `information_schema` 在 Doris 中可用，但部分 MySQL 系统表（如 `mysql.user`）不可用
- Doris 不支持事务型 UPDATE/DELETE 的 SAVEPOINT 语法，写操作需格外谨慎
- 聚合查询优先使用 Doris 原生聚合模型（ROLLUP / Materialized View），避免全表扫描

## 安全红线

- `insert`/`update`/`delete` 执行前必须：展示完整 SQL + 说明影响范围（目标表、预估行数）+ 获得用户明确确认
- UPDATE / DELETE 无 WHERE：主动提示会波及全表，需用户逐字确认
- 目标环境只有 `query` 工具（只读环境）：写请求应引导用户改用有写权限的环境，禁止任何变通绕过
- 禁止在对话中回显数据库密码；引用配置内容一律脱敏

## 大表防护

- COUNT(*) 或全表扫描前，先用 EXPLAIN 评估量级
- 大表禁止 SELECT *，必须限定列并带 WHERE / LIMIT 上限
- information_schema 的 table_rows 为估算值，精确行数需实时 COUNT

## 故障排查

- 修改环境配置后，须在对应 AI 客户端对 `doris-<env>` 执行关闭再开启才会重载 env
- 常见错误：Access denied（账密/权限）· Unknown database（MYSQL_DATABASE 错）· Can't connect（主机/端口/网络，Doris 默认查询端口 9030）· Table doesn't exist（确认 database 与表名大小写）

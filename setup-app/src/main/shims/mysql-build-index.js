'use strict';
// DB MCP Helper 内置 MySQL 工具包入口桥接。
// 适配器（MySqlAdapter）按 MYSQL_PASSWORD / MYSQL_DATABASE 注入环境变量；
// @benborla29/mcp-server-mysql 实际读取 MYSQL_PASS / MYSQL_DB。
// 这里做一次桥接后加载真实入口 dist/index.js。
// 注意：本包 package.json 为 "type":"module"（ESM），不能用 CommonJS 的 require() 加载，
// 否则启动即抛 ReferenceError: require is not defined in ES module scope。必须用动态 import()。
if (process.env.MYSQL_PASSWORD) process.env.MYSQL_PASS = process.env.MYSQL_PASSWORD;
if (process.env.MYSQL_DATABASE) process.env.MYSQL_DB = process.env.MYSQL_DATABASE;

// Doris 兼容修复：Doris 的 MySQL 协议兼容层无法处理 mysql2 握手时的 CONNECT_ATTRS
// 能力标志（会在 HandshakeResponse41 包尾部追加连接属性），导致连接立即被关闭，
// 表现为 PROTOCOL_CONNECTION_LOST。此处在加载真实 server 前 monkey-patch
// mysql2 的 ConnectionConfig.getDefaultFlags，去掉 CONNECT_ATTRS。
if (process.env.DB_MCP_DORIS === '1') {
  try {
    const { createRequire } = await import('module');
    const require = createRequire(import.meta.url);
    const mysql2 = require('mysql2');
    const origGetDefaultFlags = mysql2.ConnectionConfig.getDefaultFlags;
    mysql2.ConnectionConfig.getDefaultFlags = function(options) {
      return origGetDefaultFlags.call(this, options).filter(f => f !== 'CONNECT_ATTRS');
    };
    // Doris 的 MySQL 兼容层只支持"点查"的预处理语句，其余 COM_STMT_PREPARE
    // 一律回 "Only support prepare SelectStmt point query now"。
    // 因此把 execute（二进制预处理协议）降级为 query（文本协议），
    // 与 @benborla29 实现的行为对齐；MySQL 连接不受影响（不设 DB_MCP_DORIS）。
    const promise = require('mysql2/promise');
    for (const cls of [promise.PromisePool, promise.PromiseConnection, promise.PromisePoolConnection]) {
      if (!cls || !cls.prototype || typeof cls.prototype.execute !== 'function') continue;
      cls.prototype.execute = function(sql, values) {
        return this.query(sql, values);
      };
    }
  } catch (e) {
    // patch 失败不阻断启动，仅 Doris 连接会受影响
  }
}

await import('../dist/index.js');

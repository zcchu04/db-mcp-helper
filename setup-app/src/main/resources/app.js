/* ==========================================================================
   DB MCP Helper · app.js · 重构版主逻辑
   - 后端 24 REST 端点 + 新增 /api/prefs /api/reset/preview
   - 无框架、无 localStorage（Tauri 兼容），全内存 state + data-theme 切换
   - Hash 路由：#/welcome · #/setup/N · #/overview · #/instances[/dbId/env]
                #/runtime · #/diagnostics · #/system
   ========================================================================== */
"use strict";

/* ---------- Icons (16px 默认) ---------- */
const SVG_OPEN = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink:0;vertical-align:-2px">';
const IC = {
  plus:    SVG_OPEN+'<path d="M12 5v14M5 12h14"/></svg>',
  check:   SVG_OPEN+'<path d="M20 6 9 17l-5-5"/></svg>',
  play:    SVG_OPEN+'<polygon points="5 3 19 12 5 21 5 3"/></svg>',
  refresh: SVG_OPEN+'<path d="M3 12a9 9 0 0 1 15-6.7L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-15 6.7L3 16"/><path d="M3 21v-5h5"/></svg>',
  x:       SVG_OPEN+'<path d="M18 6 6 18M6 6l12 12"/></svg>',
  copy:    SVG_OPEN+'<rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>',
  trash:   SVG_OPEN+'<path d="M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>',
  kebab:   SVG_OPEN+'<circle cx="12" cy="12" r="1"/><circle cx="12" cy="5" r="1"/><circle cx="12" cy="19" r="1"/></svg>',
  edit:    SVG_OPEN+'<path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5z"/></svg>',
  db:      SVG_OPEN+'<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v14a9 3 0 0 0 18 0V5"/><path d="M3 12a9 3 0 0 0 18 0"/></svg>',
  right:   SVG_OPEN+'<path d="m9 18 6-6-6-6"/></svg>',
  search:  SVG_OPEN+'<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>',
  alert:   SVG_OPEN+'<path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><path d="M12 9v4M12 17h.01"/></svg>',
  info:    SVG_OPEN+'<circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/></svg>',
  rocket:  SVG_OPEN+'<path d="M4.5 16.5c-1.5 1.26-2 5-2 5s3.74-.5 5-2c.71-.84.7-2.13-.09-2.91a2.18 2.18 0 0 0-2.91-.09z"/><path d="m12 15-3-3a22 22 0 0 1 2-3.95A12.88 12.88 0 0 1 22 2c0 2.72-.78 7.5-6 11a22.35 22.35 0 0 1-4 2z"/><path d="M9 12H4s.55-3.03 2-4c1.62-1.08 5 0 5 0M12 15v5s3.03-.55 4-2c1.08-1.62 0-5 0-5"/></svg>',
  puzzle:  SVG_OPEN+'<path d="M19.439 7.85c-.049.322.059.747.213 1.048.278.543.166 1.098-.025 1.445l-.025.045a3.3 3.3 0 0 1-3.426 1.68l-.05-.01c-.3-.048-.747.047-1.048.213-.543.278-.784.723-.92 1.111l-.014.05a3.36 3.36 0 0 1-6.538.835l-.011-.05c-.049-.322.047-.747.213-1.048.278-.543.166-1.098-.025-1.445l-.025-.045a3.3 3.3 0 0 1-1.68-3.426l.01-.05c.048-.3.047-.747.213-1.048.278-.543.723-.784 1.111-.92l.05-.014a3.36 3.36 0 0 1 .835-6.538l.05.011c.322.049.747-.047 1.048-.213.543-.278 1.098-.166 1.445.025l.045.025a3.3 3.3 0 0 1 1.68 3.426l-.01.05c-.048.3.047.747.213 1.048.278.543.723.784 1.111.92l.05.014a3.36 3.36 0 0 1 6.538-.835l.011.05c.049.322-.047.747-.213 1.048-.278.543-.166 1.098.025 1.445l.025.045a3.3 3.3 0 0 1 3.426 1.68l.01.05"/></svg>',
  home:    SVG_OPEN+'<path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M9 22V12h6v10"/></svg>',
  grid:    SVG_OPEN+'<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>',
  diag:    SVG_OPEN+'<path d="M4.8 2.3A.3.3 0 1 0 5 2H4a2 2 0 0 0-2 2v5a6 6 0 0 0 6 6a6 6 0 0 0 6-6V4a2 2 0 0 0-2-2h-1a.2.2 0 1 0 .3.3"/><path d="M8 15v1a6 6 0 0 0 6 6a6 6 0 0 0 6-6v-4"/><circle cx="20" cy="10" r="2"/></svg>',
  gear:    SVG_OPEN+'<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>',
  collapse:SVG_OPEN+'<path d="m11 17-5-5 5-5"/><path d="m18 17-5-5 5-5"/></svg>',
  link:    SVG_OPEN+'<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>',
  eye:     SVG_OPEN+'<path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7z"/><circle cx="12" cy="12" r="3"/>',
  sun:     SVG_OPEN+'<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/></svg>',
  moon:    SVG_OPEN+'<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>',
  help:    SVG_OPEN+'<circle cx="12" cy="12" r="10"/><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/><path d="M12 17h.01"/></svg>',
  lock:    SVG_OPEN+'<rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>',
};
IC.eye = IC.eye + '</svg>';

/* ---------- 内置实例标识 → 别名预设 ---------- */
const ENV_ALIAS_PRESETS = {
  local:      ["本地"],
  dev:        ["开发","DEV","Development"],
  development:["开发","DEV"],
  test:       ["测试","Test"],
  sit:        ["SIT","系统集成测试"],
  uat:        ["UAT","验收","用户测试"],
  perf:       ["性能测试","Perf"],
  load:       ["压测"],
  stage:      ["预发","Staging"],
  staging:    ["预发","Staging"],
  pre:        ["预发"],
  preprod:    ["准生产","Preprod"],
  demo:       ["演示","Demo"],
  sample:     ["样例"],
  sandbox:    ["沙箱","Sandbox"],
  canary:     ["灰度","金丝雀"],
  prod:       ["生产","线上","正式","PRD"],
  production: ["生产","线上","正式"]
};
const ENV_CODE_SUGGESTIONS = ["dev","test","sit","uat","stage","preprod","prod","local","demo","sandbox","perf","canary"];
function aliasPresetFor(envCode){
  if (!envCode) return [];
  const lc = String(envCode).toLowerCase().trim();
  if (ENV_ALIAS_PRESETS[lc]) return ENV_ALIAS_PRESETS[lc].slice();
  const parts = lc.split(/[-_\s]+/).filter(Boolean);
  if (parts.length <= 1) return [];
  const base = ENV_ALIAS_PRESETS[parts[0]] || [];
  const suffix = parts.slice(1);
  if (base.length && suffix.length){
    const s = suffix.join("-").toUpperCase();
    return base.slice(0, 2).map(b => b + "·" + s);
  }
  return base.slice();
}
/* 用户已手改别名后，preset 只做「追加」不做「覆盖」 */
function autoFillAliasFromEnv(envCode){
  const preset = aliasPresetFor(envCode);
  if (!preset.length) return "";
  return preset.join(" · ");
}

/* ---------- Global state ---------- */
const S = {
  booted: false,
  detect: null,      // {home, root, rootExists, tapDeployed, javaCmd, mcpJsonPath, registeredServers, state:{envs, skillTargets, ...}}
  adapters: [],      // [{id, displayName, defaultPort, serverPrefix, skillDir, runtimeKind, allTools, requiredTools, mcpServerOptions}]
  mcpTargets: null,  // [{id, displayName, describe, icon, iconClass, tier, writable, cliBased, detected, candidatePaths, actualPath, existingServers}]
  prefs: { theme:"system", sidebarCollapsed:false, setupCompleted:false, lastEnv:null },
  route: null,
  slideOver: { dbId:null, env:null, tab:"overview", open:false },
  wizard: { step:1, dbId:null, env:null, alias:"", aliasTouched:false, password:"", pwdLocked:false, host:"", port:"", user:"", serviceOrDatabase:"", paste:"", tools:[], testResult:null, serverName:"", mcpServer:"" },
  filters: { q:"", dbType:"all", perm:"all", status:"all" },
  cmd: { open:false, q:"", idx:0 },
  modal: null,
  logPollTimer: null,
  runtimes: null,  // {java:{source,version,executable,available,override}, node:{...}}
};

/* ---------- Helpers ---------- */
const $ = (s, r=document) => r.querySelector(s);
const $$ = (s, r=document) => Array.from(r.querySelectorAll(s));
const esc = (v) => v == null ? "" : String(v).replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[c]));
function norm(p){ return p ? String(p).replace(/\\/g,"/") : ""; }

async function api(path, body, method){
  const opt = {};
  if (body || method){
    opt.method = method || "POST";
    opt.headers = {"Content-Type":"application/json"};
    opt.body = JSON.stringify(body || {});
  }
  const r = await fetch(path, opt);
  const j = await r.json();
  if (!j.ok) throw new Error(j.error || "请求失败");
  return j.data;
}

function adapter(id){ return S.adapters.find(a => a.id === id) || null; }
function envKey(dbId, env){ return dbId + "/" + env; }
function dbIcon(dbId){
  const a = adapter(dbId) || {};
  const raw = (a.displayName && a.displayName[0]) || (dbId && dbId[0]) || "?";
  const initial = String(raw).toUpperCase();
  const cls = dbId ? esc(dbId) : "unknown";
  return `<span class="db-icon ${cls}">${esc(initial)}</span>`;
}
function listEnvs(){
  const envs = (S.detect && S.detect.state && S.detect.state.envs) || {};
  const out = [];
  for (const code in envs){
    const e = envs[code];
    // 组合键 dbId + "/" + env 拆出 dbId 与 env（迁移后键必含 "/"；旧数据兜底用 info.dbType）
    const slash = code.indexOf("/");
    const dbId = slash >= 0 ? code.substring(0, slash) : (e && e.dbType) || "";
    const envCode = slash >= 0 ? code.substring(slash + 1) : code;
    const providers = (e && e.providers) || {};
    const keys = Object.keys(providers);
    if (keys.length === 0){
      // 连接存在但暂无任何实现（理论上不应发生）：仅列出连接本身，mcpServer 置空
      out.push({ env:envCode, dbId, mcpServer:"", info: {
        dbType:e.dbType, aliases:e.aliases||[], host:e.host, port:e.port, database:e.database,
        user:e.user, password:e.password, url:e.url, mcpServer:"", tools:[], serverName:"", registered:false, lastTest:null
      } });
      continue;
    }
    // 方案 B：同一连接可按实现拆成多个 provider，每个 provider 一张卡片、独立注册名
    for (const mcpServer of keys){
      const p = providers[mcpServer] || {};
      out.push({
        env:envCode, dbId, mcpServer,
        info: {
          dbType:e.dbType, aliases:e.aliases||[],
          host:e.host, port:e.port, database:e.database, user:e.user, password:e.password, url:e.url,
          mcpServer,
          tools: p.tools || [], serverName: p.serverName || "", registered: !!p.registered, lastTest: p.lastTest || null
        }
      });
    }
  }
  return out;
}
/* 默认连接器名（前端侧复算，与后端 DbAdapter.defaultServerName 规则一致）：
   默认实现=前缀+env；非默认实现=前缀+env-实现 */
function defaultServerNameFor(a, env, mcpServer){
  const pref = (a && a.serverPrefix) || (a && a.id) || "";
  if (!mcpServer) return pref + env;
  const opts = (a && a.mcpServerOptions) || [];
  const def = opts.length ? opts[0].id : null;
  if (def === mcpServer) return pref + env;
  return pref + env + "-" + mcpServer;
}
/* 实例实际使用的 MCP server 名称：已配置自定义名优先，否则按默认规则 */
function serverNameFor(dbId, env, mcpServer){
  const x = listEnvs().find(v => v.dbId === dbId && v.env === env && (v.mcpServer||"") === (mcpServer||""));
  return (x && x.info && x.info.serverName) || defaultServerNameFor(adapter(dbId) || {}, env, mcpServer);
}
function isRegistered(dbId, env, mcpServer){
  const list = (S.detect && S.detect.registeredServers) || [];
  return list.indexOf(serverNameFor(dbId, env, mcpServer)) >= 0;
}
function relTime(iso){
  if (!iso) return "";
  const t = Date.parse(iso); if (isNaN(t)) return iso;
  const d = (Date.now() - t) / 1000;
  if (d < 60) return "刚刚";
  if (d < 3600) return Math.floor(d/60) + " 分钟前";
  if (d < 86400) return Math.floor(d/3600) + " 小时前";
  if (d < 86400*30) return Math.floor(d/86400) + " 天前";
  return new Date(t).toISOString().slice(0,10);
}

/* ---------- Toast ---------- */
function toast(msg, type){
  const root = $("#toasts"); if (!root) return;
  const d = document.createElement("div");
  d.className = "toast" + (type ? " " + type : "");
  const ico = type === "err" ? IC.alert : type === "warn" ? IC.alert : type === "info" ? IC.info : IC.check;
  d.innerHTML = ico + "<span>" + esc(msg) + "</span>";
  root.appendChild(d);
  setTimeout(() => { d.style.opacity="0"; d.style.transform="translateX(12px)"; setTimeout(()=>d.remove(), 200); }, 3200);
}

/* ---------- 全屏加载遮罩（异步耗时操作期间给用户即时反馈） ---------- */
function showLoading(msg){
  hideLoading();
  const m = document.createElement("div");
  m.id = "loading-mask";
  m.className = "loading-mask";
  m.innerHTML = `<div class="loading-spinner"></div><div class="loading-msg">${esc(msg || "加载中…")}</div>`;
  document.body.appendChild(m);
}
function hideLoading(){
  const m = $("#loading-mask"); if (m) m.remove();
}

function fileToBase64(file){
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => { const s = r.result; resolve(s.indexOf(",") >= 0 ? s.split(",")[1] : s); };
    r.onerror = () => reject(r.error);
    r.readAsDataURL(file);
  });
}

function showModal(title, bodyHtml){
  openModal(`<div class="modal-header"><h3>${esc(title)}</h3><button class="so-close" onclick="closeModal()">${IC.x}</button></div><div class="modal-body">${bodyHtml}</div>`);
}

/* ---------- Theme ---------- */
function applyTheme(){
  const pref = S.prefs.theme || "system";
  let actual = pref;
  if (pref === "system"){
    actual = (window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches) ? "dark" : "light";
  }
  document.documentElement.setAttribute("data-theme", actual);
  const btn = $("#theme-toggle");
  if (btn) btn.innerHTML = (actual === "dark") ? IC.sun : IC.moon;
}
function toggleTheme(){
  const cur = document.documentElement.getAttribute("data-theme") || "light";
  S.prefs.theme = (cur === "dark") ? "light" : "dark";
  applyTheme();
  savePrefs({theme:S.prefs.theme});
}
async function savePrefs(patch){
  try { S.prefs = await api("/api/prefs", patch); }
  catch (e){ /* ignore */ }
}
async function openPath(target, mode, extra){
  const body = Object.assign({ target, mode: mode || "open" }, extra || {});
  try {
    const r = await api("/api/open", body);
    toast(r.message || "已提交系统调用", r.mode === "reveal-degraded" ? "info" : "");
    return r;
  } catch (e){ toast(e.message, "err"); }
}

/* ---------- Sidebar ---------- */
function applySidebar(){
  const sb = $("#sidebar"); if (!sb) return;
  sb.classList.toggle("collapsed", !!S.prefs.sidebarCollapsed);
}
function toggleSidebar(){
  S.prefs.sidebarCollapsed = !S.prefs.sidebarCollapsed;
  applySidebar();
  savePrefs({sidebarCollapsed:S.prefs.sidebarCollapsed});
}

/* ---------- Router ---------- */
const CRUMB = {
  welcome:"欢迎", setup:"接入向导", overview:"总览",
  instances:"MCP服务实例", implementations:"实现管理", runtime:"Skill 与运行时",
  diagnostics:"排障", system:"系统"
};
function parseHash(){
  const h = (location.hash || "#/overview").replace(/^#\/?/, "");
  const seg = h.split("/").filter(Boolean);
  if (!seg.length) return { name:"overview" };
  const name = seg[0];
  if (name === "setup") return { name:"setup", step: parseInt(seg[1]||"1",10) || 1 };
  if (name === "instances"){
    if (seg.length >= 3) return { name:"instances", dbId:seg[1], env:seg[2], mcpServer: seg.length >= 4 ? seg[3] : "" };
    return { name:"instances" };
  }
  return { name };
}
function navigate(hash){ location.hash = hash; }

async function onHash(){
  const r = parseHash();
  S.route = r;
  if (r.name === "instances" && r.dbId && r.env){
    openSlideOver(r.dbId, r.env, r.mcpServer || "");
    renderMain(); // still render list behind
  } else {
    closeSlideOver(true);
    renderMain();
  }
  updateCrumb();
  markActiveNav(r.name === "setup" ? "instances" : r.name);
  if (S.logPollTimer){ clearInterval(S.logPollTimer); S.logPollTimer = null; }
  if (r.name === "diagnostics") startLogPoll();
}

function updateCrumb(){
  const el = $("#crumb"); if (!el) return;
  el.textContent = CRUMB[S.route.name] || "总览";
}
function markActiveNav(name){
  $$(".side-item[data-route]").forEach(el => el.classList.toggle("active", el.dataset.route === name));
}

/* ---------- Boot ---------- */
async function boot(){
  try {
    const [det, adaptersR, prefs, implsR, runtimesR] = await Promise.all([
      api("/api/detect"),
      api("/api/adapters"),
      api("/api/prefs").catch(() => null),
      api("/api/impls").catch(() => null),
      api("/api/runtimes").catch(() => null)
    ]);
    S.detect = det;
    S.adapters = (adaptersR && adaptersR.adapters) || [];
    S.impls = implsR || {};
    S.runtimes = runtimesR;
    if (prefs) S.prefs = Object.assign(S.prefs, prefs);
    S.booted = true;
  } catch (e){
    document.body.innerHTML = '<div style="padding:64px;font-family:sans-serif"><h2>后端连接失败</h2><p>'+esc(e.message)+'</p><p style="color:#888">请刷新页面重试，或确认 DB MCP Helper 服务已启动。</p></div>';
    return;
  }
  applyTheme();
  applySidebar();
  bindShell();
  await onHash();
  // 心跳：周期性告知后端「浏览器壳仍活着」；后端看门狗据此在壳关闭后自动退出向导
  setInterval(() => { api("/api/heartbeat", {}).catch(() => {}); }, 15000);
}

/* ---------- Shell binding ---------- */
function bindShell(){
  const themeBtn = $("#theme-toggle");
  if (themeBtn) themeBtn.onclick = toggleTheme;
  const helpBtn = $("#help-btn");
  if (helpBtn) helpBtn.onclick = () => toast("按 ⌘K 或 Ctrl+K 打开命令面板；页面右下「?」查看快捷键。", "info");
  const cmdBtn = $("#cmd-open");
  if (cmdBtn) cmdBtn.onclick = openCmd;
  const collapse = $("#collapse-btn");
  if (collapse) collapse.onclick = toggleSidebar;
  $$(".side-item[data-route]").forEach(el => {
    el.onclick = () => navigate("#/" + el.dataset.route);
  });
  window.addEventListener("hashchange", onHash);
  document.addEventListener("keydown", onGlobalKey);
  $("#overlay").onclick = () => { closeSlideOver(); closeModal(); closeCmd(); };
  $("#cmd-input").oninput = (e) => { S.cmd.q = e.target.value; S.cmd.idx = 0; renderCmd(); };
  $("#cmd-input").onkeydown = onCmdKey;
}

/* ---------- Render main ---------- */
function renderMain(){
  const main = $("#main"); if (!main) return;
  if (!S.booted){ main.innerHTML = '<div class="page"><div class="empty"><p>加载中…</p></div></div>'; return; }
  const envs = listEnvs();
  const firstRun = !S.prefs.setupCompleted && envs.length === 0;
  const r = S.route;
  let html = "";
  try {
    switch (r.name){
      case "welcome":     html = pageWelcome(); break;
      case "setup":       html = pageSetup(r.step); break;
      case "overview":    html = pageOverview(); break;
      case "instances":   html = pageInstances(); break;
      case "implementations": html = pageImplementations(); break;
      case "runtime":     html = pageRuntime(); break;
      case "diagnostics": html = pageDiagnostics(); break;
      case "system":      html = pageSystem(); break;
      default:            html = firstRun ? pageWelcome() : pageOverview();
    }
  } catch (e){
    html = '<div class="page"><div class="card"><h3>页面渲染错误</h3><pre class="mono" style="color:var(--danger)">'+esc(e.stack || e.message)+'</pre></div></div>';
  }
  main.innerHTML = html;
  bindPage();
}

/* ==========================================================================
   PAGE: Welcome
   ========================================================================== */
function pageWelcome(){
  const d = S.detect || {};
  const envs = listEnvs();
  const okJava = !!d.javaCmd;
  const diskFree = true;
  const mcpExists = !!(d.registeredServers && d.registeredServers.length) || !!d.mcpJsonPath;
  const qoderPlugin = (d.qoderPluginRegisteredServers && d.qoderPluginRegisteredServers.length) ? "已注册 "+d.qoderPluginRegisteredServers.length+" 项" : "未安装，可忽略";
  const rows = [
    { ok: okJava, text: "Java 运行时 · " + norm(d.javaCmd || "(未检测到)") },
    { ok: true,  text: "工作目录 · " + norm(d.root) },
    { ok: mcpExists, text: "QoderWork mcp.json · " + norm(d.mcpJsonPath) },
    { ok: !!(d.qoderPluginRegisteredServers && d.qoderPluginRegisteredServers.length), warnOnly: true, text: "IDEA Qoder 插件 · " + qoderPlugin }
  ];
  return `<div class="page" style="max-width:920px">
    <div class="flex flex-col items-center" style="padding:var(--s7) 0 var(--s6); text-align:center">
      <div style="width:64px; height:64px; border-radius:14px; background:linear-gradient(135deg,var(--brand),#7c3aed); display:grid; place-items:center; color:#fff; margin-bottom:var(--s5)"><svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M4 7c0-1.66 3.58-3 8-3s8 1.34 8 3-3.58 3-8 3-8-1.34-8-3z"/><path d="M4 7v10c0 1.66 3.58 3 8 3s8-1.34 8-3V7"/><path d="M4 12c0 1.66 3.58 3 8 3s8-1.34 8-3"/></svg></div>
      <h1 style="font-size:26px; margin:0 0 var(--s3); letter-spacing:-.02em">把数据库接入 AI 客户端，只需 3 步</h1>
      <p class="text-sec" style="margin:0 0 var(--s2)">DB MCP Helper 帮你完成 Oracle / MySQL 的 MCP 服务器安装、注册、连接自检与 Skill 部署。</p>
      <p class="text-mut" style="margin:0">所有配置只留在本机，可随时一键清空。</p>
    </div>

    <div class="card">
      <div class="card-header"><h3>${IC.check} 环境检测</h3><button class="btn ghost sm" id="re-detect">${IC.refresh} 重新检测</button></div>
      ${rows.map(r => `<div class="tgt" style="background:transparent; padding:6px 0">
        <span class="badge ${r.ok?'ok':(r.warnOnly?'warn':'no')}">${r.ok?'通过':(r.warnOnly?'提示':'未通过')}</span>
        <span class="mono" style="font-size:12px">${esc(r.text)}</span>
      </div>`).join("")}
    </div>

    <div class="card">
      <div class="card-header"><h3>${IC.db} 支持的数据源</h3><span class="text-mut">${S.adapters.length} 种</span></div>
      <div class="db-picker">
        ${S.adapters.map(a => `<div class="db-pick">
          <div class="head">${dbIcon(a.id)}<div class="name">${esc(a.displayName)}</div></div>
          <div class="meta">端口 ${a.defaultPort} · ${a.allTools.length} 工具 · ${a.runtimeKind}</div>
          <div class="tools">${a.allTools.map(t => esc(t)).join(" · ")}</div>
        </div>`).join("")}
      </div>
    </div>

    <div class="flex gap-3" style="justify-content:center; padding:var(--s6) 0 var(--s4)">
      <button class="btn primary md" id="welcome-start">${IC.rocket} 快速接入向导</button>
      <button class="btn secondary md" id="welcome-skip">先看看管理台</button>
    </div>
  </div>`;
}

/* ==========================================================================
   PAGE: Setup (3 步)
   ========================================================================== */
function pageSetup(step){
  step = step || 1;
  S.wizard.step = step;
  const total = 3;
  const names = ["选择数据源与实现","配置实例","自检与注册"];
  const stepper = `<div class="stepper">${names.map((n,i) => {
    const idx = i+1;
    const cls = idx < step ? "done" : (idx === step ? "current" : "");
    return `<div class="step ${cls}"><span class="num">${idx < step ? "✓" : idx}</span><span>${esc(n)}</span></div>${i<total-1?'<div class="step-line"></div>':''}`;
  }).join("")}</div>`;
  if (step >= 2 && !S.wizard.dbId){
    return `<div class="page" style="max-width:1700px">
      <div class="page-title-row"><div><div class="page-title">接入向导</div><div class="page-sub">先完成第一步</div></div></div>
      ${stepper}
      <div class="card"><div class="empty">${IC.info}<h3>还没有选择数据源</h3><p>向导需要先在第一步选择数据源与实现。</p>
        <button class="btn primary" onclick="location.hash='#/setup/1'">返回第一步 ${IC.right}</button></div></div>
    </div>`;
  }
  let body = "";
  if (step === 1) body = setupStep1();
  else if (step === 2) body = setupStep2();
  else body = setupStep3();
  return `<div class="page" style="max-width:1700px">
    <div class="page-title-row"><div><div class="page-title">接入向导</div><div class="page-sub">3 步完成：${esc(names[step-1])}</div></div></div>
    ${stepper}
    ${body}
  </div>`;
}

/* 计算某实现的部署状态：null=未安装, 'ok'=已就绪, 'no-runtime'=已装但缺运行时 */
function implStatus(dbId, serverId){
  const impls = (S.detect && S.detect.impls) || S.impls || {};
  const info = impls[dbId] && impls[dbId][serverId];
  if (!info) return null;
  const rr = (S.detect && S.detect.runtimeReady) || {};
  if (!rr[dbId]) return 'no-runtime';
  return 'ok';
}

/* 状态徽章 HTML */
function implStatusBadge(dbId, serverId){
  const st = implStatus(dbId, serverId);
  if (st === null) return `<span class="impl-status-badge need-download">需下载</span>`;
  if (st === 'no-runtime') return `<span class="impl-status-badge need-runtime">缺运行时</span>`;
  return `<span class="impl-status-badge ok">✓ 已安装</span>`;
}

function setupStep1(){
  const curDb = S.wizard.dbId;
  const curImpl = S.wizard.mcpServer;
  return `<div class="wizard-split">
    <div>
      <div class="card">
        <div class="card-header"><h3>选择数据源与 MCP 服务实现</h3></div>
        <p class="card-desc">按数据源类型浏览，直接选择要接入的 MCP 服务实现。向导每次配置一个实例，添加更多可在完成后进「实例」页操作。</p>
        <div class="impl-groups">
          ${S.adapters.map(a => {
            const opts = a.mcpServerOptions || [];
            return `<div class="impl-group">
              <div class="impl-group-header">
                ${dbIcon(a.id)}
                <span class="impl-group-name">${esc(a.displayName)}</span>
                <span class="text-mut" style="font-size:12px; font-weight:400">默认端口 ${a.defaultPort} · ${a.runtimeKind} · ${opts.length ? opts.length + ' 种实现' : '内置实现'}</span>
              </div>
              <div class="impl-cards">
                ${opts.length ? opts.map(o => {
                  const on = curDb === a.id && curImpl === o.id;
                  const tools = o.allTools || a.allTools || [];
                  const show = tools.slice(0, 6);
                  const more = tools.length - show.length;
                  const st = implStatus(a.id, o.id);
                  const notInst = st === null ? 'not-installed' : '';
                  return `<div class="impl-card ${on?'on':''} ${notInst}" data-pick-impl data-db="${esc(a.id)}" data-impl="${esc(o.id)}">
                    ${implStatusBadge(a.id, o.id)}
                    <div class="impl-card-name">${esc(o.displayName)}</div>
                    ${o.description ? `<div class="impl-card-desc">${esc(o.description)}</div>` : ''}
                    <div class="impl-card-tools">${show.map(t => `<span class="impl-tool-chip">${esc(typeof t === 'string' ? t : t.name || t.id)}</span>`).join("")}${more > 0 ? `<span class="impl-card-tools-more">+${more}</span>` : ''}</div>
                  </div>`;
                }).join("") : (() => {
                    const dst = implStatus(a.id, "");
                    const dnotInst = dst === null ? 'not-installed' : '';
                    return `<div class="impl-card ${curDb===a.id?'on':''} ${dnotInst}" data-pick-impl data-db="${esc(a.id)}" data-impl="">
                    ${implStatusBadge(a.id, "")}
                    <div class="impl-card-name">默认实现</div>
                    <div class="impl-card-tools">${(a.allTools||[]).map(t => `<span class="impl-tool-chip">${esc(typeof t === 'string' ? t : t.name || t.id)}</span>`).join("")}</div>
                  </div>`;
                  })()}
              </div>
            </div>`;
          }).join("")}
        </div>
      </div>
      <div class="flex gap-3 mt-6" style="justify-content:flex-end">
        <button class="btn secondary" id="s1-cancel">取消</button>
        <button class="btn primary" id="s1-next" ${curDb?'':'disabled'}>${curDb && implStatus(curDb, curImpl) === null ? '下载并安装' : '下一步'} ${IC.right}</button>
      </div>
    </div>
    <aside class="wizard-side">
      <h4>本步会做什么</h4>
      <ul>
        <li>确认要接入的数据库类型与 MCP 服务实现</li>
        <li>如未部署 toolkit / tap，向导下一步会自动执行 <code>deploy</code></li>
        <li>Skill 目录同步到 QoderWork / Claude / Agents</li>
      </ul>
      <h4 style="margin-top:16px">小贴士</h4>
      <ul>
        <li>Oracle 走 JDBC + JAVA_JAR 运行时</li>
        <li>MySQL 走 Node 运行时 + 环境变量注入</li>
        <li>同一数据源可按不同实现拆成多个实例</li>
      </ul>
    </aside>
  </div>`;
}

/* 按所选 MCP server 实现渲染工具勾选清单（实现可自带 allTools/requiredTools，回退适配器级） */
function toolListHtml(a, srvId, checkedTools){
  const opts = a.mcpServerOptions || [];
  const opt = opts.find(o => o.id === srvId) || opts[0] || {};
  const all = (opt.allTools && opt.allTools.length) ? opt.allTools : (a.allTools || []);
  const req = (opt.requiredTools && opt.requiredTools.length) ? opt.requiredTools : (a.requiredTools || []);
  return all.map(t => `<label class="tool ${req.indexOf(t)>=0?'locked':''}">
    <input type="checkbox" data-tool="${esc(t)}" ${checkedTools.indexOf(t)>=0?'checked':''} ${req.indexOf(t)>=0?'disabled':''}>
    <span class="mono">${esc(t)}</span>
    ${req.indexOf(t)>=0?'<span class="tag req">必选</span>':(t.includes("write")||["insert","update","delete"].includes(t)?'<span class="tag danger">写权限</span>':'')}
  </label>`).join("");
}

/* MCP Server 名称全局唯一性实时校验：扫描所有实例（排除自身），命中即提示。
   命名规则与后端 validateServerName / Installer.validEnvName 对齐：小写字母开头，仅小写字母/数字/连字符，≤32 位 */
function checkServerNameUnique(){
  const el = $("#s2-servername");
  const hint = $("#s2-servername-hint");
  if (!el || !hint) return true;
  const sn = el.value.trim();
  if (!sn){ hint.className = "field-msg"; hint.textContent = ""; S.wizard.serverNameDup = false; return true; }
  if (!/^[a-z][a-z0-9-]{0,31}$/.test(sn)){
    hint.className = "field-msg err";
    hint.textContent = "名称格式不合法：小写字母开头，仅含小写字母/数字/连字符，≤32 位";
    S.wizard.serverNameDup = true;
    return false;
  }
  const w = S.wizard;
  const sel = $$('input[name="mcp-server-impl"]:checked')[0];
  const curImpl = sel ? sel.value : (w.mcpServer || "");
  const hit = listEnvs().find(v => v.info.serverName && v.info.serverName === sn
      && !(v.dbId === w.dbId && v.env === w.env && (v.mcpServer||"") === (curImpl||"")));
  if (hit){
    hint.className = "field-msg err";
    hint.textContent = "该名称已被实例 " + hit.dbId + "/" + hit.env + (hit.mcpServer ? (" (" + hit.mcpServer + ")") : "") + " 占用，需全局唯一";
    S.wizard.serverNameDup = true;
    return false;
  }
  hint.className = "field-msg ok";
  hint.textContent = "✓ 名称可用（全局唯一）";
  S.wizard.serverNameDup = false;
  return true;
}

function computeJdbcUrl(){
  const w = S.wizard;
  const a = adapter(w.dbId);
  if (!a) return "";
  const h = ($("#s2-host") || {}).value || w.host || "";
  const p = ($("#s2-port") || {}).value || w.port || a.defaultPort || "";
  const s = ($("#s2-service") || {}).value || w.serviceOrDatabase || "";
  let url = "";
  if (a.id === "oracle") url = "jdbc:oracle:thin:@" + h + ":" + p + "/" + s;
  else if (a.id === "mysql") url = "jdbc:mysql://" + h + ":" + p + "/" + s;
  else if (a.id === "doris") url = "jdbc:mysql://" + h + ":" + p + "/" + s;
  else url = h && p ? (h + ":" + p + (s ? "/" + s : "")) : "";
  const el = $("#s2-jdbc-display");
  if (el) el.textContent = url || "(请填写 Host / Port / " + (a.id === 'oracle' ? 'Service Name' : 'Database') + ")";
  return url;
}

function setupStep2(){
  const w = S.wizard;
  const a = adapter(w.dbId) || {defaultPort:0, allTools:[], requiredTools:[], mcpServerOptions:[], serverPrefix:(w.dbId||"")+"-"};
  // 回填定位：优先同一 (env, mcpServer) 连接器；新增实现（mcpServer 无匹配）时仅按 env 回填连接信息
  const all = listEnvs().filter(x => x.dbId === w.dbId);
  const cur = (w.env ? all.find(x => x.env === w.env && (w.mcpServer ? (x.mcpServer||"") === w.mcpServer : true)) : null)
           || (w.env ? all.find(x => x.env === w.env) : null)
           || (w.env ? null : all[0]);
  const env = w.env || (cur && cur.env) || "";
  const info = cur && cur.info;
  const sameImpl = !!(cur && (cur.mcpServer || "") === (w.mcpServer || ""));
  const srvOpts = a.mcpServerOptions || [];
  const curSrvRaw = w.mcpServer || (sameImpl && info && info.mcpServer) || "";
  const curSrv = srvOpts.some(o => o.id === curSrvRaw) ? curSrvRaw : (srvOpts[0] && srvOpts[0].id) || "";
  const tools = w.tools.length ? w.tools
              : (sameImpl && info && info.tools && info.tools.length) ? info.tools
              : ((srvOpts.find(o => o.id === curSrv) || {}).requiredTools || a.requiredTools || []).slice();
  const serverNameVal = (sameImpl ? (w.serverName || (info && info.serverName) || "") : "");
  const defServerName = (a.serverPrefix || (a.id||"") + "-") + (env || "env");
  return `<div class="wizard-split">
    <div>
      <div class="card">
        <div class="card-header">
          <h3>${dbIcon(a.id)} 配置 ${esc(a.displayName)} 实例${env?` <span class="chip mono" title="默认 MCP Server 名称">${esc(defServerName)}</span>`:""}</h3>
          <button class="btn ghost sm" id="s2-switch-db">切换数据源/实现</button>
        </div>
        <div class="field">
          <div class="field-label"><span>MCP Server 名称 (可选)</span><span class="field-hint">注册到 mcp.json 的连接器名 · 必须全局唯一 · 留空使用默认规则</span></div>
          <input class="input mono" id="s2-servername" placeholder="${esc(defServerName)}" value="${esc(serverNameVal)}">
          <div id="s2-servername-hint" class="field-msg"></div>
        </div>
        <div class="row">
          <div class="field">
            <div class="field-label"><span>实例环境标识 <span class="req">*</span></span><span class="field-hint">小写字母 / 数字 / 连字符</span></div>
            <input class="input mono" id="s2-env" placeholder="uat / dev / prod-report" value="${esc(env)}">
            <div class="flex gap-2 items-center" style="flex-wrap:wrap; margin-top:6px">
              <span class="text-mut" style="font-size:11px">常用：</span>
              ${ENV_CODE_SUGGESTIONS.slice(0,8).map(c => `<button class="chip-add mono" data-env-code="${esc(c)}">${esc(c)}</button>`).join("")}
            </div>
          </div>
          <div class="field">
            <div class="field-label"><span>别名 / 标签</span><span class="field-hint">多标签用 · 分隔</span></div>
            <input class="input" id="s2-alias" placeholder="验收 · 生产" value="${esc(w.alias || (info && info.aliases ? info.aliases.join(" · ") : ""))}">
            <div id="s2-alias-chips" class="flex gap-2 items-center" style="flex-wrap:wrap; margin-top:6px">
              <span class="text-mut" style="font-size:11px">根据标识推荐：</span>
              ${aliasPresetFor(env).map(t => `<button class="chip-add" data-alias-preset="${esc(t)}">+ ${esc(t)}</button>`).join("") || '<span class="text-mut" style="font-size:11px">输入实例环境标识后自动推荐</span>'}
            </div>
          </div>
        </div>
        <div class="field">
          <div class="field-label"><span>批量粘贴 (可选)</span><span class="field-hint">支持 jdbc URL / 简单 host=... 段落 / export 语句</span></div>
          <textarea class="textarea" id="s2-paste" placeholder="jdbc:oracle:thin:@//host:1521/svc  或  MYSQL_HOST=... MYSQL_PASSWORD=...">${esc(w.paste)}</textarea>
          <div class="flex gap-2 mt-4"><button class="btn secondary sm" id="s2-parse">解析并填充</button></div>
        </div>
        <div class="row">
          <div class="field"><div class="field-label">Host <span class="req">*</span></div>
            <input class="input mono" id="s2-host" value="${esc(w.host || (info && info.host) || "")}"></div>
          <div class="field"><div class="field-label">Port</div>
            <input class="input mono" id="s2-port" value="${esc(w.port || (info && info.port) || a.defaultPort)}"></div>
        </div>
        <div class="row">
          <div class="field"><div class="field-label">${a.id === 'oracle' ? 'Service Name / SID' : 'Database'}</div>
            <input class="input mono" id="s2-service" value="${esc(w.serviceOrDatabase || (info && info.database) || "")}"></div>
          <div class="field"><div class="field-label">User</div>
            <input class="input mono" id="s2-user" value="${esc(w.user || (info && info.user) || "")}"></div>
        </div>
        <div class="field"><div class="field-label"><span>Password ${w.pwdLocked?'<span class="badge info" style="margin-left:6px">'+IC.lock+' 已填充</span>':''}</span><span class="field-hint">${w.pwdLocked?'双击输入框解锁编辑':'数据库登录口令'}</span></div>
          <input class="input mono ${w.pwdLocked?'locked':''}" id="s2-pwd"
                 type="${w.pwdLocked?'text':'password'}"
                 ${w.pwdLocked?'readonly':''}
                 placeholder="${w.pwdLocked?('••••••  ('+( (w.password || (info && info.password) || "").length )+' 位) · 出于安全不回显 · 双击编辑'):'数据库登录口令'}"
                 value="${w.pwdLocked?'':esc(w.password || (info && info.password) || '')}"></div>
        <div class="field"><div class="field-label"><span>JDBC URL</span><span class="field-hint">根据 Host / Port / ${a.id === 'oracle' ? 'Service Name' : 'Database'} 自动拼接</span></div>
          <div class="input mono" id="s2-jdbc-display" style="color:var(--text-muted); cursor:default; user-select:all; background:var(--bg-inset)"></div></div>
        <div class="card tight" style="background:var(--bg-inset); margin-top:var(--s4)">
          <div class="field-label" style="margin-bottom:8px">MCP 服务实现</div>
          ${srvOpts.length ? srvOpts.map(o => `<label class="tool">
            <input type="radio" name="mcp-server-impl" value="${esc(o.id)}" data-mcp-server="${esc(o.id)}" ${curSrv===o.id?'checked':''}>
            <span>${esc(o.displayName)}</span>
            ${o.description?`<span style="margin-left:auto; font-size:10.5px; color:var(--text-muted); text-align:right">${esc(o.description)}</span>`:''}
          </label>`).join("") : '<p class="text-mut" style="font-size:11.5px">当前无可选项</p>'}
        </div>
        <div class="card tight" id="s2-tools-card" style="background:var(--bg-inset); margin-top:var(--s4)">
          <div class="field-label" style="margin-bottom:8px">启用工具（${tools.length} / ${((srvOpts.find(o => o.id === curSrv) || srvOpts[0] || {}).allTools || a.allTools || []).length}）</div>
          ${toolListHtml(a, curSrv, tools)}
        </div>
      </div>
      <div class="flex gap-3 mt-6" style="justify-content:flex-end">
        <button class="btn secondary" id="s2-back">${IC.right} 上一步</button>
        <button class="btn primary" id="s2-next">保存并下一步 ${IC.right}</button>
      </div>
    </div>
    <aside class="wizard-side">
      <h4>本步会做什么</h4>
      <ul>
        <li>写入 <code>${esc(norm((S.detect && S.detect.root) || ""))}/${esc(a.id||"?")}/instance/&lt;env&gt;/</code></li>
        <li>更新 <code>state.json</code> 环境索引</li>
        <li>同步 <code>environments.md</code> 映射</li>
      </ul>
      <h4 style="margin-top:16px">安全提示</h4>
      <ul>
        <li>密码仅存本机，不会外发</li>
        <li>mcp.json 每次改前生成 <code>.bak.&lt;ts&gt;</code> 备份</li>
        <li>写权限工具默认关闭，需显式勾选</li>
      </ul>
    </aside>
  </div>`;
}

function setupStep3(){
  const w = S.wizard;
  const a = adapter(w.dbId);
  const env = w.env;
  const tr = w.testResult;
  const s3ServerName = serverNameFor(w.dbId, w.env, w.mcpServer);
  return `<div class="wizard-split">
    <div>
      <div class="card">
        <div class="card-header"><h3>${dbIcon(w.dbId)} 连通自检 · <span class="chip mono">${esc(s3ServerName)}</span></h3></div>
        <p class="card-desc">进入本步自动执行连通自检；通过后在「完成向导」后自动拉起 mcp 注册弹框。</p>
        <div id="test-block">${tr ? renderTestResult(tr) : '<p class="text-mut">自检进行中…</p>'}</div>
        <div class="field-label" style="margin-top:14px">自检日志（实时）</div>
        <pre id="s3-term" class="term"></pre>
        <div class="flex gap-2 mt-4">
          <button class="btn primary" id="s3-test"${tr && tr.running ? " disabled" : ""}>${IC.play} 重新自检</button>
        </div>
      </div>
      <div class="card">
        <div class="card-header"><h3>${IC.puzzle} Skill 与运行时</h3></div>
        <p class="card-desc">向导完成后，Skill 会同步到：<span class="chip">~/.qoderwork/skills</span> <span class="chip">~/.agents/skills</span> <span class="chip">~/.claude/skills</span></p>
        <p class="text-mut" style="font-size:12px">如需增删目标目录，进「Skill 与运行时」页配置。</p>
      </div>
      <div class="flex gap-3 mt-6" style="justify-content:flex-end">
        <button class="btn secondary" id="s3-back">${IC.right} 上一步</button>
        <button class="btn primary" id="s3-done">完成向导 ${IC.check}</button>
      </div>
    </div>
    <aside class="wizard-side">
      <h4>本步会做什么</h4>
      <ul>
        <li>启动 <code>mcp-tap</code> 监听代理</li>
        <li>调用 toolkit 的 ping 工具验证连通</li>
        <li>右侧终端实时打印请求/响应明细</li>
        <li>完成后「完成向导」会同步 Skill 并拉起 mcp 注册</li>
      </ul>
      <h4 style="margin-top:16px">自检失败排查</h4>
      <ul>
        <li>Host/Port 不通 → 防火墙、VPC</li>
        <li>账号口令错误 → 检查 password / URL</li>
        <li>Oracle: <code>tnsping</code> 或 <code>sqlplus</code> 本地验证</li>
        <li>MySQL: <code>mysql -h ... -u ... -p</code> 本地验证</li>
      </ul>
    </aside>
  </div>`;
}

function renderTestResult(r){
  if (!r) return "";
  if (r.running) return `<div class="card tight" style="background:var(--info-soft); color:var(--info); display:flex; gap:8px; align-items:center">${IC.play} 自检执行中…</div>`;
  if (r.ok) return `<div class="card tight" style="border-left:3px solid var(--success); background:var(--success-soft)"><div class="flex items-center gap-2" style="color:var(--success); font-weight:500">${IC.check} 自检通过</div><div class="mono" style="font-size:11.5px; margin-top:4px">${esc(r.detail || "")}</div></div>`;
  return `<div class="card tight" style="border-left:3px solid var(--danger); background:var(--danger-soft)"><div class="flex items-center gap-2" style="color:var(--danger); font-weight:500">${IC.alert} 自检失败</div><div class="mono" style="font-size:11.5px; margin-top:4px">${esc(r.detail || r.error || "未知错误")}</div>${r.stderrTail ? `<details style="margin-top:6px"><summary style="cursor:pointer; font-size:11px; color:var(--danger)">查看进程日志</summary><pre class="mono" style="font-size:10.5px; margin-top:4px; max-height:200px; overflow:auto; white-space:pre-wrap; background:rgba(0,0,0,.04); padding:6px; border-radius:4px">${esc(r.stderrTail)}</pre></details>` : ""}</div>`;
}

/* ==========================================================================
   PAGE: Overview
   ========================================================================== */
function pageOverview(){
  const envs = listEnvs();
  const total = envs.length;
  let failed = 0, unreg = 0, ok = 0;
  envs.forEach(x => {
    const t = x.info.lastTest;
    if (t && !t.ok) failed++;
    else ok++;
    if (!isRegistered(x.dbId, x.env, x.mcpServer)) unreg++;
  });
  const rows = [];
  envs.forEach(x => {
    const t = x.info.lastTest;
    if (t && !t.ok) rows.push({ sev:"err", x, msg:"自检失败", hint:"重跑自检或改连接参数", action:"retest" });
    else if (!isRegistered(x.dbId, x.env, x.mcpServer)) rows.push({ sev:"warn", x, msg:"未注册到 mcp.json", hint:"点注册即可", action:"register" });
  });
  return `<div class="page">
    <div class="page-title-row"><div><div class="page-title">总览</div><div class="page-sub">实例、注册状态与最近一次自检</div></div>
      <div class="flex gap-2"><button class="btn secondary sm" id="ov-refresh">${IC.refresh} 刷新</button><button class="btn primary sm" id="ov-add">${IC.plus} 添加实例</button></div>
    </div>
    ${!S.prefs.setupCompleted ? `<div class="banner"><span>${IC.rocket}</span><span>首次接入未完成，<a href="#/setup/1" style="color:var(--brand); font-weight:500">继续向导 →</a></span><span class="dismiss" id="banner-x">关闭</span></div>` : ""}
    <div class="metrics">
      <div class="metric"><div class="label">实例总数</div><div class="value">${total}</div></div>
      <div class="metric tone-success"><div class="label">自检通过</div><div class="value">${ok}</div></div>
      <div class="metric tone-warn"><div class="label">未注册</div><div class="value">${unreg}</div></div>
      <div class="metric tone-danger"><div class="label">自检失败</div><div class="value">${failed}</div></div>
    </div>
    <div class="card">
      <div class="card-header"><h3>需要关注</h3></div>
      ${rows.length === 0 ? `<div class="empty"><div style="color:var(--success)">${IC.check}</div><p>所有实例状态良好</p></div>` : `
      <table class="tbl"><thead><tr><th>状态</th><th>实例</th><th>问题</th><th>建议动作</th></tr></thead><tbody>
        ${rows.map(r => `<tr class="clickable" data-open="${esc(r.x.dbId)}/${esc(r.x.env)}">
          <td><span class="badge ${r.sev==='err'?'no':'warn'}">${r.sev==='err'?'异常':'提醒'}</span></td>
          <td>${dbIcon(r.x.dbId)} ${esc(r.x.dbId)}/${esc(r.x.env)}/${esc(r.x.mcpServer)}</td>
          <td>${esc(r.msg)}</td>
          <td>${esc(r.hint)}</td>
        </tr>`).join("")}
      </tbody></table>`}
    </div>
    <div class="card">
      <div class="card-header"><h3>实例速览</h3><div class="actions"><button class="btn ghost sm" id="ov-goto-inst">查看全部 →</button></div></div>
      ${total === 0 ? `<div class="empty">${IC.db}<h3>还没有任何实例</h3><p>点右上「添加实例」开始接入 Oracle 或 MySQL。</p></div>` : `
      <div class="inst-grid">${envs.map(instCard).join("")}</div>`}
    </div>
  </div>`;
}

/* ==========================================================================
   PAGE: Instances
   ========================================================================== */
function pageInstances(){
  const envs = listEnvs().filter(instFilter);
  const f = S.filters;
  return `<div class="page">
    <div class="page-title-row"><div><div class="page-title">MCP服务实例</div><div class="page-sub">${listEnvs().length} 个实现实例 · 同一数据库/环境可按不同 MCP 提供方拆成多个独立连接器</div></div>
      <div class="flex gap-2"><button class="btn secondary sm" id="inst-refresh">${IC.refresh} 刷新</button><button class="btn primary sm" id="inst-add">${IC.plus} 添加实例</button></div>
    </div>
    <div class="filter-bar">
      <div class="search" style="flex:1; max-width:320px">
        ${IC.search}<input class="input" placeholder="搜索 code / 别名 / host / user…" value="${esc(f.q)}" id="inst-q">
      </div>
      <div class="seg" data-filter="dbType">
        <button class="${f.dbType==='all'?'on':''}" data-v="all">全部</button>
        ${S.adapters.map(a => `<button class="${f.dbType===a.id?'on':''}" data-v="${esc(a.id)}">${esc(a.displayName)}</button>`).join("")}
      </div>
      <div class="seg" data-filter="perm">
        <button class="${f.perm==='all'?'on':''}" data-v="all">全部权限</button>
        <button class="${f.perm==='ro'?'on':''}" data-v="ro">只读</button>
        <button class="${f.perm==='rw'?'on':''}" data-v="rw">读写</button>
      </div>
      <div class="seg" data-filter="status">
        <button class="${f.status==='all'?'on':''}" data-v="all">任意状态</button>
        <button class="${f.status==='err'?'on':''}" data-v="err">异常</button>
        <button class="${f.status==='unreg'?'on':''}" data-v="unreg">未注册</button>
        <button class="${f.status==='reg'?'on':''}" data-v="reg">已注册</button>
      </div>
    </div>
    ${envs.length === 0 ? `<div class="card"><div class="empty">
      <div>${IC.db}</div>
      <h3>没有匹配的实例</h3>
      <p>调整筛选或添加新实例。</p>
      <button class="btn primary" id="inst-add-2">${IC.plus} 添加实例</button>
    </div></div>` : `<div class="inst-grid">${envs.map(instCard).join("")}</div>`}
  </div>`;
}
function instFilter(x){
  const f = S.filters;
  if (f.dbType !== "all" && x.dbId !== f.dbType) return false;
  const rwTools = ["write-query","insert","update","delete"];
  const isRw = (x.info.tools || []).some(t => rwTools.includes(t));
  if (f.perm === "ro" && isRw) return false;
  if (f.perm === "rw" && !isRw) return false;
  const t = x.info.lastTest;
  const reg = isRegistered(x.dbId, x.env, x.mcpServer);
  if (f.status === "err" && !(t && !t.ok)) return false;
  if (f.status === "unreg" && reg) return false;
  if (f.status === "reg" && !reg) return false;
  if (f.q){
    const q = f.q.toLowerCase();
    const hay = [x.env, x.dbId, (x.info.aliases||[]).join(" "), x.info.host||"", x.info.user||"", x.info.database||""].join(" ").toLowerCase();
    if (!hay.includes(q)) return false;
  }
  return true;
}
function instCard(x){
  const a = adapter(x.dbId) || {};
  const t = x.info.lastTest;
  const reg = isRegistered(x.dbId, x.env, x.mcpServer);
  const rwTools = ["write-query","insert","update","delete"];
  const isRw = (x.info.tools || []).some(tt => rwTools.includes(tt));
  const alias = (x.info.aliases||[]).join(" · ");
  const serverName = serverNameFor(x.dbId, x.env, x.mcpServer);
  // 数据库类型名 + 实际 MCP server 实现显示名
  const dbTypeName = a.displayName || x.dbId || "—";
  const srvOpts = a.mcpServerOptions || [];
  const curSrvRaw = x.info.mcpServer || "";
  const srvOpt = srvOpts.find(o => o.id === curSrvRaw) || srvOpts[0] || {};
  const srvDisplay = srvOpt.displayName || curSrvRaw || "默认实现";
  const srvDesc = srvOpt.description || "";
  return `<div class="inst-card" data-open="${esc(x.dbId)}/${esc(x.env)}/${esc(x.mcpServer)}">
    <button class="icon-btn kebab" data-kebab="${esc(x.dbId)}/${esc(x.env)}/${esc(x.mcpServer)}">${IC.kebab}</button>
    <div class="head">${dbIcon(x.dbId)}<div class="env" title="MCP Server 名称 · ${esc(serverName)}">${esc(serverName)}</div></div>
    <div class="alias">${esc(x.env)}${alias?" · "+esc(alias):""} · ${esc(dbTypeName)} · ${esc(srvDisplay)}</div>
    <div class="meta">${esc(x.info.host||"—")}:${x.info.port||a.defaultPort||""}${x.info.user?" · "+esc(x.info.user):""}</div>
    <div class="info-grid">
      <div class="info-cell"><span class="info-key">连接器名</span><span class="info-val mono" title="MCP Server 名称">${esc(serverName)}</span></div>
      <div class="info-cell"><span class="info-key">MCP 实现</span><span class="info-val" title="${esc(srvDesc)}">${esc(srvDisplay)}</span></div>
    </div>
    <div class="badges">
      <span class="badge ${isRw?'warn':'ok'}"><span class="dot"></span>${isRw?'读写':'只读'}</span>
      <span class="badge ${reg?'ok':'neutral'}">${reg?'已注册':'未注册'}</span>
      ${t?`<span class="badge ${t.ok?'info':'no'}">自检 ${t.ok?'✓':'✗'} · ${esc(relTime(t.ts))}</span>`:""}
    </div>
    <div class="quick">
      <button class="btn ghost sm" data-retest="${esc(x.dbId)}/${esc(x.env)}/${esc(x.mcpServer)}">${IC.play} 自检</button>
      <button class="btn ghost sm" data-config="${esc(x.dbId)}/${esc(x.env)}/${esc(x.mcpServer)}">${IC.edit} 配置</button>
      <button class="btn ghost sm" data-mcp="${esc(x.dbId)}/${esc(x.env)}/${esc(x.mcpServer)}">${IC.link} MCP 注册</button>
    </div>
  </div>`;
}

/* ==========================================================================
   PAGE: Implementations
   ========================================================================== */
async function refreshImpls(){
  try { S.impls = await api("/api/impls") || {}; } catch(e){}
}
async function refreshRuntimes(){
  try { S.runtimes = await api("/api/runtimes") || null; } catch(e){}
}
function pageImplementations(){
  const impls = S.impls || {};
  const adapters = S.adapters || [];
  const groups = adapters.map(a => {
    const dbImpls = impls[a.id] || {};
    const opts = a.mcpServerOptions || [];
    const cards = opts.map(opt => {
      const info = dbImpls[opt.id] || null;
      return { opt, info };
    });
    return { adapter: a, cards };
  }).filter(g => g.cards.length > 0);
  const totalImpls = groups.reduce((s, g) => s + g.cards.filter(c => c.info).length, 0);
  return `<div class="page">
    <div class="page-title-row"><div><div class="page-title">MCP 服务实现管理</div><div class="page-sub">${adapters.length} 种数据源 · ${totalImpls} 个已安装实现</div></div>
      <div class="flex gap-2"><button class="btn secondary sm" id="impl-refresh">${IC.refresh} 刷新</button></div>
    </div>
    <div class="impl-groups">
      ${groups.map(g => `<div class="impl-group">
        <div class="impl-group-header">${dbIcon(g.adapter.id)}<span class="impl-group-name">${esc(g.adapter.displayName)}</span></div>
        <div class="impl-cards">${g.cards.map(c => implMgmtCard(g.adapter, c.opt, c.info)).join("")}</div>
      </div>`).join("")}
    </div>
  </div>`;
}
function implMgmtCard(a, opt, info){
  const sourceLabel = { builtin:"内置", uploaded:"用户上传", github:"GitHub" };
  const installed = !!info;
  const version = info ? (info.version || "—") : "未安装";
  const source = info ? (sourceLabel[info.source] || info.source || "—") : "—";
  const date = info && info.installedAt ? relTime(info.installedAt) : "—";
  const tools = opt.allTools || [];
  return `<div class="mgmt-card ${installed?'':'empty'}">
    <div class="mgmt-head">
      <div class="mgmt-name">${esc(opt.displayName || opt.id)}</div>
      ${installed ? `<span class="badge ok"><span class="dot"></span>已安装</span>` : `<span class="badge neutral">未安装</span>`}
    </div>
    ${opt.description ? `<div class="mgmt-desc">${esc(opt.description)}</div>` : ""}
    ${tools.length ? `<div class="impl-card-tools">${tools.slice(0,8).map(t => `<span class="impl-tool-chip">${esc(t)}</span>`).join("")}${tools.length > 8 ? `<span class="impl-card-tools-more">+${tools.length-8}</span>` : ""}</div>` : ""}
    <div class="mgmt-meta">
      <span>版本 <strong>${esc(version)}</strong></span>
      <span>来源 <strong>${esc(source)}</strong></span>
      <span>安装 ${esc(date)}</span>
    </div>
    <div class="mgmt-actions">
      <button class="btn ghost sm" data-impl-upload="${esc(a.id)}/${esc(opt.id)}"${!installed?' disabled':''}>${IC.edit} 上传替换</button>
      <button class="btn ghost sm" data-impl-rollback="${esc(a.id)}/${esc(opt.id)}"${!installed?' disabled':''}>${IC.refresh} 回滚</button>
      <button class="btn ghost sm" data-impl-bak="${esc(a.id)}/${esc(opt.id)}"${!installed?' disabled':''}>${IC.eye} 历史版本</button>
      <button class="btn ghost sm" data-impl-check-update="${esc(a.id)}/${esc(opt.id)}">${IC.refresh} 检查更新</button>
      <button class="btn ghost sm" data-impl-github-dl="${esc(a.id)}/${esc(opt.id)}">${IC.play} 从GitHub下载</button>
    </div>
  </div>`;
}

/* ==========================================================================
   PAGE: Runtime
   ========================================================================== */
function pageRuntime(){
  const st = (S.detect && S.detect.state) || {};
  const targets = st.skillTargets || [];
  const tapOk = !!(S.detect && S.detect.tapDeployed);
  const rt = S.runtimes || {};
  return `<div class="page">
    <div class="page-title-row"><div><div class="page-title">Skill 与运行时</div><div class="page-sub">管理 Skill 部署目标目录、toolkit / tap 状态</div></div>
      <div class="flex gap-2"><button class="btn secondary sm" id="rt-sync">${IC.refresh} 同步映射</button><button class="btn primary sm" id="rt-add-target">${IC.plus} 新增目标</button></div>
    </div>
    <div class="card">
      <div class="card-header"><h3>${IC.grid} 部署目标</h3></div>
      <div class="tgt-list">
        ${targets.length === 0 ? `<p class="text-mut">未配置任何 Skill 目标目录。</p>` : targets.map(t => `<div class="tgt"><span class="path">${esc(norm(t))}</span><button class="btn ghost sm" data-rm-target="${esc(t)}">${IC.trash} 移除</button></div>`).join("")}
      </div>
    </div>
    <div class="card">
      <div class="card-header"><h3>${IC.puzzle} 每个数据源的 Skill</h3></div>
      ${S.adapters.map(a => {
        const custom = listEnvs().filter(v => v.dbId === a.id && v.info.serverName).map(v => v.info.serverName);
        const deployed = targets.some(t => {
          const has = (S.detect && S.detect.registeredServers || []).some(n => n.startsWith(a.serverPrefix) || custom.indexOf(n) >= 0);
          return has;
        });
        return `<div class="tgt"><span>${dbIcon(a.id)}</span><span class="path">${esc(a.displayName)} → <code>${esc(a.skillDir)}</code></span><span class="badge ${deployed?'ok':'neutral'}">${deployed?'可用':'未启用'}</span></div>`;
      }).join("")}
    </div>
    <div class="card">
      <div class="card-header"><h3>${IC.play} 运行时</h3></div>
      <dl class="kv">
        <dt>Tap 代理</dt><dd>${tapOk ? "已部署" : "未部署"}</dd><dt></dt>
        <dt>Java 命令</dt><dd>${esc(norm(S.detect.javaCmd))}</dd><dt></dt>
        <dt>工作目录</dt><dd>${esc(norm(S.detect.root))}</dd><dt></dt>
        <dt>QoderWork mcp.json</dt><dd>${esc(norm(S.detect.mcpJsonPath))}</dd><dt></dt>
        <dt>IDEA Qoder mcp.json</dt><dd>${esc(norm(S.detect.qoderPluginMcpJsonPath))}</dd><dt></dt>
        ${(S.detect.mcpClientPaths||[]).map(c => `<dt>${esc(c.name)} mcp.json</dt><dd><code>${esc(norm(c.path))}</code></dd><dt></dt>`).join("")}
      </dl>
    </div>
    <div class="card">
      <div class="card-header"><h3>${IC.gear} 运行时环境</h3>
        <button class="btn ghost sm" id="rt-env-refresh">${IC.refresh} 刷新</button>
      </div>
      <div class="rt-env-cards">
        ${runtimeCard("java", "Java (JRE)", rt.java)}
        ${runtimeCard("node", "Node.js", rt.node)}
      </div>
    </div>
  </div>`;
}
function runtimeCard(kind, label, info){
  if (!info) return `<div class="rt-env-card"><div class="rt-env-head"><strong>${esc(label)}</strong><span class="badge neutral">未检测</span></div><p class="text-mut">点击刷新获取运行时信息。</p></div>`;
  const srcLabel = { bundled:"内置", local:"用户指定", system:"系统 PATH" };
  const src = srcLabel[info.source] || info.source || "—";
  const ok = info.available;
  return `<div class="rt-env-card">
    <div class="rt-env-head">
      <strong>${esc(label)}</strong>
      ${ok ? `<span class="badge ok"><span class="dot"></span>可用</span>` : `<span class="badge warn">不可用</span>`}
    </div>
    <dl class="kv">
      <dt>来源</dt><dd><span class="badge neutral">${esc(src)}</span></dd><dt></dt>
      <dt>版本</dt><dd>${esc(info.version || "—")}</dd><dt></dt>
      <dt>路径</dt><dd class="rt-env-path">${esc(norm(info.executable))}</dd><dt></dt>
      ${info.override ? `<dt>覆盖路径</dt><dd class="rt-env-path">${esc(norm(info.override))}</dd><dt></dt>` : ""}
    </dl>
    <div class="rt-env-actions">
      <button class="btn secondary sm" data-rt-set-local="${esc(kind)}">${IC.edit} 选择本地目录</button>
      <button class="btn ghost sm" data-rt-github-dl="${esc(kind)}">${IC.play} 从GitHub下载</button>
      ${info.override ? `<button class="btn ghost sm" data-rt-reset="${esc(kind)}">${IC.refresh} 恢复默认</button>` : ""}
      <button class="btn ghost sm" data-rt-check="${esc(kind)}">${IC.eye} 检测兼容性</button>
    </div>
  </div>`;
}

/* ==========================================================================
   PAGE: Diagnostics
   ========================================================================== */
function pageDiagnostics(){
  return `<div class="page">
    <div class="page-title-row"><div><div class="page-title">排障</div><div class="page-sub">调用日志与常用诊断动作</div></div>
      <div class="flex gap-2"><button class="btn secondary sm" id="diag-refresh">${IC.refresh} 刷新</button></div>
    </div>
    <div class="card">
      <div class="card-header"><h3>选择实例</h3></div>
      <div id="diag-env-blocks" class="env-blocks">
        ${listEnvs().map(x => `<button class="env-block" data-val="${esc(x.dbId)}/${esc(x.env)}/${esc(x.mcpServer)}"><span class="eb-db">${esc(x.dbId)}</span><span class="eb-sep">/</span><span class="eb-env">${esc(x.env)}</span>${x.mcpServer?`<span class="eb-sep">/</span><span class="eb-srv">${esc(x.mcpServer)}</span>`:""}</button>`).join("")}
      </div>
      <div id="diag-log" class="mono" style="font-size:12px; max-height:60vh; overflow:auto; padding:8px; background:var(--bg-inset); border-radius:var(--r-sm)"><p class="text-mut">加载中…</p></div>
    </div>
    <div class="card">
      <div class="card-header"><h3>常用动作</h3></div>
      <div class="flex gap-2 flex-wrap">
        <button class="btn secondary sm" id="diag-retest-all">${IC.play} 一键自检全部</button>
        <button class="btn secondary sm" id="diag-open-root">${IC.edit} 打开工作目录</button>
        <button class="btn ghost sm" id="diag-reveal-root">${IC.db} 定位工作目录</button>
      </div>
    </div>
  </div>`;
}
function startLogPoll(){
  const blocks = $$("#diag-env-blocks .env-block");
  const box = $("#diag-log");
  if (!box) return;
  let diagSel = "";
  const load = async () => {
    if (!diagSel){ box.innerHTML = '<p class="text-mut">选择一个实例查看调用日志。</p>'; return; }
    const [dbId, env] = diagSel.split("/");
    try {
      const r = await api("/api/env/log?dbId="+encodeURIComponent(dbId)+"&env="+encodeURIComponent(env)+"&limit=200");
      const lines = (r.lines || []).map(l => {
        try {
          const o = JSON.parse(l);
          const ok = o.ok !== false;
          return `<div class="log-row"><span>${esc(o.ts || "")}</span><span>${esc(o.tool || "")}</span><span class="${ok?'st-ok':'st-err'}">${ok?'OK':'ERR'}</span><span class="dur">${o.dur||''}</span><span>${esc((o.sql||o.detail||"").slice(0,120))}</span></div>`;
        } catch { return '<div class="log-row"><span colspan="5">'+esc(l)+'</span></div>'; }
      });
      box.innerHTML = '<div class="log-row" style="background:var(--bg-elevated); font-weight:600"><span>时间</span><span>工具</span><span>状态</span><span class="dur">耗时</span><span>详情</span></div>' + (lines.join("") || '<p class="text-mut">暂无日志。</p>');
    } catch (e){ box.innerHTML = '<p style="color:var(--danger)">'+esc(e.message)+'</p>'; }
  };
  blocks.forEach(b => b.onclick = () => {
    diagSel = b.dataset.val;
    blocks.forEach(x => x.classList.remove("on"));
    b.classList.add("on");
    load();
  });
  if (S.logPollTimer) clearInterval(S.logPollTimer);
  S.logPollTimer = setInterval(load, 6000);
  // 默认选中第一个实例，进入即见日志（比下拉框更友好）
  if (blocks[0]) blocks[0].click();
}

/* ==========================================================================
   PAGE: System (含危险区 dry-run)
   ========================================================================== */
function pageSystem(){
  const d = S.detect || {};
  const st = d.state || {};
  return `<div class="page">
    <div class="page-title-row"><div><div class="page-title">系统</div><div class="page-sub">运行时信息、prefs、危险区</div></div></div>
    <div class="card">
      <div class="card-header"><h3>环境</h3><div class="actions"><button class="btn ghost sm" id="sys-reload">${IC.refresh} 重新检测</button></div></div>
      <dl class="kv">
        <dt>Home</dt><dd>${esc(norm(d.home))}</dd><dt></dt>
        <dt>工作目录</dt><dd>${esc(norm(d.root))} ${d.rootExists?'<span class="badge ok">存在</span>':'<span class="badge neutral">未创建</span>'}</dd><dt></dt>
        <dt>Java 命令</dt><dd>${esc(norm(d.javaCmd))}</dd><dt></dt>
        <dt>Tap 代理</dt><dd>${d.tapDeployed?'已部署':'未部署'}</dd><dt></dt>
        <dt>QoderWork mcp.json</dt><dd>${esc(norm(d.mcpJsonPath))}</dd><dt></dt>
        <dt>IDEA Qoder mcp.json</dt><dd>${esc(norm(d.qoderPluginMcpJsonPath))}</dd><dt></dt>
        ${(d.mcpClientPaths||[]).map(c => `<dt>${esc(c.name)} mcp.json</dt><dd><code>${esc(norm(c.path))}</code></dd><dt></dt>`).join("")}
        <dt>已注册 server</dt><dd>${(d.registeredServers||[]).map(s=>'<code>'+esc(s)+'</code>').join(" · ") || '<span class="text-mut">无</span>'}</dd><dt></dt>
        <dt>prefs.json</dt><dd>${esc(norm(d.root))}/prefs.json · 主题 <code>${esc(S.prefs.theme||"system")}</code></dd><dt></dt>
      </dl>
    </div>
    <div class="card">
      <div class="card-header"><h3>状态文件</h3><div class="actions"><button class="btn ghost sm" id="sys-show-state">查看 state.json</button></div></div>
      <div id="sys-state-box" class="mono" style="font-size:11.5px; max-height:300px; overflow:auto; padding:8px; background:var(--bg-inset); border-radius:var(--r-sm); display:none">${esc(JSON.stringify(st, null, 2))}</div>
      <p class="text-mut" style="font-size:12px">state.json 保存所有实例配置与最近自检快照，明文口令仅在本机。</p>
    </div>
    <div class="card" style="border-color:var(--danger)">
      <div class="card-header"><h3 style="color:var(--danger)">${IC.alert} 危险区</h3></div>
      <p class="text-mut" style="font-size:12.5px; margin:0 0 var(--s4)">以下动作不可撤回（部分资源会先备份或移入回收站）。</p>
      <div class="flex gap-2 flex-wrap">
        <button class="btn danger-ghost" id="sys-reset">${IC.trash} 清空全部配置</button>
        <button class="btn danger-ghost" id="sys-uninstall">${IC.alert} 完全卸载</button>
      </div>
    </div>
  </div>`;
}

/* ==========================================================================
   Slide-over
   ========================================================================== */
function openSlideOver(dbId, env, mcpServer){
  let x = listEnvs().find(e => e.dbId === dbId && e.env === env && (e.mcpServer||"") === (mcpServer||""));
  if (!x){
    // 未指定实现（旧链接/未带 mcpServer）时，回退到该连接下首个实现（默认实现优先）
    const all = listEnvs().filter(e => e.dbId === dbId && e.env === env);
    if (!all.length){ toast("实例不存在：" + dbId + "/" + env, "err"); return; }
    x = all[0];
  }
  S.slideOver = { dbId, env, mcpServer: x.mcpServer, tab: S.slideOver.dbId===dbId && S.slideOver.env===env && (S.slideOver.mcpServer||"")===(x.mcpServer||"") ? S.slideOver.tab : "overview", open:true };
  $("#overlay").classList.add("on");
  const so = $("#slideover");
  so.innerHTML = renderSlideOver(x);
  so.classList.add("on");
  bindSlideOver(x);
}
function closeSlideOver(silent){
  S.slideOver.open = false;
  $("#overlay").classList.remove("on");
  const so = $("#slideover");
  so.classList.remove("on");
  if (!silent && location.hash.startsWith("#/instances/")){
    history.replaceState(null, "", "#/instances");
  }
}
function renderSlideOver(x){
  const a = adapter(x.dbId) || {};
  const t = x.info.lastTest;
  const reg = isRegistered(x.dbId, x.env, x.mcpServer);
  const rwTools = ["write-query","insert","update","delete"];
  const isRw = (x.info.tools || []).some(tt => rwTools.includes(tt));
  const alias = (x.info.aliases||[]);
  const srvOpts2 = a.mcpServerOptions || [];
  const srvOpt2 = srvOpts2.find(o => o.id === x.mcpServer) || srvOpts2[0] || {};
  const serverName = serverNameFor(x.dbId, x.env, x.mcpServer);
  return `<div class="so-header">
    <div class="so-title">${dbIcon(x.dbId)}<span title="MCP Server 名称">${esc(serverName)}</span><span class="text-mut" style="font-weight:400">· ${esc(x.env)} · ${esc(a.displayName||x.dbId)}</span>${srvOpt2.displayName?`<span class="chip mono">${esc(srvOpt2.displayName)}</span>`:""}${alias.map(s=>`<span class="chip">${esc(s)}</span>`).join("")}</div>
    <button class="so-close" id="so-close">${IC.x}</button>
  </div>
  <div class="so-tabs">${["overview","tools","logs"].map(k => `<div class="so-tab ${S.slideOver.tab===k?'on':''}" data-tab="${k}">${({overview:"概览",tools:"权限",logs:"日志"})[k]}</div>`).join("")}</div>
  <div class="so-body">${renderSlideOverTab(x)}</div>
  <div class="so-footer">
    <button class="btn danger-ghost sm" id="so-delete">${IC.trash} 删除实例</button>
    <button class="btn secondary sm" id="so-close-2">关闭</button>
  </div>`;
}
function renderSlideOverTab(x){
  const a = adapter(x.dbId) || {};
  const t = x.info.lastTest;
  const reg = isRegistered(x.dbId, x.env, x.mcpServer);
  const rwTools = ["write-query","insert","update","delete"];
  const isRw = (x.info.tools || []).some(tt => rwTools.includes(tt));
  if (S.slideOver.tab === "overview"){
    const srvOpts2 = a.mcpServerOptions || [];
    const srvOpt2 = srvOpts2.find(o => o.id === x.mcpServer) || srvOpts2[0] || {};
    return `
    <div class="flex gap-2" style="margin-bottom:16px">
      <span class="badge ${isRw?'warn':'ok'}"><span class="dot"></span>${isRw?'读写':'只读'}</span>
      <span class="badge ${reg?'ok':'neutral'}">${reg?'已注册':'未注册'}</span>
      ${t?`<span class="badge ${t.ok?'info':'no'}">自检 ${t.ok?'通过':'异常'} · ${esc(relTime(t.ts))}</span>`:""}
    </div>
    <dl class="kv">
      <dt>连接器名</dt><dd><code style="font-size:14px; font-weight:600">${esc(serverNameFor(x.dbId, x.env, x.mcpServer))}</code></dd><dt></dt>
      <dt>数据源</dt><dd>${dbIcon(x.dbId)} ${esc(a.displayName||x.dbId)}</dd><dt></dt>
      <dt>实现</dt><dd>${esc(srvOpt2.displayName || x.mcpServer || "默认实现")}</dd><dt></dt>
      <dt>Host</dt><dd>${esc(x.info.host||"—")}</dd><dt></dt>
      <dt>Port</dt><dd>${esc(x.info.port||a.defaultPort)}</dd><dt></dt>
      <dt>${x.dbId==='oracle'?'Service/SID':'Database'}</dt><dd>${esc(x.info.database||"—")}</dd><dt></dt>
      <dt>User</dt><dd>${esc(x.info.user||"—")}</dd><dt></dt>
      <dt>密码</dt><dd>${x.info.password?'<code>'+ '•'.repeat(Math.min(12, x.info.password.length)) +'</code> <button class="btn ghost sm" id="so-pw-reveal">显示</button>':'<span class="text-mut">未设置</span>'}</dd><dt></dt>
      ${x.info.url?`<dt>JDBC URL</dt><dd>${esc(x.info.url)}</dd><dt></dt>`:""}
      <dt>别名</dt><dd>${(x.info.aliases&&x.info.aliases.length)?x.info.aliases.map(s=>'<span class="chip">'+esc(s)+'</span>').join(" "):'<span class="text-mut">无</span>'}</dd><dt></dt>
    </dl>
    ${t && !t.ok ? `<div class="card tight" style="border-left:3px solid var(--danger); background:var(--danger-soft); margin-top:16px">
      <div class="flex items-center gap-2" style="color:var(--danger); font-weight:500">${IC.alert} 自检失败</div>
      <div class="mono" style="font-size:11.5px; margin-top:4px">${esc(t.detail||"")}</div>
      ${t.stderrTail ? `<details style="margin-top:6px"><summary style="cursor:pointer; font-size:11px; color:var(--danger)">查看进程日志</summary><pre class="mono" style="font-size:10.5px; margin-top:4px; max-height:240px; overflow:auto; white-space:pre-wrap; background:rgba(0,0,0,.04); padding:6px; border-radius:4px">${esc(t.stderrTail)}</pre></details>` : ""}
    </div>`:""}
    <div class="card tight" style="background:var(--bg-inset); margin-top:16px">
      <div class="field-label" style="margin-bottom:8px">工具集 · ${(x.info.tools||[]).length} 个</div>
      <div class="flex gap-2 flex-wrap">${(x.info.tools||[]).map(tt=>`<span class="chip mono">${esc(tt)}</span>`).join("")||'<span class="text-mut">未启用</span>'}</div>
    </div>
    <div class="card tight" style="background:var(--bg-inset); margin-top:12px">
      <div class="field-label">配置目录</div>
      <div class="mono" style="font-size:11.5px">共享连接：${esc(norm(S.detect.root))}/${esc(x.dbId)}/instance/${esc(x.env)}/</div>
      <div class="mono" style="font-size:11.5px; margin-top:4px">本实现：${esc(norm(S.detect.root))}/${esc(x.dbId)}/instance/${esc(x.env)}/${esc(x.mcpServer || "<默认实现>")}/</div>
      <div class="flex gap-2 flex-wrap" style="margin-top:8px">
        <button class="btn secondary sm" data-so-open-dir>${IC.edit} 打开配置目录</button>
        <button class="btn ghost sm" data-so-reveal-dir>${IC.db} 在文件夹中显示</button>
        <button class="btn ghost sm" data-so-copy-json>${IC.copy} 复制 mcp.json 条目</button>
      </div>
    </div>
    <div class="flex gap-2 mt-4">
      <button class="btn primary sm" data-so-retest>${IC.play} 一键自检</button>
      ${!reg?`<button class="btn secondary sm" data-so-register>${IC.link} 注册到 mcp.json</button>`:`<button class="btn secondary sm" data-so-unregister>${IC.x} 从 mcp.json 移除</button>`}
      <button class="btn ghost sm" data-so-guide>${IC.link} MCP 注册</button>
      <button class="btn ghost sm" data-so-add-provider>${IC.plus} 添加另一个实现</button>
    </div>`;
  }
  if (S.slideOver.tab === "tools"){
    const srvOpts = a.mcpServerOptions || [];
    const srvOpt = srvOpts.find(o => o.id === x.mcpServer) || srvOpts[0] || {};
    const allT = (srvOpt.allTools && srvOpt.allTools.length) ? srvOpt.allTools : (a.allTools || []);
    const reqT = (srvOpt.requiredTools && srvOpt.requiredTools.length) ? srvOpt.requiredTools : (a.requiredTools || []);
    return `<p class="text-mut" style="font-size:12.5px">调整该连接器（${esc(srvOpt.displayName || x.mcpServer || "默认实现")}）启用的工具集，必选工具无法关闭；写权限工具会二次确认。</p>
    ${allT.map(tt => `<label class="tool ${reqT.includes(tt)?'locked':''}">
      <input type="checkbox" data-toggle-tool="${esc(tt)}" ${(x.info.tools||[]).includes(tt)?'checked':''} ${reqT.includes(tt)?'disabled':''}>
      <span class="mono">${esc(tt)}</span>
      ${reqT.includes(tt)?'<span class="tag req">必选</span>':(rwTools.includes(tt)?'<span class="tag danger">写权限</span>':'')}
    </label>`).join("")}
    <div class="flex gap-2 mt-4"><button class="btn primary sm" data-so-save-tools>保存</button></div>`;
  }
  if (S.slideOver.tab === "logs"){
    return `<div id="so-log-box" class="so-log-box"><p class="text-mut">加载中…</p></div>`;
  }
  return `<p class="text-mut" style="font-size:12.5px">无内容</p>`;
}
function bindSlideOver(x){
  $("#so-close").onclick = () => closeSlideOver();
  $("#so-close-2").onclick = () => closeSlideOver();
  $("#so-delete").onclick = () => deleteInstanceConfirm(x);
  $$(".so-tab").forEach(el => el.onclick = () => { S.slideOver.tab = el.dataset.tab; $("#slideover").innerHTML = renderSlideOver(x); bindSlideOver(x); if (S.slideOver.tab === "logs") loadSlideOverLog(x); });
  const reveal = $("#so-pw-reveal");
  if (reveal) reveal.onclick = () => { const dd = reveal.parentElement; dd.innerHTML = '<code>'+esc(x.info.password)+'</code>'; };
  const retest = $("#slideover [data-so-retest]");
  if (retest) retest.onclick = () => runSelfTest(x.dbId, x.env, x.mcpServer, r => { toast(r.ok?"自检通过":"自检失败", r.ok?"":"err"); $("#slideover").innerHTML = renderSlideOver(x); bindSlideOver(x); refreshDetect(); });
  const reg = $("#slideover [data-so-register]");
  if (reg) reg.onclick = () => registerEnv(x.dbId, x.env, x.mcpServer);
  const unreg = $("#slideover [data-so-unregister]");
  if (unreg) unreg.onclick = () => unregisterEnv(x.dbId, x.env, x.mcpServer);
  const guide = $("#slideover [data-so-guide]");
  if (guide) guide.onclick = () => showMcpRegister(x.dbId, x.env, x.mcpServer);
  const saveTools = $("#slideover [data-so-save-tools]");
  if (saveTools) saveTools.onclick = () => saveToolsFor(x);
  const openDir = $("#slideover [data-so-open-dir]");
  if (openDir) openDir.onclick = () => openPath("env-config-dir", "open", { dbId: x.dbId, env: x.env });
  const revealDir = $("#slideover [data-so-reveal-dir]");
  if (revealDir) revealDir.onclick = () => openPath("env-config-dir", "reveal", { dbId: x.dbId, env: x.env });
  const copyJson = $("#slideover [data-so-copy-json]");
  if (copyJson) copyJson.onclick = () => copyMcpEntry(x);
  const addProvider = $("#slideover [data-so-add-provider]");
  if (addProvider) addProvider.onclick = () => openAddProvider(x);
}
async function loadSlideOverLog(x){
  const box = $("#so-log-box"); if (!box) return;
  try {
    const r = await api("/api/env/log?dbId="+encodeURIComponent(x.dbId)+"&env="+encodeURIComponent(x.env)+"&mcpServer="+encodeURIComponent(x.mcpServer||"")+"&limit=200");
    const entries = (r.lines || []).map(l => {
      try { const o = JSON.parse(l); const ok = o.ok !== false;
        const detail = o.sql || o.detail || "";
        return `<div class="so-log-entry"><div class="so-log-meta"><span class="so-log-ts">${esc(o.ts||"")}</span><span class="${ok?'st-ok':'st-err'}">${ok?'OK':'ERR'}</span><span class="so-log-dur">${o.dur||''}</span></div><pre class="so-log-console">${esc(detail)}</pre></div>`;
      } catch { return `<div class="so-log-entry"><pre class="so-log-console">${esc(l)}</pre></div>`; }
    });
    box.innerHTML = (entries.join("") || '<p class="text-mut">暂无日志。</p>');
  } catch (e){ box.innerHTML = '<p style="color:var(--danger)">'+esc(e.message)+'</p>'; }
}

/* 同一连接（dbId/env）追加另一个 MCP 实现：共享连接配置，独立连接器目录与调用日志 */
function openAddProvider(x){
  const a = adapter(x.dbId) || {};
  const opts = a.mcpServerOptions || [];
  if (!opts.length){ toast("该数据源仅有内置默认实现，无可追加项", "warn"); return; }
  const used = listEnvs().filter(v => v.dbId === x.dbId && v.env === x.env).map(v => v.mcpServer);
  const avail = opts.filter(o => used.indexOf(o.id) < 0);
  if (!avail.length){
    toast("已接入该数据源的全部 " + opts.length + " 种实现", "warn");
    return;
  }
  const info = x.info || {};
  openModal(`<div class="modal-header"><h3>为 ${esc(x.dbId)}/${esc(x.env)} 添加 MCP 实现</h3><button class="so-close" id="m-x">${IC.x}</button></div>
    <div class="modal-body">
      <p class="text-mut" style="font-size:12.5px; margin-bottom:12px">
        连接信息（host / port / 账号 / 口令）在同一环境下共享；新增实现只会创建独立的连接器目录与调用日志，
        与现有实现互不影响，各自注册为不同的 MCP 连接器名。
      </p>
      ${avail.map((o, i) => `<label class="tool">
        <input type="radio" name="add-provider-opt" value="${esc(o.id)}" ${i === 0 ? "checked" : ""}>
        <span>${esc(o.displayName || o.id)}</span>
        ${o.description ? `<span style="margin-left:auto; font-size:10.5px; color:var(--text-muted); text-align:right">${esc(o.description)}</span>` : ''}
      </label>`).join("")}
      <div class="card tight mt-4" style="background:var(--bg-inset)">
        <div class="field-label">将创建</div>
        <div class="mono" style="font-size:11.5px">${esc(norm(S.detect.root))}/${esc(x.dbId)}/instance/${esc(x.env)}/&lt;实现&gt;/calllog.jsonl</div>
      </div>
    </div>
    <div class="modal-footer">
      <button class="btn secondary" id="m-close">取消</button>
      <button class="btn primary" id="ap-go">下一步</button>
    </div>`, {});
  const close = () => closeModal();
  $("#m-x").onclick = close;
  $("#m-close").onclick = close;
  $("#ap-go").onclick = () => {
    const sel = $$('input[name="add-provider-opt"]:checked')[0];
    const srv = sel ? sel.value : (avail[0] && avail[0].id);
    const opt = avail.find(o => o.id === srv) || {};
    closeModal();
    S.wizard = {
      step:2, dbId:x.dbId, env:x.env,
      alias:(info.aliases || []).join(" · "),
      aliasTouched: !!(info.aliases && info.aliases.length),
      host: info.host || "", port: info.port || "", user: info.user || "",
      password: info.password || "", pwdLocked: !!info.password,
      serviceOrDatabase: info.database || "",
      paste:"", tools: (opt.requiredTools || a.requiredTools || []).slice(), testResult:null,
      serverName:"", mcpServer: srv
    };
    navigate("#/setup/2");
  };
}

/* ==========================================================================
   Modal
   ========================================================================== */
function openModal(html, opts){
  S.modal = opts || {};
  const m = $("#modal");
  m.innerHTML = html;
  m.classList.add("on");
  $("#overlay").classList.add("on");
  m.classList.toggle("danger", !!(opts && opts.danger));
  m.classList.toggle("wide", !!(opts && opts.wide));
}
function closeModal(){
  const opts = S.modal || {};
  S.modal = null;
  const m = $("#modal");
  m.classList.remove("on");
  m.classList.remove("wide");
  if (!S.slideOver.open) $("#overlay").classList.remove("on");
  if (opts.restoreSlideOver && opts.restoreArgs){
    setTimeout(() => openSlideOver(opts.restoreArgs[0], opts.restoreArgs[1], opts.restoreArgs[2] || ""), 220);
  }
}

async function deleteInstanceConfirm(x){
  openModal(`<div class="modal-header"><h3>删除实例 · ${esc(x.dbId)}/${esc(x.env)}/${esc(x.mcpServer)}</h3><button class="so-close" id="m-x">${IC.x}</button></div>
  <div class="modal-body">
    <p class="text-mut">将执行：</p>
    <ul>
      <li>从 <code>${esc(norm(S.detect.mcpJsonPath))}</code> 移除 <code>${esc(serverNameFor(x.dbId, x.env, x.mcpServer))}</code></li>
      <li>把配置目录 <code>${esc(norm(S.detect.root))}/${esc(x.dbId)}/instance/${esc(x.env)}/</code> 移入回收站</li>
      <li>更新 state.json 与 environments.md</li>
    </ul>
    <div class="card tight" style="border-left:3px solid var(--warning); background:var(--warning-soft)">
      <div class="flex items-center gap-2" style="color:var(--warning); font-weight:500">${IC.alert} 该操作不可撤回</div>
    </div>
  </div>
  <div class="modal-footer">
    <button class="btn secondary" id="m-cancel">取消</button>
    <button class="btn danger" id="m-ok">${IC.trash} 确认删除</button>
  </div>`, { danger:true });
  $("#m-x").onclick = closeModal;
  $("#m-cancel").onclick = closeModal;
  $("#m-ok").onclick = async () => {
    try {
      await api("/api/env/delete", { dbId:x.dbId, env:x.env, mcpServer:x.mcpServer });
      toast("已删除 " + x.dbId + "/" + x.env + (x.mcpServer ? "/" + x.mcpServer : ""));
      closeModal();
      closeSlideOver();
      await refreshDetect();
      renderMain();
    } catch (e){ toast(e.message, "err"); }
  };
}

async function showResetPreview(){
  let p;
  try { p = await api("/api/reset/preview"); }
  catch (e){ toast("预览失败：" + e.message, "err"); return; }
  const envs = (p.envs||[]).map(x => `<li>${esc(x.dbId)}/${esc(x.env)}/${esc(x.mcpServer)} ${x.registered?'<span class="badge ok">已注册</span>':'<span class="badge neutral">未注册</span>'}</li>`).join("") || '<li class="text-mut">无</li>';
  const servers = (p.mcpServersToRemove||[]).map(s => `<li><code>${esc(s)}</code></li>`).join("") || '<li class="text-mut">无</li>';
  const qplugin = (p.qoderPluginServersToRemove||[]).map(s => `<li><code>${esc(s)}</code></li>`).join("") || '<li class="text-mut">无</li>';
  const skills = (p.skillDirsToTrash||[]).map(s => `<li><code>${esc(s)}</code></li>`).join("") || '<li class="text-mut">无</li>';
  openModal(`<div class="modal-header"><h3>${IC.alert} 清空全部配置 · 预览</h3><button class="so-close" id="m-x">${IC.x}</button></div>
  <div class="modal-body">
    <p class="text-mut">${esc(p.note)}</p>
    <div class="card tight" style="background:var(--bg-inset); margin-bottom:12px">
      <div class="field-label">将删除的实例（${(p.envs||[]).length}）</div>
      <ul style="margin:0; padding-left:20px">${envs}</ul>
    </div>
    <div class="card tight" style="background:var(--bg-inset); margin-bottom:12px">
      <div class="field-label">QoderWork mcp.json 将移除（${(p.mcpServersToRemove||[]).length}）</div>
      <ul style="margin:0; padding-left:20px">${servers}</ul>
    </div>
    <div class="card tight" style="background:var(--bg-inset); margin-bottom:12px">
      <div class="field-label">IDEA Qoder mcp.json 将移除（${(p.qoderPluginServersToRemove||[]).length}）</div>
      <ul style="margin:0; padding-left:20px">${qplugin}</ul>
    </div>
    <div class="card tight" style="background:var(--bg-inset); margin-bottom:12px">
      <div class="field-label">Skill 目录将进回收站（${(p.skillDirsToTrash||[]).length}）</div>
      <ul style="margin:0; padding-left:20px">${skills}</ul>
    </div>
    <div class="field">
      <div class="field-label"><span>请输入关键词 <code>${esc(p.confirmKeyword)}</code> 以确认</span></div>
      <input class="input mono" id="m-keyword" placeholder="${esc(p.confirmKeyword)}">
    </div>
  </div>
  <div class="modal-footer">
    <button class="btn secondary" id="m-cancel">取消</button>
    <button class="btn danger" id="m-ok" disabled>${IC.trash} 执行清空</button>
  </div>`, { danger:true });
  $("#m-x").onclick = closeModal;
  $("#m-cancel").onclick = closeModal;
  const kw = $("#m-keyword"), ok = $("#m-ok");
  kw.oninput = () => { ok.disabled = kw.value.trim().toUpperCase() !== p.confirmKeyword; };
  ok.onclick = async () => {
    ok.disabled = true; ok.classList.add("loading");
    try {
      const r = await api("/api/reset", {});
      toast("已清空：移除 mcp 条目 " + r.mcpRemoved + " 个，回收站 " + (r.skillTrashed||[]).length + " 个 Skill 目录");
      closeModal();
      await refreshDetect();
      renderMain();
    } catch (e){ toast(e.message, "err"); ok.disabled = false; ok.classList.remove("loading"); }
  };
}

/* ==========================================================================
   MCP 注册 · 平台分派面板
   ========================================================================== */
async function loadMcpTargets(force){
  if (S.mcpTargets && !force) return S.mcpTargets;
  try {
    const d = await api("/api/mcp/targets");
    S.mcpTargets = (d && d.targets) || [];
  } catch (e){
    S.mcpTargets = S.mcpTargets || [];
  }
  return S.mcpTargets;
}

/* 后端 McpTarget → 前端 nav 卡片 */
function targetToCard(t, serverName){
  const existing = Array.isArray(t.existingServers) ? t.existingServers : [];
  const actual = t.actualPath || "";
  const cands = Array.isArray(t.candidatePaths) ? t.candidatePaths : [];
  const steps = Array.isArray(t.uiInstructions) ? t.uiInstructions : [];
  return {
    id: t.id, name: t.displayName, cls: t.iconClass, letter: t.icon,
    type: "target", tier: t.tier || "common",
    describe: t.describe || "",
    writable: !!t.writable, cliBased: !!t.cliBased, detected: !!t.detected,
    uiOnly: !!t.uiOnly, uiInstructions: steps,
    hasInstance: existing.indexOf(serverName) >= 0,
    existingCount: existing.length, existingServers: existing,
    actualPath: actual, candidatePaths: cands,
    configPathHint: actual || cands[0] || "",
  };
}

function getMcpPlatforms(dbId, env, mcpServer){
  const d = S.detect || {};
  const a = adapter(dbId) || {};
  const serverName = serverNameFor(dbId, env, mcpServer);
  const reg = (d.registeredServers || []);
  const qReg = (d.qoderPluginRegisteredServers || []);
  return [
    {
      id:"qoderwork", type:"primary", name:"QoderWork", cls:"qoderwork", letter:"Q", tier:"primary",
      configPath: norm(d.mcpJsonPath),
      registeredList: reg, serverName,
      note: reg.length ? ("本机 mcp.json 已有 " + reg.length + " 个 DB MCP server") : "本机 mcp.json 尚未注册任何 DB MCP server"
    },
    {
      id:"qoder-plugin", type:"primary", name:"IDEA Qoder 插件", cls:"qoder-plugin", letter:"Q", tier:"primary",
      configPath: norm(d.qoderPluginMcpJsonPath),
      registeredList: qReg, serverName,
      note: qReg.length ? ("插件配置有 " + qReg.length + " 个 server") : "插件配置未注册"
    },
  ];
}

function buildMcpEntryPreview(dbId, env, mcpServer){
  const a = adapter(dbId) || {};
  const x = listEnvs().find(v => v.dbId === dbId && v.env === env && (v.mcpServer||"") === (mcpServer||""));
  const info = (x && x.info) || {};
  const root = norm(S.detect.root || "");
  const serverName = serverNameFor(dbId, env, mcpServer);
  if (dbId === "oracle"){
    return {
      [serverName]: {
        command: norm(S.detect.javaCmd || "java"),
        args: [
          "-Dfile.encoding=UTF-8",
          "-jar", root + "/tap/mcp-tap.jar",
          "--",
          "-Dfile.encoding=UTF-8",
          "-jar", root + "/" + dbId + "/toolkit/oracle-mcp-toolkit.jar",
          env
        ]
      }
    };
  }
  return {
    [serverName]: {
      command: "node",
      args: [
        root + "/tap/mcp-tap.js", "--",
        root + "/" + dbId + "/toolkit/index.js"
      ],
      env: {
        MYSQL_HOST: info.host || "",
        MYSQL_PORT: String(info.port || ""),
        MYSQL_USER: info.user || "",
        MYSQL_PASSWORD: "••••••••",
        MYSQL_DATABASE: info.database || ""
      }
    }
  };
}

function highlightJson(obj){
  const s = JSON.stringify(obj, null, 2);
  return esc(s)
    .replace(/&quot;([^&]+?)&quot;(\s*:)/g, '<span class="mcp-snippet-key">"$1"</span>$2')
    .replace(/:\s&quot;([^&]*?)&quot;/g, ': <span class="mcp-snippet-str">"$1"</span>')
    .replace(/:\s(\d+)/g, ': <span class="mcp-snippet-num">$1</span>');
}

async function showMcpRegister(dbId, env, mcpServer){
  const reopenSlideOver = S.slideOver.open;
  if (reopenSlideOver) closeSlideOver(true);
  const a = adapter(dbId) || {};
  const serverName = serverNameFor(dbId, env, mcpServer);
  await loadMcpTargets();
  const manualItem = { id:"__manual", type:"manual", name:"通用 · 手动接入", cls:"generic", letter:"{}", tier:"manual",
    note:"把 server 定义贴到你的客户端配置里即可，适配任意 MCP 兼容客户端" };
  let allItems = [];
  function rebuildAllItems(){
    const platforms = getMcpPlatforms(dbId, env, mcpServer);
    const targetCards = (S.mcpTargets || []).map(t => targetToCard(t, serverName));
    allItems = [...platforms, ...targetCards, manualItem];
  }
  rebuildAllItems();
  const firstUnreg = allItems.find(p => p.type === "primary" && !(p.registeredList && p.registeredList.indexOf(serverName) >= 0));
  const st = { selected: (firstUnreg || allItems[0]).id, cliCommands: {} };
  const entryPreview = buildMcpEntryPreview(dbId, env, mcpServer);
  const fragJson = () => JSON.stringify(entryPreview, null, 2);
  const fullJson = () => JSON.stringify({mcpServers: entryPreview}, null, 2);

  openModal(`<div class="modal-header">
    <h3>${IC.link} MCP 注册 · ${dbIcon(dbId)} ${esc(dbId)}/${esc(env)} <span class="chip mono" style="margin-left:6px">${esc(serverName)}</span></h3>
    <button class="so-close" id="m-x">${IC.x}</button>
  </div>
  <div class="mcp-split">
    <aside class="mcp-nav" id="mcp-nav"></aside>
    <section class="mcp-detail" id="mcp-detail"></section>
  </div>
  <div class="modal-footer"><button class="btn secondary" id="m-close">关闭</button></div>`, { wide:true, restoreSlideOver:reopenSlideOver, restoreArgs:[dbId, env, mcpServer] });

  function navDot(p){
    if (p.type === "primary"){
      const isReg = p.registeredList && p.registeredList.indexOf(serverName) >= 0;
      return isReg ? "ok" : "neutral";
    }
    if (p.type === "target"){
      if (p.hasInstance) return "ok";
      if (p.uiOnly) return "ui";
      if (p.tier === "pending") return "stub";
      if (p.detected) return "partial";
      return "neutral";
    }
    return "";
  }
  function grouped(){
    const g = [];
    const prim = allItems.filter(p => p.type === "primary");
    const order = p => (p.hasInstance ? 0 : (p.detected ? 1 : 2));
    const common = allItems.filter(p => p.type === "target" && !p.uiOnly && p.tier !== "pending").sort((x,y) => order(x)-order(y));
    const uiOnly = allItems.filter(p => p.type === "target" && p.uiOnly);
    const pending = allItems.filter(p => p.type === "target" && p.tier === "pending" && !p.uiOnly);
    const man = allItems.filter(p => p.type === "manual");
    if (prim.length)    g.push({ label:"本机已检测到", items: prim });
    if (common.length)  g.push({ label:"常见客户端", items: common });
    if (uiOnly.length)  g.push({ label:"UI 手动接入", items: uiOnly });
    if (pending.length) g.push({ label:"待适配", items: pending });
    if (man.length)     g.push({ label:"通用", items: man });
    return g;
  }
  function renderNav(){
    $("#mcp-nav").innerHTML = grouped().map(gr => `
      <div class="mcp-nav-group">
        <div class="mcp-nav-label">${esc(gr.label)} <span class="count">${gr.items.length}</span></div>
        ${gr.items.map(p => {
          const dot = navDot(p);
          return `<div class="mcp-nav-item ${p.id===st.selected?'active':''}" data-mcp-item="${esc(p.id)}">
            <div class="mcp-plat-icon ${esc(p.cls)}">${esc(p.letter)}</div>
            <span>${esc(p.name)}</span>
            <span class="dot ${dot}"></span>
          </div>`;
        }).join("")}
      </div>
    `).join("");
    $$("#mcp-nav .mcp-nav-item").forEach(el => el.onclick = () => {
      st.selected = el.dataset.mcpItem;
      renderNav(); renderDetail();
    });
  }
  function renderDetail(){
    const p = allItems.find(x => x.id === st.selected);
    const body = $("#mcp-detail"); if (!p || !body) return;
    body.innerHTML = p.type === "manual" ? manualDetail(p) : (p.type === "primary" ? primaryDetail(p) : targetDetail(p));
    bindDetail(p);
    // CLI 类客户端：异步拉取可执行命令后重渲染
    if (p.type === "target" && p.cliBased && !st.cliCommands[p.id]){
      api("/api/mcp/commands", { target: p.id, dbId, env, mcpServer }).then(r => {
        st.cliCommands[p.id] = r;
        if (st.selected === p.id) { body.innerHTML = targetDetail(p); bindDetail(p); }
      }).catch(() => { st.cliCommands[p.id] = {}; });
    }
  }
  const platformToTarget = (id) => id === "qoderwork" ? "mcp-json" : (id === "qoder-plugin" ? "qoder-plugin-mcp" : null);
  function pathChips(p){
    const actual = p.actualPath || "";
    const list = (p.candidatePaths && p.candidatePaths.length) ? p.candidatePaths : (actual ? [actual] : []);
    if (!list.length) return "";
    return `<div class="mcp-detail-hint">配置候选路径：</div>
      <div class="mcp-existing-list">${list.map(x => `<code class="mcp-path-chip ${x===actual?'active':''}">${esc(x)}</code>`).join("")}</div>`;
  }
  function existingBlock(p){
    const n = p.existingCount || 0;
    const servers = p.existingServers || [];
    let head = `<div class="mcp-detail-hint">该配置文件已存在 ${n} 个 server${n ? "：" : "（尚无）"}</div>`;
    let chips = "";
    if (n > 0 && n <= 6){
      chips = `<div class="mcp-existing-list">${servers.map(s => `<span class="chip ${s===serverName?'active':''}"${s===serverName?' style="background:var(--brand-soft);color:var(--brand)"':''}>${esc(s)}</span>`).join("")}</div>`;
    }
    return head + chips;
  }
  function primaryDetail(p){
    const isReg = p.registeredList.indexOf(p.serverName) >= 0;
    return `<div class="mcp-detail-head">
      <div class="mcp-plat-icon big ${p.cls}">${esc(p.letter)}</div>
      <div style="flex:1; min-width:0">
        <div class="mcp-detail-title">${esc(p.name)}</div>
        <div class="mcp-detail-sub">${esc(p.configPath || "")}</div>
      </div>
      ${isReg ? '<span class="badge ok">'+IC.check+' 已注册</span>' : '<span class="badge neutral">未注册</span>'}
    </div>
    <p class="text-sec">${esc(p.note || "")}</p>
    <div class="mcp-detail-actions">
      ${isReg
        ? `<button class="btn secondary" data-act="re-register">${IC.refresh} 重新注册</button>
           <button class="btn danger-ghost" data-act="unregister">${IC.x} 从 mcp.json 移除</button>`
        : `<button class="btn primary" data-act="register">${IC.link} 一键注册到 ${esc(p.name)}</button>`}
      <button class="btn ghost" data-act="open-file">${IC.edit} 打开配置文件</button>
      <button class="btn ghost" data-act="reveal-file">${IC.db} 在文件夹中显示</button>
      <button class="btn ghost" data-act="copy-frag">${IC.copy} 复制片段</button>
    </div>
    <div class="mcp-detail-preview">
      <div class="field-label"><span>当前条目预览</span><span class="field-hint">将写入 <code>mcpServers.${esc(serverName)}</code></span></div>
      <div class="mcp-snippet">${highlightJson(entryPreview)}</div>
    </div>`;
  }
  function uiStepsBlock(p){
    if (!p.uiInstructions || !p.uiInstructions.length) return "";
    return `<div class="mcp-ui-steps">
      <div class="field-label"><span>${IC.help} 在 ${esc(p.name)} 中手动接入步骤</span><span class="field-hint">按顺序操作，最后一步粘贴下方 JSON</span></div>
      <ol class="mcp-steps-list">${p.uiInstructions.map(s => `<li>${esc(s)}</li>`).join("")}</ol>
    </div>`;
  }
  function cliCommandBlock(p){
    const c = st.cliCommands[p.id];
    if (!c) return `<div class="mcp-detail-hint">正在生成可执行命令…</div>`;
    const reg = c.register || "";
    const unreg = c.unregister || "";
    return `<div class="mcp-ui-steps">
      <div class="field-label"><span>${IC.play} 通过命令行配置</span><span class="field-hint">可一键执行，或复制下方命令到终端</span></div>
      ${reg ? `<div class="mcp-cmd-row"><code class="mcp-cmd">${esc(reg)}</code><button class="btn ghost sm" data-act="copy-cmd" data-cmd="${esc(reg)}">${IC.copy} 复制</button></div>` : ""}
      ${unreg ? `<div class="mcp-cmd-row mcp-cmd-remove"><span class="text-mut" style="font-size:11px">移除：</span><code class="mcp-cmd">${esc(unreg)}</code><button class="btn ghost sm" data-act="copy-cmd" data-cmd="${esc(unreg)}">${IC.copy} 复制</button></div>` : ""}
    </div>`;
  }
  function targetDetail(p){
    let badge, actions, banner = "";
    const openBtns = `
      <button class="btn ghost" data-act="open-file">${IC.edit} 打开配置文件</button>
      <button class="btn ghost" data-act="reveal-file">${IC.db} 在文件夹中显示</button>`;
    if (p.cliBased){
      if (p.hasInstance){
        badge = `<span class="badge ok">${IC.check} 已注册</span>`;
        actions = `
          <button class="btn secondary" data-act="target-re-register">${IC.refresh} 重新执行</button>
          <button class="btn danger-ghost" data-act="target-unregister">${IC.x} 移除（执行 remove 命令）</button>`;
      } else if (p.detected && p.writable){
        badge = `<span class="badge info">已检测到 CLI</span>`;
        actions = `<button class="btn primary" data-act="target-register">${IC.play} 一键执行命令</button>`;
      } else {
        badge = `<span class="badge warn">未检测到 CLI</span>`;
        banner = `<div class="mcp-detect-banner warn">${IC.alert}<div>未检测到该客户端的命令行工具（如 {@code lms}）。请先在客户端里启用 CLI，再回来一键执行；或直接用下方命令手动执行。</div></div>`;
        actions = `<button class="btn secondary" data-act="target-register" disabled>${IC.play} 一键执行命令</button>`;
      }
      return `<div class="mcp-detail-head">
        <div class="mcp-plat-icon big ${esc(p.cls)}">${esc(p.letter)}</div>
        <div style="flex:1; min-width:0">
          <div class="mcp-detail-title">${esc(p.name)}</div>
          <div class="mcp-detail-sub">${esc(p.describe || "")}</div>
        </div>
        ${badge}
      </div>
      ${banner}
      ${cliCommandBlock(p)}
      <div class="mcp-detail-actions">${actions}</div>
      <div class="mcp-detail-preview">
        <div class="field-label"><span>当前条目预览</span><span class="field-hint">命令实际写入的内容（由客户端 CLI 管理，无独立文件）</span></div>
        <div class="mcp-snippet">${highlightJson(entryPreview)}</div>
      </div>`;
    }
    if (p.uiOnly){
      badge = `<span class="badge info">UI 手动接入</span>`;
      actions = `<button class="btn primary" data-act="copy-frag">${IC.copy} 复制完整片段</button>`;
    } else if (!p.writable){
      badge = `<span class="badge warn">暂不支持</span>`;
      banner = `<div class="mcp-detect-banner warn">${IC.alert}<div>该客户端暂不支持自动写入，请按下方步骤在其 UI 中手动粘贴以下片段完成接入。</div></div>`;
      actions = `<button class="btn primary" data-act="copy-frag">${IC.copy} 复制片段</button>`;
    } else if (p.detected && p.hasInstance){
      badge = `<span class="badge ok">${IC.check} 已注册</span>`;
      actions = `
        <button class="btn secondary" data-act="target-re-register">${IC.refresh} 重新注册</button>
        <button class="btn danger-ghost" data-act="target-unregister">${IC.x} 从 mcp.json 移除</button>${openBtns}
        <button class="btn ghost" data-act="copy-frag">${IC.copy} 复制片段</button>`;
    } else if (p.detected && !p.hasInstance){
      badge = `<span class="badge info">已检测到客户端</span>`;
      actions = `
        <button class="btn primary" data-act="target-register">${IC.link} 一键注册到 ${esc(p.name)}</button>${openBtns}
        <button class="btn ghost" data-act="copy-frag">${IC.copy} 复制片段</button>`;
    } else {
      badge = `<span class="badge neutral">未检测到</span>`;
      const willPath = esc(p.actualPath || (p.candidatePaths && p.candidatePaths[0]) || "");
      banner = `<div class="mcp-detect-banner warn">${IC.alert}<div>未检测到客户端配置目录，注册会新建 <code>${willPath}</code>。若客户端未安装，注册后需自行安装客户端才能生效。</div></div>`;
      actions = `
        <button class="btn primary" data-act="target-register">${IC.link} 一键注册（新建配置文件）</button>
        <button class="btn ghost" data-act="copy-frag">${IC.copy} 复制片段</button>`;
    }
    return `<div class="mcp-detail-head">
      <div class="mcp-plat-icon big ${esc(p.cls)}">${esc(p.letter)}</div>
      <div style="flex:1; min-width:0">
        <div class="mcp-detail-title">${esc(p.name)}</div>
        <div class="mcp-detail-sub">${esc(p.describe || "")}</div>
      </div>
      ${badge}
    </div>
    ${banner}
    ${uiStepsBlock(p)}
    ${p.uiOnly ? "" : pathChips(p)}
    ${p.uiOnly ? "" : existingBlock(p)}
    <div class="mcp-detail-actions">${actions}</div>
    <div class="mcp-detail-preview">
      <div class="field-label"><span>${p.uiOnly ? "需要粘贴的 JSON 片段" : "当前条目预览"}</span><span class="field-hint">${p.uiOnly ? "复制后按上方步骤贴到客户端配置里" : "将写入 " + esc(p.name) + " 的 <code>mcpServers." + esc(serverName) + "</code>"}</span></div>
      <div class="mcp-snippet">${highlightJson(entryPreview)}</div>
    </div>`;
  }
  function manualDetail(p){
    return `<div class="mcp-detail-head">
      <div class="mcp-plat-icon big generic">{ }</div>
      <div style="flex:1; min-width:0">
        <div class="mcp-detail-title">通用 · 手动接入</div>
        <div class="mcp-detail-sub">任意 MCP 兼容客户端</div>
      </div>
    </div>
    <p class="text-sec">${esc(p.note)}</p>
    <div class="mcp-detail-actions">
      <button class="btn primary" data-act="copy-frag">${IC.copy} 复制单条 server</button>
      <button class="btn secondary" data-act="copy-full">${IC.copy} 复制完整 mcp.json 骨架</button>
      <button class="btn ghost" data-act="download">${IC.rocket} 下载 .json</button>
    </div>
    <div class="mcp-detail-preview">
      <div class="field-label"><span>单条 server 定义</span></div>
      <div class="mcp-snippet">${highlightJson(entryPreview)}</div>
    </div>
    <div class="mcp-detail-preview">
      <div class="field-label"><span>完整 mcp.json 骨架</span><span class="field-hint">新建配置文件时使用</span></div>
      <div class="mcp-snippet">${highlightJson({mcpServers: entryPreview})}</div>
    </div>`;
  }
  async function refreshTargetsAndDetect(){
    S.mcpTargets = null; await loadMcpTargets(true); await refreshDetect();
  }
  function bindDetail(p){
    $$("#mcp-detail [data-act]").forEach(el => {
      el.onclick = async () => {
        const act = el.dataset.act;
        if (act === "register" || act === "re-register"){
          el.classList.add("loading");
          try { await registerEnv(dbId, env, mcpServer); await refreshDetect(); renderNav(); renderDetail(); }
          catch (e){ toast(e.message, "err"); }
          finally { el.classList.remove("loading"); }
        } else if (act === "unregister"){
          toast("待落地：主平台移除需走 /api/env/delete，暂未提供 unregister-only 端点", "warn");
        } else if (act === "target-register" || act === "target-re-register"){
          el.classList.add("loading");
          try {
            const r = await api("/api/mcp/register", { target: p.id, dbId, env, mcpServer });
            toast(r.message || "已注册");
            await refreshTargetsAndDetect();
            rebuildAllItems();
            renderNav(); renderDetail();
          } catch (e){ toast(e.message, "err"); }
          finally { el.classList.remove("loading"); }
        } else if (act === "target-unregister"){
          if (!confirm("确认从 " + p.name + " 移除 " + serverName + "?")) return;
          el.classList.add("loading");
          try {
            const r = await api("/api/mcp/unregister", { target: p.id, dbId, env, mcpServer });
            toast(r.removed ? "已移除" : "文件里没有该 server");
            S.mcpTargets = null; await loadMcpTargets(true);
            rebuildAllItems();
            renderNav(); renderDetail();
          } catch (e){ toast(e.message, "err"); }
          finally { el.classList.remove("loading"); }
        } else if (act === "open-file" || act === "reveal-file"){
          const tgt = platformToTarget(p.id);
          if (tgt){ openPath(tgt, act === "reveal-file" ? "reveal" : "open"); return; }
          if (p.actualPath){ toast("请在系统文件管理器打开：" + p.actualPath, "info"); }
          else { toast("未定位到配置文件路径", "warn"); }
        } else if (act === "copy-frag"){
          navigator.clipboard.writeText(fragJson()); toast("已复制单条 server 定义");
        } else if (act === "copy-cmd"){
          navigator.clipboard.writeText(el.dataset.cmd || ""); toast("已复制命令，可粘贴到终端执行");
        } else if (act === "copy-full"){
          navigator.clipboard.writeText(fullJson()); toast("已复制完整 mcp.json 骨架");
        } else if (act === "download"){
          const blob = new Blob([fullJson()], {type:"application/json"});
          const url = URL.createObjectURL(blob);
          const a = document.createElement("a");
          a.href = url; a.download = "mcp-" + serverName + ".json"; a.click();
          setTimeout(() => URL.revokeObjectURL(url), 1000);
        }
      };
    });
  }
  $("#m-x").onclick = closeModal;
  $("#m-close").onclick = closeModal;
  renderNav();
  renderDetail();
}

/* ==========================================================================
   Command Palette
   ========================================================================== */
const CMDS = [];
function buildCmds(){
  CMDS.length = 0;
  const nav = [
    { g:"跳转", label:"总览", hint:"g o", run:() => navigate("#/overview") },
    { g:"跳转", label:"实例列表", hint:"g i", run:() => navigate("#/instances") },
    { g:"跳转", label:"实现管理", hint:"g m", run:() => navigate("#/implementations") },
    { g:"跳转", label:"Skill 与运行时", hint:"g r", run:() => navigate("#/runtime") },
    { g:"跳转", label:"排障", hint:"g d", run:() => navigate("#/diagnostics") },
    { g:"跳转", label:"系统", hint:"g s", run:() => navigate("#/system") }
  ];
  const instCmds = [];
  listEnvs().forEach(x => {
    instCmds.push({ g:"实例", label:"实例 · " + x.dbId + "/" + x.env, run:() => navigate("#/instances/"+x.dbId+"/"+x.env) });
    instCmds.push({ g:"实例", label:"MCP 注册 · " + x.dbId + "/" + x.env + (x.mcpServer ? "/"+x.mcpServer : ""), run:() => showMcpRegister(x.dbId, x.env, x.mcpServer) });
  });
  const actions = [
    { g:"动作", label:"添加实例", hint:"⌘N", run:() => navigate("#/setup/1") },
    { g:"动作", label:"重新检测环境", run:async () => { await refreshDetect(); renderMain(); toast("环境已刷新", "info"); } },
    { g:"动作", label:"一键自检全部", run:retestAll },
    { g:"动作", label:"切换主题", run:toggleTheme },
    { g:"动作", label:"Skill 同步映射", run:syncMappings },
    { g:"动作", label:"预览清空全部配置", run:showResetPreview }
  ];
  CMDS.push(...nav, ...instCmds, ...actions);
}
function openCmd(){
  buildCmds();
  S.cmd.open = true; S.cmd.q = ""; S.cmd.idx = 0;
  $("#cmd").classList.add("on");
  $("#overlay").classList.add("on");
  const inp = $("#cmd-input");
  inp.value = ""; renderCmd(); setTimeout(() => inp.focus(), 20);
}
function closeCmd(){
  S.cmd.open = false;
  $("#cmd").classList.remove("on");
  if (!S.slideOver.open && !S.modal) $("#overlay").classList.remove("on");
}
function renderCmd(){
  const q = (S.cmd.q || "").toLowerCase();
  const list = CMDS.map((c,i) => ({ ...c, idx:i })).filter(c => !q || c.label.toLowerCase().includes(q) || c.g.toLowerCase().includes(q));
  if (S.cmd.idx >= list.length) S.cmd.idx = 0;
  const groups = {};
  list.forEach(c => { (groups[c.g] = groups[c.g] || []).push(c); });
  const html = Object.entries(groups).map(([g, arr]) => `<div class="group-label">${esc(g)}</div>${arr.map(c => `<div class="item ${c.idx===S.cmd.idx?'on':''}" data-idx="${c.idx}">${IC.right}<span>${esc(c.label)}</span>${c.hint?`<span class="shortcut">${esc(c.hint)}</span>`:""}</div>`).join("")}`).join("") || `<div class="empty" style="padding:32px"><p>无匹配结果</p></div>`;
  $("#cmd-results").innerHTML = html;
  $$("#cmd-results .item").forEach(el => el.onclick = () => { const c = CMDS[+el.dataset.idx]; closeCmd(); c.run(); });
}
function onCmdKey(e){
  const q = (S.cmd.q || "").toLowerCase();
  const list = CMDS.map((c,i) => ({ ...c, idx:i })).filter(c => !q || c.label.toLowerCase().includes(q) || c.g.toLowerCase().includes(q));
  if (e.key === "ArrowDown"){ S.cmd.idx = Math.min(list.length-1, S.cmd.idx+1); renderCmd(); e.preventDefault(); }
  else if (e.key === "ArrowUp"){ S.cmd.idx = Math.max(0, S.cmd.idx-1); renderCmd(); e.preventDefault(); }
  else if (e.key === "Enter"){ const c = list[S.cmd.idx]; if (c){ closeCmd(); c.run(); } e.preventDefault(); }
  else if (e.key === "Escape"){ closeCmd(); e.preventDefault(); }
}

/* ==========================================================================
   Global keyboard
   ========================================================================== */
let gPrefix = false, gTimer = null;
function onGlobalKey(e){
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k"){ e.preventDefault(); if (S.cmd.open) closeCmd(); else openCmd(); return; }
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "n"){ e.preventDefault(); navigate("#/setup/1"); return; }
  if (e.key === "Escape"){
    if (S.cmd.open){ closeCmd(); return; }
    if (S.modal){ closeModal(); return; }
    if (S.slideOver.open){ closeSlideOver(); return; }
  }
  if (!S.cmd.open && !S.modal && !e.metaKey && !e.ctrlKey && !e.altKey){
    if (gPrefix){
      const map = { o:"overview", i:"instances", r:"runtime", d:"diagnostics", s:"system" };
      const t = map[e.key.toLowerCase()];
      if (t){ navigate("#/" + t); e.preventDefault(); }
      gPrefix = false; clearTimeout(gTimer);
    } else if (e.key.toLowerCase() === "g"){
      gPrefix = true; clearTimeout(gTimer); gTimer = setTimeout(() => gPrefix = false, 1200);
    }
  }
}

/* ==========================================================================
   Page-level bindings
   ========================================================================== */
function bindPage(){
  // global: instance-card click opens slide-over (works on overview & instances)
  $$("[data-open]").forEach(el => el.onclick = (e) => {
    // 卡片上的快捷按钮（自检/配置/MCP 注册/⋮）由各自 data-* handler 接管，stopPropagation 已生效；这里再 closest 兜底，避免冒泡后误触卡片本身
    if (e.target.closest("button, a")) return;
    const parts = el.dataset.open.split("/");
    const dbId = parts[0], env = parts[1], mcpServer = parts.slice(2).join("/");
    // 直接打开抽屉（不等 hashchange）— 旧版仅 navigate，hash 不变时（重复点同一卡）不会触发 onHash → 抽屉不显示
    if (typeof openSlideOver === "function") openSlideOver(dbId, env, mcpServer);
    // 同时同步 URL，便于刷新/分享/后退；与 openSlideOver 重复执行幂等
    const want = "#/instances/" + dbId + "/" + env + (mcpServer ? "/" + mcpServer : "");
    if (location.hash !== want) navigate(want);
    else if (typeof renderMain === "function") renderMain();
  });
  $$("[data-retest]").forEach(el => el.onclick = async (e) => {
    e.stopPropagation();
    const parts = el.dataset.retest.split("/");
    const dbId = parts[0], env = parts[1], mcpServer = parts.slice(2).join("/");
    await runSelfTest(dbId, env, mcpServer, r => { toast(r.ok ? "自检通过" : "自检失败", r.ok ? "" : "err"); refreshDetect().then(renderMain); });
  });
  $$("[data-reg]").forEach(el => el.onclick = async (e) => {
    e.stopPropagation();
    const parts = el.dataset.reg.split("/");
    await registerEnv(parts[0], parts[1], parts.slice(2).join("/"));
  });
  $$("[data-mcp]").forEach(el => el.onclick = (e) => {
    e.stopPropagation();
    const parts = el.dataset.mcp.split("/");
    showMcpRegister(parts[0], parts[1], parts.slice(2).join("/"));
  });
  $$("[data-config]").forEach(el => el.onclick = async (e) => {
    e.stopPropagation();
    const parts = el.dataset.config.split("/");
    const dbId = parts[0], env = parts[1], mcpServer = parts.slice(2).join("/");
    const x = listEnvs().find(v => v.dbId === dbId && v.env === env && (v.mcpServer||"") === (mcpServer||""));
    const info = x ? x.info : {};
    S.wizard = {
      step:2, dbId, env,
      alias:(info.aliases||[]).join(" · "),
      aliasTouched: !!(info.aliases && info.aliases.length),
      host: info.host || "", port: info.port || "", user: info.user || "",
      password: info.password || "", pwdLocked: !!info.password,
      serviceOrDatabase: info.database || "",
      paste:"", tools: (info.tools||[]).slice(), testResult:null,
      serverName: info.serverName || "", mcpServer: info.mcpServer || ""
    };
    navigate("#/setup/2");
  });

  // topbar cmd button
  const cmdBtn = $("#cmd-open");
  if (cmdBtn) cmdBtn.onclick = openCmd;

  // filter bar
  const q = $("#inst-q"); if (q) q.oninput = () => { S.filters.q = q.value; renderMain(); };
  $$(".seg").forEach(seg => seg.querySelectorAll("button").forEach(b => b.onclick = () => { S.filters[seg.dataset.filter] = b.dataset.v; renderMain(); }));

  // add/refresh buttons
  const bind = (id, fn) => { const el = $(id); if (el) el.onclick = fn; };
  bind("#ov-add",   () => navigate("#/setup/1"));
  bind("#inst-add", () => navigate("#/setup/1"));
  bind("#inst-add-2", () => navigate("#/setup/1"));
  bind("#ov-goto-inst", () => navigate("#/instances"));
  bind("#ov-refresh", async () => { await refreshDetect(); renderMain(); toast("已刷新", "info"); });
  bind("#inst-refresh", async () => { await refreshDetect(); renderMain(); toast("已刷新", "info"); });
  bind("#banner-x", () => { S.prefs.setupCompleted = true; savePrefs({setupCompleted:true}); renderMain(); });
  bind("#diag-refresh", () => startLogPoll());
  bind("#diag-retest-all", retestAll);
  bind("#diag-open-root", () => openPath("root-dir", "open"));
  bind("#diag-reveal-root", () => openPath("root-dir", "reveal"));
  bind("#rt-sync", syncMappings);
  bind("#rt-add-target", addSkillTargetPrompt);
  bind("#rt-env-refresh", async () => { await refreshRuntimes(); renderMain(); toast("已刷新", "info"); });
  $$("[data-rt-set-local]").forEach(el => el.onclick = () => showSetLocalRuntime(el.dataset.rtSetLocal));
  $$("[data-rt-reset]").forEach(el => el.onclick = async () => {
    const kind = el.dataset.rtReset;
    try {
      await api("/api/runtimes/" + kind + "/reset", {});
      toast("已恢复默认运行时", "ok");
      await refreshRuntimes(); renderMain();
    } catch (e) { toast(e.message, "err"); }
  });
  $$("[data-rt-check]").forEach(el => el.onclick = async () => {
    const kind = el.dataset.rtCheck;
    const rt = (S.runtimes || {})[kind];
    if (!rt || !rt.executable) { toast("无可用运行时，无法检测", "err"); return; }
    try {
      const dir = rt.executable.replace(/[/\\][^/\\]+$/, "");
      const r = await api("/api/runtimes/" + kind + "/check?path=" + encodeURIComponent(dir));
      openModal(`<div class="modal-header"><h3>${esc(kind)} 兼容性检测</h3><button class="so-close" id="m-x">${IC.x}</button></div>
        <div class="modal-body">
          <dl class="kv">
            <dt>兼容</dt><dd>${r.compatible ? '<span class="badge ok">是</span>' : '<span class="badge warn">否</span>'}</dd><dt></dt>
            <dt>版本</dt><dd>${esc(r.version || "—")}</dd><dt></dt>
            <dt>可执行文件</dt><dd>${esc(norm(r.executable))}</dd><dt></dt>
            ${r.error ? `<dt>错误</dt><dd style="color:var(--danger)">${esc(r.error)}</dd><dt></dt>` : ""}
          </dl>
        </div>
        <div class="modal-footer"><button class="btn primary sm" id="m-ok">确定</button></div>`);
      $("#m-x").onclick = closeModal;
      $("#m-ok").onclick = closeModal;
    } catch (e) { toast(e.message, "err"); }
  });
  bind("#impl-refresh", async () => { await refreshImpls(); renderMain(); toast("已刷新", "info"); });
  $$("[data-impl-upload]").forEach(el => el.onclick = async (e) => {
    e.stopPropagation();
    const [dbId, serverId] = el.dataset.implUpload.split("/");
    const inp = document.createElement("input");
    inp.type = "file"; inp.accept = ".zip,.jar";
    inp.onchange = async () => {
      const file = inp.files[0]; if (!file) return;
      showLoading("正在上传 " + file.name + "…");
      try {
        const b64 = await fileToBase64(file);
        await api("/api/impls/" + dbId + "/" + serverId + "/upload", { zipBase64: b64, version: file.name.replace(/\.(zip|jar)$/i, "") });
        await refreshImpls(); renderMain();
        toast("上传成功", "ok");
      } catch (e){ toast(e.message, "err"); }
      finally { hideLoading(); }
    };
    inp.click();
  });
  $$("[data-impl-rollback]").forEach(el => el.onclick = async (e) => {
    e.stopPropagation();
    const [dbId, serverId] = el.dataset.implRollback.split("/");
    if (!confirm("确认回滚到上一版本？当前实现将备份后可恢复。")) return;
    try {
      const r = await api("/api/impls/" + dbId + "/" + serverId + "/rollback", {});
      await refreshImpls(); renderMain();
      toast(r.message || "已回滚");
    } catch (e){ toast(e.message, "err"); }
  });
  $$("[data-impl-bak]").forEach(el => el.onclick = async (e) => {
    e.stopPropagation();
    const [dbId, serverId] = el.dataset.implBak.split("/");
    try {
      const r = await api("/api/impls/" + dbId + "/" + serverId + "/bak-versions");
      const versions = r.versions || [];
      showModal("历史版本 · " + dbId + "/" + serverId, versions.length === 0
        ? '<p class="text-mut">暂无历史版本。</p>'
        : `<div class="tbl-wrap"><table class="tbl"><thead><tr><th>版本</th><th>备份时间</th></tr></thead><tbody>${versions.map(v => `<tr><td class="mono">${esc(v.version||"—")}</td><td>${esc(relTime(v.bakTime||v.bakAt||""))}</td></tr>`).join("")}</tbody></table></div>`);
    } catch (e){ toast(e.message, "err"); }
  });
  $$("[data-impl-check-update]").forEach(el => el.onclick = async (e) => {
    e.stopPropagation();
    const [dbId, serverId] = el.dataset.implCheckUpdate.split("/");
    await checkImplUpdate(dbId, serverId, el);
  });
  $$("[data-impl-github-dl]").forEach(el => el.onclick = async (e) => {
    e.stopPropagation();
    const [dbId, serverId] = el.dataset.implGithubDl.split("/");
    await showGithubImplDownload(dbId, serverId);
  });
  $$("[data-rt-github-dl]").forEach(el => el.onclick = async (e) => {
    e.stopPropagation();
    const kind = el.dataset.rtGithubDl;
    await showGithubRuntimeDownload(kind);
  });
  $$("[data-rm-target]").forEach(el => el.onclick = async () => {
    const t = el.dataset.rmTarget;
    try { await api("/api/skill/targets", {action:"remove", target:t}); toast("已移除 " + t); await refreshDetect(); renderMain(); }
    catch (e){ toast(e.message, "err"); }
  });

  bind("#sys-reload", async () => { await refreshDetect(); renderMain(); toast("环境已刷新", "info"); });
  const sbox = $("#sys-state-box");
  bind("#sys-show-state", () => { if (sbox) sbox.style.display = sbox.style.display === "none" ? "block" : "none"; });
  bind("#sys-reset", showResetPreview);
  bind("#sys-uninstall", uninstallConfirm);

  bind("#welcome-start", () => navigate("#/setup/1"));
  bind("#welcome-skip", () => { S.prefs.setupCompleted = true; savePrefs({setupCompleted:true}); navigate("#/overview"); });
  bind("#re-detect", async () => { await refreshDetect(); renderMain(); toast("已重新检测", "info"); });

  // Setup wizard
  bind("#s1-cancel", () => navigate("#/overview"));
  $$("[data-pick-impl]").forEach(el => el.onclick = () => {
    const db = el.dataset.db;
    const impl = el.dataset.impl || "";
    if (S.wizard.dbId !== db || S.wizard.mcpServer !== impl){
      S.wizard = { step:1, dbId:db, env:null, alias:"", aliasTouched:false,
                   password:"", pwdLocked:false, host:"", port:"", user:"",
                   serviceOrDatabase:"", paste:"", tools:[],
                   testResult:null, serverName:"", mcpServer:impl };
    }
    $$("[data-pick-impl]").forEach(x => x.classList.toggle("on", x === el));
    const nx = $("#s1-next");
    if (nx) {
      nx.disabled = false;
      const st = implStatus(db, impl);
      nx.innerHTML = (st === null ? '下载并安装' : '下一步') + ' ' + IC.right;
    }
  });
  bind("#s1-next", async () => {
    if (!S.wizard.dbId){ toast("请选择数据源与实现", "warn"); return; }
    const rr = (S.detect && S.detect.runtimeReady) || {};
    const firstTime = !rr[S.wizard.dbId];
    showLoading(firstTime ? "正在部署运行时（首次需解压内置 Node 运行时与依赖，可能持续数分钟）…" : "正在部署运行时…");
    try {
      await api("/api/deploy", { dbId: S.wizard.dbId });
      await refreshDetect();
      navigate("#/setup/2");
    } catch (e){ toast(e.message, "err"); }
    finally { hideLoading(); }
  });
  bind("#s2-switch-db", () => navigate("#/setup/1"));
  // JDBC URL 实时拼接
  ["#s2-host", "#s2-port", "#s2-service"].forEach(sel => {
    const el = $(sel); if (el) el.addEventListener("input", computeJdbcUrl);
  });
  computeJdbcUrl();
  // 实例标识 → 别名预设 联动
  $$("[data-env-code]").forEach(el => el.onclick = () => {
    const code = el.dataset.envCode;
    const envInp = $("#s2-env"); if (!envInp) return;
    envInp.value = code;
    envInp.dispatchEvent(new Event("input", {bubbles:true}));
  });
  const wireAliasChipHandlers = () => $$("[data-alias-preset]").forEach(el => el.onclick = () => {
    const tag = el.dataset.aliasPreset;
    const aliasInp = $("#s2-alias"); if (!aliasInp) return;
    const cur = (aliasInp.value || "").split(/[·,;]/).map(s => s.trim()).filter(Boolean);
    if (cur.includes(tag)){ toast("已存在该标签", "info"); return; }
    cur.push(tag);
    aliasInp.value = cur.join(" · ");
    S.wizard.aliasTouched = true;
    S.wizard.alias = aliasInp.value;
  });
  wireAliasChipHandlers();
  const envInput = $("#s2-env");
  if (envInput) envInput.addEventListener("input", (e) => {
    const code = e.target.value.trim();
    S.wizard.env = code;
    refreshAliasChips(code);
    // MCP Server 名称 placeholder 跟随实例标识实时提示默认规则
    const snp = $("#s2-servername");
    if (snp){
      const pref = (adapter(S.wizard.dbId) || {}).serverPrefix || (S.wizard.dbId || "") + "-";
      snp.placeholder = pref + (code || "env");
    }
    const aliasInp = $("#s2-alias"); if (!aliasInp) return;
    if (!S.wizard.aliasTouched){
      aliasInp.value = autoFillAliasFromEnv(code);
      S.wizard.alias = aliasInp.value;
    }
  });
  const aliasInput = $("#s2-alias");
  if (aliasInput){
    // 若用户已有非空别名（例如编辑实例回填的），标记为 touched 不再自动覆盖
    if (aliasInput.value.trim()) S.wizard.aliasTouched = true;
    aliasInput.addEventListener("input", (e) => {
      S.wizard.aliasTouched = true;
      S.wizard.alias = e.target.value;
    });
  }
  const snInput = $("#s2-servername");
  if (snInput){
    snInput.addEventListener("input", () => {
      S.wizard.serverName = snInput.value.trim();
      checkServerNameUnique();
    });
    checkServerNameUnique();
  }
  function refreshAliasChips(code){
    const box = $("#s2-alias-chips"); if (!box) return;
    const preset = aliasPresetFor(code);
    box.innerHTML = `<span class="text-mut" style="font-size:11px">根据标识推荐：</span>` +
      (preset.length ? preset.map(t => `<button class="chip-add" data-alias-preset="${esc(t)}">+ ${esc(t)}</button>`).join("") :
        '<span class="text-mut" style="font-size:11px">未识别到常用标识，可自由命名</span>');
    wireAliasChipHandlers();
  }
  // 密码框：锁定态双击解锁 / 明文输入双向同步
  const pwdEl = $("#s2-pwd");
  if (pwdEl){
    pwdEl.addEventListener("input", (e) => { S.wizard.password = e.target.value; });
    pwdEl.addEventListener("dblclick", () => {
      if (!S.wizard.pwdLocked) return;
      S.wizard.pwdLocked = false;
      renderMain();
      const p2 = $("#s2-pwd");
      if (p2){
        p2.focus();
        const end = p2.value.length;
        try { p2.setSelectionRange(end, end); } catch {}
        toast("已解锁，可编辑密码", "info");
      }
    });
    // 键盘 Enter 也能解锁（无障碍）
    pwdEl.addEventListener("keydown", (e) => {
      if (S.wizard.pwdLocked && (e.key === "Enter" || e.key === "F2")){
        e.preventDefault(); pwdEl.dispatchEvent(new Event("dblclick"));
      }
    });
  }
  bind("#s2-back", () => navigate("#/setup/1"));
  // MCP 服务实现切换 → 按所选实现局部重绘工具勾选区（保留仍可用的勾选，必选项自动补勾）
  const redrawToolCard = () => {
    const a = adapter(S.wizard.dbId) || {};
    const box = $("#s2-tools-card"); if (!box) return;
    const opts = a.mcpServerOptions || [];
    const srvIdRaw = S.wizard.mcpServer || (opts[0] && opts[0].id);
    const opt = opts.find(o => o.id === srvIdRaw) || opts[0] || {};
    const srvId = opt.id || srvIdRaw;
    const all = (opt.allTools && opt.allTools.length) ? opt.allTools : (a.allTools || []);
    const req = (opt.requiredTools && opt.requiredTools.length) ? opt.requiredTools : (a.requiredTools || []);
    const checked = $$("[data-tool]:checked").map(el => el.dataset.tool).filter(t => all.indexOf(t) >= 0);
    req.forEach(t => { if (checked.indexOf(t) < 0) checked.push(t); });
    box.innerHTML = `<div class="field-label" style="margin-bottom:8px">启用工具（${checked.length} / ${all.length}）</div>`
      + toolListHtml(a, srvId, checked);
  };
  $$('input[name="mcp-server-impl"]').forEach(r => r.onchange = () => {
    S.wizard.mcpServer = r.value;
    redrawToolCard();
  });
  bind("#s2-parse", async () => {
    const paste = $("#s2-paste").value;
    if (!paste){ toast("请先粘贴内容", "warn"); return; }
    try {
      const r = await api("/api/env/parse", { text:paste, dbId:S.wizard.dbId });
      const f = r.fields || r;
      if (f.host) S.wizard.host = f.host;
      if (f.port) S.wizard.port = f.port;
      if (f.user) S.wizard.user = f.user;
      if (f.service || f.database) S.wizard.serviceOrDatabase = f.service || f.database;
      let lockedNow = false;
      if (f.password){
        S.wizard.password = f.password;
        S.wizard.pwdLocked = true;
        lockedNow = true;
      }
      renderMain();
      if (lockedNow) toast("已解析填充，出于安全密码不再回显（双击密码框可编辑）", "info");
      else toast("已解析填充");
    } catch (e){ toast(e.message, "err"); }
  });
  bind("#s2-next", async () => {
    if (!checkServerNameUnique()){
      toast("MCP Server 名称不合法或已被其他实例占用，请修改后再保存", "err");
      return;
    }
    const envCode = $("#s2-env").value.trim();
    let aliases = ($("#s2-alias").value || "").split(/[·,;\s]+/).filter(Boolean);
    if (aliases.length === 0) aliases = aliasPresetFor(envCode);
    const pwdVal = S.wizard.pwdLocked ? (S.wizard.password || "") : $("#s2-pwd").value;
    const body = {
      dbId: S.wizard.dbId,
      env: envCode,
      host: $("#s2-host").value.trim(),
      port: $("#s2-port").value.trim(),
      user: $("#s2-user").value.trim(),
      password: pwdVal,
      service: $("#s2-service").value.trim(),
      database: $("#s2-service").value.trim(),
      paste: $("#s2-paste").value,
      aliases: aliases
    };
    const tools = $$("[data-tool]:checked").map(el => el.dataset.tool);
    body.tools = tools;
    body.serverName = ($("#s2-servername") ? $("#s2-servername").value.trim() : "");
    const srvSel = $$('input[name="mcp-server-impl"]').filter(el => el.checked)[0];
    body.mcpServer = srvSel ? srvSel.value : "";
    try {
      await api("/api/env/config", body);
      S.wizard.env = body.env;
      S.wizard.password = pwdVal;
      S.wizard.tools = tools;
      S.wizard.serverName = body.serverName;
      S.wizard.mcpServer = body.mcpServer;
      await refreshDetect();
      navigate("#/setup/3");
    } catch (e){ toast(e.message, "err"); }
  });
  bind("#s3-back", () => { S.wizard.s3Token = null; navigate("#/setup/2"); });
  bind("#s3-test", () => runWizardSelfTest());
  // 进入第三步自动开始自检（仅对当前实例执行一次，避免重复渲染反复触发）
  if (S.wizard.step === 3){
    const tok = S.wizard.dbId + "/" + S.wizard.env + "/" + (S.wizard.mcpServer||"");
    if (S.wizard.s3Token !== tok){
      S.wizard.s3Token = tok;
      runWizardSelfTest();
    }
  }
  bind("#s3-done", async () => {
    S.prefs.setupCompleted = true;
    await savePrefs({ setupCompleted:true, lastEnv: S.wizard.dbId + "/" + S.wizard.env });
    try { await api("/api/skill/sync", {}); toast("已同步所有 Skill"); }
    catch (e){ toast("Skill 同步失败：" + e.message, "err"); }
    navigate("#/instances");
    await refreshDetect();
    showMcpRegister(S.wizard.dbId, S.wizard.env, S.wizard.mcpServer);
  });
}

async function pollTest(dbId, env, mcpServer, max=180){
  for (let i=0; i<max; i++){
    await new Promise(r => setTimeout(r, 500));
    try {
      const r = await api("/api/env/test/poll?dbId=" + encodeURIComponent(dbId) + "&env=" + encodeURIComponent(env) + "&mcpServer=" + encodeURIComponent(mcpServer||""));
      if (r && !r.running) return r;
    } catch (e){ /* retry */ }
  }
  return { ok:false, detail:"超时" };
}

async function runSelfTest(dbId, env, mcpServer, cb){
  try {
    toast("自检开始…", "info");
    await api("/api/env/test", { dbId, env, mcpServer });
    const r = await pollTest(dbId, env, mcpServer);
    cb && cb(r);
    await refreshDetect();
    return r;
  } catch (e){ toast(e.message, "err"); cb && cb({ ok:false, detail:e.message }); }
}

/** 第三步向导自检：启动实时日志轮询（黑色终端）+ 等待最终结果。 */
async function runWizardSelfTest(){
  const dbId = S.wizard.dbId, env = S.wizard.env, mcpServer = S.wizard.mcpServer;
  const enc = encodeURIComponent;
  const logUrl = "/api/env/test/log?dbId=" + enc(dbId) + "&env=" + enc(env) + "&mcpServer=" + enc(mcpServer||"");
  S.wizard.testResult = { running:true };
  const block = $("#test-block"); if (block) block.innerHTML = renderTestResult(S.wizard.testResult);
  const testBtn = $("#s3-test"); if (testBtn) testBtn.disabled = true;
  const term = $("#s3-term");
  if (term) term.innerHTML = "";
  let lastN = 0;
  const appendLogs = (lines) => {
    if (!lines) return;
    // 防御：若期间发生重渲染导致 term 被替换，重新查询（保持引用有效）；
    // 一旦 term 被重建，lastN 必须归零从整份日志重新渲染，否则会漏掉已存在的行、终端变空白。
    let el = (term && document.body.contains(term)) ? term : null;
    if (!el) { el = $("#s3-term"); lastN = 0; }
    if (!el) return;
    let advanced = false;
    for (let i = lastN; i < lines.length; i++){
      // renderTermLine 返回 HTML 字符串，用 insertAdjacentHTML 插入（之前误用 appendChild 传字符串会抛 TypeError，被 try/catch 静默吞掉，导致终端始终空白）
      el.insertAdjacentHTML("beforeend", renderTermLine(lines[i]));
      advanced = true;
    }
    if (advanced){ lastN = lines.length; el.scrollTop = el.scrollHeight; }
  };
  const pollLog = setInterval(async () => {
    try { const r = await api(logUrl); appendLogs(r.lines || []); } catch (e){ /* ignore */ }
  }, 400);
  try {
    await api("/api/env/test", { dbId, env, mcpServer });
    const r = await pollTest(dbId, env, mcpServer);
    S.wizard.testResult = r;
    const b2 = $("#test-block"); if (b2) b2.innerHTML = renderTestResult(r);
  } catch (e){
    S.wizard.testResult = { ok:false, detail:e.message };
    const b2 = $("#test-block"); if (b2) b2.innerHTML = renderTestResult(S.wizard.testResult);
  } finally {
    clearInterval(pollLog);
    const tb = $("#s3-test"); if (tb) tb.disabled = false;
    // 末次补齐，确保完整日志落盘
    try { const r = await api(logUrl); appendLogs(r.lines || []); } catch (e){ /* ignore */ }
  }
}

/** 自检日志单行着色：请求(>>)青、响应(<<)绿、tap 转发灰、错误红、分隔头黄。 */
function renderTermLine(line){
  let cls = "ln";
  if (line.startsWith(">>")) cls += " req";
  else if (line.startsWith("<<")) cls += " res";
  else if (line.indexOf("[tap->") >= 0) cls += " tap";
  else if (/FAIL|EXCEPTION|ERROR|错误|Exception/.test(line)) cls += " err";
  else if (line.startsWith("===")) cls += " head";
  return `<span class="${cls}">${esc(line)}</span>`;
}
async function registerEnv(dbId, env, mcpServer){
  try {
    const r = await api("/api/env/register", { dbId, env, mcpServer });
    toast("已注册 " + r.serverName);
    await refreshDetect();
    renderMain();
  } catch (e){ toast(e.message, "err"); }
}
async function unregisterEnv(dbId, env, mcpServer){
  try {
    await api("/api/env/delete", { dbId, env, mcpServer });
    toast("已移除 " + dbId + "/" + env + (mcpServer ? "/" + mcpServer : ""));
    await refreshDetect();
    closeSlideOver();
    renderMain();
  } catch (e){ toast(e.message, "err"); }
}
async function retestAll(){
  const envs = listEnvs();
  if (!envs.length){ toast("没有可自检的实例", "warn"); return; }
  toast("排队自检 " + envs.length + " 个实现…", "info");
  for (const x of envs){
    try { await api("/api/env/test", { dbId:x.dbId, env:x.env, mcpServer:x.mcpServer }); await pollTest(x.dbId, x.env, x.mcpServer, 60); } catch {}
  }
  await refreshDetect();
  renderMain();
  toast("全部自检完成");
}
async function syncMappings(){
  try { const r = await api("/api/skill/sync", {}); toast("已同步 " + (r.updated||[]).length + " 份映射"); }
  catch (e){ toast(e.message, "err"); }
}
function fmtSize(bytes){
  if (!bytes || bytes <= 0) return "—";
  if (bytes < 1024) return bytes + " B";
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + " KB";
  return (bytes / 1048576).toFixed(1) + " MB";
}

async function checkImplUpdate(dbId, serverId, el){
  const installed = (S.impls || {})[dbId]?.[serverId];
  const curVer = installed ? (installed.version || "—") : "未安装";
  if (el){ el.disabled = true; el.textContent = "检查中…"; }
  try {
    const rel = await api("/api/releases/latest/artifacts?type=impl", null, "GET");
    const tag = rel.tagName || "unknown";
    const remoteVer = tag.replace(/^v/, "");
    const localVer = (installed?.version || "").replace(/^v/, "");
    const hasUpdate = localVer && remoteVer && remoteVer !== localVer;
    if (!installed) {
      toast("当前未安装，可直接从 GitHub 下载", "info");
    } else if (hasUpdate) {
      toast("发现新版本: " + tag + "（当前: " + curVer + "）", "info");
    } else {
      toast("已是最新版本 (" + curVer + ")", "ok");
    }
  } catch (e) {
    toast("检查更新失败: " + e.message, "err");
  } finally {
    if (el){ el.disabled = false; el.innerHTML = IC.refresh + " 检查更新"; }
  }
}

async function showGithubImplDownload(dbId, serverId){
  const a = adapter(dbId);
  const opt = a?.mcpServerOptions?.find(o => o.id === serverId);
  const label = opt ? opt.displayName : serverId;
  openModal(`<div class="modal-header"><h3>从 GitHub 下载 · ${esc(label)}</h3><button class="so-close" id="m-x">${IC.x}</button></div>
  <div class="modal-body" id="m-github-body"><p class="text-mut">正在查询 GitHub Release…</p></div>
  <div class="modal-footer"><button class="btn secondary" id="m-cancel">关闭</button></div>`);
  $("#m-x").onclick = closeModal;
  $("#m-cancel").onclick = closeModal;
  try {
    const rel = await api("/api/releases/latest/artifacts?type=impl", null, "GET");
    const artifacts = rel.artifacts || [];
    const body = $("#m-github-body");
    if (!artifacts.length) {
      body.innerHTML = `<p class="text-mut">当前 Release (${esc(rel.tagName)}) 没有可用的实现产物。</p>`;
      return;
    }
    const installed = (S.impls || {})[dbId]?.[serverId];
    const curVer = installed ? (installed.version || "—") : "未安装";
    body.innerHTML = `<p class="text-mut" style="margin-bottom:12px">最新版本: <strong>${esc(rel.tagName)}</strong> · 当前: <strong>${esc(curVer)}</strong></p>
    <div class="sel-dir-list">${artifacts.map((art, i) => `<div class="sel-dir-block" style="cursor:pointer" data-dl-idx="${i}">
      <span class="sd-path">${esc(art.name)} <span class="text-mut" style="font-size:11px">${esc(art.platform)} · ${fmtSize(art.size)}</span></span>
    </div>`).join("")}</div>`;
    body.querySelectorAll("[data-dl-idx]").forEach(el => {
      el.onclick = async () => {
        const idx = Number(el.dataset.dlIdx);
        const art = artifacts[idx];
        body.innerHTML = `<p class="text-mut">正在下载 <strong>${esc(art.name)}</strong>…</p><div class="progress-bar"><div class="progress-fill" id="m-dl-bar" style="width:0%"></div></div><p class="text-mut" id="m-dl-status" style="font-size:12px;margin-top:8px">连接中…</p>`;
        try {
          const r = await api("/api/impls/" + dbId + "/" + serverId + "/install-url", { url: art.downloadUrl, version: rel.tagName });
          toast("已安装 " + art.name, "ok");
          closeModal();
          await refreshImpls();
          renderMain();
        } catch (e) {
          $("#m-dl-status").textContent = "下载失败: " + e.message;
          $("#m-dl-status").style.color = "var(--danger)";
        }
      };
    });
  } catch (e) {
    $("#m-github-body").innerHTML = `<p style="color:var(--danger)">查询失败: ${esc(e.message)}</p>`;
  }
}

async function showGithubRuntimeDownload(kind){
  const label = kind === "java" ? "Java (JRE)" : "Node.js";
  const assetType = kind === "java" ? "runtime-jre" : "runtime-node";
  openModal(`<div class="modal-header"><h3>从 GitHub 下载 · ${esc(label)} 运行时</h3><button class="so-close" id="m-x">${IC.x}</button></div>
  <div class="modal-body" id="m-github-body"><p class="text-mut">正在查询 GitHub Release…</p></div>
  <div class="modal-footer"><button class="btn secondary" id="m-cancel">关闭</button></div>`);
  $("#m-x").onclick = closeModal;
  $("#m-cancel").onclick = closeModal;
  try {
    const rel = await api("/api/releases/latest/artifacts?type=" + assetType, null, "GET");
    const artifacts = rel.artifacts || [];
    const body = $("#m-github-body");
    if (!artifacts.length) {
      body.innerHTML = `<p class="text-mut">当前 Release (${esc(rel.tagName)}) 没有可用的 ${esc(label)} 运行时产物。</p>`;
      return;
    }
    const curInfo = (S.runtimes || {})[kind];
    const curVer = curInfo ? (curInfo.version || "—") : "未检测到";
    body.innerHTML = `<p class="text-mut" style="margin-bottom:12px">最新版本: <strong>${esc(rel.tagName)}</strong> · 当前: <strong>${esc(curVer)}</strong></p>
    <div class="sel-dir-list">${artifacts.map((art, i) => `<div class="sel-dir-block" style="cursor:pointer" data-dl-idx="${i}">
      <span class="sd-path">${esc(art.name)} <span class="text-mut" style="font-size:11px">${esc(art.platform)} · ${fmtSize(art.size)}</span></span>
    </div>`).join("")}</div>`;
    body.querySelectorAll("[data-dl-idx]").forEach(el => {
      el.onclick = async () => {
        const idx = Number(el.dataset.dlIdx);
        const art = artifacts[idx];
        body.innerHTML = `<p class="text-mut">正在下载 <strong>${esc(art.name)}</strong>…</p><div class="progress-bar"><div class="progress-fill" id="m-dl-bar" style="width:0%"></div></div><p class="text-mut" id="m-dl-status" style="font-size:12px;margin-top:8px">连接中…</p>`;
        try {
          const r = await api("/api/runtimes/" + kind + "/install", { url: art.downloadUrl });
          toast("已安装 " + label + "运行时 " + (r.version || ""), "ok");
          closeModal();
          await refreshRuntimes();
          renderMain();
        } catch (e) {
          $("#m-dl-status").textContent = "下载失败: " + e.message;
          $("#m-dl-status").style.color = "var(--danger)";
        }
      };
    });
  } catch (e) {
    $("#m-github-body").innerHTML = `<p style="color:var(--danger)">查询失败: ${esc(e.message)}</p>`;
  }
}

function showSetLocalRuntime(kind){
  const label = kind === "java" ? "Java (JRE)" : "Node.js";
  openModal(`<div class="modal-header"><h3>设置${esc(label)}本地目录</h3><button class="so-close" id="m-x">${IC.x}</button></div>
  <div class="modal-body">
    <div class="field"><div class="field-label"><span>运行时目录路径</span><span class="field-hint">包含 bin/${esc(kind === "java" ? "java" : "node")} 可执行文件的目录</span></div>
      <input class="input mono" id="m-rt-path" placeholder="${esc(kind === 'java' ? 'C:\\Program Files\\Java\\jdk-17' : 'C:\\Program Files\\nodejs')}" style="width:100%">
    </div>
  </div>
  <div class="modal-footer"><button class="btn secondary" id="m-cancel">取消</button><button class="btn primary" id="m-ok">确定</button></div>`);
  $("#m-x").onclick = closeModal;
  $("#m-cancel").onclick = closeModal;
  $("#m-ok").onclick = async () => {
    const p = ($("#m-rt-path") || {}).value || "";
    if (!p.trim()) { toast("请输入目录路径", "err"); return; }
    try {
      const r = await api("/api/runtimes/" + kind + "/set-local", { path: p.trim() });
      toast("已设置" + label + "运行时", "ok");
      closeModal();
      await refreshRuntimes();
      renderMain();
    } catch (e) { toast(e.message, "err"); }
  };
  setTimeout(() => { const inp = $("#m-rt-path"); if (inp) inp.focus(); }, 100);
}

async function addSkillTargetPrompt(){
  // 推荐目录 = 与 MCP 注册弹框相同的客户端集合（QoderWork + 各 McpTarget 的技能根目录）
  const items = [{ name:"QoderWork", dir:"~/.qoderwork/skills" }];
  try { await loadMcpTargets(true); } catch (e){ /* ignore */ }
  (S.mcpTargets || []).forEach(t => { if (t.skillDir) items.push({ name: t.displayName, dir: t.skillDir }); });
  const selected = [];                       // 已选目录（去重、保序）
  openModal(`<div class="modal-header"><h3>新增 Skill 目标目录</h3><button class="so-close" id="m-x">${IC.x}</button></div>
  <div class="modal-body">
    <div class="field"><div class="field-label"><span>路径</span><span class="field-hint">agent 技能根目录，可手动输入后点"添加此路径"</span></div>
      <div class="flex gap-2"><input class="input mono" id="m-path" placeholder="C:\\Users\\you\\.qoderwork\\skills" style="flex:1; min-width:0"><button class="btn secondary sm" id="m-add-path">添加此路径</button></div>
    </div>
    <div class="field-label" style="margin-top:16px">常用客户端（可多选，点击切换）</div>
    <div class="flex gap-2 flex-wrap sk-clients">${items.map(it => `<button class="btn ghost sm sk-client" data-skilldir="${esc(it.dir)}">${esc(it.name)}</button>`).join("")}</div>
    <div class="field-label" style="margin-top:16px">已选目录（<span id="m-sel-count">0</span>）</div>
    <div id="m-sel" class="sel-dir-list"></div>
  </div>
  <div class="modal-footer"><button class="btn secondary" id="m-cancel">取消</button><button class="btn primary" id="m-ok" disabled>添加</button></div>`);
  $("#m-x").onclick = closeModal; $("#m-cancel").onclick = closeModal;
  const renderSel = () => {
    const box = $("#m-sel"); if (!box) return;
    const cnt = $("#m-sel-count"); if (cnt) cnt.textContent = selected.length;
    const ok = $("#m-ok"); if (ok) ok.disabled = selected.length === 0;
    // 同步客户端 chip 的选中态（删除已选块时让对应 chip 取消高亮）
    $$("[data-skilldir]").forEach(b => b.classList.toggle("on", selected.includes(b.dataset.skilldir)));
    if (!selected.length){ box.innerHTML = '<p class="text-mut" style="font-size:12px">尚未选择任何目录</p>'; return; }
    box.innerHTML = selected.map((p,i) => `<div class="sel-dir-block"><span class="sd-path" title="${esc(p)}">${esc(p)}</span><button class="sd-x" data-i="${i}" title="移除">${IC.x}</button></div>`).join("");
    box.querySelectorAll(".sd-x").forEach(x => x.onclick = () => { selected.splice(Number(x.dataset.i),1); renderSel(); });
  };
  // 客户端多选切换
  $$("[data-skilldir]").forEach(b => b.onclick = () => {
    const d = b.dataset.skilldir;
    const idx = selected.indexOf(d);
    if (idx >= 0) selected.splice(idx,1); else selected.push(d);
    renderSel();
  });
  // 手动输入路径
  const addTyped = () => {
    const inp = $("#m-path"); const v = inp.value.trim();
    if (!v) return;
    if (!selected.includes(v)) selected.push(v);
    inp.value = "";
    renderSel();
  };
  $("#m-add-path").onclick = addTyped;
  $("#m-path").addEventListener("keydown", e => { if (e.key === "Enter"){ e.preventDefault(); addTyped(); } });
  renderSel();
  // 确认：逐个目录提交（后端 skillAddTarget 单次仅接受一个 target，故循环调用）
  $("#m-ok").onclick = async () => {
    if (!selected.length){ toast("请至少选择一个目录", "warn"); return; }
    const btn = $("#m-ok"); btn.disabled = true; btn.textContent = "添加中…";
    let okN = 0;
    for (const p of selected){
      try { await api("/api/skill/targets", {action:"add", target:p}); okN++; }
      catch (e){ toast("添加失败：" + p + " " + e.message, "err"); }
    }
    toast("已添加 " + okN + " 个目录并部署 Skill");
    closeModal(); await refreshDetect(); renderMain();
  };
}
async function saveToolsFor(x){
  const body = {
    dbId:x.dbId, env:x.env, mcpServer:x.mcpServer,
    host:x.info.host, port:x.info.port, user:x.info.user, password:x.info.password,
    service:x.info.database, database:x.info.database, url:x.info.url,
    aliases:x.info.aliases,
    tools: $$("[data-toggle-tool]:checked").map(el => el.dataset.toggleTool)
  };
  try {
    await api("/api/env/config", body);
    toast("已保存");
    await refreshDetect();
    const nx = listEnvs().find(e => e.dbId===x.dbId && e.env===x.env && (e.mcpServer||"")===(x.mcpServer||"")) || x;
    $("#slideover").innerHTML = renderSlideOver(nx);
    bindSlideOver(nx);
  } catch (e){ toast(e.message, "err"); }
}
function copyMcpEntry(x){
  const name = serverNameFor(x.dbId, x.env, x.mcpServer);
  const stub = { [name]: { command: S.detect.javaCmd, args:["-jar", norm(S.detect.root)+"/tap/mcp-tap.jar"], env:{} } };
  navigator.clipboard.writeText(JSON.stringify(stub, null, 2));
  toast("已复制 mcp.json 条目");
}
function uninstallConfirm(){
  openModal(`<div class="modal-header"><h3>${IC.alert} 完全卸载</h3><button class="so-close" id="m-x">${IC.x}</button></div>
    <div class="modal-body"><p class="text-mut">将清空所有配置并调用系统卸载程序。JAR 本体需要在向导关闭后手动删除。</p></div>
    <div class="modal-footer"><button class="btn secondary" id="m-cancel">取消</button><button class="btn danger" id="m-ok">${IC.alert} 执行卸载</button></div>`, { danger:true });
  $("#m-x").onclick = closeModal;
  $("#m-cancel").onclick = closeModal;
  $("#m-ok").onclick = async () => {
    try { const r = await api("/api/uninstall", {}); toast("已卸载。" + (r.selfRemoved || "")); closeModal(); }
    catch (e){ toast(e.message, "err"); }
  };
}

async function refreshDetect(){
  try {
    const [det, ad] = await Promise.all([api("/api/detect"), api("/api/adapters")]);
    S.detect = det; S.adapters = (ad && ad.adapters) || S.adapters;
    S.mcpTargets = null;
    markActiveNav(S.route ? S.route.name : "overview");
  } catch (e){ /* silent */ }
}

/* ---------- Bootstrap ---------- */
document.addEventListener("DOMContentLoaded", boot);

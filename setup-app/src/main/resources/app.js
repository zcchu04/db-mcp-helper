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
  adapters: [],      // [{id, displayName, defaultPort, serverPrefix, skillDir, runtimeKind, allTools, requiredTools}]
  mcpTargets: null,  // [{id, displayName, describe, icon, iconClass, tier, writable, cliBased, detected, candidatePaths, actualPath, existingServers}]
  prefs: { theme:"system", sidebarCollapsed:false, setupCompleted:false, lastEnv:null },
  route: null,
  slideOver: { dbId:null, env:null, tab:"overview", open:false },
  wizard: { step:1, dbId:null, env:null, alias:"", aliasTouched:false, password:"", pwdLocked:false, host:"", port:"", user:"", serviceOrDatabase:"", jdbcUrl:"", paste:"", tools:[], testResult:null },
  filters: { q:"", dbType:"all", perm:"all", status:"all" },
  cmd: { open:false, q:"", idx:0 },
  modal: null,
  logPollTimer: null,
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
    out.push({ env:code, dbId:e.dbType, info:e });
  }
  return out;
}
function isRegistered(dbId, env){
  const pref = (adapter(dbId) || {}).serverPrefix || (dbId + "-");
  const list = (S.detect && S.detect.registeredServers) || [];
  return list.indexOf(pref + env) >= 0;
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
  instances:"实例", runtime:"Skill 与运行时",
  diagnostics:"排障", system:"系统"
};
function parseHash(){
  const h = (location.hash || "#/overview").replace(/^#\/?/, "");
  const seg = h.split("/").filter(Boolean);
  if (!seg.length) return { name:"overview" };
  const name = seg[0];
  if (name === "setup") return { name:"setup", step: parseInt(seg[1]||"1",10) || 1 };
  if (name === "instances"){
    if (seg.length >= 3) return { name:"instances", dbId:seg[1], env:seg[2] };
    return { name:"instances" };
  }
  return { name };
}
function navigate(hash){ location.hash = hash; }

async function onHash(){
  const r = parseHash();
  S.route = r;
  if (r.name === "instances" && r.dbId && r.env){
    openSlideOver(r.dbId, r.env);
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
    const [det, adaptersR, prefs] = await Promise.all([
      api("/api/detect"),
      api("/api/adapters"),
      api("/api/prefs").catch(() => null)
    ]);
    S.detect = det;
    S.adapters = (adaptersR && adaptersR.adapters) || [];
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
  const names = ["选择数据源","配置实例","自检与注册"];
  const stepper = `<div class="stepper">${names.map((n,i) => {
    const idx = i+1;
    const cls = idx < step ? "done" : (idx === step ? "current" : "");
    return `<div class="step ${cls}"><span class="num">${idx < step ? "✓" : idx}</span><span>${esc(n)}</span></div>${i<total-1?'<div class="step-line"></div>':''}`;
  }).join("")}</div>`;
  if (step >= 2 && !S.wizard.dbId){
    return `<div class="page" style="max-width:1080px">
      <div class="page-title-row"><div><div class="page-title">接入向导</div><div class="page-sub">先完成第一步</div></div></div>
      ${stepper}
      <div class="card"><div class="empty">${IC.info}<h3>还没有选择数据源</h3><p>向导需要先在第一步选择 Oracle 或 MySQL。</p>
        <button class="btn primary" onclick="location.hash='#/setup/1'">返回第一步 ${IC.right}</button></div></div>
    </div>`;
  }
  let body = "";
  if (step === 1) body = setupStep1();
  else if (step === 2) body = setupStep2();
  else body = setupStep3();
  return `<div class="page" style="max-width:1080px">
    <div class="page-title-row"><div><div class="page-title">接入向导</div><div class="page-sub">3 步完成：${esc(names[step-1])}</div></div></div>
    ${stepper}
    ${body}
  </div>`;
}

function setupStep1(){
  const cur = S.wizard.dbId;
  return `<div class="wizard-split">
    <div>
      <div class="card">
        <div class="card-header"><h3>选择一种数据源</h3></div>
        <p class="card-desc">向导每次配置一种数据源的一个实例。添加多个实例可在完成后进「实例」页操作。</p>
        <div class="db-picker">
          ${S.adapters.map(a => `<div class="db-pick ${cur===a.id?'on':''}" data-pick-db="${esc(a.id)}">
            <div class="head">${dbIcon(a.id)}<div class="name">${esc(a.displayName)}</div></div>
            <div class="meta">默认端口 ${a.defaultPort} · ${a.runtimeKind}</div>
            <div class="tools">工具 ${a.allTools.length} 个 · Skill 目录 <code>${esc(a.skillDir)}</code></div>
          </div>`).join("")}
        </div>
      </div>
      <div class="flex gap-3 mt-6" style="justify-content:flex-end">
        <button class="btn secondary" id="s1-cancel">取消</button>
        <button class="btn primary" id="s1-next" ${cur?'':'disabled'}>下一步 ${IC.right}</button>
      </div>
    </div>
    <aside class="wizard-side">
      <h4>本步会做什么</h4>
      <ul>
        <li>确认要接入的数据库类型</li>
        <li>如未部署 toolkit / tap，向导下一步会自动执行 <code>deploy</code></li>
        <li>Skill 目录同步到 QoderWork / Claude / Agents</li>
      </ul>
      <h4 style="margin-top:16px">小贴士</h4>
      <ul>
        <li>Oracle 走 JDBC + JAVA_JAR 运行时</li>
        <li>MySQL 走 Node 运行时 + 环境变量注入</li>
      </ul>
    </aside>
  </div>`;
}

function setupStep2(){
  const w = S.wizard;
  const a = adapter(w.dbId) || {defaultPort:0, allTools:[], requiredTools:[]};
  const cur = listEnvs().find(x => x.dbId === w.dbId);
  const env = w.env || (cur && cur.env) || "";
  const info = cur && cur.info;
  const tools = w.tools.length ? w.tools : (info && info.tools) || a.requiredTools.slice();
  return `<div class="wizard-split">
    <div>
      <div class="card">
        <div class="card-header">
          <h3>${dbIcon(a.id)} 配置 ${esc(a.displayName)} 实例</h3>
          <button class="btn ghost sm" id="s2-switch-db">切换数据源</button>
        </div>
        <div class="row">
          <div class="field">
            <div class="field-label"><span>实例标识 <span class="req">*</span></span><span class="field-hint">小写字母 / 数字 / 连字符</span></div>
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
              ${aliasPresetFor(env).map(t => `<button class="chip-add" data-alias-preset="${esc(t)}">+ ${esc(t)}</button>`).join("") || '<span class="text-mut" style="font-size:11px">输入实例标识后自动推荐</span>'}
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
        <div class="field"><div class="field-label">JDBC URL (可选，Oracle 优先)</div>
          <input class="input mono" id="s2-jdbc" value="${esc(w.jdbcUrl || (info && info.url) || "")}"></div>
        <div class="card tight" style="background:var(--bg-inset); margin-top:var(--s4)">
          <div class="field-label" style="margin-bottom:8px">启用工具（${tools.length} / ${a.allTools.length}）</div>
          ${a.allTools.map(t => `<label class="tool ${a.requiredTools.indexOf(t)>=0?'locked':''}">
            <input type="checkbox" data-tool="${esc(t)}" ${tools.indexOf(t)>=0?'checked':''} ${a.requiredTools.indexOf(t)>=0?'disabled':''}>
            <span class="mono">${esc(t)}</span>
            ${a.requiredTools.indexOf(t)>=0?'<span class="tag req">必选</span>':(t.includes("write")||["insert","update","delete"].includes(t)?'<span class="tag danger">写权限</span>':'')}
          </label>`).join("")}
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
  return `<div class="wizard-split">
    <div>
      <div class="card">
        <div class="card-header"><h3>${dbIcon(w.dbId)} 自检与注册</h3></div>
        <p class="card-desc">对实例 <code>${esc(w.dbId)}/${esc(env||"")}</code> 执行连通自检，通过后可一键注册到 mcp.json。</p>
        <div id="test-block">${tr ? renderTestResult(tr) : '<p class="text-mut">点击下方「一键自检」开始。</p>'}</div>
        <div class="flex gap-2 mt-6">
          <button class="btn primary" id="s3-test">${IC.play} 一键自检</button>
          <button class="btn secondary" id="s3-register" ${tr && tr.ok ? '' : 'disabled'}>${IC.link} 注册到 mcp.json</button>
          <button class="btn ghost" id="s3-skip">稍后再注册</button>
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
        <li>通过后注册到 <code>${esc(norm(S.detect && S.detect.mcpJsonPath || ""))}</code></li>
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
  return `<div class="card tight" style="border-left:3px solid var(--danger); background:var(--danger-soft)"><div class="flex items-center gap-2" style="color:var(--danger); font-weight:500">${IC.alert} 自检失败</div><div class="mono" style="font-size:11.5px; margin-top:4px">${esc(r.detail || r.error || "未知错误")}</div></div>`;
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
    if (!isRegistered(x.dbId, x.env)) unreg++;
  });
  const rows = [];
  envs.forEach(x => {
    const t = x.info.lastTest;
    if (t && !t.ok) rows.push({ sev:"err", x, msg:"自检失败", hint:"重跑自检或改连接参数", action:"retest" });
    else if (!isRegistered(x.dbId, x.env)) rows.push({ sev:"warn", x, msg:"未注册到 mcp.json", hint:"点注册即可", action:"register" });
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
          <td>${dbIcon(r.x.dbId)} ${esc(r.x.dbId)}/${esc(r.x.env)}</td>
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
    <div class="page-title-row"><div><div class="page-title">实例</div><div class="page-sub">${listEnvs().length} 个实例 · 每个实例对应一个独立的 MCP 连接器</div></div>
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
  const reg = isRegistered(x.dbId, x.env);
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
  const reg = isRegistered(x.dbId, x.env);
  const rwTools = ["write-query","insert","update","delete"];
  const isRw = (x.info.tools || []).some(tt => rwTools.includes(tt));
  const alias = (x.info.aliases||[]).join(" · ");
  return `<div class="inst-card" data-open="${esc(x.dbId)}/${esc(x.env)}">
    <button class="icon-btn kebab" data-kebab="${esc(x.dbId)}/${esc(x.env)}">${IC.kebab}</button>
    <div class="head">${dbIcon(x.dbId)}<div class="env">${esc(x.env)}</div>${alias?`<div class="alias">${esc(alias)}</div>`:""}</div>
    <div class="meta">${esc(x.info.host||"—")}:${x.info.port||a.defaultPort||""}${x.info.user?" · "+esc(x.info.user):""}</div>
    <div class="badges">
      <span class="badge ${isRw?'warn':'ok'}"><span class="dot"></span>${isRw?'读写':'只读'}</span>
      <span class="badge ${reg?'ok':'neutral'}">${reg?'已注册':'未注册'}</span>
      ${t?`<span class="badge ${t.ok?'info':'no'}">自检 ${t.ok?'✓':'✗'} · ${esc(relTime(t.ts))}</span>`:""}
    </div>
    <div class="quick">
      <button class="btn ghost sm" data-retest="${esc(x.dbId)}/${esc(x.env)}">${IC.play} 自检</button>
      <button class="btn ghost sm" data-config="${esc(x.dbId)}/${esc(x.env)}">${IC.edit} 配置</button>
      <button class="btn ghost sm" data-mcp="${esc(x.dbId)}/${esc(x.env)}">${IC.link} MCP 注册</button>
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
        const deployed = targets.some(t => {
          const has = (S.detect && S.detect.registeredServers || []).some(n => n.startsWith(a.serverPrefix));
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
      </dl>
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
      <div class="card-header"><h3>选择实例</h3><div class="actions"><select class="select" id="diag-env-sel" style="width:auto"><option value="">全部</option>${listEnvs().map(x => `<option value="${esc(x.dbId)}/${esc(x.env)}">${esc(x.dbId)}/${esc(x.env)}</option>`).join("")}</select></div></div>
      <div id="diag-log" class="mono" style="font-size:12px; max-height:60vh; overflow:auto; padding:8px; background:var(--bg-inset); border-radius:var(--r-sm)"><p class="text-mut">加载中…</p></div>
    </div>
    <div class="card">
      <div class="card-header"><h3>常用动作</h3></div>
      <div class="flex gap-2 flex-wrap">
        <button class="btn secondary sm" id="diag-retest-all">${IC.play} 一键自检全部</button>
        <button class="btn secondary sm" id="diag-open-root">${IC.edit} 打开工作目录</button>
        <button class="btn ghost sm" id="diag-reveal-root">${IC.db} 定位工作目录</button>
        <button class="btn secondary sm" id="diag-open-mcp">${IC.edit} 打开 mcp.json</button>
        <button class="btn ghost sm" id="diag-reveal-mcp">${IC.db} 定位 mcp.json</button>
        <button class="btn ghost sm" id="diag-copy-mcp-path">${IC.copy} 复制 mcp.json 路径</button>
      </div>
    </div>
  </div>`;
}
function startLogPoll(){
  const sel = $("#diag-env-sel");
  const box = $("#diag-log");
  if (!box) return;
  const load = async () => {
    const val = sel && sel.value;
    if (!val){ box.innerHTML = '<p class="text-mut">选择一个实例查看调用日志。</p>'; return; }
    const [dbId, env] = val.split("/");
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
  if (sel) sel.onchange = load;
  load();
  if (S.logPollTimer) clearInterval(S.logPollTimer);
  S.logPollTimer = setInterval(load, 6000);
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
function openSlideOver(dbId, env){
  const x = listEnvs().find(e => e.dbId === dbId && e.env === env);
  if (!x){ toast("实例不存在：" + dbId + "/" + env, "err"); return; }
  S.slideOver = { dbId, env, tab: S.slideOver.dbId===dbId && S.slideOver.env===env ? S.slideOver.tab : "overview", open:true };
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
  const reg = isRegistered(x.dbId, x.env);
  const rwTools = ["write-query","insert","update","delete"];
  const isRw = (x.info.tools || []).some(tt => rwTools.includes(tt));
  const alias = (x.info.aliases||[]);
  return `<div class="so-header">
    <div class="so-title">${dbIcon(x.dbId)}<span>${esc(x.env)}</span><span class="text-mut" style="font-weight:400">· ${esc(a.displayName||x.dbId)}</span>${alias.map(s=>`<span class="chip">${esc(s)}</span>`).join("")}</div>
    <button class="so-close" id="so-close">${IC.x}</button>
  </div>
  <div class="so-tabs">${["overview","tools","logs","advanced"].map(k => `<div class="so-tab ${S.slideOver.tab===k?'on':''}" data-tab="${k}">${({overview:"概览",tools:"权限",logs:"日志",advanced:"高级"})[k]}</div>`).join("")}</div>
  <div class="so-body">${renderSlideOverTab(x)}</div>
  <div class="so-footer">
    <button class="btn danger-ghost sm" id="so-delete">${IC.trash} 删除实例</button>
    <button class="btn secondary sm" id="so-close-2">关闭</button>
  </div>`;
}
function renderSlideOverTab(x){
  const a = adapter(x.dbId) || {};
  const t = x.info.lastTest;
  const reg = isRegistered(x.dbId, x.env);
  const rwTools = ["write-query","insert","update","delete"];
  const isRw = (x.info.tools || []).some(tt => rwTools.includes(tt));
  if (S.slideOver.tab === "overview"){
    return `
    <div class="flex gap-2" style="margin-bottom:16px">
      <span class="badge ${isRw?'warn':'ok'}"><span class="dot"></span>${isRw?'读写':'只读'}</span>
      <span class="badge ${reg?'ok':'neutral'}">${reg?'已注册':'未注册'}</span>
      ${t?`<span class="badge ${t.ok?'info':'no'}">自检 ${t.ok?'通过':'异常'} · ${esc(relTime(t.ts))}</span>`:""}
    </div>
    <dl class="kv">
      <dt>Host</dt><dd>${esc(x.info.host||"—")}</dd><dt></dt>
      <dt>Port</dt><dd>${esc(x.info.port||a.defaultPort)}</dd><dt></dt>
      <dt>${x.dbId==='oracle'?'Service/SID':'Database'}</dt><dd>${esc(x.info.database||"—")}</dd><dt></dt>
      <dt>User</dt><dd>${esc(x.info.user||"—")}</dd><dt></dt>
      <dt>密码</dt><dd>${x.info.password?'<code>'+ '•'.repeat(Math.min(12, x.info.password.length)) +'</code> <button class="btn ghost sm" id="so-pw-reveal">显示</button>':'<span class="text-mut">未设置</span>'}</dd><dt></dt>
      ${x.info.url?`<dt>JDBC URL</dt><dd>${esc(x.info.url)}</dd><dt></dt>`:""}
      <dt>连接器名</dt><dd><code>${esc((a.serverPrefix||x.dbId+"-") + x.env)}</code></dd><dt></dt>
    </dl>
    ${t && !t.ok ? `<div class="card tight" style="border-left:3px solid var(--danger); background:var(--danger-soft); margin-top:16px">
      <div class="flex items-center gap-2" style="color:var(--danger); font-weight:500">${IC.alert} 自检失败</div>
      <div class="mono" style="font-size:11.5px; margin-top:4px">${esc(t.detail||"")}</div>
    </div>`:""}
    <div class="card tight" style="background:var(--bg-inset); margin-top:16px">
      <div class="field-label" style="margin-bottom:8px">工具集 · ${(x.info.tools||[]).length} 个</div>
      <div class="flex gap-2 flex-wrap">${(x.info.tools||[]).map(tt=>`<span class="chip mono">${esc(tt)}</span>`).join("")||'<span class="text-mut">未启用</span>'}</div>
    </div>
    <div class="flex gap-2 mt-4">
      <button class="btn primary sm" data-so-retest>${IC.play} 一键自检</button>
      ${!reg?`<button class="btn secondary sm" data-so-register>${IC.link} 注册到 mcp.json</button>`:`<button class="btn secondary sm" data-so-unregister>${IC.x} 从 mcp.json 移除</button>`}
      <button class="btn ghost sm" data-so-guide>${IC.link} MCP 注册</button>
    </div>`;
  }
  if (S.slideOver.tab === "tools"){
    return `<p class="text-mut" style="font-size:12.5px">调整该实例启用的工具集，必选工具无法关闭；写权限工具会二次确认。</p>
    ${a.allTools.map(tt => `<label class="tool ${(a.requiredTools||[]).includes(tt)?'locked':''}">
      <input type="checkbox" data-toggle-tool="${esc(tt)}" ${(x.info.tools||[]).includes(tt)?'checked':''} ${(a.requiredTools||[]).includes(tt)?'disabled':''}>
      <span class="mono">${esc(tt)}</span>
      ${(a.requiredTools||[]).includes(tt)?'<span class="tag req">必选</span>':(rwTools.includes(tt)?'<span class="tag danger">写权限</span>':'')}
    </label>`).join("")}
    <div class="flex gap-2 mt-4"><button class="btn primary sm" data-so-save-tools>保存</button></div>`;
  }
  if (S.slideOver.tab === "logs"){
    return `<div class="mono" id="so-log-box" style="font-size:12px; max-height:60vh; overflow:auto; padding:8px; background:var(--bg-inset); border-radius:var(--r-sm)"><p class="text-mut">加载中…</p></div>`;
  }
  return `<p class="text-mut" style="font-size:12.5px">高级操作</p>
  <div class="flex gap-2 flex-wrap">
    <button class="btn secondary sm" data-so-guide>${IC.link} MCP 注册</button>
    <button class="btn secondary sm" data-so-copy-json>${IC.copy} 复制 mcp.json 条目</button>
    <button class="btn secondary sm" data-so-open-dir>${IC.edit} 打开配置目录</button>
    <button class="btn ghost sm" data-so-reveal-dir>${IC.db} 在文件夹中显示</button>
  </div>
  <div class="card tight mt-6" style="background:var(--bg-inset)">
    <div class="field-label">配置目录</div>
    <div class="mono" style="font-size:11.5px">${esc(norm(S.detect.root))}/${esc(x.dbId)}/instance/${esc(x.env)}/</div>
  </div>`;
}
function bindSlideOver(x){
  $("#so-close").onclick = () => closeSlideOver();
  $("#so-close-2").onclick = () => closeSlideOver();
  $("#so-delete").onclick = () => deleteInstanceConfirm(x);
  $$(".so-tab").forEach(el => el.onclick = () => { S.slideOver.tab = el.dataset.tab; $("#slideover").innerHTML = renderSlideOver(x); bindSlideOver(x); if (S.slideOver.tab === "logs") loadSlideOverLog(x); });
  const reveal = $("#so-pw-reveal");
  if (reveal) reveal.onclick = () => { const dd = reveal.parentElement; dd.innerHTML = '<code>'+esc(x.info.password)+'</code>'; };
  const retest = $("#slideover [data-so-retest]");
  if (retest) retest.onclick = () => runSelfTest(x.dbId, x.env, r => { toast(r.ok?"自检通过":"自检失败", r.ok?"":"err"); $("#slideover").innerHTML = renderSlideOver(x); bindSlideOver(x); refreshDetect(); });
  const reg = $("#slideover [data-so-register]");
  if (reg) reg.onclick = () => registerEnv(x.dbId, x.env);
  const unreg = $("#slideover [data-so-unregister]");
  if (unreg) unreg.onclick = () => unregisterEnv(x.dbId, x.env);
  const guide = $("#slideover [data-so-guide]");
  if (guide) guide.onclick = () => showMcpRegister(x.dbId, x.env);
  const saveTools = $("#slideover [data-so-save-tools]");
  if (saveTools) saveTools.onclick = () => saveToolsFor(x);
  const openDir = $("#slideover [data-so-open-dir]");
  if (openDir) openDir.onclick = () => openPath("env-config-dir", "open", { dbId: x.dbId, env: x.env });
  const revealDir = $("#slideover [data-so-reveal-dir]");
  if (revealDir) revealDir.onclick = () => openPath("env-config-dir", "reveal", { dbId: x.dbId, env: x.env });
  const copyJson = $("#slideover [data-so-copy-json]");
  if (copyJson) copyJson.onclick = () => copyMcpEntry(x);
}
async function loadSlideOverLog(x){
  const box = $("#so-log-box"); if (!box) return;
  try {
    const r = await api("/api/env/log?dbId="+encodeURIComponent(x.dbId)+"&env="+encodeURIComponent(x.env)+"&limit=200");
    const lines = (r.lines || []).map(l => {
      try { const o = JSON.parse(l); const ok = o.ok !== false;
        return `<div class="log-row"><span>${esc(o.ts||"")}</span><span>${esc(o.tool||"")}</span><span class="${ok?'st-ok':'st-err'}">${ok?'OK':'ERR'}</span><span class="dur">${o.dur||''}</span><span>${esc((o.sql||o.detail||"").slice(0,120))}</span></div>`;
      } catch { return '<div class="log-row">'+esc(l)+'</div>'; }
    });
    box.innerHTML = (lines.join("") || '<p class="text-mut">暂无日志。</p>');
  } catch (e){ box.innerHTML = '<p style="color:var(--danger)">'+esc(e.message)+'</p>'; }
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
    setTimeout(() => openSlideOver(opts.restoreArgs[0], opts.restoreArgs[1]), 220);
  }
}

async function deleteInstanceConfirm(x){
  openModal(`<div class="modal-header"><h3>删除实例 · ${esc(x.dbId)}/${esc(x.env)}</h3><button class="so-close" id="m-x">${IC.x}</button></div>
  <div class="modal-body">
    <p class="text-mut">将执行：</p>
    <ul>
      <li>从 <code>${esc(norm(S.detect.mcpJsonPath))}</code> 移除 <code>${esc((adapter(x.dbId)||{}).serverPrefix + x.env)}</code></li>
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
      await api("/api/env/delete", { dbId:x.dbId, env:x.env });
      toast("已删除 " + x.dbId + "/" + x.env);
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
  const envs = (p.envs||[]).map(x => `<li>${esc(x.dbId)}/${esc(x.env)} ${x.registered?'<span class="badge ok">已注册</span>':'<span class="badge neutral">未注册</span>'}</li>`).join("") || '<li class="text-mut">无</li>';
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

function getMcpPlatforms(dbId, env){
  const d = S.detect || {};
  const a = adapter(dbId) || {};
  const serverName = (a.serverPrefix || dbId + "-") + env;
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

function buildMcpEntryPreview(dbId, env){
  const a = adapter(dbId) || {};
  const x = listEnvs().find(v => v.dbId === dbId && v.env === env);
  const info = (x && x.info) || {};
  const root = norm(S.detect.root || "");
  const serverName = (a.serverPrefix || dbId + "-") + env;
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

async function showMcpRegister(dbId, env){
  const reopenSlideOver = S.slideOver.open;
  if (reopenSlideOver) closeSlideOver(true);
  const a = adapter(dbId) || {};
  const serverName = (a.serverPrefix || dbId + "-") + env;
  await loadMcpTargets();
  const manualItem = { id:"__manual", type:"manual", name:"通用 · 手动接入", cls:"generic", letter:"{}", tier:"manual",
    note:"把 server 定义贴到你的客户端配置里即可，适配任意 MCP 兼容客户端" };
  let allItems = [];
  function rebuildAllItems(){
    const platforms = getMcpPlatforms(dbId, env);
    const targetCards = (S.mcpTargets || []).map(t => targetToCard(t, serverName));
    allItems = [...platforms, ...targetCards, manualItem];
  }
  rebuildAllItems();
  const firstUnreg = allItems.find(p => p.type === "primary" && !(p.registeredList && p.registeredList.indexOf(serverName) >= 0));
  const st = { selected: (firstUnreg || allItems[0]).id, cliCommands: {} };
  const entryPreview = buildMcpEntryPreview(dbId, env);
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
  <div class="modal-footer"><button class="btn secondary" id="m-close">关闭</button></div>`, { wide:true, restoreSlideOver:reopenSlideOver, restoreArgs:[dbId, env] });

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
      api("/api/mcp/commands", { target: p.id, dbId, env }).then(r => {
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
          try { await registerEnv(dbId, env); await refreshDetect(); renderNav(); renderDetail(); }
          catch (e){ toast(e.message, "err"); }
          finally { el.classList.remove("loading"); }
        } else if (act === "unregister"){
          toast("待落地：主平台移除需走 /api/env/delete，暂未提供 unregister-only 端点", "warn");
        } else if (act === "target-register" || act === "target-re-register"){
          el.classList.add("loading");
          try {
            const r = await api("/api/mcp/register", { target: p.id, dbId, env });
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
            const r = await api("/api/mcp/unregister", { target: p.id, dbId, env });
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
    { g:"跳转", label:"Skill 与运行时", hint:"g r", run:() => navigate("#/runtime") },
    { g:"跳转", label:"排障", hint:"g d", run:() => navigate("#/diagnostics") },
    { g:"跳转", label:"系统", hint:"g s", run:() => navigate("#/system") }
  ];
  const instCmds = [];
  listEnvs().forEach(x => {
    instCmds.push({ g:"实例", label:"实例 · " + x.dbId + "/" + x.env, run:() => navigate("#/instances/"+x.dbId+"/"+x.env) });
    instCmds.push({ g:"实例", label:"MCP 注册 · " + x.dbId + "/" + x.env, run:() => showMcpRegister(x.dbId, x.env) });
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
    if (e.target.closest("button")) return;
    const [dbId, env] = el.dataset.open.split("/");
    navigate("#/instances/" + dbId + "/" + env);
  });
  $$("[data-retest]").forEach(el => el.onclick = async (e) => {
    e.stopPropagation();
    const [dbId, env] = el.dataset.retest.split("/");
    await runSelfTest(dbId, env, r => { toast(r.ok ? "自检通过" : "自检失败", r.ok ? "" : "err"); refreshDetect().then(renderMain); });
  });
  $$("[data-reg]").forEach(el => el.onclick = async (e) => {
    e.stopPropagation();
    const [dbId, env] = el.dataset.reg.split("/");
    await registerEnv(dbId, env);
  });
  $$("[data-mcp]").forEach(el => el.onclick = (e) => {
    e.stopPropagation();
    const [dbId, env] = el.dataset.mcp.split("/");
    showMcpRegister(dbId, env);
  });
  $$("[data-config]").forEach(el => el.onclick = async (e) => {
    e.stopPropagation();
    const [dbId, env] = el.dataset.config.split("/");
    const x = listEnvs().find(v => v.dbId === dbId && v.env === env);
    const info = x ? x.info : {};
    S.wizard = {
      step:2, dbId, env,
      alias:(info.aliases||[]).join(" · "),
      aliasTouched: !!(info.aliases && info.aliases.length),
      host: info.host || "", port: info.port || "", user: info.user || "",
      password: info.password || "", pwdLocked: !!info.password,
      serviceOrDatabase: info.database || "", jdbcUrl: info.url || "",
      paste:"", tools: (info.tools||[]).slice(), testResult:null
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
  bind("#diag-open-mcp", () => openPath("mcp-json", "open"));
  bind("#diag-reveal-mcp", () => openPath("mcp-json", "reveal"));
  bind("#diag-copy-mcp-path", () => { navigator.clipboard.writeText(norm(S.detect.mcpJsonPath)); toast("已复制", "info"); });
  bind("#rt-sync", syncMappings);
  bind("#rt-add-target", addSkillTargetPrompt);
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
  $$("[data-pick-db]").forEach(el => el.onclick = () => { S.wizard.dbId = el.dataset.pickDb; $$("[data-pick-db]").forEach(x => x.classList.toggle("on", x === el)); const nx = $("#s1-next"); if (nx) nx.disabled = false; });
  bind("#s1-next", async () => {
    if (!S.wizard.dbId){ toast("请选择一种数据源", "warn"); return; }
    try {
      await api("/api/deploy", { dbId: S.wizard.dbId });
      await refreshDetect();
      navigate("#/setup/2");
    } catch (e){ toast(e.message, "err"); }
  });
  bind("#s2-switch-db", () => navigate("#/setup/1"));
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
      if (f.url || f.jdbcUrl) S.wizard.jdbcUrl = f.url || f.jdbcUrl;
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
      jdbcUrl: $("#s2-jdbc").value.trim(),
      paste: $("#s2-paste").value,
      aliases: aliases
    };
    const tools = $$("[data-tool]:checked").map(el => el.dataset.tool);
    body.tools = tools;
    try {
      await api("/api/env/config", body);
      S.wizard.env = body.env;
      S.wizard.password = pwdVal;
      S.wizard.tools = tools;
      await refreshDetect();
      navigate("#/setup/3");
    } catch (e){ toast(e.message, "err"); }
  });
  bind("#s3-back", () => navigate("#/setup/2"));
  bind("#s3-test", async () => {
    const env = S.wizard.env;
    S.wizard.testResult = { running:true };
    const box = $("#test-block"); if (box) box.innerHTML = renderTestResult(S.wizard.testResult);
    try {
      await api("/api/env/test", { dbId:S.wizard.dbId, env });
      const r = await pollTest(env);
      S.wizard.testResult = r;
      const b2 = $("#test-block"); if (b2) b2.innerHTML = renderTestResult(r);
      const btn = $("#s3-register"); if (btn && r.ok) btn.disabled = false;
    } catch (e){
      S.wizard.testResult = { ok:false, detail:e.message };
      const b2 = $("#test-block"); if (b2) b2.innerHTML = renderTestResult(S.wizard.testResult);
    }
  });
  bind("#s3-register", () => registerEnv(S.wizard.dbId, S.wizard.env));
  bind("#s3-skip", () => navigate("#/setup/3-done"));
  bind("#s3-done", async () => {
    S.prefs.setupCompleted = true;
    await savePrefs({ setupCompleted:true, lastEnv: S.wizard.dbId + "/" + S.wizard.env });
    toast("首次接入完成");
    navigate("#/instances");
  });
}

async function pollTest(env, max=180){
  for (let i=0; i<max; i++){
    await new Promise(r => setTimeout(r, 500));
    try {
      const r = await api("/api/env/test/poll?env=" + encodeURIComponent(env));
      if (r && !r.running) return r;
    } catch (e){ /* retry */ }
  }
  return { ok:false, detail:"超时" };
}

async function runSelfTest(dbId, env, cb){
  try {
    toast("自检开始…", "info");
    await api("/api/env/test", { dbId, env });
    const r = await pollTest(env);
    cb && cb(r);
    await refreshDetect();
    return r;
  } catch (e){ toast(e.message, "err"); cb && cb({ ok:false, detail:e.message }); }
}
async function registerEnv(dbId, env){
  try {
    const r = await api("/api/env/register", { dbId, env });
    toast("已注册 " + r.serverName);
    await refreshDetect();
    renderMain();
  } catch (e){ toast(e.message, "err"); }
}
async function unregisterEnv(dbId, env){
  try {
    await api("/api/env/delete", { dbId, env });
    toast("已移除并回收实例 " + dbId + "/" + env);
    await refreshDetect();
    closeSlideOver();
    renderMain();
  } catch (e){ toast(e.message, "err"); }
}
async function retestAll(){
  const envs = listEnvs();
  if (!envs.length){ toast("没有可自检的实例", "warn"); return; }
  toast("排队自检 " + envs.length + " 个实例…", "info");
  for (const x of envs){
    try { await api("/api/env/test", { dbId:x.dbId, env:x.env }); await pollTest(x.env, 60); } catch {}
  }
  await refreshDetect();
  renderMain();
  toast("全部自检完成");
}
async function syncMappings(){
  try { const r = await api("/api/skill/sync", {}); toast("已同步 " + (r.updated||[]).length + " 份映射"); }
  catch (e){ toast(e.message, "err"); }
}
function addSkillTargetPrompt(){
  openModal(`<div class="modal-header"><h3>新增 Skill 目标目录</h3><button class="so-close" id="m-x">${IC.x}</button></div>
  <div class="modal-body"><div class="field"><div class="field-label"><span>路径</span><span class="field-hint">例如 ~/.qoderwork/skills</span></div>
    <input class="input mono" id="m-path" placeholder="C:\\Users\\you\\.qoderwork\\skills">
  </div>
  <div class="field-label" style="margin-top:16px">常用推荐</div>
  <div class="flex gap-2 flex-wrap">
    <button class="btn ghost sm" data-qpath="~/.qoderwork/skills">QoderWork</button>
    <button class="btn ghost sm" data-qpath="~/.agents/skills">Agents</button>
    <button class="btn ghost sm" data-qpath="~/.claude/skills">Claude</button>
  </div></div>
  <div class="modal-footer"><button class="btn secondary" id="m-cancel">取消</button><button class="btn primary" id="m-ok">添加</button></div>`);
  $("#m-x").onclick = closeModal; $("#m-cancel").onclick = closeModal;
  $$("[data-qpath]").forEach(b => b.onclick = () => $("#m-path").value = b.dataset.qpath);
  $("#m-ok").onclick = async () => {
    const t = $("#m-path").value.trim();
    if (!t){ toast("请输入路径", "warn"); return; }
    try { await api("/api/skill/targets", {action:"add", target:t}); toast("已添加"); closeModal(); await refreshDetect(); renderMain(); }
    catch (e){ toast(e.message, "err"); }
  };
}
async function saveToolsFor(x){
  const body = {
    dbId:x.dbId, env:x.env,
    host:x.info.host, port:x.info.port, user:x.info.user, password:x.info.password,
    service:x.info.database, database:x.info.database, url:x.info.url,
    aliases:x.info.aliases,
    tools: $$("[data-toggle-tool]:checked").map(el => el.dataset.toggleTool)
  };
  try {
    await api("/api/env/config", body);
    toast("已保存");
    await refreshDetect();
    $("#slideover").innerHTML = renderSlideOver(listEnvs().find(e => e.dbId===x.dbId && e.env===x.env) || x);
    bindSlideOver(listEnvs().find(e => e.dbId===x.dbId && e.env===x.env) || x);
  } catch (e){ toast(e.message, "err"); }
}
function copyMcpEntry(x){
  const a = adapter(x.dbId) || {};
  const name = (a.serverPrefix||x.dbId+"-") + x.env;
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

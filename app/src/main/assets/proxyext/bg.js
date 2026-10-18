const port = browser.runtime.connectNative("floatbrowser");
const cfg = {}, byTab = {}, byStore = {}, errs = {}, tools = {}, pages = {};
let regs = [];
let toolsOn = false;
const BLOCK = { type: "http", host: "127.0.0.1", port: 9 };

function who(d) {
  if (d.tabId >= 0 && byTab[d.tabId]) return byTab[d.tabId];
  if (d.cookieStoreId && byStore[d.cookieStoreId]) return byStore[d.cookieStoreId];
  return null;
}
function T(w) {
  return tools[w] || (tools[w] = { pick: false, record: false, steps: [], match: null, monitor: null, autostart: false });
}
function pagesOf(w) {
  const out = [];
  for (const id in byTab) if (byTab[id] === w && pages[id]) out.push(pages[id]);
  return out;
}
function toPages(w, msg) { pagesOf(w).forEach(p => { try { p.postMessage(msg); } catch (e) {} }); }
function stateOf(w) {
  const t = T(w);
  return {
    cmd: "state", pick: t.pick, record: t.record, first: t.steps.length === 0,
    monitor: t.monitor ? { sel: t.monitor.sel, every: t.monitor.every } : null,
    autostart: t.autostart
  };
}

async function setScripts(list) {
  for (const r of regs) { try { await r.unregister(); } catch (e) {} }
  regs = [];
  for (const s of list) {
    try {
      regs.push(await browser.contentScripts.register({
        matches: s.matches, js: [{ code: s.code }], runAt: "document_idle"
      }));
    } catch (e) {}
  }
}

// ---------- mensagens vindas do app ----------
port.onMessage.addListener(async m => {
  if (m.type === "cfg") { cfg[m.w] = m; errs[m.w] = 0; }
  else if (m.type === "forget") {
    delete cfg[m.w]; delete tools[m.w];
    for (const k in byTab) if (byTab[k] === m.w) delete byTab[k];
    for (const k in byStore) if (byStore[k] === m.w) delete byStore[k];
  } else if (m.type === "scripts") await setScripts(m.list || []);
  else if (m.type === "tools") toolsOn = !!m.on;
  else if (m.type === "tool") {
    const t = T(m.w);
    if (m.cmd === "pick") t.pick = !!m.on;
    else if (m.cmd === "record") {
      if (m.on) { t.record = true; t.steps = []; t.match = null; }
      else {
        t.record = false;
        port.postMessage({ type: "recorded", w: m.w, steps: t.steps, match: t.match });
        t.steps = [];
      }
    } else if (m.cmd === "table") { toPages(m.w, { cmd: "table" }); return; }
    toPages(m.w, stateOf(m.w));
  } else if (m.type === "monitor") {
    const t = T(m.w);
    t.monitor = m.on ? { sel: m.sel, every: Math.max(30, m.every || 60), text: m.text || "", last: undefined, hit: false } : null;
    toPages(m.w, stateOf(m.w));
    monState(m.w);
  } else if (m.type === "autostart") { T(m.w).autostart = true; }
  else if (m.type === "ui") toPages(m.w, { cmd: "ui", tool: m.tool });   // teclas da janela acionam as ferramentas da página
});

// ---------- páginas (porta "page" aberta por tools.js: recebe comandos das teclas da janela) ----------
browser.runtime.onConnect.addListener(p => {
  if (p.name !== "page" || !p.sender || !p.sender.tab) return;
  const tabId = p.sender.tab.id;
  pages[tabId] = p;
  p.onDisconnect.addListener(() => { if (pages[tabId] === p) delete pages[tabId]; });
  const w0 = who({ tabId: tabId, cookieStoreId: p.sender.tab.cookieStoreId });
  if (w0) {
    const s = stateOf(w0);
    try { p.postMessage(s); } catch (e) {}
    T(w0).autostart = false;
  }
  p.onMessage.addListener(m => onPage(who({ tabId: tabId, cookieStoreId: p.sender.tab.cookieStoreId }), m));
});

// mensagens dos scripts de usuário (automação)
browser.runtime.onMessage.addListener((m, sender) => {
  if (!sender.tab) return;
  const w = who({ tabId: sender.tab.id, cookieStoreId: sender.tab.cookieStoreId });
  // monitor de página (👁): a página pergunta o que monitorar, liga ou desliga
  if (m && m.cmd === "monGet") {
    const mon = w && T(w).monitor;
    return Promise.resolve(mon ? { sel: mon.sel, every: mon.every } : null);
  }
  if (m && m.cmd === "monSet" && w) {
    T(w).monitor = { sel: String(m.sel || ""), every: Math.max(30, m.every || 60), text: String(m.text || ""), last: undefined, hit: false };
    monState(w);
    return Promise.resolve(true);
  }
  if (m && m.cmd === "monOff" && w) { T(w).monitor = null; monState(w); return Promise.resolve(true); }
  onPage(w, m);
});

function onPage(w, m) {
  if (!w || !m) return;
  const t = T(w);
  switch (m.type) {
    case "picked":
      t.pick = false;
      port.postMessage({ type: "picked", w: w, sel: m.sel, text: m.text });
      break;
    case "step":
      if (!t.record) break;
      if (m.step.ir && !t.match) { try { t.match = new URL(m.step.ir).origin + "/*"; } catch (e) {} }
      t.steps.push(m.step);
      break;
    case "csv": port.postMessage({ type: "csv", w: w, name: m.name, rows: m.rows }); break;
    case "notify": port.postMessage({ type: "notify", w: w, msg: m.msg }); break;
    case "info": port.postMessage({ type: "info", w: w, msg: m.msg }); break;
    case "mon": monitorCheck(w, t, m.value); break;
  }
}

function monState(w) {
  const mon = T(w).monitor;
  port.postMessage({ type: "monstate", w: w, on: !!mon, sel: mon ? mon.sel : "", every: mon ? mon.every : 0, text: mon ? mon.text : "" });
}

function monitorCheck(w, t, value) {
  const mon = t.monitor;
  if (!mon) return;
  if (value === null) {
    if (mon.last === undefined) port.postMessage({ type: "info", w: w, msg: "Seletor não encontrado nesta página" });
    return;
  }
  if (mon.last === undefined) {
    mon.last = value;
    if (mon.text) mon.hit = value.toLowerCase().includes(mon.text.toLowerCase());
    port.postMessage({ type: "info", w: w, msg: "Monitorando. Valor atual: " + value });
    return;
  }
  if (mon.text) {
    const has = value.toLowerCase().includes(mon.text.toLowerCase());
    if (has && !mon.hit) { mon.hit = true; port.postMessage({ type: "monitor", w: w, sel: mon.sel, old: mon.last, new: value }); }
    if (!has) mon.hit = false;
    mon.last = value;
  } else if (value !== mon.last) {
    port.postMessage({ type: "monitor", w: w, sel: mon.sel, old: mon.last, new: value });
    mon.last = value;
  }
}

// ---------- handshake: descobre qual aba é cada janela ----------
browser.webRequest.onBeforeRequest.addListener(d => {
  const w = new URL(d.url).searchParams.get("w");
  let ok = false;
  if (d.tabId >= 0) { byTab[d.tabId] = w; ok = true; }
  if (d.cookieStoreId && d.cookieStoreId !== "firefox-default") { byStore[d.cookieStoreId] = w; ok = true; }
  port.postMessage({ type: "mapped", w: w, ok: ok });
  return { cancel: true };
}, { urls: ["*://floatbrowser.invalid/*"] }, ["blocking"]);

// ---------- proxy por janela ----------
browser.proxy.onRequest.addListener(d => {
  const w = who(d);
  if (!w) return { type: "direct" };
  const c = cfg[w];
  if (!c) return BLOCK;
  if (c.direct) return { type: "direct" };
  if (!c.proxy) return BLOCK;
  const p = c.proxy;
  if (p.type.startsWith("socks"))
    return { type: "socks", host: p.host, port: p.port, proxyDNS: true, failoverTimeout: 5 };
  return { type: "http", host: p.host, port: p.port, failoverTimeout: 5 };
}, { urls: ["<all_urls>"] });

browser.webRequest.onAuthRequired.addListener(d => {
  if (!d.isProxy) return {};
  const w = who(d), p = w && cfg[w] && cfg[w].proxy;
  if (p && p.user) return { authCredentials: { username: p.user, password: p.pass || "" } };
  return {};
}, { urls: ["<all_urls>"] }, ["blocking"]);

browser.webRequest.onErrorOccurred.addListener(d => {
  const w = who(d), c = w && cfg[w];
  if (!c || c.direct || !c.proxy) return;
  if (/PROXY|NET_TIMEOUT|CONNECTION_REFUSED|NET_RESET|NET_INTERRUPT/.test(d.error)) {
    errs[w] = (errs[w] || 0) + 1;
    const limit = (d.type === "main_frame" && /PROXY/.test(d.error)) ? 1 : 3;
    if (errs[w] >= limit) { errs[w] = 0; port.postMessage({ type: "fail", w: w }); }
  }
}, { urls: ["<all_urls>"] });

browser.webRequest.onCompleted.addListener(d => {
  const w = who(d); if (w) errs[w] = 0;
}, { urls: ["<all_urls>"] });

// Ferramentas da página (🛠) falam com o app por aqui
browser.runtime.onMessage.addListener(m => {
  if (!m || !m.cmd) return;
  if (m.cmd === "toolsOn") return Promise.resolve(toolsOn);
  if (m.cmd === "csv" || m.cmd === "savescript" || m.cmd === "copy") {
    port.postMessage({ type: m.cmd, name: m.name || "", text: m.text || "" });
    return Promise.resolve(true);
  }
});

const port = browser.runtime.connectNative("browser");
const cfg = {};      // cookieStoreId -> atribuição enviada pelo app
const waiting = {};
const fails = {};
let regs = [];

function flush(cs) { (waiting[cs] || []).forEach(r => r()); delete waiting[cs]; }
function ask(cs) {
  return new Promise(res => {
    if (waiting[cs]) { waiting[cs].push(res); return; }
    waiting[cs] = [res];
    port.postMessage({ type: "ctx", cs });
    setTimeout(() => flush(cs), 4000);
  });
}

async function applyScripts(list) {
  for (const r of regs) { try { await r.unregister(); } catch (e) {} }
  regs = [];
  for (const s of list) {
    try {
      regs.push(await browser.contentScripts.register({
        matches: s.matches, js: [{ code: s.code }], runAt: "document_idle"
      }));
    } catch (e) { console.log("script falhou: " + s.name, e); }
  }
}

port.onMessage.addListener(m => {
  if (m.type === "assign") { cfg[m.cs] = m; fails[m.cs] = 0; flush(m.cs); }
  else if (m.type === "scripts") applyScripts(m.list || []);
});
port.postMessage({ type: "hello" });

browser.proxy.onRequest.addListener(async d => {
  const cs = d.cookieStoreId || "default";
  if (!cfg[cs]) await ask(cs);
  const c = cfg[cs];
  if (c && c.mode === "direct") return { type: "direct" };
  if (c && c.mode === "proxy")
    return { type: c.ptype, host: c.host, port: c.port, proxyDNS: c.ptype === "socks" };
  return { type: "http", host: "127.0.0.1", port: 9 }; // kill switch: bloqueia
}, { urls: ["<all_urls>"] });

browser.webRequest.onErrorOccurred.addListener(d => {
  const cs = d.cookieStoreId || "default";
  const c = cfg[cs];
  if (!c || c.mode !== "proxy") return;
  if (/PROXY|TIMEOUT|CONNECTION_REFUSED|CONNECTION_RESET|UNEXPECTED_PROXY/.test(d.error || "")) {
    fails[cs] = (fails[cs] || 0) + 1;
    if (fails[cs] >= 3) { fails[cs] = 0; port.postMessage({ type: "fail", cs }); }
  }
}, { urls: ["<all_urls>"] });

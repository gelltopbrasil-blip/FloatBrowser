const port = browser.runtime.connectNative("floatbrowser");
const cfg = {}, byTab = {}, byStore = {}, errs = {};
let regs = [];
const BLOCK = { type: "http", host: "127.0.0.1", port: 9 };

function who(d) {
  if (d.tabId >= 0 && byTab[d.tabId]) return byTab[d.tabId];
  if (d.cookieStoreId && byStore[d.cookieStoreId]) return byStore[d.cookieStoreId];
  return null;
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

port.onMessage.addListener(async m => {
  if (m.type === "cfg") { cfg[m.w] = m; errs[m.w] = 0; }
  else if (m.type === "forget") {
    delete cfg[m.w];
    for (const k in byTab) if (byTab[k] === m.w) delete byTab[k];
    for (const k in byStore) if (byStore[k] === m.w) delete byStore[k];
  } else if (m.type === "scripts") await setScripts(m.list || []);
});

// Handshake: a janela abre uma URL falsa com ?w=ID para descobrirmos qual aba/contêiner é dela
browser.webRequest.onBeforeRequest.addListener(d => {
  const w = new URL(d.url).searchParams.get("w");
  let ok = false;
  if (d.tabId >= 0) { byTab[d.tabId] = w; ok = true; }
  if (d.cookieStoreId && d.cookieStoreId !== "firefox-default") { byStore[d.cookieStoreId] = w; ok = true; }
  port.postMessage({ type: "mapped", w: w, ok: ok });
  return { cancel: true };
}, { urls: ["*://floatbrowser.invalid/*"] }, ["blocking"]);

browser.proxy.onRequest.addListener(d => {
  const w = who(d);
  if (!w) return { type: "direct" };
  const c = cfg[w];
  if (!c) return BLOCK;
  if (c.direct) return { type: "direct" };
  if (!c.proxy) return BLOCK; // kill switch: sem proxy válido, não navega
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

// Failover: 3 falhas seguidas de proxy -> avisa o app
browser.webRequest.onErrorOccurred.addListener(d => {
  const w = who(d), c = w && cfg[w];
  if (!c || c.direct || !c.proxy) return;
  if (/PROXY|NET_TIMEOUT|CONNECTION_REFUSED|NET_RESET|NET_INTERRUPT/.test(d.error)) {
    errs[w] = (errs[w] || 0) + 1;
    if (errs[w] >= 3) { errs[w] = 0; port.postMessage({ type: "fail", w: w }); }
  }
}, { urls: ["<all_urls>"] });

browser.webRequest.onCompleted.addListener(d => {
  const w = who(d); if (w) errs[w] = 0;
}, { urls: ["<all_urls>"] });

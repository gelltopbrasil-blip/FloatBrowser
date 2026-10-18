// Roda em toda página: seletor (🎯), gravador (⏺), tabela→CSV e monitor (👁)
(function () {
  if (window.top !== window) return;
  if (window.__fbContentLoaded) return;
  window.__fbContentLoaded = true;
  let port;
  try { port = browser.runtime.connect({ name: "page" }); } catch (e) { return; }

  let st = { pick: false, record: false, first: false, monitor: null };
  let badge = null, lastTs = 0, monTimer = null, monStart = null, recordStarted = false;
  const lastInput = {};
  const send = (m) => { try { port.postMessage(m); } catch (e) {} };
  const step = (s) => send({ type: "step", step: s });

  // ---------- aviso na tela ----------
  function refreshBadge() {
    const text = st.pick ? "🎯 Toque num elemento" : st.record ? "● Gravando" : st.monitor ? "👁 Monitorando" : "";
    if (!text) { if (badge) { badge.remove(); badge = null; } return; }
    if (!badge) {
      badge = document.createElement("div");
      badge.style.cssText = "position:fixed;right:6px;top:6px;z-index:2147483647;background:#b71c1c;" +
        "color:#fff;font:12px sans-serif;padding:4px 8px;border-radius:8px;pointer-events:none;opacity:.9";
      document.documentElement.appendChild(badge);
    }
    badge.textContent = text;
  }

  // ---------- seletor CSS ----------
  const uniq = (s) => { try { return document.querySelectorAll(s).length === 1; } catch (e) { return false; } };
  function selector(el) {
    if (!(el instanceof Element)) return "";
    const parts = [];
    let cur = el;
    while (cur && cur.nodeType === 1 && parts.length < 6) {
      let part = cur.tagName.toLowerCase();
      if (cur.id && uniq("#" + CSS.escape(cur.id))) { parts.unshift("#" + CSS.escape(cur.id)); break; }
      const name = cur.getAttribute("name");
      const testid = cur.getAttribute("data-testid") || cur.getAttribute("data-test");
      if (testid) part += '[data-testid="' + testid + '"]';
      else if (name) part += '[name="' + name + '"]';
      else {
        const cls = Array.from(cur.classList).filter((c) => !/\d{3,}|^(css|jsx|sc)-/.test(c)).slice(0, 2);
        if (cls.length) part += "." + cls.map(CSS.escape).join(".");
      }
      const sibs = cur.parentElement ? Array.from(cur.parentElement.children).filter((c) => c.tagName === cur.tagName) : [];
      if (sibs.length > 1 && !testid && !name) part += ":nth-of-type(" + (sibs.indexOf(cur) + 1) + ")";
      parts.unshift(part);
      if (uniq(parts.join(" > "))) break;
      cur = cur.parentElement;
    }
    return parts.join(" > ");
  }

  function flash(el) {
    const o = el.style.outline;
    el.style.outline = "3px solid #f44336";
    setTimeout(() => { el.style.outline = o; }, 1200);
  }

  // clique: modo seletor tem prioridade; senão grava
  document.addEventListener("click", (e) => {
    if (st.pick) {
      e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
      const el = e.target;
      flash(el);
      send({ type: "picked", sel: selector(el), text: (el.innerText || el.value || "").trim().slice(0, 60) });
      st.pick = false; refreshBadge();
      return;
    }
    if (!st.record) return;
    const el = e.target.closest("a,button,[role=button],input[type=submit],input[type=button],input[type=checkbox],input[type=radio],select,summary,label") || e.target;
    if (/^(INPUT|TEXTAREA)$/.test(el.tagName) && !/^(submit|button|checkbox|radio)$/.test(el.type || "")) return;
    gap(); step({ clicar: selector(el) });
  }, true);

  // ---------- gravador ----------
  function gap() {
    const now = Date.now();
    if (lastTs && now - lastTs > 1200) step({ esperar: Math.min(8000, Math.round((now - lastTs) / 500) * 500) });
    lastTs = now;
  }
  function flushInput(el) {
    const v = el.type === "password" ? "SENHA_AQUI" : el.value;
    const s = selector(el);
    if (lastInput[s] === v) return;
    lastInput[s] = v;
    gap(); step({ digitar: [s, v] });
  }
  document.addEventListener("change", (e) => {
    if (!st.record) return;
    const el = e.target;
    if (!/^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName)) return;
    if (/^(checkbox|radio|submit|button)$/.test(el.type || "")) return;
    flushInput(el);
  }, true);
  document.addEventListener("keydown", (e) => {
    if (!st.record || e.key !== "Enter") return;
    const el = e.target;
    if (/^(INPUT|TEXTAREA)$/.test(el.tagName)) { flushInput(el); gap(); step({ enter: selector(el) }); }
  }, true);

  // ---------- tabela -> CSV ----------
  function tableCsv() {
    const tables = Array.from(document.querySelectorAll("table"));
    if (!tables.length) { send({ type: "info", msg: "Nenhuma tabela nesta página" }); return; }
    tables.sort((a, b) => b.rows.length - a.rows.length);
    const rows = Array.from(tables[0].rows).map((r) =>
      Array.from(r.cells).map((c) => (c.innerText || "").replace(/\s+/g, " ").trim()));
    send({ type: "csv", name: "tabela-" + location.hostname + ".csv", rows: rows });
  }

  // ---------- monitor ----------
  function runMonitor() {
    if (!st.monitor) return;
    const el = document.querySelector(st.monitor.sel);
    const value = el ? (el.innerText || el.textContent || "").replace(/\s+/g, " ").trim().slice(0, 200) : null;
    send({ type: "mon", value: value });
    clearTimeout(monTimer);
    monTimer = setTimeout(() => location.reload(), st.monitor.every * 1000);
  }

  // ---------- comandos do app ----------
  port.onMessage.addListener((m) => {
    if (m.cmd === "state") {
      st = { pick: !!m.pick, record: !!m.record, first: !!m.first, monitor: m.monitor || null };
      refreshBadge();
      if (st.record && st.first && !recordStarted) {
        recordStarted = true; lastTs = Date.now();
        step({ ir: location.href });
      }
      clearTimeout(monStart); clearTimeout(monTimer);
      if (st.monitor) monStart = setTimeout(runMonitor, 2000);
      if (m.autostart) {
        try { localStorage.setItem("__fb_auto__", JSON.stringify({ on: true, i: 0 })); } catch (e) {}
      }
    } else if (m.cmd === "table") tableCsv();
  });
})();

function __fbEngine(PASSOS, REPETIR, PAUSA, AUTO) {
  if (window.top !== window) return;
  var sig = 0, raw = JSON.stringify(PASSOS);
  for (var i0 = 0; i0 < raw.length; i0++) sig = (sig * 31 + raw.charCodeAt(i0)) | 0;
  var KEY = "__fb_auto_" + sig + "__";
  var wait = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
  function getState() { try { return JSON.parse(localStorage.getItem(KEY)) || {}; } catch (e) { return {}; } }
  function setState(s) { try { localStorage.setItem(KEY, JSON.stringify(s)); } catch (e) {} }

  // ---------- painel na página ----------
  var box = document.createElement("div");
  box.style.cssText = "position:fixed;left:6px;bottom:6px;z-index:2147483646;background:#263238;" +
    "color:#fff;font:12px sans-serif;padding:6px 8px;border-radius:8px;opacity:.92;max-width:70vw";
  var btn = document.createElement("button");
  btn.style.cssText = "font:12px sans-serif;padding:4px 8px;margin-right:6px";
  var msg = document.createElement("span");
  box.appendChild(btn); box.appendChild(msg);
  document.documentElement.appendChild(box);
  function log(t) { msg.textContent = t; }

  // ---------- utilitários ----------
  function csv(rows) {
    return rows.map(function (r) {
      return r.map(function (c) { return '"' + String(c).replace(/"/g, '""') + '"'; }).join(";");
    }).join("\r\n");
  }
  async function waitFor(sel, timeout) {
    var t0 = Date.now();
    while (Date.now() - t0 < timeout) {
      var el = document.querySelector(sel);
      if (el) return el;
      await wait(200);
    }
    return null;
  }
  function setValue(el, text) {
    var proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, "value");
    if (desc && desc.set) desc.set.call(el, text); else el.value = text;
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }

  // ---------- passos ----------
  async function exec(p) {
    if (p.esperar) return wait(p.esperar);
    if (p.esperarElemento) {
      var e1 = await waitFor(p.esperarElemento, p.timeout || 10000);
      if (!e1 && !p.opcional) throw new Error("não apareceu: " + p.esperarElemento);
      return;
    }
    if (p.clicar) {
      var e2 = await waitFor(p.clicar, p.timeout || 8000);
      if (!e2) { if (p.opcional) return; throw new Error("não achei: " + p.clicar); }
      e2.scrollIntoView({ block: "center" });
      await wait(150);
      e2.click();
      return;
    }
    if (p.digitar) {
      var e3 = await waitFor(p.digitar[0], p.timeout || 8000);
      if (!e3) { if (p.opcional) return; throw new Error("não achei: " + p.digitar[0]); }
      e3.focus();
      setValue(e3, p.digitar[1]);
      return;
    }
    if (p.selecionar) {
      var e4 = await waitFor(p.selecionar[0], p.timeout || 8000);
      if (!e4) { if (p.opcional) return; throw new Error("não achei: " + p.selecionar[0]); }
      e4.value = p.selecionar[1];
      e4.dispatchEvent(new Event("change", { bubbles: true }));
      return;
    }
    if (p.extrair) {
      var els = Array.prototype.slice.call(document.querySelectorAll(p.extrair));
      var rows = [["texto", "link"]].concat(els.map(function (e) {
        var a = e.closest("a") || e.querySelector("a");
        return [(e.innerText || e.textContent || "").replace(/\s+/g, " ").trim(), a ? a.href : ""];
      }));
      if (typeof browser !== "undefined")
        browser.runtime.sendMessage({ cmd: "csv", name: p.arquivo || "dados", text: csv(rows) });
      log(els.length + " itens salvos em Downloads");
      return;
    }
    if (p.rolar !== undefined) { window.scrollBy({ top: p.rolar, behavior: "smooth" }); return; }
    if (p.ir) { location.href = p.ir; await wait(60000); return; }
    if (p.recarregar) { location.reload(); await wait(60000); return; }
  }

  // ---------- controle ----------
  function stop(t) {
    setState({ on: false, i: 0 });
    btn.textContent = "▶ Iniciar";
    log(t || "parado");
  }
  async function run() {
    var i = getState().i || 0;
    while (getState().on && i < PASSOS.length) {
      setState({ on: true, i: i + 1 }); // grava o próximo passo ANTES: se a página recarregar, continua dali
      log("passo " + (i + 1) + "/" + PASSOS.length);
      try { await exec(PASSOS[i]); }
      catch (e) { stop("erro: " + e.message); return; }
      i++;
    }
    if (!getState().on) return;
    if (REPETIR) {
      log("repetindo em " + Math.round(PAUSA / 1000) + "s…");
      await wait(PAUSA);
      if (getState().on) { setState({ on: true, i: 0 }); run(); }
    } else stop("concluído");
  }
  function start() {
    setState({ on: true, i: 0 });
    btn.textContent = "⏹ Parar";
    run();
  }
  btn.onclick = function () { getState().on ? stop("parado") : start(); };
  if (getState().on) { btn.textContent = "⏹ Parar"; run(); }
  else { btn.textContent = "▶ Iniciar"; if (AUTO) start(); }
}

/* FloatBrowser · Ferramentas (🛠): seletor, gravador de passos e exportar CSV */
(async function () {
  if (window.top !== window) return;
  var on = false;
  try { on = await browser.runtime.sendMessage({ cmd: "toolsOn" }); } catch (e) { return; }
  if (!on) return;

  const post = (m) => { try { browser.runtime.sendMessage(m); } catch (e) {} };
  const esc = (typeof CSS !== "undefined" && CSS.escape) ? CSS.escape.bind(CSS) : (s) => String(s).replace(/[^\w-]/g, "\\$&");
  const q = (s) => { try { return document.querySelectorAll(s).length; } catch (e) { return 0; } };
  const okClass = (c) => c.length < 30 && !/\d{3,}/.test(c);
  const clean = (t) => String(t || "").replace(/\s+/g, " ").trim();
  const linkOf = (e) => { const a = (e.closest && e.closest("a")) || e.querySelector("a"); return a ? a.href : ""; };
  const toCsv = (rows) => rows.map((r) => r.map((c) => '"' + String(c).replace(/"/g, '""') + '"').join(";")).join("\r\n");

  // ---------- seletor CSS ----------
  function cssPath(el) {
    const tag = el.tagName.toLowerCase();
    if (el.id && !/\d{4,}/.test(el.id)) { const s = "#" + esc(el.id); if (q(s) === 1) return s; }
    for (const a of ["data-testid", "data-test", "data-qa", "name", "aria-label", "placeholder", "title"]) {
      const v = el.getAttribute(a);
      if (v && v.length < 60) {
        const s = tag + "[" + a + '="' + v.replace(/\\/g, "\\\\").replace(/"/g, '\\"') + '"]';
        if (q(s) === 1) return s;
      }
    }
    const cls = Array.prototype.filter.call(el.classList, okClass);
    for (let i = 0; i < cls.length; i++) { const s = tag + "." + esc(cls[i]); if (q(s) === 1) return s; }
    if (cls.length > 1) { const s = tag + "." + cls.slice(0, 2).map(esc).join("."); if (q(s) === 1) return s; }
    let node = el; const parts = [];
    while (node && node.nodeType === 1 && node !== document.documentElement) {
      let part = node.tagName.toLowerCase();
      if (node.id && !/\d{4,}/.test(node.id)) { parts.unshift("#" + esc(node.id)); break; }
      const par = node.parentElement;
      if (par) {
        const sib = Array.prototype.filter.call(par.children, (n) => n.tagName === node.tagName);
        if (sib.length > 1) part += ":nth-of-type(" + (sib.indexOf(node) + 1) + ")";
      }
      parts.unshift(part);
      if (q(parts.join(" > ")) === 1) return parts.join(" > ");
      node = par;
    }
    return parts.join(" > ");
  }

  // seletor "amplo" (pega todos os itens parecidos), usado no Extrair
  function piece(n) {
    const c = Array.prototype.filter.call(n.classList, okClass).slice(0, 3);
    return n.tagName.toLowerCase() + c.map((x) => "." + esc(x)).join("");
  }
  function generalSel(el) {
    let sel = piece(el), node = el.parentElement;
    for (let d = 0; d < 4; d++) {
      const n = q(sel);
      if (n >= 2 && n <= 1000) return sel;
      if (!node || node === document.body) break;
      sel = piece(node) + " > " + sel;
      node = node.parentElement;
    }
    return piece(el);
  }

  // ---------- destaque ----------
  let marks = [];
  const clearMarks = () => { marks.forEach((m) => { m[0].style.outline = m[1]; }); marks = []; };
  const mark = (els) => { clearMarks(); els.forEach((e) => { marks.push([e, e.style.outline]); e.style.outline = "3px solid #ff9800"; }); };

  // ---------- painel (estilos por JS: não dependem do CSP do site) ----------
  const h = (tag, css, txt) => { const e = document.createElement(tag); if (css) e.style.cssText = css; if (txt) e.textContent = txt; return e; };
  const BTN = "font:13px sans-serif;padding:8px 10px;margin:2px;border:0;border-radius:6px;background:#455a64;color:#fff;";
  const host = h("div", "all:initial;position:fixed;right:6px;bottom:70px;z-index:2147483647;");
  const sh = host.attachShadow({ mode: "open" });
  const handle = h("div", "font:20px sans-serif;background:#263238;color:#fff;width:38px;height:38px;border-radius:50%;text-align:center;line-height:38px;opacity:.75;", "🛠");
  const panel = h("div", "display:none;background:#263238;color:#fff;font:13px sans-serif;padding:8px;border-radius:10px;width:min(92vw,340px);box-shadow:0 2px 10px #0008;");
  const row1 = h("div"), row2 = h("div", "margin-top:4px");
  const bsel = h("button", BTN, "🎯 Seletor"), brec = h("button", BTN, "⏺ Gravar"), bext = h("button", BTN, "📊 Extrair"), bx = h("button", BTN, "✕");
  const out = h("textarea", "width:100%;box-sizing:border-box;height:90px;margin-top:6px;font:12px monospace;background:#102027;color:#cfd8dc;border:0;border-radius:6px;");
  out.readOnly = true;
  const bcopy = h("button", BTN, "Copiar"), bup = h("button", BTN, "↑ Pai"), bsave = h("button", BTN + "background:#00796b;display:none;", "");
  [bsel, brec, bext, bx].forEach((b) => row1.appendChild(b));
  [bcopy, bup, bsave].forEach((b) => row2.appendChild(b));
  panel.appendChild(row1); panel.appendChild(out); panel.appendChild(row2);
  sh.appendChild(handle); sh.appendChild(panel);
  document.documentElement.appendChild(host);

  let mode = "idle", cur = null, lastText = "", extRows = [], recording = false, recState = null;
  const inPanel = (e) => e.composedPath && e.composedPath().indexOf(host) >= 0;
  const openPanel = () => { panel.style.display = "block"; handle.style.display = "none"; };
  const closePanel = () => { setMode("idle"); panel.style.display = "none"; handle.style.display = "block"; };

  function refreshButtons() {
    bsel.style.background = mode === "pick" ? "#ef6c00" : "#455a64";
    bext.style.background = mode === "extract" ? "#ef6c00" : "#455a64";
    brec.textContent = recording ? "⏺ Gravando" : "⏺ Gravar";
    brec.style.background = recording ? "#c62828" : "#455a64";
    if (recording) { bsave.style.display = "inline-block"; bsave.textContent = "⏹ Parar e salvar"; }
    else if (mode === "extract") { bsave.style.display = "inline-block"; bsave.textContent = "💾 Salvar CSV"; }
    else bsave.style.display = "none";
  }
  function setMode(m) {
    mode = m; clearMarks(); cur = null;
    if (m === "pick") out.value = "Toque em qualquer elemento da página para ver o seletor.";
    else if (m === "extract") out.value = "Toque em UM item da lista (ex.: um título ou preço). Eu pego todos os parecidos.";
    refreshButtons();
  }

  // bloqueia a página enquanto escolhe um elemento
  function block(e) {
    if (mode !== "pick" && mode !== "extract") return;
    if (inPanel(e)) return;
    e.stopPropagation();
    if (e.type === "click") { e.preventDefault(); onPick(e.target); }
  }
  ["pointerdown", "pointerup", "mousedown", "mouseup", "touchstart", "touchend", "click"]
    .forEach((t) => window.addEventListener(t, block, true));

  function onPick(el) {
    cur = el;
    if (mode === "pick") {
      const s = cssPath(el);
      lastText = s; mark([el]);
      out.value = s + "\n\n" + q(s) + " elemento(s) · toque em Copiar";
    } else if (mode === "extract") {
      const s = generalSel(el);
      const els = Array.prototype.slice.call(document.querySelectorAll(s));
      mark(els);
      extRows = [["texto", "link"]].concat(els.map((e) => [clean(e.innerText || e.textContent), linkOf(e)]));
      lastText = s;
      out.value = s + "\n" + els.length + " item(ns). Se pegou demais ou de menos, use ↑ Pai.\n\n" +
        extRows.slice(1, 4).map((r) => r.join(" | ")).join("\n");
    }
  }

  // ---------- gravador ----------
  const recGet = async () => { try { return (await browser.storage.local.get("rec")).rec || null; } catch (e) { return null; } };
  const recPut = () => { try { browser.storage.local.set({ rec: recState }); } catch (e) {} };

  function addStep(step) {
    const now = Date.now();
    const gap = now - (recState.t || now);
    if (recState.steps.length && gap > 1500)
      recState.steps.push({ esperar: Math.min(10000, Math.round(gap / 500) * 500) });
    recState.steps.push(step); recState.t = now;
    recPut();
    out.value = "Gravando… " + recState.steps.length + " passos\nÚltimo: " + JSON.stringify(step);
  }
  function onRecClick(e) {
    if (inPanel(e) || mode === "pick" || mode === "extract") return;
    let t = e.target;
    if (t.closest) t = t.closest("a,button,input,select,textarea,label,[role=button],[onclick]") || t;
    if (!t || t.nodeType !== 1) return;
    addStep({ clicar: cssPath(t) });
  }
  function onRecInput(e) {
    const t = e.target;
    if (!t || inPanel(e)) return;
    const tag = t.tagName;
    if (tag === "SELECT") return;
    if (tag !== "INPUT" && tag !== "TEXTAREA") return;
    if (/^(checkbox|radio|file|button|submit|range|color)$/.test(t.type)) return;
    const sel = cssPath(t);
    const val = t.type === "password" ? "SENHA_AQUI" : t.value;
    const last = recState.steps[recState.steps.length - 1];
    if (last && last.digitar && last.digitar[0] === sel) { last.digitar[1] = val; recPut(); return; }
    addStep({ digitar: [sel, val] });
  }
  function onRecChange(e) {
    const t = e.target;
    if (t && t.tagName === "SELECT" && !inPanel(e)) addStep({ selecionar: [cssPath(t), t.value] });
  }
  function attachRec() {
    document.addEventListener("click", onRecClick, true);
    document.addEventListener("input", onRecInput, true);
    document.addEventListener("change", onRecChange, true);
  }
  function detachRec() {
    document.removeEventListener("click", onRecClick, true);
    document.removeEventListener("input", onRecInput, true);
    document.removeEventListener("change", onRecChange, true);
  }
  async function startRec() {
    recState = { on: true, steps: [], t: 0 };
    recording = true; recPut(); attachRec(); refreshButtons();
    out.value = "Gravando… faça as ações normalmente.\nQuando terminar: ⏹ Parar e salvar.\nSenhas viram SENHA_AQUI (digite a sua depois, no arquivo).";
  }
  function buildScript(steps) {
    const passos = steps.map((s) => "  " + JSON.stringify(s)).join(",\n");
    return "// @name Gravação " + location.hostname + "\n// @match " + location.origin + "/*\n" +
      "// Gravado automaticamente. Edite os PASSOS se precisar. Senhas viram SENHA_AQUI.\n" +
      "(function () {\n" +
      "const PASSOS = [\n" + passos + "\n];\n" +
      "const REPETIR = false, PAUSA_ENTRE_VOLTAS = 5000, INICIAR_AUTOMATICO = false;\n" +
      __fbEngine.toString() + "\n" +
      "__fbEngine(PASSOS, REPETIR, PAUSA_ENTRE_VOLTAS, INICIAR_AUTOMATICO);\n" +
      "})();\n";
  }
  async function stopRec() {
    detachRec(); recording = false;
    const steps = recState ? recState.steps : [];
    recState = null;
    try { await browser.storage.local.remove("rec"); } catch (e) {}
    refreshButtons();
    if (!steps.length) { out.value = "Nada foi gravado."; return; }
    const d = new Date(), p2 = (n) => String(n).padStart(2, "0");
    const name = "gravacao-" + location.hostname + "-" + p2(d.getHours()) + p2(d.getMinutes()) + ".js";
    post({ cmd: "savescript", name: name, text: buildScript(steps) });
    out.value = "Salvo como script: " + name + "\n" + steps.length + " passos.\nAbra 'Gerenciar scripts' no app. Recarregue a página e toque em ▶ Iniciar.";
  }

  // ---------- botões ----------
  handle.onclick = openPanel;
  bx.onclick = closePanel;
  bsel.onclick = () => setMode(mode === "pick" ? "idle" : "pick");
  bext.onclick = () => setMode(mode === "extract" ? "idle" : "extract");
  brec.onclick = () => { if (!recording) startRec(); };
  bcopy.onclick = () => post({ cmd: "copy", text: lastText || out.value });
  bup.onclick = () => { if (cur && cur.parentElement && cur.parentElement !== document.documentElement) onPick(cur.parentElement); };
  bsave.onclick = () => {
    if (recording) { stopRec(); return; }
    if (mode === "extract" && extRows.length > 1) {
      post({ cmd: "csv", name: "dados-" + location.hostname, text: toCsv(extRows) });
      out.value = extRows.length - 1 + " itens enviados. Veja em Downloads (CSV com ponto e vírgula).";
    }
  };

  // retoma gravação ao trocar de página
  const st = await recGet();
  if (st && st.on) {
    recState = st; recording = true;
    const last = st.steps[st.steps.length - 1];
    if (st.steps.length && !(last && last.esperar)) { st.steps.push({ esperar: 2000 }); recPut(); }
    attachRec(); openPanel(); refreshButtons();
    out.value = "Gravando… " + st.steps.length + " passos";
  }
})();

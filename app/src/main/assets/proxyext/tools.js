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

  // ---------- cliques ----------
  // clique comum, com a sequência completa de eventos (alguns sites só ouvem pointerdown/mousedown)
  function fullClick(el) {
    var r = { left: 0, top: 0, width: 0, height: 0 };
    try { r = el.getBoundingClientRect(); } catch (e) {}
    var o = { bubbles: true, cancelable: true, composed: true, button: 0, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
    try { el.dispatchEvent(new PointerEvent("pointerdown", o)); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent("mousedown", o)); } catch (e) {}
    try { el.dispatchEvent(new PointerEvent("pointerup", o)); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent("mouseup", o)); } catch (e) {}
    el.click();
  }
  // toque REAL: o app manda um toque de verdade ao navegador (isTrusted = true), como um dedo.
  // Devolve false (e o chamador faz o clique comum) se algo não estiver disponível.
  async function realTap(el) {
    if (typeof browser === "undefined") return false;
    el.scrollIntoView({ block: "center" });
    await wait(450);
    var r = el.getBoundingClientRect(), v = window.visualViewport;
    var vx = v ? v.offsetLeft : 0, vy = v ? v.offsetTop : 0, vw = v ? v.width : window.innerWidth, vh = v ? v.height : window.innerHeight;
    var fx = (r.left + r.width / 2 - vx) / vw, fy = (r.top + r.height / 2 - vy) / vh;
    if (!(fx >= 0 && fx <= 1 && fy >= 0 && fy <= 1)) return false;
    var ok = false;
    box.style.pointerEvents = "none";
    try { ok = await browser.runtime.sendMessage({ cmd: "tap", fx: fx, fy: fy }); } catch (e) {}
    await wait(500);
    box.style.pointerEvents = "";
    return !!ok;
  }

  // ---------- quadros (iframes): passo com "quadro" roda dentro do iframe ----------
  function selOf(p) {
    return p.esperarElemento || p.clicar || p.extrair || (p.digitar && p.digitar[0]) || (p.selecionar && p.selecionar[0]) || "";
  }
  async function inFrame(p) {
    if (typeof browser === "undefined") throw new Error("quadros só funcionam dentro do app");
    var sel = selOf(p), limit = p.timeout || (p.esperarElemento ? 10000 : 8000), t0 = Date.now(), r = null;
    for (;;) {
      try { r = await browser.runtime.sendMessage({ cmd: "frameFind", quadro: p.quadro, sel: sel }); } catch (e) { r = null; }
      if (r && r.frameId != null) break;
      if (Date.now() - t0 >= limit) {
        if (p.opcional) return null;
        throw new Error('não achei no quadro "' + p.quadro + '": ' + (sel || "(quadro)"));
      }
      await wait(250);
    }
    var res = null;
    try { res = await browser.runtime.sendMessage({ cmd: "frameRun", frameId: r.frameId, step: p }); } catch (e) {}
    if (!res || !res.ok) { if (p.opcional) return null; throw new Error((res && res.error) || "falha no quadro"); }
    return res;
  }

  // ---------- passos ----------
  async function exec(p) {
    if (p.quadro && (p.esperarElemento || p.clicar || p.digitar || p.selecionar || p.extrair || p.rolar !== undefined)) {
      var fr = await inFrame(p);
      if (p.clicar && fr && fr.fx != null) {   // toque real no iframe: o quadro mandou onde tocar
        try { await browser.runtime.sendMessage({ cmd: "tap", fx: fr.fx, fy: fr.fy }); } catch (e) {}
        await wait(500);
      }
      if (p.extrair && fr) {
        var its = fr.items || [];
        var rowsF = [["texto", "link"]].concat(its.map(function (it) { return [it.t, it.a]; }));
        browser.runtime.sendMessage({ cmd: "csv", name: p.arquivo || "dados", text: csv(rowsF) });
        log(its.length + " itens salvos em Downloads");
      }
      return;
    }
    if (p.esperar) return wait(p.esperar);
    if (p.esperarElemento) {
      var e1 = await waitFor(p.esperarElemento, p.timeout || 10000);
      if (!e1 && !p.opcional) throw new Error("não apareceu: " + p.esperarElemento);
      return;
    }
    if (p.clicar) {
      var e2 = await waitFor(p.clicar, p.timeout || 8000);
      if (!e2) { if (p.opcional) return; throw new Error("não achei: " + p.clicar); }
      if (p.real && await realTap(e2)) return;
      e2.scrollIntoView({ block: "center" });
      await wait(150);
      fullClick(e2);
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

/* FloatBrowser · Seletor CSS e destaque, compartilhados pelo quadro principal e pelos iframes */
var FB = (function () {
  const esc = (typeof CSS !== "undefined" && CSS.escape) ? CSS.escape.bind(CSS) : (s) => String(s).replace(/[^\w-]/g, "\\$&");
  const q = (s) => { try { return document.querySelectorAll(s).length; } catch (e) { return 0; } };
  const okClass = (c) => c.length < 30 && !/\d{3,}/.test(c);
  const clean = (t) => String(t || "").replace(/\s+/g, " ").trim();
  const linkOf = (e) => { const a = (e.closest && e.closest("a")) || e.querySelector("a"); return a ? a.href : ""; };

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

  return { esc: esc, q: q, clean: clean, linkOf: linkOf, cssPath: cssPath, generalSel: generalSel, mark: mark, clearMarks: clearMarks };
})();

/* FloatBrowser · Medidor de quadros: descobre onde um elemento de um iframe está na tela (toque real) */
var FBG = (function () {
  var reqs = {};
  function vvInfo() {
    var v = window.visualViewport;
    return v ? { ox: v.offsetLeft, oy: v.offsetTop, w: v.width, h: v.height } : { ox: 0, oy: 0, w: window.innerWidth, h: window.innerHeight };
  }
  function offsetOf(win) {   // onde está, neste quadro, o iframe cuja janela é `win`
    var list = document.querySelectorAll("iframe,frame");
    for (var i = 0; i < list.length; i++) {
      var f = list[i];
      if (f.contentWindow === win) {
        var r = f.getBoundingClientRect();
        return { x: r.left + f.clientLeft, y: r.top + f.clientTop,
          sx: f.offsetWidth ? r.width / f.offsetWidth : 1, sy: f.offsetHeight ? r.height / f.offsetHeight : 1 };
      }
    }
    return null;
  }
  // transformação do viewport DESTE quadro para a área visível da página principal; cb(null) se não der
  function chain(cb) {
    if (window.top === window) { cb({ ax: 0, ay: 0, sx: 1, sy: 1, vv: vvInfo() }); return; }
    var id = String(Math.random()), done = false;
    var fin = function (t) { if (done) return; done = true; delete reqs[id]; cb(t); };
    reqs[id] = fin;
    setTimeout(function () { fin(null); }, 1000);
    try { window.parent.postMessage({ __fb: 1, op: "off", id: id }, "*"); } catch (e) { fin(null); }
  }
  window.addEventListener("message", function (e) {
    var d = e.data;
    if (!d || d.__fb !== 1 || !d.id) return;
    if (d.op === "off" && e.source && e.source !== window) {
      var src = e.source;
      var reply = function (v) { try { src.postMessage({ __fb: 1, op: "offr", id: d.id, t: v }, "*"); } catch (x) {} };
      var o = offsetOf(src);
      if (!o) { reply(null); return; }
      chain(function (t) {
        if (!t) { reply(null); return; }
        reply({ ax: t.ax + o.x * t.sx, ay: t.ay + o.y * t.sy, sx: o.sx * t.sx, sy: o.sy * t.sy, vv: t.vv });
      });
    } else if (d.op === "offr" && reqs[d.id]) reqs[d.id](d.t || null);
  });
  return { chain: chain };
})();

/* FloatBrowser · Ferramentas (🛠): seletor, gravador de passos e exportar CSV */
(async function () {
  if (window.top !== window) return;
  var on = false;
  try { on = await browser.runtime.sendMessage({ cmd: "toolsOn" }); } catch (e) { return; }
  if (!on) return;

  const post = (m) => { try { browser.runtime.sendMessage(m); } catch (e) {} };
  const { q, clean, linkOf, cssPath, generalSel, mark, clearMarks } = FB;
  const toCsv = (rows) => rows.map((r) => r.map((c) => '"' + String(c).replace(/"/g, '""') + '"').join(";")).join("\r\n");

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
  const bmon = h("button", BTN, "👁 Monitorar");
  const row3 = h("div", "margin-top:4px;display:flex;gap:4px;align-items:center;");
  const INP = "font:13px sans-serif;padding:6px;border:0;border-radius:6px;background:#102027;color:#fff;box-sizing:border-box;";
  const mevery = h("input", INP + "width:64px;"); mevery.type = "number"; mevery.min = "30"; mevery.value = "60"; mevery.title = "segundos entre as verificações";
  const mtext = h("input", INP + "flex:1;min-width:0;"); mtext.placeholder = "avisar se contiver… (opcional)";
  row3.appendChild(mevery); row3.appendChild(h("span", "font:12px sans-serif;", "s")); row3.appendChild(mtext);
  [bcopy, bup, bmon, bsave].forEach((b) => row2.appendChild(b));
  panel.appendChild(row1); panel.appendChild(out); panel.appendChild(row2); panel.appendChild(row3);
  sh.appendChild(handle); sh.appendChild(panel);
  document.documentElement.appendChild(host);

  let mode = "idle", cur = null, curSel = "", curQuadro = "", lastText = "", extRows = [], recording = false, recState = null;
  // avisa os iframes do modo atual (seletor/extrair/gravar): eles passam a capturar toques e gravar também
  const syncFrames = () => post({ cmd: "fstate", mode: mode, recording: recording });
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
    mode = m; clearMarks(); cur = null; curSel = ""; curQuadro = "";
    syncFrames();
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
    cur = el; curQuadro = ""; post({ cmd: "fclear" });
    if (mode === "pick") {
      const s = cssPath(el);
      curSel = s; lastText = s; mark([el]);
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

  // toque feito dentro de um iframe: o quadro mandou o seletor e o endereço (quadro) dele
  function onFramePick(d, fid) {
    clearMarks(); cur = null;
    post({ cmd: "fclear", keep: fid });
    if (!d || d.mode !== mode) return;
    curSel = d.sel; curQuadro = d.quadro;
    if (d.mode === "pick") {
      lastText = JSON.stringify({ clicar: d.sel, quadro: d.quadro });
      out.value = d.sel + "\n\nquadro: " + d.quadro + " · " + d.n + " elemento(s)\nCopiar leva o passo pronto, já com \"quadro\".";
    } else {
      extRows = [["texto", "link"]].concat(d.rows || []);
      lastText = JSON.stringify({ extrair: d.sel, quadro: d.quadro });
      out.value = d.sel + "\nquadro: " + d.quadro + " · " + (d.rows || []).length + " item(ns). Se pegou demais ou de menos, use ↑ Pai.\n\n" +
        extRows.slice(1, 4).map((r) => r.join(" | ")).join("\n");
    }
  }

  // ---------- gravador ----------
  const recGet = async () => { try { return (await browser.storage.local.get("rec")).rec || null; } catch (e) { return null; } };
  const recPut = () => { try { browser.storage.local.set({ rec: recState }); } catch (e) {} };

  function addStep(step) {
    const last = recState.steps[recState.steps.length - 1];
    if (step.digitar && last && last.digitar && last.digitar[0] === step.digitar[0] && last.quadro === step.quadro) {
      last.digitar[1] = step.digitar[1]; recPut(); return;
    }
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
    addStep({ digitar: [sel, val] });
  }
  function onFrameStep(step) { if (recording && recState && step) addStep(step); }
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
    recording = true; recPut(); attachRec(); refreshButtons(); syncFrames();
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
    detachRec(); recording = false; syncFrames();
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
  bup.onclick = () => {
    if (curQuadro) { post({ cmd: "fup" }); return; }   // o elemento está num iframe: ele mesmo sobe
    if (cur && cur.parentElement && cur.parentElement !== document.documentElement) onPick(cur.parentElement);
  };
  bsave.onclick = () => {
    if (recording) { stopRec(); return; }
    if (mode === "extract" && extRows.length > 1) {
      post({ cmd: "csv", name: "dados-" + location.hostname, text: toCsv(extRows) });
      out.value = extRows.length - 1 + " itens enviados. Veja em Downloads (CSV com ponto e vírgula).";
    }
  };

  // ---------- monitor de página (👁) ----------
  let monOn = false;
  function refreshMon() {
    bmon.textContent = monOn ? "👁 Parar monitor" : "👁 Monitorar";
    bmon.style.background = monOn ? "#c62828" : "#455a64";
  }
  try { monOn = !!(await browser.runtime.sendMessage({ cmd: "monGet" })); } catch (e) {}
  refreshMon();
  bmon.onclick = () => {
    if (monOn) { post({ cmd: "monOff" }); monOn = false; refreshMon(); out.value = "Monitor desligado."; return; }
    if (mode !== "pick" || !curSel) {
      out.value = "Primeiro toque em 🎯 Seletor e escolha o elemento (preço, texto…) que quer monitorar. Depois toque em 👁.";
      return;
    }
    const sel = curSel;
    const every = Math.max(30, parseInt(mevery.value, 10) || 60);
    post({ cmd: "monSet", sel: sel, quadro: curQuadro, every: every, text: mtext.value.trim() });
    monOn = true; refreshMon();
    out.value = "Monitorando " + sel + (curQuadro ? " (quadro " + curQuadro + ")" : "") + " a cada " + every + " s.\nA página vai recarregar para ler o valor. Você recebe uma notificação quando mudar.";
    setTimeout(() => location.reload(), 800);
  };

  // ---------- teclas da janela (app) acionam as ferramentas daqui ----------
  try {
    const pp = browser.runtime.connect({ name: "page" });
    pp.onMessage.addListener((m) => {
      if (!m) return;
      if (m.cmd === "fpick") { onFramePick(m.data, m.frameId); return; }
      if (m.cmd === "fstep") { if (m.data) onFrameStep(m.data.step); return; }
      if (m.cmd !== "ui") return;
      openPanel();
      if (m.tool === "pick") setMode(mode === "pick" ? "idle" : "pick");
      else if (m.tool === "extract") setMode(mode === "extract" ? "idle" : "extract");
      else if (m.tool === "rec") { if (!recording) startRec(); else stopRec(); }
      else if (m.tool === "mon") bmon.onclick();
    });
  } catch (e) {}

  // retoma gravação ao trocar de página
  const st = await recGet();
  if (st && st.on) {
    recState = st; recording = true;
    const last = st.steps[st.steps.length - 1];
    if (st.steps.length && !(last && last.esperar)) { st.steps.push({ esperar: 2000 }); recPut(); }
    attachRec(); openPanel(); refreshButtons(); syncFrames();
    out.value = "Gravando… " + st.steps.length + " passos";
  }
})();

/* FloatBrowser · Monitor de página (👁): a cada carga lê o elemento, avisa o app e agenda a próxima recarga */
(async function () {
  if (window.top !== window) return;
  var cfg = null;
  try { cfg = await browser.runtime.sendMessage({ cmd: "monGet" }); } catch (e) { return; }
  if (!cfg || !cfg.sel) return;
  var read = async function () {
    if (cfg.quadro) {   // elemento dentro de um iframe: procura e lê pelo agente do quadro
      try {
        var f = await browser.runtime.sendMessage({ cmd: "frameFind", quadro: cfg.quadro, sel: cfg.sel });
        if (!f || f.frameId == null) return null;
        var res = await browser.runtime.sendMessage({ cmd: "frameRun", frameId: f.frameId, step: { ler: cfg.sel } });
        return res && res.ok ? res.value : null;
      } catch (e) { return null; }
    }
    try {
      var el = document.querySelector(cfg.sel);
      return el ? String(el.innerText || el.textContent || "").replace(/\s+/g, " ").trim() : null;
    } catch (e) { return null; }
  };
  var t0 = Date.now(), v = await read();
  while (v === null && Date.now() - t0 < 8000) { // páginas que montam o conteúdo depois
    await new Promise(function (r) { setTimeout(r, 500); });
    v = await read();
  }
  try { browser.runtime.sendMessage({ type: "mon", value: v }); } catch (e) {}
  setTimeout(async function () {
    var c = null;
    try { c = await browser.runtime.sendMessage({ cmd: "monGet" }); } catch (e) {}
    if (c && c.sel) location.reload(); // só continua se o monitor ainda estiver ligado
  }, Math.max(30, cfg.every || 60) * 1000);
})();

/* FloatBrowser · Agente de quadros (iframes): faz dentro do iframe o que o quadro principal pede
   (passos com "quadro", seletor 🎯, extrair 📊, gravador ⏺ e monitor 👁). Não mostra nenhum painel. */
(function () {
  if (window.top === window) return;
  if (typeof browser === "undefined") return;
  var port;
  try { port = browser.runtime.connect({ name: "frame" }); } catch (e) { return; }
  var send = function (m) { try { port.postMessage(m); } catch (e) {} };
  var wait = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
  var clean = FB.clean;

  // pedaço do endereço que identifica este quadro (vai no campo "quadro" dos passos gravados)
  function quadroKey() { return location.host || location.href.slice(0, 60); }
  function matches(qd) {
    if (qd === true || qd === "*" || qd == null || qd === "") return true;
    return location.href.toLowerCase().indexOf(String(qd).toLowerCase()) >= 0;
  }
  function has(sel) {
    if (!sel) return true;
    try { return !!document.querySelector(sel); } catch (e) { return false; }
  }
  async function waitFor(sel, ms) {
    var t0 = Date.now();
    while (Date.now() - t0 < ms) {
      var el = null;
      try { el = document.querySelector(sel); } catch (e) {}
      if (el) return el;
      await wait(200);
    }
    return null;
  }
  function setValue(el, text) {
    var proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype
      : el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, "value");
    if (desc && desc.set) desc.set.call(el, text); else el.value = text;
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }
  function textOf(el) { return clean(el.innerText || el.textContent); }
  function fullClick(el) {
    var r = { left: 0, top: 0, width: 0, height: 0 };
    try { r = el.getBoundingClientRect(); } catch (e) {}
    var o = { bubbles: true, cancelable: true, composed: true, button: 0, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
    try { el.dispatchEvent(new PointerEvent("pointerdown", o)); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent("mousedown", o)); } catch (e) {}
    try { el.dispatchEvent(new PointerEvent("pointerup", o)); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent("mouseup", o)); } catch (e) {}
    el.click();
  }
  // posição do elemento na área visível da página principal (fração 0..1) ou null
  function tapPoint(el) {
    return new Promise(function (resolve) {
      var r = el.getBoundingClientRect(), x = r.left + r.width / 2, y = r.top + r.height / 2;
      FBG.chain(function (t) {
        if (!t) { resolve(null); return; }
        var v = t.vv, fx = (t.ax + x * t.sx - v.ox) / v.w, fy = (t.ay + y * t.sy - v.oy) / v.h;
        resolve(fx >= 0 && fx <= 1 && fy >= 0 && fy <= 1 ? { fx: fx, fy: fy } : null);
      });
    });
  }

  // ---------- passos pedidos pelo quadro principal ----------
  // (o quadro já foi escolhido porque o elemento existe nele: a espera aqui é curta)
  async function runStep(p) {
    var T = Math.min(p.timeout || 3000, 3000), el, sel;
    if (p.ler !== undefined) { el = has(p.ler) ? document.querySelector(p.ler) : null; return { value: el ? textOf(el) : null }; }
    if (p.esperarElemento) return {};
    if (p.clicar) {
      el = await waitFor(p.clicar, T);
      if (!el) throw new Error("não achei: " + p.clicar);
      el.scrollIntoView({ block: "center" });
      await wait(p.real ? 450 : 150);
      if (p.real) {   // toque real: devolve onde tocar (quem faz o toque é o quadro principal, via app)
        var pt = await tapPoint(el);
        if (pt) return { fx: pt.fx, fy: pt.fy };
      }
      fullClick(el);
      return {};
    }
    if (p.digitar) {
      sel = p.digitar[0];
      el = await waitFor(sel, T);
      if (!el) throw new Error("não achei: " + sel);
      el.focus();
      setValue(el, p.digitar[1]);
      return {};
    }
    if (p.selecionar) {
      sel = p.selecionar[0];
      el = await waitFor(sel, T);
      if (!el) throw new Error("não achei: " + sel);
      setValue(el, p.selecionar[1]);
      return {};
    }
    if (p.enter) {
      el = await waitFor(p.enter, T);
      if (!el) throw new Error("não achei: " + p.enter);
      var o = { key: "Enter", code: "Enter", keyCode: 13, which: 13, bubbles: true, cancelable: true };
      var handled = !el.dispatchEvent(new KeyboardEvent("keydown", o));
      el.dispatchEvent(new KeyboardEvent("keypress", o));
      el.dispatchEvent(new KeyboardEvent("keyup", o));
      if (!handled && el.form) { try { el.form.requestSubmit ? el.form.requestSubmit() : el.form.submit(); } catch (e) {} }
      return {};
    }
    if (p.extrair) {
      var els = Array.prototype.slice.call(document.querySelectorAll(p.extrair));
      return {
        items: els.map(function (e) {
          return {
            t: textOf(e), a: FB.linkOf(e),
            c: Array.prototype.map.call(e.querySelectorAll("td,th"), function (c) { return clean(c.innerText); })
          };
        })
      };
    }
    if (p.rolar !== undefined) { window.scrollBy({ top: p.rolar, behavior: "smooth" }); return {}; }
    return {};
  }

  // ---------- ferramentas (🎯 📊 ⏺) dentro do quadro ----------
  var mode = "idle", cur = null, blockOn = false, recOn = false;
  var EVT = ["pointerdown", "pointerup", "mousedown", "mouseup", "touchstart", "touchend", "click"];

  function block(e) {
    if (mode !== "pick" && mode !== "extract") return;
    e.stopPropagation();
    if (e.type === "click") { e.preventDefault(); onPick(e.target); }
  }
  function onPick(el) {
    cur = el;
    var s;
    if (mode === "pick") {
      s = FB.cssPath(el);
      FB.mark([el]);
      send({ t: "pick", mode: "pick", sel: s, n: FB.q(s), quadro: quadroKey() });
    } else if (mode === "extract") {
      s = FB.generalSel(el);
      var els = Array.prototype.slice.call(document.querySelectorAll(s));
      FB.mark(els);
      send({
        t: "pick", mode: "extract", sel: s, quadro: quadroKey(),
        rows: els.map(function (e) { return [textOf(e), FB.linkOf(e)]; })
      });
    }
  }

  function recClick(e) {
    if (mode === "pick" || mode === "extract") return;
    var t = e.target;
    if (t && t.closest) t = t.closest("a,button,input,select,textarea,label,[role=button],[onclick]") || t;
    if (!t || t.nodeType !== 1) return;
    send({ t: "step", step: { clicar: FB.cssPath(t), quadro: quadroKey() } });
  }
  function recInput(e) {
    var t = e.target;
    if (!t || (t.tagName !== "INPUT" && t.tagName !== "TEXTAREA")) return;
    if (/^(checkbox|radio|file|button|submit|range|color)$/.test(t.type)) return;
    send({ t: "step", step: { digitar: [FB.cssPath(t), t.type === "password" ? "SENHA_AQUI" : t.value], quadro: quadroKey() } });
  }
  function recChange(e) {
    var t = e.target;
    if (t && t.tagName === "SELECT") send({ t: "step", step: { selecionar: [FB.cssPath(t), t.value], quadro: quadroKey() } });
  }
  function setRec(on) {
    if (on && !recOn) {
      document.addEventListener("click", recClick, true);
      document.addEventListener("input", recInput, true);
      document.addEventListener("change", recChange, true);
      recOn = true;
    } else if (!on && recOn) {
      document.removeEventListener("click", recClick, true);
      document.removeEventListener("input", recInput, true);
      document.removeEventListener("change", recChange, true);
      recOn = false;
    }
  }
  function applyState(s) {
    var m = s.mode || "idle";
    if (m !== mode) { FB.clearMarks(); cur = null; }
    mode = m;
    if ((mode === "pick" || mode === "extract") && !blockOn) {
      EVT.forEach(function (t) { window.addEventListener(t, block, true); });
      blockOn = true;
    }
    setRec(!!s.recording);
  }

  // ---------- mensagens do background ----------
  port.onMessage.addListener(function (m) {
    if (!m) return;
    if (m.cmd === "find") send({ t: "found", id: m.id, ok: matches(m.quadro) && has(m.sel) });
    else if (m.cmd === "run") {
      runStep(m.step || {}).then(
        function (r) { r = r || {}; r.t = "ran"; r.id = m.id; r.ok = true; send(r); },
        function (e) { send({ t: "ran", id: m.id, ok: false, error: String((e && e.message) || e) }); }
      );
    }
    else if (m.cmd === "state") applyState(m);
    else if (m.cmd === "clear") { FB.clearMarks(); cur = null; }
    else if (m.cmd === "up") {
      if (cur && cur.parentElement && cur.parentElement !== document.documentElement) onPick(cur.parentElement);
    }
  });
})();

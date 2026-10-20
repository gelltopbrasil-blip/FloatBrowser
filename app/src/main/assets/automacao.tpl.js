// ==FloatBrowser Automação==
// @name Automação
// @match __MATCH__
//
// COMO USAR: edite só a área CONFIGURE AQUI (e o endereço do site na linha "match" acima).
// Importe o arquivo no app, recarregue a página e toque em ▶ Iniciar (botão no canto da tela).
//
// PASSOS (na ordem; use quantos quiser):
//   { "esperar": 2000 }                              espera 2 segundos
//   { "esperarElemento": ".classe", "timeout": 15000 }   espera algo aparecer
//   { "clicar": "button.entrar" }                    clica (seletor CSS)
//   { "clicar": ".aceitar", "opcional": true }       clica se existir; senão segue
//   { "clicar": ".botao", "real": true }             toque REAL (como um dedo; passa em anúncios/captchas que ignoram
//                                                    clique de script). Se não der, faz o clique comum. Funciona com "quadro".
//   { "digitar": ["input[name=q]", "texto"] }        escreve num campo (ou escolhe numa lista)
//   { "enter": "input[name=q]" }                     aperta Enter no campo
//   { "rolar": 600 }                                 rola a página (pixels)
//   { "ir": "https://site.com/pagina" }              abre outra página
//   { "recarregar": true }                           recarrega a página
//   { "extrair": "tr.linha" }                        guarda o texto de cada elemento (linhas de tabela viram colunas)
//   { "salvarCsv": "dados.csv" }                     salva o que foi extraído na pasta Download
//   { "avisar": "terminei" }                         manda uma notificação
//
// IFRAMES (anúncios, formulários embutidos): acrescente "quadro" ao passo e ele roda dentro do iframe.
//   { "clicar": "button.enviar", "quadro": "forms.exemplo.com" }   "quadro" = pedaço do endereço do iframe
//   { "digitar": ["input[name=email]", "a@b.com"], "quadro": "forms.exemplo.com" }
//   { "extrair": "li.item", "quadro": "*" }                        "*" = qualquer iframe que tenha o elemento
//   Vale para: esperarElemento, clicar, digitar, enter, rolar, extrair. Se vários iframes combinarem,
//   usa o primeiro que tiver o elemento. Para descobrir o seletor E o quadro, use 🎯 e toque dentro do iframe.
// Dica: use o botão 🎯 da janela para descobrir o seletor de qualquer elemento.
// Atenção: senha escrita aqui fica visível no arquivo; prefira não guardar senhas importantes.

(function () {
  if (window.top !== window) return;

  // ====================== CONFIGURE AQUI ======================
  const PASSOS = __PASSOS__;
  const REPETIR = false;            // true = volta ao primeiro passo quando terminar
  const PAUSA_ENTRE_VOLTAS = 5000;  // espera (ms) antes de repetir
  const INICIAR_AUTOMATICO = false; // true = começa sozinho ao abrir a página
  // ============================================================

  const KEY = "__fb_auto__", ROWS = "__fb_rows__";
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const clean = (t) => (t || "").replace(/\s+/g, " ").trim();
  const getState = () => { try { return JSON.parse(localStorage.getItem(KEY)) || {}; } catch (e) { return {}; } };
  const setState = (s) => { try { localStorage.setItem(KEY, JSON.stringify(s)); } catch (e) {} };
  const bg = (m) => { try { if (typeof browser !== "undefined") browser.runtime.sendMessage(m); } catch (e) {} };
  let running = false;

  // ---------- painel na página ----------
  let box = document.getElementById("__fb_panel__");
  if (!box) {
    box = document.createElement("div");
    box.id = "__fb_panel__";
    box.style.cssText = "position:fixed;left:6px;bottom:6px;z-index:2147483646;background:#263238;" +
      "color:#fff;font:12px sans-serif;padding:6px 8px;border-radius:8px;opacity:.92;max-width:70vw";
    document.documentElement.appendChild(box);
  }
  const btn = document.createElement("button");
  btn.style.cssText = "font:12px sans-serif;padding:4px 8px;margin-right:6px";
  const msg = document.createElement("span");
  box.appendChild(btn); box.appendChild(msg);
  const log = (t) => { msg.textContent = t; };

  // ---------- ações ----------
  async function waitFor(sel, timeout) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeout) {
      const el = document.querySelector(sel);
      if (el) return el;
      await wait(200);
    }
    return null;
  }

  function setValue(el, text) {
    const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype
      : el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    const desc = Object.getOwnPropertyDescriptor(proto, "value");
    if (desc && desc.set) desc.set.call(el, text); else el.value = text;
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }

  // ---------- cliques ----------
  function fullClick(el) {
    let r = { left: 0, top: 0, width: 0, height: 0 };
    try { r = el.getBoundingClientRect(); } catch (e) {}
    const o = { bubbles: true, cancelable: true, composed: true, button: 0, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
    try { el.dispatchEvent(new PointerEvent("pointerdown", o)); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent("mousedown", o)); } catch (e) {}
    try { el.dispatchEvent(new PointerEvent("pointerup", o)); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent("mouseup", o)); } catch (e) {}
    el.click();
  }
  // toque REAL (isTrusted = true): o app manda um toque de verdade ao navegador. false = use o clique comum
  async function realTap(el) {
    if (typeof browser === "undefined") return false;
    el.scrollIntoView({ block: "center" });
    await wait(450);
    const r = el.getBoundingClientRect(), v = window.visualViewport;
    const vx = v ? v.offsetLeft : 0, vy = v ? v.offsetTop : 0, vw = v ? v.width : window.innerWidth, vh = v ? v.height : window.innerHeight;
    const fx = (r.left + r.width / 2 - vx) / vw, fy = (r.top + r.height / 2 - vy) / vh;
    if (!(fx >= 0 && fx <= 1 && fy >= 0 && fy <= 1)) return false;
    let ok = false;
    box.style.pointerEvents = "none";
    try { ok = await browser.runtime.sendMessage({ cmd: "tap", fx: fx, fy: fy }); } catch (e) {}
    await wait(500);
    box.style.pointerEvents = "";
    return !!ok;
  }

  // ---------- iframes: passo com "quadro" roda dentro do iframe ----------
  const selOf = (p) => p.esperarElemento || p.clicar || p.enter || p.extrair || (p.digitar && p.digitar[0]) || "";
  async function inFrame(p) {
    if (typeof browser === "undefined") throw new Error("quadros só funcionam dentro do app");
    const sel = selOf(p), limit = p.timeout || (p.esperarElemento ? 10000 : 8000), t0 = Date.now();
    let r = null;
    for (;;) {
      try { r = await browser.runtime.sendMessage({ cmd: "frameFind", quadro: p.quadro, sel: sel }); } catch (e) { r = null; }
      if (r && r.frameId != null) break;
      if (Date.now() - t0 >= limit) {
        if (p.opcional) return null;
        throw new Error('não achei no quadro "' + p.quadro + '": ' + (sel || "(quadro)"));
      }
      await wait(250);
    }
    let res = null;
    try { res = await browser.runtime.sendMessage({ cmd: "frameRun", frameId: r.frameId, step: p }); } catch (e) {}
    if (!res || !res.ok) { if (p.opcional) return null; throw new Error((res && res.error) || "falha no quadro"); }
    return res;
  }

  async function exec(p) {
    if (p.quadro && (p.esperarElemento || p.clicar || p.digitar || p.enter || p.extrair || p.rolar !== undefined)) {
      const fr = await inFrame(p);
      if (p.clicar && fr && fr.fx != null) {   // toque real no iframe: o quadro mandou onde tocar
        try { await browser.runtime.sendMessage({ cmd: "tap", fx: fr.fx, fy: fr.fy }); } catch (e) {}
        await wait(500);
      }
      if (p.extrair && fr) {
        const rows = (fr.items || []).map((it) => (it.c && it.c.length ? it.c : [it.t]));
        let buf = []; try { buf = JSON.parse(localStorage.getItem(ROWS)) || []; } catch (e) {}
        try { localStorage.setItem(ROWS, JSON.stringify(buf.concat(rows))); } catch (e) {}
      }
      return;
    }
    if (p.esperar) return wait(p.esperar);
    if (p.esperarElemento) {
      const el = await waitFor(p.esperarElemento, p.timeout || 10000);
      if (!el && !p.opcional) throw new Error("não apareceu: " + p.esperarElemento);
      return;
    }
    if (p.clicar) {
      const el = await waitFor(p.clicar, p.timeout || 8000);
      if (!el) { if (p.opcional) return; throw new Error("não achei: " + p.clicar); }
      if (p.real && await realTap(el)) return;
      el.scrollIntoView({ block: "center" });
      await wait(150);
      fullClick(el);
      return;
    }
    if (p.digitar) {
      const sel = p.digitar[0], text = p.digitar[1];
      const el = await waitFor(sel, p.timeout || 8000);
      if (!el) { if (p.opcional) return; throw new Error("não achei: " + sel); }
      el.focus();
      setValue(el, text);
      return;
    }
    if (p.enter) {
      const el = await waitFor(p.enter, p.timeout || 8000);
      if (!el) { if (p.opcional) return; throw new Error("não achei: " + p.enter); }
      const o = { key: "Enter", code: "Enter", keyCode: 13, which: 13, bubbles: true, cancelable: true };
      const handled = !el.dispatchEvent(new KeyboardEvent("keydown", o));
      el.dispatchEvent(new KeyboardEvent("keypress", o));
      el.dispatchEvent(new KeyboardEvent("keyup", o));
      if (!handled && el.form) { try { el.form.requestSubmit ? el.form.requestSubmit() : el.form.submit(); } catch (e) {} }
      return;
    }
    if (p.rolar !== undefined) { window.scrollBy({ top: p.rolar, behavior: "smooth" }); return; }
    if (p.ir) { location.href = p.ir; await wait(60000); return; }
    if (p.recarregar) { location.reload(); await wait(60000); return; }
    if (p.extrair) {
      const els = Array.from(document.querySelectorAll(p.extrair));
      const rows = els.map((el) => {
        const cells = Array.from(el.querySelectorAll("td,th"));
        return cells.length ? cells.map((c) => clean(c.innerText)) : [clean(el.innerText)];
      });
      let buf = []; try { buf = JSON.parse(localStorage.getItem(ROWS)) || []; } catch (e) {}
      try { localStorage.setItem(ROWS, JSON.stringify(buf.concat(rows))); } catch (e) {}
      return;
    }
    if (p.salvarCsv) {
      let buf = []; try { buf = JSON.parse(localStorage.getItem(ROWS)) || []; } catch (e) {}
      bg({ type: "csv", name: p.salvarCsv, rows: buf });
      try { localStorage.removeItem(ROWS); } catch (e) {}
      return;
    }
    if (p.avisar) { bg({ type: "notify", msg: p.avisar }); return; }
  }

  // ---------- controle ----------
  function stop(t) {
    setState({ on: false, i: 0 });
    btn.textContent = "▶ Iniciar";
    log(t || "parado");
  }

  async function run() {
    if (running) return;
    running = true;
    btn.textContent = "⏹ Parar";
    try {
      let i = getState().i || 0;
      while (getState().on && i < PASSOS.length) {
        // grava o próximo passo ANTES de executar: se a página recarregar, continua dali
        setState({ on: true, i: i + 1 });
        log("passo " + (i + 1) + "/" + PASSOS.length);
        try { await exec(PASSOS[i]); }
        catch (e) { stop("erro: " + e.message); return; }
        i++;
      }
      if (!getState().on) return;
      if (REPETIR) {
        log("repetindo em " + Math.round(PAUSA_ENTRE_VOLTAS / 1000) + "s…");
        await wait(PAUSA_ENTRE_VOLTAS);
        if (getState().on) { setState({ on: true, i: 0 }); running = false; run(); }
      } else stop("concluído");
    } finally { running = false; }
  }

  function start() { setState({ on: true, i: 0 }); run(); }

  btn.onclick = () => (getState().on ? stop("parado") : start());
  btn.textContent = getState().on ? "⏹ Parar" : "▶ Iniciar";
  if (!getState().on && INICIAR_AUTOMATICO) start();
  // um agendamento ou o app pode ligar o estado de fora: este vigia começa a execução
  setInterval(() => { if (!running && getState().on) run(); }, 1000);
})();

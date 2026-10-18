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
//   { "digitar": ["input[name=q]", "texto"] }        escreve num campo (ou escolhe numa lista)
//   { "enter": "input[name=q]" }                     aperta Enter no campo
//   { "rolar": 600 }                                 rola a página (pixels)
//   { "ir": "https://site.com/pagina" }              abre outra página
//   { "recarregar": true }                           recarrega a página
//   { "extrair": "tr.linha" }                        guarda o texto de cada elemento (linhas de tabela viram colunas)
//   { "salvarCsv": "dados.csv" }                     salva o que foi extraído na pasta Download
//   { "avisar": "terminei" }                         manda uma notificação
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

  async function exec(p) {
    if (p.esperar) return wait(p.esperar);
    if (p.esperarElemento) {
      const el = await waitFor(p.esperarElemento, p.timeout || 10000);
      if (!el && !p.opcional) throw new Error("não apareceu: " + p.esperarElemento);
      return;
    }
    if (p.clicar) {
      const el = await waitFor(p.clicar, p.timeout || 8000);
      if (!el) { if (p.opcional) return; throw new Error("não achei: " + p.clicar); }
      el.scrollIntoView({ block: "center" });
      await wait(150);
      el.click();
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

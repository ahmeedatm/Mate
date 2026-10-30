import { DECK_CSS, DECK_HTML, DECK_JS } from "./local-config-deck.ts";

/**
 * Panneau de configuration de la page locale (local-page.ts) : une `<section id="pf-…" data-title="…">` par partie, qui
 * réutilise les classes de la page (card group, row, ghost, danger, interrupteurs, tuiles .cx, modale .cxdialog), `.pf-`
 * seulement pour ce qui est propre à ces sections, et les tokens de web-style.ts. La grille de tuiles est refaite en
 * `.pf-tiles` / `.pf-slots` : e2e/local-page.spec.ts compte les `.connectors`, réservés aux connecteurs.
 * Profils et barres de la tablette (local-config-profiles.ts), touches, plugins, applis et sites de l'ordinateur
 * (local-config-deck.ts). Le JS tient dans un template literal : pas de backtick, de `${` ni d'antislash.
 */

/** Ce que les deux parties partagent : listes déroulantes habillées, choix à boutons, lignes de modale, petites icônes. */
const COMMON_CSS = `
  [id^="pf-"] button:disabled { opacity: .4; cursor: default; }
  [id^="pf-"] button:disabled:active { transform: none; }
  [id^="pf-"] input[type=checkbox]:disabled { opacity: .4; cursor: default; }
  .pf-contents { display: contents; }
  .label + .row::before, .label + div > .row:first-child::before, .group > .row[hidden] + .row::before { display: none; }
  .pf-h3 { font: 500 16px/1.25 Outfit, sans-serif; margin: 0 0 10px 4px; }
  .pf-h3:not(:first-of-type) { margin-top: 28px; }
  .pf-h { margin: 0 0 8px; font: 500 14px/1.3 Outfit, sans-serif; color: var(--ink-2); }
  .pf-h:not(:first-child) { margin-top: 22px; }
  .pf-msg { flex: 1 1 160px; min-width: 0; margin: 0; font-size: 14px; color: var(--ink-2); }
  .pf-msg[data-kind=ok] { color: var(--ok); }
  .pf-msg[data-kind=ko] { color: var(--ko); }
  p.pf-msg { padding: 0 4px; margin-top: 12px; }
  p.pf-msg:empty { display: none; }
  .pf-sel { position: relative; flex: 1 1 180px; min-width: 0; }
  .pf-sel select { appearance: none; -webkit-appearance: none; width: 100%; height: 46px; padding: 0 44px 0 20px; border: 0; border-radius: 23px; cursor: pointer;
    box-shadow: inset 0 0 0 1px var(--line); background: var(--bg); color: var(--ink); font: 400 15px Outfit, sans-serif; text-overflow: ellipsis; }
  .pf-sel select:focus { outline: 0; box-shadow: inset 0 0 0 2px var(--ink); }
  .pf-sel select:disabled { opacity: .4; cursor: default; }
  .pf-sel::after { content: ""; position: absolute; right: 20px; top: 50%; width: 7px; height: 7px; border: solid var(--ink-2); border-width: 0 1.5px 1.5px 0;
    transform: translateY(-70%) rotate(45deg); pointer-events: none; }
  .row > input[type=text] { flex: 1 1 200px; width: auto; min-width: 0; }
  .pf-choices { display: flex; flex-wrap: wrap; gap: 8px; }
  .pf-choices button { min-height: 44px; padding: 0 16px; border-radius: 14px; background: var(--tile-high); color: var(--ink); font-weight: 400; }
  .pf-choices button[aria-pressed=true] { background: var(--now-soft); box-shadow: inset 0 0 0 1.5px var(--now); font-weight: 500; }
  button.pf-icon { width: 40px; padding: 0; flex: none; }
  .pf-list .row { padding: 8px 0; min-height: 48px; gap: 8px; }
  .pf-list .row + .row { border-top: 1px solid var(--line); }
  .pf-field { display: block; margin-top: 14px; }
  .pf-fl { display: block; margin: 0 0 6px 4px; font-size: 14px; color: var(--ink-2); }
  .pf-foot { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 22px; }
  .pf-foot .pf-msg { order: -1; flex-basis: 100%; }
  .pf-foot .pf-msg:empty { display: none; }
  @media (max-width: 820px) { .row .pf-sel { flex-basis: 100%; } }
`;

const COMMON_JS = `
  const PF_PATHS = {
    up: "M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z",
    down: "M20 12l-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z",
  };
  /** Icône Material en SVG, sans innerHTML. */
  function pfSvg(path, cls) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("class", cls || "i");
    svg.setAttribute("aria-hidden", "true");
    const p = document.createElementNS("http://www.w3.org/2000/svg", "path");
    p.setAttribute("d", path);
    svg.append(p);
    return svg;
  }
  /** Bouton rond à icône : le libellé passe par aria-label. */
  function pfIconButton(path, label, onclick) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "ghost pf-icon";
    b.setAttribute("aria-label", label);
    b.append(pfSvg(path));
    b.onclick = onclick;
    return b;
  }
  /** Message de retour : data-kind colore en vert (ok) ou en rouge (ko). */
  function pfSay(el, text, kind) {
    el.textContent = text || "";
    if (kind) el.dataset.kind = kind; else delete el.dataset.kind;
  }
  /** Modale : croix, clic à côté (Échap est natif). */
  function pfWire(dialog, onclose) {
    dialog.querySelector(".close").onclick = () => dialog.close();
    dialog.addEventListener("click", (e) => { if (e.target === dialog) dialog.close(); });
    if (onclose) dialog.addEventListener("close", onclose);
  }
`;

export const CONFIG_CSS = COMMON_CSS + DECK_CSS;
export const CONFIG_HTML = DECK_HTML;
export const CONFIG_JS = COMMON_JS + DECK_JS;

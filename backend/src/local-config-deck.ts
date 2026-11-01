/**
 * Panneau de configuration, partie ordinateur : touches et macros, applis et sites du Deck (les plugins sont dans la section Connecteurs de local-page.ts). Branché par local-config.ts.
 * Routes : src/routes/local-config.ts. Le JS client tient dans un template literal : pas de backtick, de `${` ni d'antislash.
 * Aucun innerHTML : les textes viennent du compagnon.
 */
const CLOSE =
  '<svg class="i" viewBox="0 0 24 24" aria-hidden="true"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>';

export const DECK_CSS = `
  .pf-pages { margin-bottom: 16px; }
  .pf-slots .cx { min-height: 104px; }
  .pf-slots .cx .logo { margin-bottom: 2px; }
  .pf-slots .cx.pf-free { background: transparent; box-shadow: inset 0 0 0 1px var(--line); color: var(--ink-2); }
  .pf-slots .cx.pf-free .logo { fill: var(--ink-3); }
  .pf-slots .cx.pf-free:hover { box-shadow: inset 0 0 0 1px var(--ink-3); }
  .pf-slots .cx[aria-pressed=true] { box-shadow: 0 0 0 2px var(--now); }
  .pf-slots .dot { box-shadow: inset 0 0 0 1px rgba(0, 0, 0, .18); }
  .pf-keydialog { width: min(640px, calc(100vw - 32px)); }
  .pf-keydialog .pf-where { margin: -6px 0 0; }
  .pf-steps { list-style: none; counter-reset: s; margin: 0; padding: 0; }
  .pf-steps li { counter-increment: s; position: relative; padding: 16px 0 18px 38px; border-top: 1px solid var(--line); }
  .pf-steps li::before { content: counter(s); position: absolute; left: 0; top: 16px; font: 300 24px/40px Figtree, sans-serif; color: var(--ink); }
  .pf-stephead { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
  .pf-stephead .pf-sel { flex: 1 1 200px; }
  .pf-steps .pf-field:first-of-type { margin-top: 12px; }
  @media (max-width: 820px) { .pf-stephead .pf-sel { flex-basis: 100%; } }
  .pf-keydialog textarea { width: 100%; min-height: 96px; padding: 12px 20px; border: 0; border-radius: 20px; resize: vertical; box-shadow: inset 0 0 0 1px var(--line);
    background: var(--bg); color: var(--ink); font: 400 15px/1.5 Figtree, sans-serif; }
  .pf-keydialog textarea:focus { outline: 0; box-shadow: inset 0 0 0 2px var(--ink); }
  .pf-keydialog input[type=color] { appearance: none; -webkit-appearance: none; width: 40px; height: 40px; padding: 0; border: 0; border-radius: 20px; background: none; cursor: pointer; flex: none; }
  .pf-keydialog input[type=color]::-webkit-color-swatch-wrapper { padding: 0; }
  .pf-keydialog input[type=color]::-webkit-color-swatch { border: 0; border-radius: 20px; box-shadow: inset 0 0 0 1px var(--line); }
  .pf-keydialog input[type=color]::-moz-color-swatch { border: 0; border-radius: 20px; }
  .pf-keydialog input[type=color]:disabled { opacity: .4; cursor: default; }
  .pf-sub-row { color: var(--ink-2); font-size: 14px; display: block; overflow: hidden; text-overflow: ellipsis; }
`;

export const DECK_HTML = `
  <section id="pf-touches" data-title="Touches" data-icon="keys">
    <div class="sechead"><h2>Touches</h2><span class="dim">Les touches de la page Perso et des pages du Deck. Une touche à plusieurs étapes est une macro.</span></div>
    <div class="pf-choices pf-pages" id="pf-deck-tabs" role="group" aria-label="Pages de touches"></div>
    <div class="pf-slots" id="pf-deck-grid"></div>
    <p class="pf-msg" id="pf-deck-keys-msg" role="status"></p>
    <dialog class="cxdialog pf-keydialog" id="pf-key-dialog" aria-labelledby="pf-key-title">
      <div class="cxbody">
        <header class="cxhead"><h2 id="pf-key-title"></h2><button type="button" class="close" aria-label="Fermer">${CLOSE}</button></header>
        <div id="pf-deck-editor"></div>
      </div>
    </dialog>
  </section>
  <section id="pf-applis" data-title="Applis et sites" data-icon="apps">
    <div class="sechead"><h2>Applis et sites</h2><span class="dim">Ce que le Deck lance, et les sites de la page Média.</span></div>
    <h3 class="pf-h3">Applis du Deck</h3>
    <div class="card group">
      <div id="pf-deck-apps"></div>
      <div class="row">
        <div class="pf-sel"><select id="pf-deck-app-pick" aria-label="Appli installée à ajouter"></select></div>
        <button type="button" class="ghost" id="pf-deck-app-add">Ajouter</button>
        <button type="button" class="ghost" id="pf-deck-app-reset">Liste de départ</button>
      </div>
      <div class="dim note pf-msg" id="pf-deck-apps-msg" role="status"></div>
    </div>
    <h3 class="pf-h3">Sites de la page Média</h3>
    <div class="card group">
      <div id="pf-deck-links"></div>
      <div class="row">
        <div class="pf-sel"><select id="pf-deck-link-pick" aria-label="Site proposé à ajouter"></select></div>
        <button type="button" class="ghost" id="pf-deck-link-add">Ajouter</button>
      </div>
      <div class="row">
        <input id="pf-deck-link-url" type="text" placeholder="Une autre adresse (netflix.com)" aria-label="Adresse du site" maxlength="2000" autocomplete="off">
        <input id="pf-deck-link-label" type="text" placeholder="Nom (facultatif)" aria-label="Nom du site" maxlength="40" autocomplete="off">
        <button type="button" class="ghost" id="pf-deck-link-new">Ajouter</button>
      </div>
      <div class="dim note pf-msg" id="pf-deck-links-msg" role="status"></div>
    </div>
  </section>
`;

export const DECK_JS = `
  const pfDeckStepTypes = [
    ["app", "Ouvrir une appli"], ["url", "Ouvrir une adresse"], ["keys", "Raccourci clavier"], ["shortcut", "Raccourci Apple"],
    ["media", "Lecture multimédia"], ["system", "Commande système"], ["text", "Coller un texte"], ["wait", "Attendre"],
    ["window", "Placer une fenêtre"],
  ];
  const pfDeckMedia = [["play-pause", "Lecture ou pause"], ["next", "Suivant"], ["previous", "Précédent"]];
  const pfDeckSystemNames = {
    "sleep-display": "Éteindre l'écran", "toggle-dark-mode": "Mode sombre", "screenshot-clipboard": "Capture d'écran",
    lock: "Verrouiller", "mute-mic": "Couper ou rétablir le micro", dnd: "Ne pas déranger",
  };
  const pfDeckLayouts = [["keep", "Même place"], ["left", "Moitié gauche"], ["right", "Moitié droite"], ["max", "Plein écran"]];
  let pfDeckData = null;
  let pfDeckPage = 0;
  let pfDeckSlot = null;
  let pfDeckDraft = null;
  const pfDeckDialog = document.getElementById("pf-key-dialog");

  function pfDeckEl(tag, props, kids) {
    const el = document.createElement(tag);
    (kids || []).forEach((k) => el.append(k));
    const p = props || {};
    for (const name in p) {
      if (name === "text") el.textContent = p[name];
      else if (name === "className" || name === "value" || name === "checked" || name === "disabled" || name === "hidden") el[name] = p[name];
      else if (name.slice(0, 2) === "on") el[name] = p[name];
      else el.setAttribute(name, p[name]);
    }
    return el;
  }

  function pfDeckSay(id, text, kind) { pfSay(document.getElementById(id), text, kind); }

  async function pfDeckApi(method, path, body) {
    try {
      const init = { method: method };
      if (body !== undefined) {
        init.headers = { "content-type": "application/json" };
        init.body = JSON.stringify(body);
      }
      const res = await fetch("/local/config/" + path, init);
      const json = await res.json().catch(() => ({}));
      return { ok: res.ok, json: json, error: json.error || "Erreur " + res.status };
    } catch (e) {
      return { ok: false, json: {}, error: "Le compagnon ne répond pas" };
    }
  }

  // --- Touches

  function pfDeckPages() {
    const pages = [{ title: "Perso", keys: pfDeckData.perso.keys, path: (n) => "keys/" + n }];
    pfDeckData.pages.forEach((p, i) => pages.push({ title: p.title, keys: p.keys, path: (n) => "pages/" + i + "/keys/" + n }));
    return pages;
  }

  function pfDeckNewStep(type) {
    switch (type) {
      case "app": return { type: type, name: "" };
      case "url": return { type: type, url: "" };
      case "keys": return { type: type, keys: "", app: "" };
      case "shortcut": return { type: type, name: "" };
      case "media": return { type: type, command: "play-pause" };
      case "system": return { type: type, command: pfDeckData.systemCommands[0] };
      case "text": return { type: type, text: "" };
      case "wait": return { type: type, ms: 500 };
      default: return { type: "window", app: "", display: "", layout: "keep" };
    }
  }

  function pfDeckCleanStep(s) {
    switch (s.type) {
      case "app": case "shortcut": return { type: s.type, name: s.name.trim() };
      case "url": return { type: s.type, url: s.url.trim() };
      case "keys": return s.app.trim() ? { type: s.type, keys: s.keys.trim(), app: s.app.trim() } : { type: s.type, keys: s.keys.trim() };
      case "media": case "system": return { type: s.type, command: s.command };
      case "text": return { type: s.type, text: s.text };
      case "wait": return { type: s.type, ms: Math.min(10000, Math.max(0, parseInt(s.ms, 10) || 0)) };
      default: {
        const out = { type: "window", app: s.app.trim(), layout: s.layout };
        if (s.display) out.display = s.display;
        return out;
      }
    }
  }

  function pfDeckRenderTabs() {
    const tabs = document.getElementById("pf-deck-tabs");
    tabs.replaceChildren();
    pfDeckPages().forEach((page, i) => {
      tabs.append(pfDeckEl("button", {
        type: "button", text: page.title, "aria-pressed": String(i === pfDeckPage),
        onclick: () => { pfDeckPage = i; pfDeckSlot = null; pfDeckRenderTabs(); pfDeckRenderGrid(); pfDeckSay("pf-deck-keys-msg", ""); },
      }));
    });
  }

  // Une tuile par emplacement : touche posée (nom, nombre d'étapes) ou libre.
  function pfDeckRenderGrid() {
    const grid = document.getElementById("pf-deck-grid");
    grid.replaceChildren();
    pfDeckPages()[pfDeckPage].keys.forEach((key, slot) => {
      const dot = pfDeckEl("span", { className: key ? "dot on" : "dot" });
      if (key && key.color) dot.style.background = key.color;
      const state = key ? (key.steps.length > 1 ? (key.cycle ? "Cycle, " : "Macro, ") + key.steps.length + " étapes" : "1 étape") : "Libre";
      grid.append(pfDeckEl("button", {
        type: "button", className: key ? "cx" : "cx pf-free", "aria-pressed": String(slot === pfDeckSlot),
        "aria-label": "Touche " + (slot + 1) + (key ? " : " + key.label : " : libre"),
        onclick: () => pfDeckOpen(slot),
      }, [
        pfSvg(key ? ICONS.keys : ICONS.add, "logo ink"),
        pfDeckEl("b", { text: key ? key.label : "Touche " + (slot + 1) }),
        pfDeckEl("small", {}, [dot, document.createTextNode(state)]),
      ]));
    });
  }

  function pfDeckOpen(slot) {
    const key = pfDeckPages()[pfDeckPage].keys[slot];
    pfDeckSlot = slot;
    pfDeckDraft = key
      ? { label: key.label, symbol: key.symbol || "", glyph: key.glyph || "", color: key.color || "", cycle: key.cycle === true, hold: key.hold, steps: key.steps.map((s) => Object.assign(pfDeckNewStep(s.type), s)) }
      : { label: "", symbol: "", glyph: "", color: "", cycle: false, steps: [] };
    pfDeckSay("pf-deck-keys-msg", "");
    pfDeckRenderGrid();
    pfDeckRenderEditor();
    if (!pfDeckDialog.open) pfDeckDialog.showModal();
    document.getElementById("pf-deck-label").focus();
  }

  pfWire(pfDeckDialog, () => { pfDeckSlot = null; pfDeckRenderGrid(); });

  function pfDeckField(text, control) {
    return pfDeckEl("label", { className: "pf-field" }, [pfDeckEl("span", { className: "pf-fl", text: text }), control]);
  }

  function pfDeckSelect(options, value, onchange, label) {
    const select = pfDeckEl("select", { "aria-label": label || "" }, options.map((o) => pfDeckEl("option", { value: o[0], text: o[1] })));
    select.value = value;
    select.onchange = () => onchange(select.value);
    return select;
  }

  const pfDeckWrapSel = (select) => pfDeckEl("div", { className: "pf-sel" }, [select]);

  function pfDeckText(value, onchange, attrs) {
    const input = pfDeckEl("input", Object.assign({ type: "text", value: value, autocomplete: "off" }, attrs || {}));
    input.oninput = () => onchange(input.value);
    return input;
  }

  function pfDeckStepFields(step) {
    const catalog = pfDeckData.catalog;
    switch (step.type) {
      case "app":
        return [pfDeckField("Appli", pfDeckText(step.name, (v) => { step.name = v; }, { list: "pf-deck-catalog-apps", maxlength: 200, placeholder: "Notes" }))];
      case "url":
        return [pfDeckField("Adresse", pfDeckText(step.url, (v) => { step.url = v; }, { maxlength: 2000, placeholder: "https://exemple.fr" }))];
      case "keys":
        return [
          pfDeckField("Raccourci", pfDeckText(step.keys, (v) => { step.keys = v; }, { maxlength: 60, placeholder: "cmd+shift+4" })),
          pfDeckField("Dans l'appli (facultatif)", pfDeckText(step.app, (v) => { step.app = v; }, { list: "pf-deck-catalog-apps", maxlength: 200 })),
        ];
      case "shortcut":
        return [pfDeckField("Raccourci Apple", pfDeckText(step.name, (v) => { step.name = v; }, { list: "pf-deck-catalog-shortcuts", maxlength: 200 }))];
      case "media":
        return [pfDeckField("Commande", pfDeckWrapSel(pfDeckSelect(pfDeckMedia, step.command, (v) => { step.command = v; })))];
      case "system":
        return [pfDeckField("Commande", pfDeckWrapSel(pfDeckSelect(pfDeckData.systemCommands.map((c) => [c, pfDeckSystemNames[c] || c]), step.command, (v) => { step.command = v; })))];
      case "text": {
        const area = pfDeckEl("textarea", { maxlength: 4000, value: step.text });
        area.oninput = () => { step.text = area.value; };
        return [pfDeckField("Texte collé dans l'appli au premier plan", area)];
      }
      case "wait":
        return [pfDeckField("Durée en millisecondes (0 à 10000)", pfDeckText(String(step.ms), (v) => { step.ms = v; }, { inputmode: "numeric" }))];
      default:
        return [
          pfDeckField("Appli", pfDeckText(step.app, (v) => { step.app = v; }, { list: "pf-deck-catalog-apps", maxlength: 200 })),
          pfDeckField("Écran", pfDeckWrapSel(pfDeckSelect([["", "Celui de la fenêtre"]].concat(catalog.displays.map((d) => [d, d])), step.display, (v) => { step.display = v; }))),
          pfDeckField("Place", pfDeckWrapSel(pfDeckSelect(pfDeckLayouts, step.layout, (v) => { step.layout = v; }))),
        ];
    }
  }

  function pfDeckMove(i, by) {
    const steps = pfDeckDraft.steps;
    const moved = steps.splice(i, 1)[0];
    steps.splice(i + by, 0, moved);
    pfDeckRenderEditor();
  }

  // Formulaire de la touche, dans la modale : libellé, symbole, couleur, étapes numérotées, enregistrer.
  function pfDeckRenderEditor() {
    const box = document.getElementById("pf-deck-editor");
    box.replaceChildren();
    if (pfDeckSlot === null) return;
    const d = pfDeckDraft;
    const page = pfDeckPages()[pfDeckPage];
    const saved = page.keys[pfDeckSlot];
    document.getElementById("pf-key-title").textContent = saved ? "Modifier la touche" : "Nouvelle touche";
    box.append(pfDeckEl("p", { className: "dim pf-where", text: page.title + ", touche " + (pfDeckSlot + 1) }));
    box.append(pfDeckEl("datalist", { id: "pf-deck-catalog-apps" }, pfDeckData.catalog.apps.map((a) => pfDeckEl("option", { value: a }))));
    box.append(pfDeckEl("datalist", { id: "pf-deck-catalog-shortcuts" }, pfDeckData.catalog.shortcuts.map((a) => pfDeckEl("option", { value: a }))));

    box.append(pfDeckField("Libellé", pfDeckText(d.label, (v) => { d.label = v; }, { id: "pf-deck-label", maxlength: 40 })));
    box.append(pfDeckField("Symbole", pfDeckWrapSel(pfDeckSelect([["", "Aucun"]].concat(pfDeckData.symbols.map((s) => [s, s])), d.symbol, (v) => { d.symbol = v; }))));

    const useColor = pfDeckEl("input", { type: "checkbox", checked: d.color !== "", id: "pf-deck-use-color" });
    const color = pfDeckEl("input", { type: "color", value: d.color || "#B8F34A", "aria-label": "Couleur de la touche", disabled: d.color === "" });
    useColor.onchange = () => { color.disabled = !useColor.checked; d.color = useColor.checked ? color.value : ""; };
    color.oninput = () => { d.color = color.value; };
    box.append(pfDeckEl("div", { className: "pf-list" }, [
      pfDeckEl("div", { className: "row" }, [pfDeckEl("label", { for: "pf-deck-use-color", text: "Couleur de la touche" }), color, useColor]),
    ]));

    box.append(pfDeckEl("p", { className: "pf-h", text: "Étapes" }));
    if (d.steps.length === 0) box.append(pfDeckEl("p", { className: "dim", text: "Aucune étape. Avec plusieurs étapes, la touche devient une macro." }));
    const list = pfDeckEl("ol", { className: "pf-steps" });
    d.steps.forEach((step, i) => {
      const typeSelect = pfDeckSelect(pfDeckStepTypes, step.type, (v) => { d.steps[i] = pfDeckNewStep(v); pfDeckRenderEditor(); }, "Type de l'étape " + (i + 1));
      const up = pfIconButton(PF_PATHS.up, "Monter l'étape " + (i + 1), () => pfDeckMove(i, -1));
      up.disabled = i === 0;
      const down = pfIconButton(PF_PATHS.down, "Descendre l'étape " + (i + 1), () => pfDeckMove(i, 1));
      down.disabled = i === d.steps.length - 1;
      const del = pfDeckEl("button", { type: "button", className: "ghost", text: "Retirer", "aria-label": "Retirer l'étape " + (i + 1), onclick: () => { d.steps.splice(i, 1); pfDeckRenderEditor(); } });
      const head = pfDeckEl("div", { className: "pf-stephead" }, [pfDeckWrapSel(typeSelect), up, down, del]);
      list.append(pfDeckEl("li", { "data-step": String(i) }, [head].concat(pfDeckStepFields(step))));
    });
    box.append(list);

    const addType = pfDeckSelect(pfDeckStepTypes, "app", () => {}, "Type de la nouvelle étape");
    addType.id = "pf-deck-add-type";
    box.append(pfDeckEl("div", { className: "pf-list" }, [
      pfDeckEl("div", { className: "row" }, [
        pfDeckWrapSel(addType),
        pfDeckEl("button", { type: "button", className: "ghost", id: "pf-deck-add-step", text: "Ajouter une étape", onclick: () => { d.steps.push(pfDeckNewStep(addType.value)); pfDeckRenderEditor(); } }),
      ]),
    ]));

    box.append(pfDeckEl("div", { className: "pf-foot" }, [
      pfDeckEl("p", { className: "pf-msg", id: "pf-key-msg", role: "status" }),
      pfDeckEl("button", { type: "button", className: "danger", id: "pf-deck-clear", text: "Vider la touche", hidden: !saved, onclick: pfDeckClearKey }),
      pfDeckEl("button", { type: "button", id: "pf-deck-save", text: "Enregistrer", onclick: pfDeckSaveKey }),
    ]));
  }

  async function pfDeckSaveKey() {
    const d = pfDeckDraft;
    const say = (text) => pfDeckSay("pf-key-msg", text, "ko");
    if (!d.label.trim()) return say("Donne un libellé à la touche.");
    if (d.steps.length === 0) return say("Ajoute au moins une étape.");
    const body = { label: d.label.trim(), steps: d.steps.map(pfDeckCleanStep) };
    if (d.symbol) body.symbol = d.symbol;
    if (d.glyph) body.glyph = d.glyph;
    if (d.color) body.color = d.color;
    // Le cycle se règle sur la tablette ; ici on le garde tel quel pour ne pas transformer la touche en macro.
    if (d.cycle) body.cycle = true;
    // Pareil pour la seconde action (appui long) : elle se règle sur la tablette.
    if (d.hold) body.hold = d.hold;
    const r = await pfDeckApi("PUT", pfDeckPages()[pfDeckPage].path(pfDeckSlot), body);
    if (!r.ok) return say(r.error);
    pfDeckDialog.close();
    await pfDeckLoadKeys();
    pfDeckSay("pf-deck-keys-msg", "Touche enregistrée.", "ok");
  }

  async function pfDeckClearKey() {
    const r = await pfDeckApi("DELETE", pfDeckPages()[pfDeckPage].path(pfDeckSlot));
    if (!r.ok) return pfDeckSay("pf-key-msg", r.error, "ko");
    pfDeckDialog.close();
    await pfDeckLoadKeys();
    pfDeckSay("pf-deck-keys-msg", "Touche vidée.", "ok");
  }

  async function pfDeckLoadKeys() {
    const r = await pfDeckApi("GET", "keys");
    if (!r.ok) return pfDeckSay("pf-deck-keys-msg", r.error, "ko");
    pfDeckData = r.json;
    if (pfDeckPage >= pfDeckPages().length) { pfDeckPage = 0; pfDeckSlot = null; }
    pfDeckRenderTabs();
    pfDeckRenderGrid();
  }

  // --- Applis et sites du Deck

  function pfDeckRemovable(items, empty, path, msgId, reload, subtitle) {
    return (box) => {
      box.replaceChildren();
      if (items.length === 0) return box.append(pfDeckEl("div", { className: "row" }, [pfDeckEl("span", { className: "grow dim", text: empty })]));
      items.forEach((it) => {
        const sub = subtitle(it);
        const name = pfDeckEl("span", { className: "grow" }, [document.createTextNode(it.label)]);
        if (sub) name.append(pfDeckEl("span", { className: "pf-sub-row", text: sub }));
        box.append(pfDeckEl("div", { className: "row" }, [
          name,
          pfDeckEl("button", {
            type: "button", className: "ghost", text: "Retirer", "aria-label": "Retirer " + it.label,
            onclick: async () => {
              const r = await pfDeckApi("DELETE", path + "/" + it.id);
              if (!r.ok) return pfDeckSay(msgId, r.error, "ko");
              await reload();
              pfDeckSay(msgId, it.label + " retiré.", "ok");
            },
          }),
        ]));
      });
    };
  }

  function pfDeckFill(select, options, placeholder) {
    select.replaceChildren(pfDeckEl("option", { value: "", text: placeholder }));
    options.forEach((o) => select.append(pfDeckEl("option", { value: o[0], text: o[1] })));
  }

  async function pfDeckLoadApps() {
    const r = await pfDeckApi("GET", "apps");
    if (!r.ok) return pfDeckSay("pf-deck-apps-msg", r.error, "ko");
    pfDeckRemovable(r.json.apps, "Aucune appli dans le Deck.", "apps", "pf-deck-apps-msg", pfDeckLoadApps, () => "")(document.getElementById("pf-deck-apps"));
    const have = new Set(r.json.apps.map((a) => a.name));
    pfDeckFill(document.getElementById("pf-deck-app-pick"), r.json.installed.filter((n) => !have.has(n)).map((n) => [n, n]), "Ajouter une appli installée…");
  }

  async function pfDeckLoadLinks() {
    const r = await pfDeckApi("GET", "links");
    if (!r.ok) return pfDeckSay("pf-deck-links-msg", r.error, "ko");
    pfDeckRemovable(r.json.links, "Aucun site : la page Média n'a que la lecture en cours.", "links", "pf-deck-links-msg", pfDeckLoadLinks, (l) => l.url)(document.getElementById("pf-deck-links"));
    const have = new Set(r.json.links.map((l) => l.url));
    pfDeckFill(document.getElementById("pf-deck-link-pick"), r.json.suggestions.filter((s) => !have.has(s.url)).map((s) => [s.url + "|" + s.label, s.label]), "Ajouter un site proposé…");
  }

  async function pfDeckAddApp() {
    const name = document.getElementById("pf-deck-app-pick").value;
    if (!name) return pfDeckSay("pf-deck-apps-msg", "Choisis une appli dans la liste.", "ko");
    const r = await pfDeckApi("POST", "apps", { name: name });
    if (!r.ok) return pfDeckSay("pf-deck-apps-msg", r.error, "ko");
    await pfDeckLoadApps();
    pfDeckSay("pf-deck-apps-msg", name + " ajoutée.", "ok");
  }

  async function pfDeckResetApps() {
    const r = await pfDeckApi("DELETE", "apps");
    if (!r.ok) return pfDeckSay("pf-deck-apps-msg", r.error, "ko");
    await pfDeckLoadApps();
    pfDeckSay("pf-deck-apps-msg", "Liste de départ rétablie.", "ok");
  }

  async function pfDeckAddLink(url, label) {
    const body = { url: url };
    if (label) body.label = label;
    const r = await pfDeckApi("POST", "links", body);
    if (!r.ok) return pfDeckSay("pf-deck-links-msg", r.error, "ko");
    await pfDeckLoadLinks();
    pfDeckSay("pf-deck-links-msg", "Site ajouté.", "ok");
    return true;
  }

  document.getElementById("pf-deck-app-add").onclick = pfDeckAddApp;
  document.getElementById("pf-deck-app-reset").onclick = pfDeckResetApps;
  document.getElementById("pf-deck-link-add").onclick = async () => {
    const pick = document.getElementById("pf-deck-link-pick").value;
    if (!pick) return pfDeckSay("pf-deck-links-msg", "Choisis un site dans la liste.", "ko");
    const cut = pick.indexOf("|");
    await pfDeckAddLink(pick.slice(0, cut), pick.slice(cut + 1));
  };
  document.getElementById("pf-deck-link-new").onclick = async () => {
    const url = document.getElementById("pf-deck-link-url").value.trim();
    if (!url) return pfDeckSay("pf-deck-links-msg", "Saisis l'adresse du site.", "ko");
    if (await pfDeckAddLink(url, document.getElementById("pf-deck-link-label").value.trim())) {
      document.getElementById("pf-deck-link-url").value = "";
      document.getElementById("pf-deck-link-label").value = "";
    }
  };

  pfDeckLoadKeys();
  pfDeckLoadApps();
  pfDeckLoadLinks();
`;

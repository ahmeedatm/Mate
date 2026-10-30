/**
 * Panneau de configuration, partie tablette : profils (pages de widgets de l'Accueil) et barres (bandeau du haut, barre
 * de gauche). La tablette reste la source de vérité : la page lit son état par GET /local/tablet/layout, l'utilisateur
 * prépare ses changements, puis « Envoyer à la tablette » les lui donne (PUT, routes/local-profiles.ts) ; rien n'est
 * enregistré tout seul. Sections masquées tant qu'aucune disposition n'a été lue. Branché par local-config.ts.
 * Le JS client tient dans un template literal : pas de backtick, de `${` ni d'antislash. Aucun innerHTML : les textes
 * viennent de la tablette et du deck.
 */
const CLOSE =
  '<svg class="i" viewBox="0 0 24 24" aria-hidden="true"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>';

export const PROFILES_CSS = `
  .pf-tiles, .pf-slots { display: grid; grid-template-columns: repeat(auto-fill, minmax(156px, 1fr)); gap: 12px; }
  .pf-tiles { margin-bottom: 20px; }
  .pf-tiles .cx[disabled] { color: var(--ink); }
  .pf-tiles .cx[disabled] .logo { filter: none; opacity: 1; }
  .pf-new { background: transparent; box-shadow: inset 0 0 0 1px var(--line); }
  .pf-new:hover { box-shadow: inset 0 0 0 1px var(--ink-3); }
  .pf-new .logo { fill: var(--ink-2); }
  .pf-hint { display: block; color: var(--ink-2); font-size: 14px; }
  .pf-ro .dot { margin-right: 2px; }
  .pf-screen .pf-cells { display: grid; grid-template-columns: repeat(var(--cols, 4), 1fr); grid-template-rows: repeat(var(--rows, 4), 1fr); gap: 1.1cqw;
    padding: 3.4cqw 3.6cqw 3.4cqw 1cqw; min-height: 0; }
  .pf-cells button { min-height: 0; width: 100%; height: 100%; padding: 1cqw 1.4cqw; border-radius: 1.6cqw; display: flex; flex-direction: column; align-items: flex-start;
    justify-content: space-between; gap: .4cqw; text-align: left; white-space: normal; overflow: hidden; background: var(--t-tile); color: var(--t-ink);
    font: 500 2.5cqw/1.2 Outfit, system-ui, sans-serif; transition: box-shadow 160ms, transform 160ms cubic-bezier(.2, .8, .2, 1); }
  .pf-cells button span { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .pf-cells button small { font-weight: 400; font-size: 2.1cqw; color: var(--t-ink2); }
  .pf-cells button[aria-pressed=true] { box-shadow: 0 0 0 .4cqw var(--t-now); }
  .pf-cells button.pf-free { background: transparent; border: 1px dashed var(--t-line); }
  .pf-cells button.pf-free:hover { background: var(--t-tile); }
  .pf-board .card .row .grow b { font-weight: 500; }
  @media (max-width: 560px) { .pf-screen { aspect-ratio: 4 / 3; } }
`;

export const PROFILES_HTML = `
  <section id="pf-profiles" data-title="Profils" data-icon="grid" hidden>
    <div class="sechead"><h2>Profils de la tablette</h2><span class="dim">Chaque profil est une page de widgets de l'Accueil. Prépare tes changements, puis envoie-les.</span></div>
    <div class="pf-tiles" role="group" aria-label="Profils de la tablette">
      <div class="pf-contents" id="pf-chips"></div>
      <button type="button" class="cx pf-new" id="pf-new"><svg class="logo" viewBox="0 0 24 24" aria-hidden="true"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/></svg><b>Nouveau profil</b><small>Une page de plus</small></button>
    </div>
    <div class="twin-grid pf-board">
      <figure class="twin">
        <div class="device"><div class="screen pf-screen" id="pf-screen">
          <div class="pf-cells" id="pf-grid" role="group" aria-label="Grille du profil"></div>
        </div></div>
        <figcaption><span id="pf-cap"></span><span id="pf-dim"></span></figcaption>
      </figure>
      <div class="card group">
        <div class="row pf-ro" id="pf-banner" hidden><span class="dot"></span><span class="grow dim" id="pf-banner-text"></span></div>
        <div class="row"><span class="grow"><b id="pf-cur"></b><span class="pf-hint" id="pf-cur-meta"></span></span><button type="button" class="ghost" id="pf-rename">Renommer</button></div>
        <div class="row"><span class="grow">Changer l'ordre</span><button type="button" class="ghost" id="pf-up">Monter</button><button type="button" class="ghost" id="pf-down">Descendre</button></div>
        <div class="row"><span class="grow">Supprimer ce profil<span class="pf-hint" id="pf-del-hint"></span></span><button type="button" class="danger" id="pf-del">Supprimer</button></div>
        <div class="row"><span class="grow" id="pf-sel-text"></span><button type="button" class="ghost" id="pf-sel-edit" hidden>Régler le widget</button></div>
        <div class="row"><div class="pf-sel"><select id="pf-add" aria-label="Widget à ajouter"></select></div><button type="button" class="ghost" id="pf-add-go">Ajouter</button></div>
        <div class="row"><span class="pf-msg" id="pf-msg" role="status"></span><button type="button" class="ghost" id="pf-reset">Annuler les changements</button><button type="button" id="pf-send">Envoyer à la tablette</button></div>
      </div>
    </div>
    <dialog class="cxdialog" id="pf-widget-dialog" aria-labelledby="pf-widget-title">
      <div class="cxbody">
        <header class="cxhead"><h2 id="pf-widget-title"></h2><button type="button" class="close" aria-label="Fermer">${CLOSE}</button></header>
        <div id="pf-widget"></div>
      </div>
    </dialog>
    <dialog class="cxdialog" id="pf-rename-dialog" aria-labelledby="pf-rename-title">
      <form class="cxbody" id="pf-rename-form">
        <header class="cxhead"><h2 id="pf-rename-title">Renommer le profil</h2><button type="button" class="close" aria-label="Fermer">${CLOSE}</button></header>
        <label class="pf-field" style="margin-top:0"><span class="pf-fl">Nom du profil</span><input type="text" id="pf-name" autocomplete="off"></label>
        <div class="pf-foot"><button type="submit">Terminé</button></div>
      </form>
    </dialog>
  </section>
  <section id="pf-bars" data-title="Barres" data-icon="bars" hidden>
    <div class="sechead"><h2>Barres de la tablette</h2><span class="dim">Le bandeau du haut et la barre de gauche.</span></div>
    <div class="card group">
      <div class="row pf-ro" id="pf-bars-banner" hidden><span class="dot"></span><span class="grow dim" id="pf-bars-banner-text"></span></div>
      <div class="label">Bandeau du haut</div>
      <div id="pf-top"></div>
      <div class="row"><div class="pf-sel"><select id="pf-top-add" aria-label="Élément à ajouter au bandeau"></select></div><button type="button" class="ghost" id="pf-top-go">Ajouter</button></div>
      <div class="label">Barre de gauche</div>
      <div id="pf-rail"></div>
      <div class="row"><span class="pf-msg" id="pf-bars-msg" role="status"></span><button type="button" class="ghost" id="pf-bars-reset">Annuler les changements</button><button type="button" id="pf-bars-send">Envoyer à la tablette</button></div>
    </div>
  </section>
`;

export const PROFILES_JS = `
  (function () {
    var S = { base: null, layout: null, top: null, rail: null, page: 0, sel: -1, ro: true, connected: null, busy: false };
    var $ = function (id) { return document.getElementById(id); };
    var clone = function (x) { return JSON.parse(JSON.stringify(x)); };
    var same = function (a, b) { return JSON.stringify(a) === JSON.stringify(b); };
    var dlgWidget = $("pf-widget-dialog"), dlgRename = $("pf-rename-dialog");

    function el(tag, cls, text) {
      var e = document.createElement(tag);
      if (cls) e.className = cls;
      if (text != null) e.textContent = text;
      return e;
    }
    function button(text, cls, onclick, label) {
      var b = el("button", cls, text);
      b.type = "button";
      if (label) b.setAttribute("aria-label", label);
      b.onclick = onclick;
      return b;
    }
    function say(id, text, kind) { pfSay($(id), text, kind); }
    function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }
    function dot(on) { return el("span", "dot" + (on ? " on" : "")); }

    // --- Lecture de la disposition

    var L = function () { return S.layout; };
    var cols = function () { return S.base.cols || 4; };
    var rows = function () { return S.base.rows || 4; };
    var sizeOf = function (key) {
      var all = S.base.sizes || [];
      for (var i = 0; i < all.length; i++) if (all[i].key === key) return all[i];
      return { key: key, w: 1, h: 1, label: key };
    };
    var typeOf = function (key) {
      var all = S.base.catalog || [];
      for (var i = 0; i < all.length; i++) if (all[i].key === key) return all[i];
      return null;
    };
    var titleOf = function (key) { var t = typeOf(key); return t ? t.title : key; };
    function nameOf(i) {
      var n = L().names[i];
      return n || (i === 0 ? "Accueil" : "Profil " + (i + 1));
    }
    function roleOf(i) {
      var l = L();
      if (i === l.home) return "Accueil";
      if (i === l.call) return "Réunion";
      if (i === l.deck) return "Deck";
      return "";
    }
    function locked(i) { var l = L(); return i === l.home || (l.call >= 0 && i === l.call); }
    function countOf(i) { return L().widgets.filter(function (w) { return w.p === i; }).length; }
    function fits(page, c, r, size, ignore) {
      if (c < 0 || r < 0 || c + size.w > cols() || r + size.h > rows()) return false;
      var list = L().widgets;
      for (var i = 0; i < list.length; i++) {
        var o = list[i];
        if (i === ignore || o.p !== page) continue;
        var s = sizeOf(o.s);
        if (c < o.c + s.w && o.c < c + size.w && r < o.r + s.h && o.r < r + size.h) return false;
      }
      return true;
    }
    function freeSpot(page, size) {
      for (var r = 0; r <= rows() - size.h; r++) for (var c = 0; c <= cols() - size.w; c++) if (fits(page, c, r, size, -1)) return [c, r];
      return null;
    }
    function dirtyLayout() { return !!S.base && !!S.base.layout && !same(S.layout, S.base.layout); }
    function dirtyBars() {
      if (!S.base || !S.base.layout) return false;
      return (
        !same(S.top, S.base.topBar.items.map(function (x) { return x.key; })) ||
        !same(S.rail, { labels: S.base.rail.labels, tools: S.base.rail.tools.map(function (x) { return x.key; }) })
      );
    }

    // --- Profils : changements du brouillon

    function moveProfile(from, to) {
      var l = L();
      if (from === to || to < 0 || to >= l.names.length) return;
      var map = function (p) {
        if (p === from) return to;
        if (from < to && p > from && p <= to) return p - 1;
        if (from > to && p >= to && p < from) return p + 1;
        return p;
      };
      l.names.splice(to, 0, l.names.splice(from, 1)[0]);
      l.icons = l.icons || [];
      while (l.icons.length < l.names.length) l.icons.push(null);
      l.icons.splice(to, 0, l.icons.splice(from, 1)[0]);
      l.widgets.forEach(function (w) { w.p = map(w.p); });
      l.home = map(l.home);
      if (l.deck >= 0) l.deck = map(l.deck);
      if (l.call >= 0) l.call = map(l.call);
      S.page = to;
    }
    function removeProfile(index) {
      var l = L();
      if (l.names.length < 2 || locked(index)) return;
      var after = function (p) { return p === index ? -1 : p > index ? p - 1 : p; };
      l.names.splice(index, 1);
      l.icons = l.icons || [];
      l.icons.splice(index, 1);
      l.widgets = l.widgets.filter(function (w) { return w.p !== index; });
      l.widgets.forEach(function (w) { w.p = after(w.p); });
      l.home = after(l.home);
      l.deck = after(l.deck);
      l.call = after(l.call);
      S.page = Math.min(index, l.names.length - 1);
      S.sel = -1;
    }
    function newProfile() {
      var l = L();
      var n = 2;
      var taken = function (x) { return l.names.indexOf(x) >= 0; };
      while (taken("Profil " + n)) n++;
      l.names.push("Profil " + n);
      l.icons = l.icons || [];
      while (l.icons.length < l.names.length) l.icons.push(null);
      S.page = l.names.length - 1;
      S.sel = -1;
    }
    function syncPages() { L().pages = L().names.length; }

    // --- Profils : rendu

    function renderProfiles() {
      var l = L();
      syncPages();
      var tiles = $("pf-chips");
      clear(tiles);
      l.names.forEach(function (_, i) {
        var n = countOf(i);
        var tile = el("button", "cx");
        tile.type = "button";
        tile.setAttribute("aria-pressed", i === S.page ? "true" : "false");
        var small = el("small");
        var role = roleOf(i) && roleOf(i) !== nameOf(i) ? roleOf(i) + " · " : "";
        small.append(dot(n > 0), role + (n === 0 ? "Vide" : n + (n > 1 ? " widgets" : " widget")));
        tile.append(pfSvg(ICONS.grid, "logo ink"), el("b", "", nameOf(i)), small);
        tile.onclick = function () { S.page = i; S.sel = -1; renderProfiles(); };
        tiles.appendChild(tile);
      });
      $("pf-new").disabled = S.ro;
      $("pf-cur").textContent = nameOf(S.page);
      $("pf-cur-meta").textContent = "Profil " + (S.page + 1) + " sur " + l.names.length + (roleOf(S.page) ? " · " + roleOf(S.page) : "");
      $("pf-cap").textContent = nameOf(S.page);
      $("pf-dim").textContent = "Grille " + cols() + " × " + rows();
      $("pf-rename").disabled = S.ro;
      $("pf-up").disabled = S.ro || S.page === 0;
      $("pf-down").disabled = S.ro || S.page >= l.names.length - 1;
      $("pf-del").disabled = S.ro || l.names.length < 2 || locked(S.page);
      $("pf-del-hint").textContent = locked(S.page) ? "L'Accueil et la Réunion ne se suppriment pas." : "";
      renderGrid();
      renderSelection();
      renderAdd();
      if (dlgWidget.open) renderWidgetDialog();
      $("pf-send").disabled = S.ro || S.busy || !dirtyLayout();
      $("pf-reset").disabled = S.busy || !dirtyLayout();
    }

    function renderGrid() {
      var grid = $("pf-grid");
      clear(grid);
      grid.style.setProperty("--cols", cols());
      grid.style.setProperty("--rows", rows());
      var l = L();
      var covered = {};
      l.widgets.forEach(function (w, i) {
        if (w.p !== S.page) return;
        var s = sizeOf(w.s);
        for (var dc = 0; dc < s.w; dc++) for (var dr = 0; dr < s.h; dr++) covered[(w.c + dc) + "," + (w.r + dr)] = true;
        var cell = el("button");
        cell.type = "button";
        cell.appendChild(el("span", "", titleOf(w.t)));
        cell.appendChild(el("small", "", s.label));
        cell.style.gridColumn = (w.c + 1) + " / span " + s.w;
        cell.style.gridRow = (w.r + 1) + " / span " + s.h;
        cell.setAttribute("aria-pressed", i === S.sel ? "true" : "false");
        cell.setAttribute("aria-label", titleOf(w.t) + ", " + s.label);
        cell.onclick = function () { S.sel = i; say("pf-msg", ""); renderProfiles(); };
        grid.appendChild(cell);
      });
      for (var r = 0; r < rows(); r++) {
        for (var c = 0; c < cols(); c++) {
          if (covered[c + "," + r]) continue;
          var free = el("button", "pf-free");
          free.type = "button";
          free.style.gridColumn = String(c + 1);
          free.style.gridRow = String(r + 1);
          free.setAttribute("aria-label", "Case libre, colonne " + (c + 1) + ", ligne " + (r + 1));
          free.disabled = S.ro;
          free.onclick = (function (cc, rr) { return function () { moveSelected(cc, rr); }; })(c, r);
          grid.appendChild(free);
        }
      }
    }

    function moveSelected(c, r) {
      var w = L().widgets[S.sel];
      if (!w) { say("pf-msg", "Choisis d'abord un widget à déplacer.", ""); return; }
      var s = sizeOf(w.s);
      if (!fits(w.p, c, r, s, S.sel)) { say("pf-msg", "Ce widget (" + s.w + " × " + s.h + ") ne tient pas à cet endroit.", "ko"); return; }
      w.c = c;
      w.r = r;
      say("pf-msg", "");
      renderProfiles();
    }

    // Ligne du widget choisi : de quoi le régler, ou comment en choisir un.
    function renderSelection() {
      var text = $("pf-sel-text");
      clear(text);
      var w = L().widgets[S.sel];
      if (!w || w.p !== S.page) {
        S.sel = -1;
        text.textContent = "Touche un widget de l'aperçu pour le régler, puis une case libre pour le déplacer.";
        text.className = "grow dim";
        $("pf-sel-edit").hidden = true;
        return;
      }
      text.className = "grow";
      text.append(el("b", "", titleOf(w.t)), el("span", "pf-hint", "Touche une case libre pour le déplacer."));
      $("pf-sel-edit").hidden = false;
      $("pf-sel-edit").disabled = false;
    }

    // --- Modale d'un widget : taille, touches, retrait

    function renderWidgetDialog() {
      var w = L().widgets[S.sel];
      if (!w || w.p !== S.page) { if (dlgWidget.open) dlgWidget.close(); return; }
      $("pf-widget-title").textContent = titleOf(w.t);
      var box = $("pf-widget");
      clear(box);
      box.appendChild(el("p", "pf-h", "Taille"));
      var type = typeOf(w.t);
      var sizes = el("div", "pf-choices");
      (type ? type.sizes : [sizeOf(w.s)]).forEach(function (s) {
        var ok = s.key === w.s || fits(w.p, w.c, w.r, s, S.sel);
        var b = button(s.label + " (" + s.w + " × " + s.h + ")", "pf-size", function () { w.s = s.key; renderProfiles(); });
        b.setAttribute("aria-pressed", s.key === w.s ? "true" : "false");
        b.disabled = S.ro || !ok;
        sizes.appendChild(b);
      });
      box.appendChild(sizes);
      if (w.t === "keys") renderKeys(box, w);
      var foot = el("div", "pf-foot");
      var rm = button("Retirer le widget", "danger", function () {
        L().widgets.splice(S.sel, 1);
        S.sel = -1;
        dlgWidget.close();
        renderProfiles();
      });
      rm.disabled = S.ro;
      foot.appendChild(rm);
      box.appendChild(foot);
    }

    // --- Touches d'un widget Touches : celles de l'ordinateur actif

    function keysOf(cfg) {
      var cid = S.base.computerId;
      var own = cid && cfg.byComputer && cfg.byComputer[cid];
      var src = own ? own.keys : cfg.keys;
      return Array.isArray(src) ? src.slice() : [];
    }
    function putKeys(cfg, keys) {
      var cid = S.base.computerId;
      cfg.byComputer = cfg.byComputer || {};
      if (cfg.keys !== undefined) {
        if (!cfg.byComputer[cid]) cfg.byComputer[cid] = { keys: cfg.keys };
        delete cfg.keys;
      }
      cfg.byComputer[cid] = { keys: keys };
    }
    function refOf(t) {
      var ref = { id: t.id, label: t.label || "", glyph: t.glyph || "", color: t.color || "" };
      if (t.symbol) ref.symbol = t.symbol;
      return ref;
    }
    function renderKeys(box, w) {
      box.appendChild(el("p", "pf-h", "Touches du widget"));
      if (!S.base.computerId) {
        box.appendChild(el("p", "dim", "Aucun ordinateur actif sur la tablette : ses touches ne peuvent pas être réglées."));
        return;
      }
      w.cfg = w.cfg || {};
      var keys = keysOf(w.cfg);
      var change = function (next) { putKeys(w.cfg, next); renderProfiles(); };
      if (!keys.length) box.appendChild(el("p", "dim", "Aucune touche choisie."));
      var list = el("div", "pf-list");
      keys.forEach(function (k, i) {
        var name = k.label || k.id;
        var row = el("div", "row");
        row.appendChild(el("span", "grow", name));
        var up = pfIconButton(PF_PATHS.up, "Monter " + name, function () { var n = keys.slice(); n.splice(i - 1, 0, n.splice(i, 1)[0]); change(n); });
        up.disabled = S.ro || i === 0;
        var down = pfIconButton(PF_PATHS.down, "Descendre " + name, function () { var n = keys.slice(); n.splice(i + 1, 0, n.splice(i, 1)[0]); change(n); });
        down.disabled = S.ro || i === keys.length - 1;
        var del = button("Retirer", "ghost", function () { var n = keys.slice(); n.splice(i, 1); change(n); }, "Retirer " + name);
        del.disabled = S.ro;
        row.append(up, down, del);
        list.appendChild(row);
      });
      if (keys.length) box.appendChild(list);
      var chosen = {};
      keys.forEach(function (k) { chosen[k.id] = true; });
      var select = el("select");
      select.setAttribute("aria-label", "Touche à ajouter");
      var count = 0;
      (S.base.deck || []).forEach(function (page, pi) {
        var group = el("optgroup");
        group.label = page.title;
        page.tiles.forEach(function (t, ti) {
          if (chosen[t.id]) return;
          var o = el("option", "", t.label || t.id);
          o.value = pi + ":" + ti;
          group.appendChild(o);
          count++;
        });
        if (group.firstChild) select.appendChild(group);
      });
      if (!count) {
        box.appendChild(el("p", "dim", "Toutes les touches du Deck sont déjà choisies."));
        return;
      }
      var add = button("Ajouter", "ghost", function () {
        var at = select.value.split(":");
        var t = S.base.deck[Number(at[0])].tiles[Number(at[1])];
        change(keys.concat([refOf(t)]));
      });
      add.disabled = S.ro;
      var sel = el("div", "pf-sel");
      sel.appendChild(select);
      var line = el("div", "pf-foot");
      line.style.marginTop = "12px";
      line.append(sel, add);
      box.appendChild(line);
    }

    // --- Ajouter un widget

    function renderAdd() {
      var select = $("pf-add");
      clear(select);
      (S.base.catalog || []).forEach(function (t) {
        if (!t.offered) return;
        var o = el("option", "", t.title);
        o.value = t.key;
        select.appendChild(o);
      });
      select.disabled = S.ro;
      $("pf-add-go").disabled = S.ro || !select.firstChild;
    }
    function addWidget() {
      var type = typeOf($("pf-add").value);
      if (!type) return;
      for (var i = 0; i < type.sizes.length; i++) {
        var spot = freeSpot(S.page, type.sizes[i]);
        if (!spot) continue;
        L().widgets.push({ t: type.key, s: type.sizes[i].key, p: S.page, c: spot[0], r: spot[1], cfg: {} });
        S.sel = L().widgets.length - 1;
        say("pf-msg", "");
        renderProfiles();
        return;
      }
      say("pf-msg", "Plus de place sur ce profil pour ce widget.", "ko");
    }

    // --- Barres

    function labelOfTop(key) {
      var all = S.base.topBar.items.concat(S.base.topBar.addable);
      for (var i = 0; i < all.length; i++) if (all[i].key === key) return all[i].label;
      return key;
    }
    function renderBars() {
      var top = $("pf-top");
      clear(top);
      if (!S.top.length) {
        var empty = el("div", "row");
        empty.appendChild(el("span", "grow dim", "Le bandeau est vide."));
        top.appendChild(empty);
      }
      S.top.forEach(function (key, i) {
        var row = el("div", "row");
        row.appendChild(el("span", "grow", labelOfTop(key)));
        var up = pfIconButton(PF_PATHS.up, "Monter " + labelOfTop(key), function () { S.top.splice(i - 1, 0, S.top.splice(i, 1)[0]); renderBars(); });
        up.disabled = S.ro || i === 0;
        var down = pfIconButton(PF_PATHS.down, "Descendre " + labelOfTop(key), function () { S.top.splice(i + 1, 0, S.top.splice(i, 1)[0]); renderBars(); });
        down.disabled = S.ro || i === S.top.length - 1;
        var del = button("Retirer", "ghost", function () { S.top.splice(i, 1); renderBars(); }, "Retirer " + labelOfTop(key));
        del.disabled = S.ro;
        row.append(up, down, del);
        top.appendChild(row);
      });
      var select = $("pf-top-add");
      clear(select);
      S.base.topBar.items.concat(S.base.topBar.addable).forEach(function (it) {
        if (S.top.indexOf(it.key) >= 0) return;
        var o = el("option", "", it.label);
        o.value = it.key;
        select.appendChild(o);
      });
      select.disabled = S.ro;
      $("pf-top-go").disabled = S.ro || !select.firstChild;

      var rail = $("pf-rail");
      clear(rail);
      var labels = el("div", "row");
      var cb = el("input");
      cb.type = "checkbox";
      cb.id = "pf-rail-labels";
      cb.checked = !!S.rail.labels;
      cb.disabled = S.ro;
      cb.onchange = function () { S.rail.labels = cb.checked; renderBars(); };
      var lb = el("label", "", "Noms sous les icônes");
      lb.htmlFor = cb.id;
      labels.append(lb, cb);
      rail.appendChild(labels);
      S.base.rail.tools.concat(S.base.rail.available).forEach(function (tool) {
        var row = el("div", "row");
        var box = el("input");
        box.type = "checkbox";
        box.id = "pf-rail-tool-" + tool.key;
        box.checked = S.rail.tools.indexOf(tool.key) >= 0;
        box.disabled = S.ro;
        box.onchange = function () {
          S.rail.tools = S.rail.tools.filter(function (k) { return k !== tool.key; });
          if (box.checked) S.rail.tools.push(tool.key);
          renderBars();
        };
        var l2 = el("label", "", "Outil " + tool.label);
        l2.htmlFor = box.id;
        row.append(l2, box);
        rail.appendChild(row);
      });
      $("pf-bars-send").disabled = S.ro || S.busy || !dirtyBars();
      $("pf-bars-reset").disabled = S.busy || !dirtyBars();
    }

    // --- Échanges avec la tablette

    // Aperçu : couleurs du thème de la tablette, recopiées de l'aperçu de la section Tablette (il les suit en direct).
    var TV = ["bg", "tile", "ink", "ink2", "now", "line"];
    function syncTheme() {
      var twin = $("twin"), screen = $("pf-screen");
      TV.forEach(function (v) {
        var x = twin.style.getPropertyValue("--t-" + v);
        if (x) screen.style.setProperty("--t-" + v, x); else screen.style.removeProperty("--t-" + v);
      });
    }
    var rl = $("twin").querySelector(".rl");
    if (rl) $("pf-screen").prepend(rl.cloneNode(true));
    new MutationObserver(syncTheme).observe($("twin"), { attributes: true, attributeFilter: ["style"] });
    syncTheme();

    function render() {
      var has = !!(S.base && S.base.layout);
      $("pf-profiles").hidden = !has;
      $("pf-bars").hidden = !has;
      if (!has) return;
      var text = "";
      if (S.ro) text = S.base.error ? "La tablette n'a pas répondu : " + S.base.error + ". Dernière disposition connue, en lecture seule." : "Tablette non connectée : dernière disposition connue, en lecture seule.";
      for (var id of ["pf-banner", "pf-bars-banner"]) {
        $(id + "-text").textContent = text;
        $(id).hidden = !text;
      }
      S.page = Math.min(S.page, L().names.length - 1);
      renderProfiles();
      renderBars();
    }

    function adopt(data, parts) {
      S.base = data;
      S.ro = !!data.readOnly;
      if (!data.layout) return;
      if (parts.indexOf("layout") >= 0) { S.layout = clone(data.layout); S.sel = -1; }
      if (parts.indexOf("bars") >= 0) {
        S.top = data.topBar.items.map(function (x) { return x.key; });
        S.rail = { labels: data.rail.labels, tools: data.rail.tools.map(function (x) { return x.key; }) };
      }
    }

    function fail(e) { return e && e.message ? e.message : "La tablette n'a pas répondu."; }

    function load() {
      return fetch("/local/tablet/layout")
        .then(function (r) { return r.json().then(function (j) { if (!r.ok) throw new Error(j.error || j.message || "Erreur " + r.status); return j; }); })
        .then(function (data) { adopt(data, ["layout", "bars"]); render(); })
        .catch(function () {});
    }

    function put(body) {
      return fetch("/local/tablet/layout", { method: "PUT", headers: { "content-type": "application/json" }, body: JSON.stringify(body) })
        .then(function (r) { return r.json().then(function (j) { if (!r.ok) throw new Error(j.error || j.message || "Erreur " + r.status); return j; }); });
    }

    function send(body, parts, msgId) {
      S.busy = true;
      say(msgId, "Envoi à la tablette…", "");
      render();
      put(body)
        .then(function (data) { S.busy = false; adopt(data, parts); say(msgId, "Envoyé à la tablette.", "ok"); render(); })
        .catch(function (e) { S.busy = false; say(msgId, fail(e), "ko"); render(); });
    }

    pfWire(dlgWidget);
    pfWire(dlgRename);
    $("pf-new").onclick = function () { newProfile(); renderProfiles(); };
    $("pf-up").onclick = function () { moveProfile(S.page, S.page - 1); renderProfiles(); };
    $("pf-down").onclick = function () { moveProfile(S.page, S.page + 1); renderProfiles(); };
    $("pf-del").onclick = function () { removeProfile(S.page); renderProfiles(); };
    $("pf-rename").onclick = function () {
      var input = $("pf-name");
      input.maxLength = S.base.nameMax || 30;
      input.value = L().names[S.page] || "";
      dlgRename.showModal();
      input.select();
    };
    $("pf-name").oninput = function () {
      L().names[S.page] = $("pf-name").value.trim() || null;
      renderProfiles();
    };
    $("pf-rename-form").onsubmit = function (e) { e.preventDefault(); dlgRename.close(); };
    $("pf-sel-edit").onclick = function () { renderWidgetDialog(); dlgWidget.showModal(); };
    $("pf-add-go").onclick = addWidget;
    $("pf-send").onclick = function () { send({ layout: S.layout }, ["layout"], "pf-msg"); };
    $("pf-reset").onclick = function () { adopt(S.base, ["layout"]); say("pf-msg", ""); render(); };
    $("pf-top-go").onclick = function () {
      var key = $("pf-top-add").value;
      if (key) S.top.unshift(key);
      renderBars();
    };
    $("pf-bars-send").onclick = function () { send({ topBar: S.top, rail: S.rail }, ["bars"], "pf-bars-msg"); };
    $("pf-bars-reset").onclick = function () { adopt(S.base, ["bars"]); say("pf-bars-msg", ""); render(); };

    // La tablette se connecte ou se déconnecte : relue, sauf si des changements attendent leur envoi.
    setInterval(function () {
      if (document.hidden || S.busy) return;
      fetch("/local/status")
        .then(function (r) { return r.json(); })
        .then(function (s) {
          var on = !!s.tablet;
          var changed = S.connected !== null && on !== S.connected;
          S.connected = on;
          if (changed && !dirtyLayout() && !dirtyBars()) load();
        })
        .catch(function () {});
    }, 4000);

    load();
  })();
`;

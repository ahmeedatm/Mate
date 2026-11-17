import { EN } from "./i18n-en.ts";

/**
 * Messages du compagnon en anglais (Réglages > Langue de la tablette, choix Français / English de la page locale).
 * Le code garde ses phrases en français ; `EN` les traduit à la sortie : texte exact, ou gabarit dont `{}` marque une
 * valeur insérée (nom, nombre). Un message absent du catalogue reste en français.
 */
export type Lang = "fr" | "en";

/** Première langue de l'en-tête Accept-Language : la tablette envoie `fr` ou `en`, la page locale aussi. */
export function langOf(header: string | undefined): Lang {
  return /^\s*en\b/i.test(header ?? "") ? "en" : "fr";
}

type Pattern = { re: RegExp; en: string };
let patterns: Pattern[] | null = null;

/** Gabarits compilés une fois : `{}` devient un groupe, le reste est pris tel quel. */
function compiled(): Pattern[] {
  patterns ??= Object.entries(EN)
    .filter(([fr]) => fr.includes("{}"))
    .map(([fr, en]) => ({
      re: new RegExp(
        "^" +
          fr
            .split("{}")
            .map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"))
            .join("(.+?)") +
          "$",
        "s",
      ),
      en,
    }));
  return patterns;
}

/**
 * Script des pages de l'ordinateur (page locale, visualiseur), mis dans leur <head> : langue choisie par le lien
 * Français / English (`mate-lang`), sinon celle du navigateur. En anglais, chaque texte posé dans la page (à
 * l'analyse comme ensuite par les scripts) passe par le même catalogue ; les appels au compagnon portent la langue
 * (`Accept-Language`), ses erreurs reviennent donc traduites. Aucun texte de saisie (champ, zone de texte) n'est touché.
 */
export function pageI18nScript(): string {
  const catalog = JSON.stringify(EN).replace(/</g, "\\u003c");
  const pats = JSON.stringify(compiled().map(({ re, en }) => [re.source, en])).replace(/</g, "\\u003c");
  return String.raw`<script>
(() => {
  var KEY = "mate-lang", lang = null;
  try { lang = localStorage.getItem(KEY); } catch (e) {}
  if (lang !== "fr" && lang !== "en") lang = /^en\b/i.test(navigator.language || "") ? "en" : "fr";
  document.documentElement.lang = lang;
  window.mateSetLang = function (l) { try { localStorage.setItem(KEY, l); } catch (e) {} location.reload(); };
  var send = window.fetch.bind(window);
  window.fetch = function (input, init) {
    init = init || {};
    var headers = new Headers(init.headers || (input instanceof Request ? input.headers : undefined));
    headers.set("Accept-Language", lang);
    return send(input, Object.assign({}, init, { headers: headers }));
  };
  if (lang === "fr") return;
  var EN = ${catalog};
  var PATS = ${pats}.map(function (p) { return [new RegExp(p[0], "s"), p[1]]; });
  function tr(value) {
    var key = value.replace(/\s+/g, " ").trim();
    if (!key) return null;
    var en = Object.prototype.hasOwnProperty.call(EN, key) ? EN[key] : undefined;
    for (var i = 0; en === undefined && i < PATS.length; i++) {
      var m = PATS[i][0].exec(key);
      if (m) { var k = 1; en = PATS[i][1].replace(/\{\}/g, function () { return m[k++] || ""; }); }
    }
    if (en === undefined) return null;
    return value.match(/^\s*/)[0] + en + value.match(/\s*$/)[0];
  }
  var SKIP = { SCRIPT: 1, STYLE: 1, TEXTAREA: 1, CODE: 1, PRE: 1 };
  var ATTRS = ["placeholder", "title", "aria-label", "alt"];
  function skipped(e) { return !e || SKIP[e.nodeName] || e.isContentEditable || (e.closest && e.closest("[translate=no]")); }
  function text(n) {
    if (skipped(n.parentElement)) return;
    var t = tr(n.nodeValue);
    if (t !== null && t !== n.nodeValue) n.nodeValue = t;
  }
  function element(e) {
    if (skipped(e)) return;
    for (var i = 0; i < ATTRS.length; i++) {
      var v = e.getAttribute(ATTRS[i]), t = v && tr(v);
      if (t && t !== v) e.setAttribute(ATTRS[i], t);
    }
  }
  function walk(root) {
    if (root.nodeType === 3) return text(root);
    if (root.nodeType !== 1) return;
    element(root);
    var w = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT), n;
    while ((n = w.nextNode())) n.nodeType === 3 ? text(n) : element(n);
  }
  new MutationObserver(function (list) {
    list.forEach(function (m) {
      if (m.type === "characterData") text(m.target);
      else if (m.type === "attributes") element(m.target);
      else m.addedNodes.forEach(walk);
    });
  }).observe(document.documentElement, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ATTRS });
  ["alert", "confirm", "prompt"].forEach(function (name) {
    var original = window[name].bind(window);
    window[name] = function (message) {
      var args = Array.prototype.slice.call(arguments);
      if (typeof message === "string") args[0] = tr(message) || message;
      return original.apply(null, args);
    };
  });
})();
</script>`;
}

export function translate(text: string, lang: Lang): string {
  if (lang === "fr") return text;
  const exact = EN[text];
  if (exact !== undefined) return exact;
  for (const { re, en } of compiled()) {
    const m = re.exec(text);
    if (m) {
      let i = 1;
      return en.replace(/\{\}/g, () => m[i++] ?? "");
    }
  }
  return text;
}

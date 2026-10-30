import { CONFIG_CSS, CONFIG_HTML, CONFIG_JS } from "./local-config.ts";
import { CADRAN_CSS, FAVICON, ICON_SVG } from "./web-style.ts";

/**
 * Page http://127.0.0.1:3000/ ouverte sur l'ordinateur (le panneau de l'appli : relancer « UnoMate » la rouvre) :
 * barre de gauche (état du compagnon et de la tablette, sections), puis les sections l'une sous l'autre : réglages de la
 * tablette connectée avec son aperçu dessiné, connexion d'une tablette (code d'appairage affiché par la tablette),
 * agenda, GitLab et la carte « Cette appli » (lancement au démarrage, pare-feu, ancienne installation, mise à jour,
 * quitter, tout oublier). La barre liste les `main > section[data-title]` dans l'ordre de la page.
 * Lue sans jeton, en local seulement (voir isLocalPage dans server.ts).
 */
export const LOCAL_PAGE = `<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>UnoMate</title>
${FAVICON}
<style>
  ${CADRAN_CSS}
  * { box-sizing: border-box; }
  /* Les display posés plus bas (boutons, lignes) ne doivent pas l'emporter sur l'attribut hidden. */
  [hidden] { display: none !important; }
  html { scroll-behavior: smooth; scroll-padding-top: 32px; }
  body { margin: 0; background: var(--bg); color: var(--ink); font: 400 15px/1.5 Outfit, system-ui, sans-serif; -webkit-font-smoothing: antialiased;
    display: grid; grid-template-columns: 272px minmax(0, 1fr); min-height: 100vh; }
  ::selection { background: var(--now-soft); color: var(--ink); }
  :focus-visible { outline: 2px solid var(--now); outline-offset: 2px; }
  h1, h2 { font-weight: 500; margin: 0; letter-spacing: -.01em; text-wrap: balance; }
  b { font-weight: 500; }
  a { color: var(--ink); text-underline-offset: 3px; text-decoration-color: var(--line); }
  .dim { color: var(--ink-2); font-size: 14px; }
  .ok { color: var(--ok); } .ko { color: var(--ko); }
  code { font: 500 14px Outfit, sans-serif; font-feature-settings: "tnum"; color: var(--ink); background: var(--tile-high); padding: 3px 9px; border-radius: 8px; overflow-wrap: anywhere; }
  svg.i { width: 20px; height: 20px; fill: currentColor; flex: none; }
  .dot { width: 8px; height: 8px; border-radius: 4px; background: var(--ink-3); flex: none; display: inline-block; }
  .dot.on { background: var(--ok); }
  .dot.ko { background: var(--now); }

  /* Barre de gauche : même geste que la barre fixe de la tablette, un trait rouge glisse sur la section affichée. */
  nav { background: var(--rail); position: sticky; top: 0; height: 100vh; overflow-y: auto; padding: 28px 16px 20px; display: flex; flex-direction: column; }
  .who { display: flex; gap: 12px; align-items: center; padding: 0 8px; }
  .who .logo svg { display: block; width: 40px; height: 40px; }
  .who h1 { font-size: 16px; line-height: 1.25; overflow-wrap: anywhere; }
  .who p { margin: 2px 0 0; display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--ink-2); }
  .health { margin: 22px 8px 18px; padding: 14px 0 0; border-top: 1px solid var(--line); display: flex; flex-direction: column; gap: 8px; font-size: 14px; color: var(--ink-2); }
  .health div { display: flex; align-items: center; gap: 10px; }
  .health a { color: var(--ink); text-decoration: none; }
  .menu { position: relative; display: flex; flex-direction: column; gap: 2px; }
  .menu .grp { font-size: 12px; color: var(--ink-2); padding: 16px 12px 6px; }
  .menu a { display: flex; align-items: center; gap: 12px; min-height: 40px; padding: 8px 12px 8px 16px; border-radius: 12px; text-decoration: none; color: var(--ink-2); font-size: 14px; line-height: 1.3; }
  .menu a:hover { background: var(--tile-high); color: var(--ink); }
  .menu a[aria-current=true] { color: var(--ink); background: var(--tile); font-weight: 500; }
  .trait { position: absolute; left: 4px; top: 0; width: 3px; height: 20px; border-radius: 2px; background: var(--now); transition: transform 320ms cubic-bezier(.16, 1, .3, 1); }
  nav .foot { margin-top: auto; padding: 18px 12px 0; font-size: 13px; color: var(--ink-2); }
  nav .foot a { color: var(--ink-2); }

  main { padding: 36px 56px 96px; max-width: 1080px; width: 100%; }
  main > section { margin-bottom: 48px; }
  .sechead { display: flex; align-items: baseline; gap: 16px; margin: 0 0 14px 4px; flex-wrap: wrap; }
  h2 { font-size: 24px; line-height: 1.2; }
  .card { background: var(--tile); border-radius: 24px; padding: 22px 26px; }
  /* Ouverte depuis la tablette (« Relier mon agenda », « Régler depuis l'ordi ») : la carte demandée est mise en avant. */
  .card.focus { box-shadow: 0 0 0 2px var(--now); }

  /* Listes groupées : une ligne par réglage, séparateur fin entre deux lignes. */
  .group { padding: 4px 0; }
  .row { position: relative; display: flex; align-items: center; gap: 16px; padding: 12px 26px; min-height: 56px; }
  .group .row::before { content: ""; position: absolute; top: 0; left: 26px; right: 0; height: 1px; background: var(--line); }
  .group > .row:first-child::before, .group > div:first-child > .row:first-child::before { display: none; }
  .row label, .row .grow { flex: 1; min-width: 0; text-wrap: pretty; }
  .row .value { width: 48px; text-align: right; font-feature-settings: "tnum"; color: var(--ink-2); }
  .note { padding: 0 26px 14px; }
  .group .dim.note:empty { display: none; }

  /* Connecter une IA : configuration à copier, sur plusieurs lignes. */
  .mcp pre { margin: 0 26px 14px; padding: 12px 14px; border-radius: 12px; background: var(--tile-high); font: 400 13px/1.5 ui-monospace, Menlo, Consolas, monospace;
    color: var(--ink); white-space: pre-wrap; overflow-wrap: anywhere; }
  .mcp .row code { font: 400 13px ui-monospace, Menlo, Consolas, monospace; }
  /* Contrôles : pilules, interrupteurs et curseurs habillés en Cadran. */
  button, a.button { display: inline-flex; align-items: center; justify-content: center; gap: 8px; min-height: 40px; padding: 0 18px; border-radius: 20px; border: 0;
    font: 500 14px Outfit, sans-serif; background: var(--ink); color: var(--bg); cursor: pointer; text-decoration: none; white-space: nowrap; transition: transform 160ms cubic-bezier(.2, .8, .2, 1), opacity 160ms; }
  button:hover, a.button:hover { opacity: .88; }
  button:active, a.button:active { transform: scale(.97); }
  button.ghost { background: var(--tile-high); color: var(--ink); }
  button.danger { background: transparent; color: var(--ko); box-shadow: inset 0 0 0 1px var(--line); }
  input[type=checkbox] { appearance: none; width: 44px; height: 26px; border-radius: 13px; background: var(--line); position: relative; cursor: pointer; margin: 0; flex: none; transition: background 200ms; }
  input[type=checkbox]::after { content: ""; position: absolute; top: 3px; left: 3px; width: 20px; height: 20px; border-radius: 10px; background: var(--tile); box-shadow: 0 1px 3px rgba(0, 0, 0, .25); transition: transform 200ms cubic-bezier(.2, .8, .2, 1); }
  input[type=checkbox]:checked { background: var(--now); }
  input[type=checkbox]:checked::after { transform: translateX(18px); }
  input[type=range] { appearance: none; -webkit-appearance: none; width: 220px; height: 24px; margin: 0; background: transparent; cursor: pointer; }
  input[type=range]::-webkit-slider-runnable-track { height: 4px; border-radius: 2px; background: linear-gradient(to right, var(--ink) var(--v, 50%), var(--line) var(--v, 50%)); }
  input[type=range]::-webkit-slider-thumb { -webkit-appearance: none; width: 18px; height: 18px; margin-top: -7px; border-radius: 9px; background: var(--now); box-shadow: 0 0 0 4px var(--tile); }
  input[type=range]::-moz-range-track { height: 4px; border-radius: 2px; background: var(--line); }
  input[type=range]::-moz-range-progress { height: 4px; border-radius: 2px; background: var(--ink); }
  input[type=range]::-moz-range-thumb { width: 18px; height: 18px; border: 0; border-radius: 9px; background: var(--now); }
  input[type=text], input:not([type]) { width: 100%; font: 400 15px Outfit, sans-serif; height: 46px; padding: 0 20px; border-radius: 23px; border: 0;
    box-shadow: inset 0 0 0 1px var(--line); background: var(--bg); color: var(--ink); }
  input::placeholder { color: var(--ink-3); }
  input:not([type=checkbox]):not([type=range]):focus { outline: 0; box-shadow: inset 0 0 0 2px var(--ink); }

  /* Code d'appairage : grands chiffres fins, et la ligne du jour qui avance pendant ses 2 minutes. */
  #pairing { display: none; margin-bottom: 48px; padding: 30px 36px 28px; box-shadow: 0 0 0 2px var(--now); }
  #pairing h2 { font-size: 20px; }
  #paircode { font: 300 72px/1.1 Outfit, sans-serif; font-feature-settings: "tnum"; letter-spacing: .04em; height: auto; padding: 14px 0 18px;
    border-radius: 0; box-shadow: none; background: transparent; margin: 0; }
  #paircode:focus { box-shadow: none; }
  #paircode::placeholder { color: var(--line); }
  .minuteur { --p: 0; position: relative; height: 2px; background: var(--line); border-radius: 1px; margin-bottom: 18px; }
  .minuteur i { position: absolute; left: 0; top: 0; height: 2px; width: calc(var(--p) * 100%); background: var(--ink-3); border-radius: 1px; transition: width 1.5s linear; }
  .minuteur b { position: absolute; left: calc(var(--p) * 100%); top: -5px; width: 12px; height: 12px; margin-left: -6px; border-radius: 6px;
    background: var(--now); box-shadow: 0 0 0 3px var(--tile); transition: left 1.5s linear; }

  /* Autorisation qui manque (Mac) : seul bloc teinté du rouge « maintenant », en tête. */
  #access { background: var(--now-soft); margin-bottom: 48px; display: flex; gap: 24px; align-items: center; }
  #access .grow { flex: 1; }
  #access p { margin: 6px 0 0; }

  /* Tablette : son aperçu dessiné à gauche (thème et luminosité suivis en direct), ses réglages à droite. */
  .twin-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.05fr); gap: 28px; align-items: start; }
  .twin { margin: 0; position: sticky; top: 32px; }
  .device { background: #1A1717; border-radius: 26px; padding: 18px; box-shadow: 0 24px 48px -28px rgba(30, 10, 10, .5), 0 0 0 1px var(--line); }
  .screen { --t-bg: #FAF2F1; --t-tile: #FFFFFF; --t-ink: #1E1B1B; --t-ink2: #4D4545; --t-now: #CA0538; --t-line: #D0C4C3;
    aspect-ratio: 16 / 10; border-radius: 8px; overflow: hidden; background: var(--t-bg); color: var(--t-ink); container-type: inline-size;
    display: grid; grid-template-columns: 9% 1fr; transition: background 300ms, color 300ms, filter 250ms; }
  .screen .rl { display: flex; flex-direction: column; align-items: center; gap: 5cqw; padding-top: 4.5cqw; }
  .screen .rl svg { width: 3.4cqw; height: 3.4cqw; fill: var(--t-ink2); transition: fill 300ms; }
  .screen .rl svg:nth-child(2) { fill: var(--t-now); }
  .screen .bd { padding: 4cqw 4.5cqw 3.5cqw 1cqw; display: grid; grid-template-rows: auto auto 1fr; gap: 2cqw; min-height: 0; }
  .screen .clock { font-weight: 300; font-size: 14cqw; line-height: .9; letter-spacing: -.02em; font-feature-settings: "tnum"; }
  .screen .date { font-size: 2.4cqw; color: var(--t-ink2); margin-top: 1.2cqw; }
  .screen .line { position: relative; height: .35cqw; background: var(--t-line); margin: 1.5cqw 0; }
  .screen .line::before { content: ""; position: absolute; left: 0; width: var(--d, 40%); height: 100%; background: var(--t-ink2); }
  .screen .line::after { content: ""; position: absolute; left: var(--d, 40%); top: 50%; width: 1.8cqw; height: 1.8cqw; margin: -.9cqw 0 0 -.9cqw; border-radius: 50%; background: var(--t-now); }
  .screen .tl { display: grid; grid-template-columns: 1.4fr 1fr 1fr; gap: 1.8cqw; min-height: 0; }
  .screen .tl div { background: var(--t-tile); border-radius: 2.6cqw; padding: 2.2cqw; display: flex; flex-direction: column; gap: .6cqw; font-size: 2.2cqw; line-height: 1.25; color: var(--t-ink2); transition: background 300ms; }
  .screen .tl b { font-size: 2.6cqw; color: var(--t-ink); }
  .screen .tl .now { color: var(--t-now); }
  .twin figcaption { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 4px 12px; margin-top: 14px; font-size: 14px; color: var(--ink-2); }
  .twin figcaption #twintheme { white-space: nowrap; }
  .twin figcaption #twintheme::before { content: "Aperçu : "; }
  .themes { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; padding: 6px 26px 16px; }
  .themes button { justify-content: flex-start; gap: 10px; min-height: 44px; padding: 0 12px; border-radius: 14px; background: var(--tile-high); color: var(--ink); font-weight: 400; }
  .themes button[aria-pressed=true] { background: var(--now-soft); box-shadow: inset 0 0 0 1.5px var(--now); font-weight: 500; }
  .themes button span { width: 16px; height: 16px; border-radius: 8px; flex: none; box-shadow: inset 0 0 0 1px rgba(0, 0, 0, .18); }
  .label { padding: 14px 26px 4px; color: var(--ink-2); font-size: 14px; }
  .twin-grid input[type=range] { width: 150px; }

  /* Connecter une tablette : les trois étapes, l'adresse à saisir si la tablette ne trouve pas l'ordinateur. */
  #connect:not(.waiting) .wait { display: none; }
  #connect:not(.waiting) .twin-grid { grid-template-columns: 1fr; }
  .screen .bd.pair { grid-template-rows: none; align-content: start; gap: 2.4cqw; padding-top: 5cqw; }
  .pair .ptitle { font-size: 4.4cqw; font-weight: 500; }
  .pair .prow { background: var(--t-tile); border-radius: 2.6cqw; padding: 2.6cqw 3cqw; display: flex; align-items: center; justify-content: space-between; gap: 2cqw; font-size: 2.4cqw; line-height: 1.3; color: var(--t-ink2); }
  .pair .prow b { color: var(--t-ink); font-size: 2.8cqw; }
  .pair .prow i { font-style: normal; font-weight: 500; background: var(--t-now); color: #FFF; padding: 1cqw 2.6cqw; border-radius: 3cqw; }
  .pair .pcode { font-size: 2.4cqw; color: var(--t-ink2); }
  .steps { list-style: none; margin: 0; padding: 10px 26px 6px; counter-reset: s; display: grid; gap: 4px; }
  .steps li { counter-increment: s; display: flex; gap: 16px; align-items: baseline; padding: 8px 0; color: var(--ink-2); }
  .steps li::before { content: counter(s); font: 300 28px/1 Outfit, sans-serif; color: var(--ink); width: 22px; flex: none; }
  .steps b { color: var(--ink); }

  .account { display: flex; align-items: center; gap: 10px; }
  .account .grow { display: flex; align-items: center; gap: 10px; }
  #accounts, #gitlabaccount { display: flex; flex-direction: column; align-items: flex-start; gap: 10px; margin-top: 14px; }
  #accounts:empty, #gitlabaccount:empty { display: none; }
  details { margin-top: 14px; }
  summary { cursor: pointer; color: var(--ink-2); font-size: 14px; width: fit-content; list-style: none; display: flex; align-items: center; gap: 10px; }
  summary::-webkit-details-marker { display: none; }
  summary::before { content: ""; width: 7px; height: 7px; border: solid currentColor; border-width: 0 1.5px 1.5px 0; transform: rotate(-45deg); transition: transform 200ms cubic-bezier(.16, 1, .3, 1); }
  details[open] > summary::before { transform: rotate(45deg); }
  summary:hover { color: var(--ink); }
  details input { margin-top: 12px; }
  .actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
  #icshelp { line-height: 1.6; }

  /* Compagnon arrêté pendant que la page est ouverte : rien ne doit faire croire qu'il répond encore. */
  body.down #pairing, body.down #tabletcard { display: none !important; }
  body.down main { opacity: .55; }

  /* Connecteurs : une tuile par service, logo à sa couleur ; grisée tant qu'elle n'est pas disponible ici. */
  .connectors { display: grid; grid-template-columns: repeat(auto-fill, minmax(156px, 1fr)); gap: 12px; }
  .cx { flex-direction: column; align-items: flex-start; justify-content: flex-start; gap: 10px; min-height: 128px; padding: 18px; border-radius: 22px;
    background: var(--tile); color: var(--ink); font-weight: 400; white-space: normal; text-align: left; transition: box-shadow 160ms, transform 160ms cubic-bezier(.2, .8, .2, 1); }
  .cx:hover { opacity: 1; box-shadow: 0 0 0 1px var(--line); }
  .cx[aria-pressed=true] { box-shadow: 0 0 0 2px var(--now); }
  .cx .logo { width: 32px; height: 32px; flex: none; margin-bottom: 6px; }
  .cx .logo.ink { fill: var(--ink); }
  .cx b { font-size: 15px; }
  .cx small { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--ink-2); min-width: 0; overflow-wrap: anywhere; }
  .cx.na .logo, .cx:disabled .logo { filter: grayscale(1); opacity: .45; }
  .cx:disabled { cursor: default; color: var(--ink-2); }
  .cx:disabled:active { transform: none; }
  .cx .soon { padding: 2px 10px; border-radius: 10px; background: var(--tile-high); }
  .cxdialog { width: min(560px, calc(100vw - 32px)); max-height: calc(100vh - 48px); padding: 0; border: 0; border-radius: 28px; background: var(--tile); color: var(--ink);
    box-shadow: 0 30px 80px -20px rgba(20, 8, 8, .45); overflow: auto; }
  .cxdialog[open] { animation: cxin 220ms cubic-bezier(.16, 1, .3, 1); }
  .cxdialog::backdrop { background: rgba(20, 10, 10, .45); }
  @keyframes cxin { from { opacity: 0; transform: translateY(8px) scale(.98); } }
  .cxbody { padding: 22px 28px 28px; }
  .cxhead { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
  .cxhead h2 { flex: 1; font-size: 22px; }
  .cxhead .close { width: 40px; min-height: 40px; padding: 0; border-radius: 20px; background: var(--tile-high); color: var(--ink); }
  .cxdialog .card { padding: 0; border-radius: 0; background: none; }
  .cxdialog .card.focus { box-shadow: none; }
  .connectors + .pf-h3 { margin-top: 28px; }
  .cxplug { margin-top: 18px; }
  .cxplug .row, .cxplug .note { padding-left: 0; padding-right: 0; }
  .cxsteps { padding-bottom: 18px; margin-bottom: 18px; border-bottom: 1px solid var(--line); }
  .cxsteps:last-child, .cxsteps:has(+ .dim:empty) { border-bottom: 0; margin-bottom: 0; padding-bottom: 0; }
  .cxpanel > .dim:empty { display: none; }
  .cxsteps h3 { font-size: 18px; font-weight: 500; margin: 0 0 6px; }
  .cxsteps ol { list-style: none; counter-reset: s; margin: 0; padding: 0; display: grid; gap: 2px; }
  .cxsteps li { counter-increment: s; display: flex; gap: 14px; align-items: baseline; padding: 6px 0; color: var(--ink-2); }
  .cxsteps li::before { content: counter(s); font: 300 24px/1 Outfit, sans-serif; color: var(--ink); width: 18px; flex: none; }
  .cxsteps p:empty, .cxsteps .actions:empty { display: none; }
  ${CONFIG_CSS}
  @media (prefers-reduced-motion: reduce) { html { scroll-behavior: auto; } * { transition: none !important; } }
  @media (max-width: 1100px) { .twin-grid { grid-template-columns: 1fr; } .twin { position: static; max-width: 520px; } }
  @media (max-width: 820px) {
    body { display: block; }
    nav { position: static; height: auto; padding: 20px 16px 8px; }
    .menu { flex-direction: row; overflow-x: auto; gap: 6px; padding-right: 32px; -webkit-mask-image: linear-gradient(to right, #000 85%, transparent); mask-image: linear-gradient(to right, #000 85%, transparent); }
    .menu .grp, .trait { display: none; }
    nav { position: relative; }
    .who { padding-right: 112px; }
    nav .foot { position: absolute; top: 22px; right: 16px; padding: 0; text-align: right; max-width: 104px; line-height: 1.4; }
    nav .foot #licences { display: block; }
    .menu a { white-space: nowrap; }
    main { padding: 24px 20px 64px; }
    .themes { grid-template-columns: repeat(2, 1fr); }
    .row { flex-wrap: wrap; }
    .row:has(input[type=range]) label { flex-basis: 100%; }
    .twin-grid input[type=range], input[type=range] { flex: 1; width: auto; }
  }
</style>
</head>
<body>
<nav aria-label="Sections">
  <div class="who">
    <span class="logo">${ICON_SVG}</span>
    <div><h1 id="name">UnoMate</h1><p><span class="dot on" id="srvdot"></span><span id="os"></span></p></div>
  </div>
  <div class="health">
    <div><span class="dot" id="dot"></span><span id="state">Tablette non connectée</span></div>
    <div id="accessline" hidden><span class="dot ko"></span><a href="#access">Accessibilité à autoriser</a></div>
  </div>
  <div class="menu" id="menu"><span class="trait" id="trait" hidden></span></div>
  <!-- Politique de confidentialité (config/confidentialite.txt) ; compagnon installé : licences des composants tiers (LICENCES-TIERS.txt, scripts/package.ts). -->
  <div class="foot">
    <a href="/local/confidentialite" target="_blank">Confidentialité</a>
    <span id="licences" hidden> · <a href="/local/licences" target="_blank">Licences des composants</a></span>
  </div>
</nav>
<main>
  <!-- Le code vient de la tablette : il prouve qu'elle parle bien à cet ordinateur (voir src/pairing.ts). -->
  <div class="card" id="pairing">
    <h2><b id="tablet">La tablette</b> demande à se connecter</h2>
    <div class="dim" style="margin-top:6px">Saisis le code affiché sur la tablette.</div>
    <form id="pairform">
      <input id="paircode" inputmode="numeric" autocomplete="off" maxlength="7" placeholder="000 000" aria-label="Code affiché sur la tablette">
      <div class="minuteur" id="pairtimer"><i></i><b></b></div>
      <button type="submit">Connecter la tablette</button>
    </form>
    <div class="dim" id="pairmsg" style="margin-top:12px">Valable 2 minutes. Ne saisis ici qu'un code affiché par ta tablette.</div>
  </div>

  <!-- Mac sans l'autorisation Accessibilité : fenêtres et raccourcis du deck ne marchent pas encore. -->
  <div class="card" id="access" hidden>
    <div class="grow">
      <h2 style="font-size:18px">Une autorisation manque sur ce Mac</h2>
      <p class="dim">Pour placer les fenêtres et envoyer les raccourcis du deck, autorise le compagnon dans
        Réglages Système › Confidentialité et sécurité › <b>Accessibilité</b>, puis active l'interrupteur.</p>
    </div>
    <button id="accessopen">Ouvrir le réglage</button>
  </div>

  <!-- Tablette connectée : sa section passe en premier. Son état et ses réglages, changés d'ici (ordre « settings », voir routes/local.ts). -->
  <section id="tabletcard" data-title="Tablette" data-icon="tablet" hidden>
    <div class="sechead"><h2 id="tabletname">Tablette</h2></div>
    <div class="twin-grid">
      <figure class="twin" aria-hidden="true">
        <div class="device"><div class="screen" id="twin">
          <div class="rl"><svg viewBox="0 0 24 24"><path d="M12 14c1.66 0 2.99-1.34 2.99-3L15 5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z"/></svg><svg viewBox="0 0 24 24"><path d="M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z"/></svg><svg viewBox="0 0 24 24"><path d="M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z"/></svg><svg viewBox="0 0 24 24"><path d="M15 1H9v2h6V1zm-4 13h2V8h-2v6zm8.03-6.61l1.42-1.42c-.43-.51-.9-.99-1.41-1.41l-1.42 1.42C16.07 4.74 14.12 4 12 4c-4.97 0-9 4.03-9 9s4.02 9 9 9 9-4.03 9-9c0-2.12-.74-4.07-1.97-5.61zM12 20c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z"/></svg><svg viewBox="0 0 24 24"><path d="M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"/></svg></div>
          <div class="bd">
            <div><div class="clock" id="twinclock">--:--</div><div class="date" id="twindate"></div></div>
            <div class="line" id="twinline"></div>
            <div class="tl"><div><span class="now">Prochain rendez-vous</span><b>Agenda</b><span>Aujourd'hui et 7 jours</span></div><div><b>Tâches</b><span>À faire</span></div><div><b>Focus</b><span>25:00</span></div></div>
          </div>
        </div></div>
        <figcaption><span id="tabletinfo"></span><span id="twintheme"></span></figcaption>
      </figure>
      <div class="card group">
        <div class="row" id="updaterow" hidden><span class="grow" id="updatestate"></span><button id="updatebtn" hidden></button></div>
        <div class="dim note" id="tabletold" hidden>Mets à jour l'appli de la tablette pour la régler d'ici.</div>
        <div id="tabletsettings">
          <div class="label">Thème</div>
          <div class="themes">
            <button data-theme="CADRAN"><span></span>Cadran</button><button data-theme="CADRAN_NUIT"><span></span>Cadran nuit</button><button data-theme="CLAIR"><span></span>Clair</button><button data-theme="SOMBRE"><span></span>Sombre</button><button data-theme="RETRO"><span></span>Rétro</button>
            <button data-theme="CONTRASTE"><span></span>Contraste élevé</button><button data-theme="REGISTRE"><span></span>Registre</button><button data-theme="STUDIO"><span></span>Studio</button>
            <button data-theme="MAISON"><span></span>Maison</button><button data-theme="TABLEAU"><span></span>Tableau</button><button data-theme="TERMINAL"><span></span>Terminal</button>
          </div>
          <div class="row"><label for="brightness">Luminosité de l'écran</label><input type="range" id="brightness" min="5" max="100"><span class="value" id="brightnessvalue"></span></div>
          <div class="row"><label for="volume">Volume du haut-parleur</label><input type="range" id="volume" min="0" max="100"><span class="value" id="volumevalue"></span></div>
          <div class="row"><label for="nightdim">Atténuer l'écran la nuit, de 22 h à 7 h</label><input type="checkbox" id="nightdim"></div>
          <div class="row"><label for="focusdnd">Ne pas déranger sur l'ordinateur pendant la concentration</label><input type="checkbox" id="focusdnd"></div>
          <div class="dim note" id="tabletmsg"></div>
        </div>
      </div>
    </div>
  </section>
  ${CONFIG_HTML}

  <section id="connect" data-title="Connecter une tablette" data-icon="add" data-group="Cet ordinateur">
    <div class="sechead"><h2 id="connecttitle">Connecter une tablette</h2></div>
    <div class="twin-grid">
    <!-- Sans tablette : l'écran de la tablette où l'on ajoute cet ordinateur, dessiné à côté des étapes. -->
    <figure class="twin wait" aria-hidden="true">
      <div class="device"><div class="screen">
        <div class="rl"><svg viewBox="0 0 24 24"><path d="M12 14c1.66 0 2.99-1.34 2.99-3L15 5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z"/></svg><svg viewBox="0 0 24 24"><path d="M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z"/></svg><svg viewBox="0 0 24 24"><path d="M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z"/></svg><svg viewBox="0 0 24 24"><path d="M15 1H9v2h6V1zm-4 13h2V8h-2v6zm8.03-6.61l1.42-1.42c-.43-.51-.9-.99-1.41-1.41l-1.42 1.42C16.07 4.74 14.12 4 12 4c-4.97 0-9 4.03-9 9s4.02 9 9 9 9-4.03 9-9c0-2.12-.74-4.07-1.97-5.61zM12 20c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z"/></svg><svg viewBox="0 0 24 24"><path d="M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"/></svg></div>
        <div class="bd pair">
          <div class="ptitle">Ordinateurs</div>
          <div class="prow"><span><b id="waitname">Cet ordinateur</b><br>trouvé sur le WiFi</span><i>Ajouter</i></div>
          <div class="pcode">Puis un code à 6 chiffres, à saisir sur cet ordinateur</div>
        </div>
      </div></div>
    </figure>
    <div class="card group">
      <ol class="steps">
        <li><span>Tablette et ordinateur sur le même WiFi.</span></li>
        <li><span>Sur la tablette : <b>Réglages › Ordinateurs › Ajouter</b>, puis toucher cet ordinateur.</span></li>
        <li><span>Saisir ici le code qui s'affiche sur la tablette.</span></li>
      </ol>
      <div class="row"><span class="grow">Ordinateur absent de la liste : « Saisir l'adresse » sur la tablette, avec</span><code id="addr">…</code></div>
    </div>
    </div>
  </section>

  <!-- Connecteurs : un service par icône ; toucher une icône relie le service (connexion dans le navigateur) ou ouvre son détail dessous.
       Logos : simple-icons (CC0), sauf Microsoft, Google et Slack (logos officiels des marques, dessinés ici).
       Discord et Claude Code sont aussi des plugins de la tablette : leur tuile montre s'ils sont installés et actifs ;
       Teams (Microsoft) et Jira se règlent dans la modale de leur connecteur. -->
  <section id="connecteurs" data-title="Connecteurs" data-icon="link" data-group="Cet ordinateur">
    <div class="sechead"><h2>Connecteurs</h2><span class="dim">Touche une icône pour relier un service ou une appli à cet ordinateur, et choisir ce que la tablette en fait.</span></div>
    <div class="connectors">
        <button class="cx" data-cx="microsoft"><svg viewBox="0 0 24 24" class="logo" aria-hidden="true"><path fill="#F25022" d="M1 1h10.5v10.5H1z"/><path fill="#7FBA00" d="M12.5 1H23v10.5H12.5z"/><path fill="#00A4EF" d="M1 12.5h10.5V23H1z"/><path fill="#FFB900" d="M12.5 12.5H23V23H12.5z"/></svg><b>Microsoft</b><small><span class="dot" id="cxdot-microsoft"></span><span id="cxtext-microsoft">…</span></small></button>
        <button class="cx" data-cx="google"><svg viewBox="0 0 48 48" class="logo" aria-hidden="true"><path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"/><path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"/><path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"/><path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"/></svg><b>Google</b><small><span class="dot" id="cxdot-google"></span><span id="cxtext-google">…</span></small></button>
        <button class="cx" data-cx="ics"><svg viewBox="0 0 24 24" class="logo ink" aria-hidden="true"><path d="M17 12h-5v5h5v-5zM16 1v2H8V1H6v2H5c-1.11 0-1.99.9-1.99 2L3 19c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2h-1V1h-2zm3 18H5V8h14v11z"/></svg><b>Lien d'agenda</b><small><span class="dot" id="cxdot-ics"></span><span id="cxtext-ics">…</span></small></button>
        <button class="cx" data-cx="gitlab"><svg viewBox="0 0 24 24" class="logo" style="fill:#FC6D26" aria-hidden="true"><path d="m23.6004 9.5927-.0337-.0862L20.3.9814a.851.851 0 0 0-.3362-.405.8748.8748 0 0 0-.9997.0539.8748.8748 0 0 0-.29.4399l-2.2055 6.748H7.5375l-2.2057-6.748a.8573.8573 0 0 0-.29-.4412.8748.8748 0 0 0-.9997-.0537.8585.8585 0 0 0-.3362.4049L.4332 9.5015l-.0325.0862a6.0657 6.0657 0 0 0 2.0119 7.0105l.0113.0087.03.0213 4.976 3.7264 2.462 1.8633 1.4995 1.1321a1.0085 1.0085 0 0 0 1.2197 0l1.4995-1.1321 2.4619-1.8633 5.006-3.7489.0125-.01a6.0682 6.0682 0 0 0 2.0094-7.003z"/></svg><b>GitLab</b><small><span class="dot" id="cxdot-gitlab"></span><span id="cxtext-gitlab">…</span></small></button>
        <button class="cx" data-cx="jira"><svg viewBox="0 0 24 24" class="logo" style="fill:#0052CC" aria-hidden="true"><path d="M11.571 11.513H0a5.218 5.218 0 0 0 5.232 5.215h2.13v2.057A5.215 5.215 0 0 0 12.575 24V12.518a1.005 1.005 0 0 0-1.005-1.005zm5.723-5.756H5.736a5.215 5.215 0 0 0 5.215 5.214h2.129v2.058a5.218 5.218 0 0 0 5.215 5.214V6.758a1.001 1.001 0 0 0-1.001-1.001zM23.013 0H11.455a5.215 5.215 0 0 0 5.215 5.215h2.129v2.057A5.215 5.215 0 0 0 24 12.483V1.005A1.001 1.001 0 0 0 23.013 0Z"/></svg><b>Jira</b><small><span class="dot" id="cxdot-jira"></span><span id="cxtext-jira">…</span></small></button>
        <button class="cx" data-cx="discord"><svg viewBox="0 0 24 24" class="logo" style="fill:#5865F2" aria-hidden="true"><path d="M20.317 4.3698a19.7913 19.7913 0 00-4.8851-1.5152.0741.0741 0 00-.0785.0371c-.211.3753-.4447.8648-.6083 1.2495-1.8447-.2762-3.68-.2762-5.4868 0-.1636-.3933-.4058-.8742-.6177-1.2495a.077.077 0 00-.0785-.037 19.7363 19.7363 0 00-4.8852 1.515.0699.0699 0 00-.0321.0277C.5334 9.0458-.319 13.5799.0992 18.0578a.0824.0824 0 00.0312.0561c2.0528 1.5076 4.0413 2.4228 5.9929 3.0294a.0777.0777 0 00.0842-.0276c.4616-.6304.8731-1.2952 1.226-1.9942a.076.076 0 00-.0416-.1057c-.6528-.2476-1.2743-.5495-1.8722-.8923a.077.077 0 01-.0076-.1277c.1258-.0943.2517-.1923.3718-.2914a.0743.0743 0 01.0776-.0105c3.9278 1.7933 8.18 1.7933 12.0614 0a.0739.0739 0 01.0785.0095c.1202.099.246.1981.3728.2924a.077.077 0 01-.0066.1276 12.2986 12.2986 0 01-1.873.8914.0766.0766 0 00-.0407.1067c.3604.698.7719 1.3628 1.225 1.9932a.076.076 0 00.0842.0286c1.961-.6067 3.9495-1.5219 6.0023-3.0294a.077.077 0 00.0313-.0552c.5004-5.177-.8382-9.6739-3.5485-13.6604a.061.061 0 00-.0312-.0286zM8.02 15.3312c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9555-2.4189 2.157-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.9555 2.4189-2.1569 2.4189zm7.9748 0c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9554-2.4189 2.1569-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.946 2.4189-2.1568 2.4189Z"/></svg><b>Discord</b><small><span class="dot" id="cxdot-discord"></span><span id="cxtext-discord">…</span></small></button>
        <button class="cx" data-cx="claude"><svg viewBox="0 0 24 24" class="logo" style="fill:#D97757" aria-hidden="true"><path d="m4.7144 15.9555 4.7174-2.6471.079-.2307-.079-.1275h-.2307l-.7893-.0486-2.6956-.0729-2.3375-.0971-2.2646-.1214-.5707-.1215-.5343-.7042.0546-.3522.4797-.3218.686.0608 1.5179.1032 2.2767.1578 1.6514.0972 2.4468.255h.3886l.0546-.1579-.1336-.0971-.1032-.0972L6.973 9.8356l-2.55-1.6879-1.3356-.9714-.7225-.4918-.3643-.4614-.1578-1.0078.6557-.7225.8803.0607.2246.0607.8925.686 1.9064 1.4754 2.4893 1.8336.3643.3035.1457-.1032.0182-.0728-.164-.2733-1.3539-2.4467-1.445-2.4893-.6435-1.032-.17-.6194c-.0607-.255-.1032-.4674-.1032-.7285L6.287.1335 6.6997 0l.9957.1336.419.3642.6192 1.4147 1.0018 2.2282 1.5543 3.0296.4553.8985.2429.8318.091.255h.1579v-.1457l.1275-1.706.2368-2.0947.2307-2.6957.0789-.7589.3764-.9107.7468-.4918.5828.2793.4797.686-.0668.4433-.2853 1.8517-.5586 2.9021-.3643 1.9429h.2125l.2429-.2429.9835-1.3053 1.6514-2.0643.7286-.8196.85-.9046.5464-.4311h1.0321l.759 1.1293-.34 1.1657-1.0625 1.3478-.8804 1.1414-1.2628 1.7-.7893 1.36.0729.1093.1882-.0183 2.8535-.607 1.5421-.2794 1.8396-.3157.8318.3886.091.3946-.3278.8075-1.967.4857-2.3072.4614-3.4364.8136-.0425.0304.0486.0607 1.5482.1457.6618.0364h1.621l3.0175.2247.7892.522.4736.6376-.079.4857-1.2142.6193-1.6393-.3886-3.825-.9107-1.3113-.3279h-.1822v.1093l1.0929 1.0686 2.0035 1.8092 2.5075 2.3314.1275.5768-.3218.4554-.34-.0486-2.2039-1.6575-.85-.7468-1.9246-1.621h-.1275v.17l.4432.6496 2.3436 3.5214.1214 1.0807-.17.3521-.6071.2125-.6679-.1214-1.3721-1.9246L14.38 17.959l-1.1414-1.9428-.1397.079-.674 7.2552-.3156.3703-.7286.2793-.6071-.4614-.3218-.7468.3218-1.4753.3886-1.9246.3157-1.53.2853-1.9004.17-.6314-.0121-.0425-.1397.0182-1.4328 1.9672-2.1796 2.9446-1.7243 1.8456-.4128.164-.7164-.3704.0667-.6618.4008-.5889 2.386-3.0357 1.4389-1.882.929-1.0868-.0062-.1579h-.0546l-6.3385 4.1164-1.1293.1457-.4857-.4554.0608-.7467.2307-.2429 1.9064-1.3114Z"/></svg><b>Claude Code</b><small><span class="dot" id="cxdot-claude"></span><span id="cxtext-claude">…</span></small></button>
        <button class="cx" disabled><svg viewBox="0 0 24 24" class="logo" style="fill:#F24E1E" aria-hidden="true"><path d="M15.852 8.981h-4.588V0h4.588c2.476 0 4.49 2.014 4.49 4.49s-2.014 4.491-4.49 4.491zM12.735 7.51h3.117c1.665 0 3.019-1.355 3.019-3.019s-1.355-3.019-3.019-3.019h-3.117V7.51zm0 1.471H8.148c-2.476 0-4.49-2.014-4.49-4.49S5.672 0 8.148 0h4.588v8.981zm-4.587-7.51c-1.665 0-3.019 1.355-3.019 3.019s1.354 3.02 3.019 3.02h3.117V1.471H8.148zm4.587 15.019H8.148c-2.476 0-4.49-2.014-4.49-4.49s2.014-4.49 4.49-4.49h4.588v8.98zM8.148 8.981c-1.665 0-3.019 1.355-3.019 3.019s1.355 3.019 3.019 3.019h3.117V8.981H8.148zM8.172 24c-2.489 0-4.515-2.014-4.515-4.49s2.014-4.49 4.49-4.49h4.588v4.441c0 2.503-2.047 4.539-4.563 4.539zm-.024-7.51a3.023 3.023 0 0 0-3.019 3.019c0 1.665 1.365 3.019 3.044 3.019 1.705 0 3.093-1.376 3.093-3.068v-2.97H8.148zm7.704 0h-.098c-2.476 0-4.49-2.014-4.49-4.49s2.014-4.49 4.49-4.49h.098c2.476 0 4.49 2.014 4.49 4.49s-2.014 4.49-4.49 4.49zm-.097-7.509c-1.665 0-3.019 1.355-3.019 3.019s1.355 3.019 3.019 3.019h.098c1.665 0 3.019-1.355 3.019-3.019s-1.355-3.019-3.019-3.019h-.098z"/></svg><b>Figma</b><small><span class="soon">Bientôt</span></small></button>
        <button class="cx" disabled><svg viewBox="0 0 24 24" class="logo ink" aria-hidden="true"><path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"/></svg><b>GitHub</b><small><span class="soon">Bientôt</span></small></button>
        <button class="cx" disabled><svg viewBox="0 0 24 24" class="logo ink" aria-hidden="true"><path d="M4.459 4.208c.746.606 1.026.56 2.428.466l13.215-.793c.28 0 .047-.28-.046-.326L17.86 1.968c-.42-.326-.981-.7-2.055-.607L3.01 2.295c-.466.046-.56.28-.374.466zm.793 3.08v13.904c0 .747.373 1.027 1.214.98l14.523-.84c.841-.046.935-.56.935-1.167V6.354c0-.606-.233-.933-.748-.887l-15.177.887c-.56.047-.747.327-.747.933zm14.337.745c.093.42 0 .84-.42.888l-.7.14v10.264c-.608.327-1.168.514-1.635.514-.748 0-.935-.234-1.495-.933l-4.577-7.186v6.952L12.21 19s0 .84-1.168.84l-3.222.186c-.093-.186 0-.653.327-.746l.84-.233V9.854L7.822 9.76c-.094-.42.14-1.026.793-1.073l3.456-.233 4.764 7.279v-6.44l-1.215-.139c-.093-.514.28-.887.747-.933zM1.936 1.035l13.31-.98c1.634-.14 2.055-.047 3.082.7l4.249 2.986c.7.513.934.653.934 1.213v16.378c0 1.026-.373 1.634-1.68 1.726l-15.458.934c-.98.047-1.448-.093-1.962-.747l-3.129-4.06c-.56-.747-.793-1.306-.793-1.96V2.667c0-.839.374-1.54 1.447-1.632z"/></svg><b>Notion</b><small><span class="soon">Bientôt</span></small></button>
        <button class="cx" disabled><svg viewBox="0 0 122.8 122.8" class="logo" aria-hidden="true"><path fill="#E01E5A" d="M25.8 77.6c0 7.1-5.8 12.9-12.9 12.9S0 84.7 0 77.6s5.8-12.9 12.9-12.9h12.9v12.9zm6.5 0c0-7.1 5.8-12.9 12.9-12.9s12.9 5.8 12.9 12.9v32.3c0 7.1-5.8 12.9-12.9 12.9s-12.9-5.8-12.9-12.9V77.6z"/><path fill="#36C5F0" d="M45.2 25.8c-7.1 0-12.9-5.8-12.9-12.9S38.1 0 45.2 0s12.9 5.8 12.9 12.9v12.9H45.2zm0 6.5c7.1 0 12.9 5.8 12.9 12.9s-5.8 12.9-12.9 12.9H12.9C5.8 58.1 0 52.3 0 45.2s5.8-12.9 12.9-12.9h32.3z"/><path fill="#2EB67D" d="M97 45.2c0-7.1 5.8-12.9 12.9-12.9s12.9 5.8 12.9 12.9-5.8 12.9-12.9 12.9H97V45.2zm-6.5 0c0 7.1-5.8 12.9-12.9 12.9s-12.9-5.8-12.9-12.9V12.9C64.7 5.8 70.5 0 77.6 0s12.9 5.8 12.9 12.9v32.3z"/><path fill="#ECB22E" d="M77.6 97c7.1 0 12.9 5.8 12.9 12.9s-5.8 12.9-12.9 12.9-12.9-5.8-12.9-12.9V97h12.9zm0-6.5c-7.1 0-12.9-5.8-12.9-12.9s5.8-12.9 12.9-12.9h32.3c7.1 0 12.9 5.8 12.9 12.9s-5.8 12.9-12.9 12.9H77.6z"/></svg><b>Slack</b><small><span class="soon">Bientôt</span></small></button>
    </div>
    <h3 class="pf-h3">Fonctions de la tablette</h3>
    <div class="card group">
      <div id="fnlist"></div>
      <div class="dim note pf-msg" id="fnmsg" role="status"></div>
    </div>
    <!-- Modale du connecteur touché : étapes pour le relier, ou son compte. Fermée par la croix, Échap ou un clic à côté. -->
    <dialog class="cxdialog" id="cxdialog" aria-labelledby="cxtitle">
    <div class="cxbody">
    <header class="cxhead"><h2 id="cxtitle"></h2>
      <button class="close" id="cxclose" aria-label="Fermer"><svg class="i" viewBox="0 0 24 24" aria-hidden="true"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg></button>
    </header>
    <div class="card cxpanel" id="agenda" hidden>
      <div class="dim" id="calstate">…</div>
      <!-- Comptes connectés et boutons « Se connecter avec … » (si l'appli est enregistrée, voir src/accounts.ts). -->
      <div id="accounts"></div>
      <div id="accountmsg" class="dim" style="margin-top:8px"></div>
      <details id="ics">
        <summary id="icssummary">Lien ICS de ton agenda</summary>
        <input id="calurl" placeholder="Lien ICS (https://… ou webcal://…)" autocomplete="off">
        <div class="actions"><button id="calsave">Enregistrer</button><button class="ghost" id="calclear">Retirer</button></div>
        <div id="calmsg" class="dim" style="margin-top:8px"></div>
        <details class="dim" id="icshelp">
          <summary>Où trouver le lien ICS ?</summary>
          <b>Outlook</b> (outlook.office.com) : Paramètres › Calendrier › Calendriers partagés › Publier un calendrier, choisir
          « Peut afficher tous les détails », Publier, puis copier le lien <b>ICS</b>.<br>
          <b>Google Agenda</b> : Paramètres › (ton agenda) › Intégrer l'agenda › Adresse secrète au format iCal.<br>
          <b>iCloud</b> : app Calendrier › clic droit sur l'agenda › Partager › Agenda public, copier le lien webcal://.
        </details>
      </details>
    </div>
    <div class="card cxpanel" id="gitlab" hidden>
      <div class="dim" id="gitlabstate"></div>
      <div id="gitlabaccount"></div>
      <div id="gitlabmsg" class="dim" style="margin-top:8px"></div>
    </div>
    <!-- Étapes du connecteur touché : placées en tête du panneau ouvert (renderSteps). -->
    <div class="cxsteps" id="cxsteps" hidden>
      <ol id="cxlist"></ol>
      <p class="dim" id="cxnote"></p>
      <div class="actions" id="cxaction"></div>
    </div>
    <div class="card cxpanel" id="jira" hidden>
      <div class="dim" id="jirastate"></div>
    </div>
    <div class="card cxpanel" id="plugin" hidden>
      <div class="dim" id="plugindesc"></div>
    </div>
    <!-- Interrupteur du plugin de la tuile ouverte (Discord, Claude Code, Jira ; Teams dans Microsoft) : ce que la tablette en fait. -->
    <div class="card group cxplug" id="cxplug" hidden>
      <div class="row"><label for="plugon" id="pluglabel">Actif sur la tablette</label><input type="checkbox" id="plugon"></div>
      <div class="dim note pf-msg" id="plugmsg" role="status"></div>
    </div>
    </div>
    </dialog>
  </section>

  <!-- Porte MCP du compagnon (src/routes/mcp.ts) : une IA de bureau règle la tablette avec les outils de src/mcp-tools.ts.
       Claude Desktop ne sait que lancer un programme : le pont src/mcp-bridge.ts, avec le node du compagnon. -->
  <section id="ia" data-title="Connecter une IA" data-icon="extension" data-group="Cet ordinateur">
    <div class="sechead"><h2>Connecter une IA</h2><span class="dim">Demande à ton IA de régler la tablette : thème, profils, widgets, touches, plugins. Elle passe par cet ordinateur, l'adresse ne marche pas ailleurs.</span></div>
    <div class="card group mcp">
      <div class="row"><span class="grow"><b>Claude Desktop</b><br><span class="dim">Réglages › Développeur › Modifier la configuration : coller dans claude_desktop_config.json, puis relancer Claude.</span></span><button class="ghost" data-copy="mcpdesktop">Copier</button></div>
      <pre id="mcpdesktop">…</pre>
      <div class="row"><span class="grow"><b>Claude Code</b><br><code id="mcpcode">…</code></span><button class="ghost" data-copy="mcpcode">Copier</button></div>
      <div class="row"><span class="grow"><b>Cursor, VS Code et autres</b><br><span class="dim">Serveur MCP de type HTTP, à l'adresse</span> <code id="mcpurl">…</code></span><button class="ghost" data-copy="mcpurl">Copier</button></div>
      <div class="dim note">ChatGPT ne joint que des adresses publiques sur Internet : pas encore possible.</div>
      <div class="dim note" id="mcpmsg" role="status"></div>
    </div>
  </section>

  <!-- Appli portable seulement (voir src/routes/portable.ts) : rien n'est installé, ces réglages sont ceux de l'utilisateur. -->
  <section id="panel" data-title="Cette appli" data-icon="power" data-group="Cet ordinateur" hidden>
    <div class="sechead"><h2>Cette appli</h2><span class="dim" id="appinfo"></span></div>
    <div class="card group">
      <div class="row"><label for="autostart">Lancer au démarrage de l'ordinateur</label><input type="checkbox" id="autostart"></div>
      <div class="dim note">Rien n'est inscrit tant que la case n'est pas cochée. Sinon, rouvre « UnoMate » quand tu en as besoin.</div>
      <div id="firewallrow" hidden>
        <div class="row"><span class="grow" id="firewallstate"></span><button id="firewallopen">Ouvrir le pare-feu</button></div>
        <div class="dim note">Réseaux privés seulement. Windows demande l'accord d'un administrateur.</div>
      </div>
      <div id="legacyrow" hidden>
        <div class="row"><span class="grow"><b>Ancienne installation trouvée</b><br><span class="dim" id="legacylist"></span></span><button id="legacyremove">Retirer</button></div>
        <div class="dim note">Tes réglages, ton agenda et l'appairage de la tablette sont gardés.</div>
      </div>
      <div class="row"><span class="grow" id="appupdatestate">Mise à jour depuis la tablette (même WiFi).</span><button class="ghost" id="updatecheck">Chercher</button><button id="updateinstall" hidden>Installer</button></div>
      <div class="row"><span class="grow">Extension du navigateur (onglets, page Média) : à charger en mode développeur depuis <code id="extpath"></code></span></div>
      <div class="row"><span class="grow dim">Tout oublier retire le lancement au démarrage, l'appairage, l'agenda et les tâches de cet ordinateur.</span><button class="ghost" id="quit">Quitter</button><button class="danger" id="forget">Tout oublier</button></div>
      <div id="panelmsg" class="dim note"></div>
    </div>
  </section>
</main>
<script>
  const OS = { mac: "macOS", windows: "Windows", other: "Linux (pilotage indisponible)" };
  let version = null;
  async function refresh() {
    try {
      const s = await (await fetch("/local/status")).json();
      // Mise à jour posée : le nouveau compagnon répond avec une autre version, la page se recharge.
      if (version !== null && s.version !== version) return location.reload();
      version = s.version;
      document.getElementById("name").textContent = s.name;
      document.getElementById("waitname").textContent = s.name;
      document.getElementById("licences").hidden = !s.licences;
      document.body.classList.remove("down");
      document.getElementById("srvdot").className = "dot on";
      document.getElementById("access").hidden = s.accessibility !== false;
      document.getElementById("accessline").hidden = s.accessibility !== false;
      document.getElementById("os").textContent = "Compagnon prêt · " + (OS[s.os] || s.os);
      document.getElementById("addr").textContent = s.addresses.map((a) => a + ":" + (s.tlsPort || s.port)).join("  ·  ") || "aucune (pas de réseau)";
      document.getElementById("dot").className = "dot" + (s.tablet ? " on" : "");
      document.getElementById("state").textContent = s.tablet ? "Tablette connectée" : "Tablette non connectée";
      const c = s.calendar, n = c.env + c.saved.length + c.accounts.length;
      document.getElementById("calstate").textContent = n === 0 ? "Aucun agenda : la tablette propose de le relier."
        : c.saved.length ? "Lien enregistré : " + c.saved.join(", ") : "Connecté.";
      renderAccounts(c);
      renderGitlab(s.gitlab);
      renderConnectors(s);
      renderMcp(s.mcp);
      renderSteps();
      renderTablet(s.tabletInfo);
      placeConnect(!!s.tabletInfo);
      buildRail();
      document.title = s.tabletInfo ? "Compagnon · " + (s.tabletInfo.name || "tablette") : "UnoMate";
      const p = s.pairing, card = document.getElementById("pairing");
      const shown = card.style.display === "block";
      card.style.display = p ? "block" : "none";
      if (p) {
        document.getElementById("tablet").textContent = p.tablet;
        // Ligne du jour : le point avance pendant la validité du code.
        if (p.ttlS) document.getElementById("pairtimer").style.setProperty("--p", String(1 - p.remainingS / p.ttlS));
        if (!shown) document.getElementById("paircode").focus();
      }
    } catch (e) {
      // Plus de réponse : la page le dit partout (barre, état) et cache ce qui ferait croire le contraire (code, réglages).
      document.body.classList.add("down");
      document.getElementById("srvdot").className = "dot ko";
      document.getElementById("os").textContent = "Compagnon arrêté : rouvre « UnoMate »";
      document.getElementById("dot").className = "dot";
      document.getElementById("state").textContent = "Tablette : état inconnu";
    }
  }
  // Comptes : reconstruits seulement s'ils changent (la page se rafraîchit toutes les 1,5 s).
  let accountsKey = "", firstRender = true;
  function renderAccounts(c) {
    const key = JSON.stringify([c.accounts, c.providers]);
    if (key !== accountsKey) {
      accountsKey = key;
      const box = document.getElementById("accounts");
      box.replaceChildren();
      for (const a of c.accounts) {
        const row = document.createElement("div");
        row.className = "account";
        const label = document.createElement("span");
        label.className = "grow";
        label.append(stateDot(!(a.expired || a.mailMissing)),
          a.label + " · " + a.email + (a.expired ? " (à reconnecter)" : a.mailMissing ? " (à reconnecter pour le courrier)" : ""));
        const off = document.createElement("button");
        off.className = "ghost";
        off.textContent = "Déconnecter";
        off.onclick = async () => { await fetch("/local/accounts/" + a.provider, { method: "DELETE" }); refresh(); };
        row.append(label);
        if (a.expired || a.mailMissing) row.append(connectLink(a.provider, "Reconnecter"));
        row.append(off);
        box.append(row);
      }
      // Avec un compte possible, le lien ICS devient la solution de repli.
      document.getElementById("icssummary").textContent = c.providers.length ? "Autre agenda : lien ICS" : "Lien ICS de ton agenda";
    }
    if (firstRender) {
      firstRender = false;
      if (!c.providers.length) document.getElementById("ics").open = true;
      if (new URLSearchParams(location.search).get("section") === "agenda" && !c.providers.length) {
        document.getElementById("icshelp").open = true;
        document.getElementById("calurl").focus();
      }
    }
  }
  // GitLab : reconstruit seulement s'il change (même raison que les comptes).
  let gitlabKey = "";
  function renderGitlab(g) {
    const key = JSON.stringify(g);
    if (key === gitlabKey) return;
    gitlabKey = key;
    const box = document.getElementById("gitlabaccount");
    box.replaceChildren();
    const state = document.getElementById("gitlabstate");
    if (g.account) {
      state.textContent = "La tablette lit et fusionne tes MR avec ce compte.";
      const row = document.createElement("div");
      row.className = "account";
      const label = document.createElement("span");
      label.className = "grow";
      label.append(stateDot(!g.account.expired),
        "GitLab" + (g.account.username ? " · " + g.account.username : "") + (g.account.expired ? " (à reconnecter)" : ""));
      const off = document.createElement("button");
      off.className = "ghost";
      off.textContent = "Déconnecter GitLab";
      off.onclick = async () => { await fetch("/local/accounts/gitlab", { method: "DELETE" }); refresh(); };
      row.append(label);
      if (g.account.expired) row.append(connectLink("gitlab", "Reconnecter"));
      row.append(off);
      box.append(row);
    } else {
      state.textContent = g.token ? "Connecté." : "Non connecté.";
    }
  }
  // Réglages de la tablette : pas réécrits pendant un réglage en cours (curseur tenu, envoi pas encore fini).
  let tabletBusyUntil = 0;
  const percent = (v) => Math.round(v * 100);
  let tabletFocused = false;
  // Connecter une IA : textes posés seulement s'ils changent (la page se rafraîchit toutes les 1,5 s, une sélection resterait).
  function renderMcp(m) {
    const desktop = JSON.stringify({ mcpServers: { unomate: m.desktop } }, null, 2);
    const texts = { mcpdesktop: desktop, mcpcode: "claude mcp add --transport http unomate " + m.url, mcpurl: m.url };
    for (const [id, text] of Object.entries(texts)) if ($(id).textContent !== text) $(id).textContent = text;
  }
  for (const b of document.querySelectorAll("[data-copy]")) {
    b.onclick = async () => {
      try {
        await navigator.clipboard.writeText($(b.dataset.copy).textContent);
        $("mcpmsg").textContent = "Copié.";
      } catch (e) {
        $("mcpmsg").textContent = "Copie impossible : sélectionne le texte à la main.";
      }
    };
  }
  function renderTablet(t) {
    document.getElementById("tabletcard").hidden = !t;
    if (!t) return;
    // « Régler depuis l'ordi » : carte mise en avant une fois, dès que la tablette est vue connectée.
    if (!tabletFocused && new URLSearchParams(location.search).get("section") === "tablette") {
      tabletFocused = true;
      const card = document.querySelector("#tabletcard .card");
      card.classList.add("focus");
      card.scrollIntoView({ block: "center" });
    }
    document.getElementById("tabletname").textContent = t.name || "Tablette";
    const b = t.battery;
    document.getElementById("tabletinfo").textContent = [
      t.model, t.app && "appli " + t.app, b && "batterie " + b.level + " %" + (b.charging ? " (en charge)" : ""),
    ].filter(Boolean).join(" · ");
    renderUpdate(t);
    const st = t.settings;
    document.getElementById("tabletold").hidden = !!st;
    document.getElementById("tabletsettings").hidden = !st;
    if (!st || Date.now() < tabletBusyUntil) return;
    for (const btn of document.querySelectorAll(".themes button")) btn.setAttribute("aria-pressed", String(btn.dataset.theme === st.theme));
    for (const id of ["brightness", "volume"]) {
      document.getElementById(id).value = percent(st[id]);
      document.getElementById(id + "value").textContent = percent(st[id]) + " %";
      paintRange(document.getElementById(id));
    }
    showTwin(st.theme);
    document.getElementById("nightdim").checked = st.nightDim;
    document.getElementById("focusdnd").checked = st.focusDnd;
  }
  // Mise à jour de l'appli de la tablette : état remonté par la tablette (RemoteUpdate). Recherche lancée seule une fois
  // par version de l'appli : à l'ouverture, puis après une installation (l'appli relancée n'a encore rien cherché).
  // updateNote : refus de la dernière demande, gardé jusqu'à la suivante (sinon effacé au rafraîchissement).
  let checkedFor = null, updateBusy = false, updateNote = null;
  function renderUpdate(t) {
    const u = t.update;
    document.getElementById("updaterow").hidden = !u;
    if (!u || updateBusy) return;
    let text, action = null;
    if (u.installing) text = "Installation de la version " + u.available + " en cours : la tablette redémarre l'appli à la fin.";
    else if (!u.kiosk) text = "Mise à jour d'ici possible seulement en kiosque (pas dans l'appli d'essai).";
    else if (u.error) [text, action] = [u.error, "check"];
    else if (!u.checked) text = "Recherche d'une mise à jour…";
    else if (u.available) [text, action] = ["Version " + u.available + " disponible (installée : " + t.app + ").", "install"];
    else [text, action] = ["Appli à jour (" + t.app + ").", "check"];
    if (updateNote) text = updateNote;
    document.getElementById("updatestate").textContent = text;
    const btn = document.getElementById("updatebtn");
    btn.hidden = !action;
    btn.textContent = action === "install" ? "Mettre à jour" : u.error ? "Réessayer" : "Rechercher";
    btn.dataset.action = action || "";
    if (u.kiosk && !u.checked && checkedFor !== t.app) {
      checkedFor = t.app;
      sendUpdate("check");
    }
  }
  async function sendUpdate(action) {
    updateBusy = true;
    updateNote = null;
    const state = document.getElementById("updatestate"), btn = document.getElementById("updatebtn");
    state.textContent = action === "install" ? "Lancement de l'installation…" : "Recherche d'une mise à jour…";
    btn.hidden = true;
    try {
      const res = await fetch("/local/tablet/update", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ action }) });
      const r = await res.json();
      updateBusy = false;
      if (!res.ok) updateNote = "Pas fait : " + (r.error || r.message);
      refresh();
    } catch (e) {
      updateBusy = false;
      updateNote = "Compagnon injoignable";
      state.textContent = updateNote;
    }
  }
  document.getElementById("updatebtn").onclick = (e) => sendUpdate(e.target.dataset.action);
  async function saveTablet(change) {
    const msg = document.getElementById("tabletmsg");
    tabletBusyUntil = Date.now() + 20000;
    msg.textContent = "Envoi à la tablette…";
    try {
      const res = await fetch("/local/tablet/settings", { method: "PUT", headers: { "content-type": "application/json" }, body: JSON.stringify(change) });
      const r = await res.json();
      msg.textContent = res.ok ? "Réglé sur la tablette." : "Pas réglé : " + (r.error || r.message);
    } catch (e) {
      msg.textContent = "Compagnon injoignable";
    }
    tabletBusyUntil = 0;
    refresh();
  }
  for (const btn of document.querySelectorAll(".themes button")) {
    btn.onclick = () => {
      for (const other of document.querySelectorAll(".themes button")) other.setAttribute("aria-pressed", String(other === btn));
      showTwin(btn.dataset.theme);
      saveTablet({ theme: btn.dataset.theme });
    };
  }
  for (const id of ["brightness", "volume"]) {
    const range = document.getElementById(id);
    // Valeur affichée pendant le glisser, envoyée au lâcher.
    range.oninput = () => {
      tabletBusyUntil = Date.now() + 20000;
      document.getElementById(id + "value").textContent = range.value + " %";
      paintRange(range);
    };
    range.onchange = () => saveTablet({ [id]: range.value / 100 });
  }
  document.getElementById("nightdim").onchange = (e) => saveTablet({ nightDim: e.target.checked });
  document.getElementById("focusdnd").onchange = (e) => saveTablet({ focusDnd: e.target.checked });
  function connectLink(provider, text) {
    const link = document.createElement("a");
    link.className = "button";
    link.href = "/oauth/" + provider + "/start";
    link.textContent = text;
    return link;
  }
  const ACCOUNT_MSG = {
    ok: "✓ Compte connecté : l'agenda arrive sur la tablette dans la minute.",
    refused: "Connexion annulée.",
    error: "La connexion a échoué. Réessaie ; sinon colle un lien ICS ci-dessous.",
  };
  const accountResult = new URLSearchParams(location.search).get("account");
  if (ACCOUNT_MSG[accountResult]) document.getElementById("accountmsg").textContent = ACCOUNT_MSG[accountResult];

  async function saveCalendar(urls) {
    const msg = document.getElementById("calmsg");
    msg.textContent = "Vérification…";
    try {
      const res = await fetch("/local/calendar", { method: "PUT", headers: { "content-type": "application/json" }, body: JSON.stringify({ urls }) });
      const r = await res.json();
      if (!res.ok) msg.textContent = "Lien refusé : " + (r.message || r.error);
      else if (!urls.length) msg.textContent = "Agenda retiré.";
      else msg.textContent = r.ok ? "Agenda enregistré : " + r.today + " événement(s) aujourd'hui." : "Enregistré, mais illisible : " + r.error;
      document.getElementById("calurl").value = "";
      refresh();
    } catch (e) {
      msg.textContent = "Compagnon injoignable";
    }
  }
  document.getElementById("pairform").onsubmit = async (e) => {
    e.preventDefault();
    const input = document.getElementById("paircode"), msg = document.getElementById("pairmsg");
    try {
      const res = await fetch("/local/pair", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ code: input.value }) });
      const json = await res.json();
      msg.textContent = res.ok ? "Tablette connectée : « " + json.tablet + " »." : json.error;
      if (res.ok) input.value = "";
    } catch (err) {
      msg.textContent = "Compagnon injoignable";
    }
  };
  document.getElementById("accessopen").onclick = async () => {
    await fetch("/system/accessibility", { method: "POST" });
  };
  document.getElementById("calsave").onclick = () => {
    const url = document.getElementById("calurl").value.trim();
    if (url) saveCalendar([url]);
  };
  document.getElementById("calclear").onclick = () => saveCalendar([]);
  const GITLAB_MSG = {
    ok: "✓ GitLab connecté : l'onglet GitLab de la tablette se remplit dans quelques secondes.",
    refused: "Connexion annulée.",
    error: "La connexion à GitLab a échoué. Réessaie.",
  };
  const gitlabResult = new URLSearchParams(location.search).get("gitlab");
  if (GITLAB_MSG[gitlabResult]) document.getElementById("gitlabmsg").textContent = GITLAB_MSG[gitlabResult];

  // Carte « Cette appli » : lue au chargement et après chaque action, pas à chaque rafraîchissement.
  const $ = (id) => document.getElementById(id);
  const say = (text) => { $("panelmsg").textContent = text; };
  async function post(url, body) {
    const res = await fetch(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body || {}) });
    const json = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(json.error || json.message || "Échec");
    return json;
  }
  async function loadPanel() {
    try {
      const p = await (await fetch("/local/panel")).json();
      $("panel").hidden = !p.portable;
      if (!p.portable) return;
      $("appinfo").textContent = "Version " + p.version + " · " + p.app;
      $("autostart").checked = p.autostart;
      $("firewallrow").hidden = p.firewall === null;
      $("firewallstate").textContent = p.firewall ? "Pare-feu ouvert : la tablette peut joindre ce PC." : "Pare-feu fermé : la tablette ne trouvera pas ce PC.";
      $("firewallopen").hidden = p.firewall === true;
      $("legacyrow").hidden = p.legacy.length === 0;
      $("legacylist").textContent = p.legacy.join(" · ");
      $("extpath").textContent = p.extension;
    } catch (e) {
      $("panel").hidden = true;
    }
  }
  // last : l'action arrête ou relance le compagnon, la carte ne se relit pas (elle resterait vide).
  async function act(fn, okText, last) {
    say("…");
    try { say(okText ? okText(await fn()) : ""); } catch (e) { say(e.message); }
    if (!last) loadPanel();
  }
  $("autostart").onchange = (e) => act(() => post("/local/autostart", { on: e.target.checked }),
    (r) => r.autostart ? "Lancement au démarrage activé." : "Lancement au démarrage retiré.");
  $("firewallopen").onclick = () => act(() => post("/local/firewall"), (r) => r.ok ? "Pare-feu ouvert." : "Pare-feu inchangé : l'accord administrateur a été refusé.");
  $("legacyremove").onclick = () => act(() => post("/local/legacy/remove"),
    (r) => r.failed.length ? "Pas pu retirer : " + r.failed.join(", ") : "Ancienne installation retirée.");
  $("updatecheck").onclick = () => act(async () => {
    const r = await (await fetch("/local/update")).json().then((j) => j.error ? Promise.reject(new Error(j.error)) : j);
    $("updateinstall").hidden = !r.available;
    $("updateinstall").textContent = "Installer la version " + r.available;
    $("appupdatestate").textContent = r.available ? "Version " + r.available + " sur la tablette (tu as la " + r.current + ")." : "À jour (version " + r.current + ").";
  });
  $("updateinstall").onclick = () => act(() => post("/local/update"), (r) => "Version " + r.version + " téléchargée : l'appli redémarre, la page se recharge.", true);
  $("quit").onclick = () => act(() => post("/local/quit"), () => "Compagnon arrêté. Rouvre « UnoMate » pour le relancer.", true);
  $("forget").onclick = () => {
    if (confirm("Tout oublier ? La tablette devra être appairée à nouveau, l'agenda et les tâches de cet ordinateur sont effacés.")) {
      act(() => post("/local/forget", { sure: true }), () => "Tout est oublié. Tu peux supprimer l'appli.", true);
    }
  };
  // Point d'état d'un compte : vert s'il marche, rouge s'il faut le reconnecter.
  function stateDot(ok) {
    const d = document.createElement("span");
    d.className = "dot " + (ok ? "on" : "ko");
    return d;
  }

  // Aperçu de la tablette : couleurs des thèmes (fond, tuile, texte, texte 2, maintenant, filet, principale), d'après palettes.xml.
  const THEMES = {
    CADRAN: ["#EEE0DA", "#FCF5F1", "#2B1E1F", "#5A4746", "#CA0538", "#D6C1B9", "#2B1E1F"],
    CADRAN_NUIT: ["#1D1617", "#2A2122", "#EBDDD9", "#CFC0BC", "#FF5165", "#574849", "#EBDDD9"],
    CLAIR: ["#FAF2F1", "#FFFFFF", "#1E1B1B", "#4D4545", "#CA0538", "#D0C4C3", "#1E1B1B"],
    SOMBRE: ["#100D0D", "#221F1F", "#E9E1E0", "#D0C4C3", "#FF5165", "#4D4545", "#E9E1E0"],
    RETRO: ["#F3DECB", "#FBEFE3", "#552805", "#7A4A25", "#B82800", "#D9B99A", "#552805"],
    CONTRASTE: ["#FFFFFF", "#F2F2F2", "#140F0F", "#140F0F", "#960026", "#140F0F", "#140F0F"],
    REGISTRE: ["#F8F3E5", "#FFFDF6", "#1D1C14", "#4A4838", "#B3261E", "#D6CFB8", "#175E34"],
    STUDIO: ["#F6F3F6", "#FFFFFF", "#1B1B1E", "#4A4850", "#C0153A", "#D4D0D8", "#513FFF"],
    MAISON: ["#FFF1E7", "#FFFAF5", "#211A14", "#5A4A3E", "#B3412F", "#E3CDBD", "#456179"],
    TABLEAU: ["#F4F3F5", "#FFFFFF", "#1A1C1D", "#44484C", "#C62828", "#C9CDD2", "#005592"],
    TERMINAL: ["#0C0F0C", "#171B17", "#E2E3DD", "#A9AFA3", "#FFB000", "#2E352E", "#43E188"],
  };
  const VARS = ["bg", "tile", "ink", "ink2", "now", "line"];
  for (const btn of document.querySelectorAll(".themes button")) {
    const c = THEMES[btn.dataset.theme];
    if (c) btn.querySelector("span").style.background = "linear-gradient(135deg, " + c[0] + " 0 42%, " + c[6] + " 42% 68%, " + c[4] + " 68%)";
  }
  function showTwin(theme) {
    const c = THEMES[theme] || THEMES.CADRAN, twin = document.getElementById("twin");
    VARS.forEach((v, i) => twin.style.setProperty("--t-" + v, c[i]));
    const btn = document.querySelector('.themes button[data-theme="' + theme + '"]');
    document.getElementById("twintheme").textContent = btn ? btn.textContent : "";
  }
  // Curseur : partie remplie dessinée par --v ; la luminosité assombrit aussi l'aperçu.
  function paintRange(range) {
    const min = Number(range.min || 0), max = Number(range.max || 100);
    range.style.setProperty("--v", ((range.value - min) / (max - min)) * 100 + "%");
    if (range.id === "brightness") document.getElementById("twin").style.filter = "brightness(" + Math.min(1, 0.45 + range.value * 0.0065) + ")";
  }
  function tickTwin() {
    const now = new Date(), minutes = now.getHours() * 60 + now.getMinutes();
    document.getElementById("twinclock").textContent = String(now.getHours()).padStart(2, "0") + ":" + String(now.getMinutes()).padStart(2, "0");
    document.getElementById("twindate").textContent = now.toLocaleDateString("fr-FR", { weekday: "long", day: "numeric", month: "long" });
    // Ligne du jour de la tablette : de 7 h à 22 h.
    document.getElementById("twinline").style.setProperty("--d", Math.min(100, Math.max(0, ((minutes - 420) / 900) * 100)) + "%");
    for (const id of ["brightness", "volume"]) paintRange(document.getElementById(id));
  }

  // Connecteurs : état de chaque service sur son icône ; « on » relié, « off » à relier, « na » pas encore disponible ici.
  const cxState = {};
  function setTile(cx, state, text, warn) {
    cxState[cx] = state;
    const tile = document.querySelector('.cx[data-cx="' + cx + '"]');
    tile.classList.toggle("na", state === "na");
    document.getElementById("cxdot-" + cx).className = "dot" + (warn ? " ko" : state === "on" ? " on" : "");
    document.getElementById("cxtext-" + cx).textContent = text;
  }
  function renderConnectors(s) {
    const c = s.calendar, g = s.gitlab;
    // État affiché : seulement « Connecté » ou « Non connecté » (un compte expiré ne marche plus : non connecté).
    for (const p of ["microsoft", "google"]) {
      const a = c.accounts.find((x) => x.provider === p);
      if (a && !a.expired) setTile(p, "on", a.mailMissing ? "Non connecté" : "Connecté", a.mailMissing);
      else setTile(p, c.providers.some((x) => x.provider === p) ? "off" : "na", "Non connecté", !!a);
    }
    setTile("ics", c.saved.length + c.env ? "on" : "off", c.saved.length + c.env ? "Connecté" : "Non connecté");
    if ((g.account && !g.account.expired) || (!g.account && g.token)) setTile("gitlab", "on", "Connecté");
    else setTile("gitlab", g.available ? "off" : "na", "Non connecté", !!g.account);
    const j = s.jira || { configured: false };
    setTile("jira", j.configured ? "on" : "off", j.configured ? "Connecté" : "Non connecté");
    // Pas relié : les étapes suffisent, l'état est déjà sur l'icône.
    document.getElementById("jirastate").textContent = j.configured ? "Connecté à " + j.site + "." : "";
  }
  // Étapes pour relier chaque connecteur ; action : bouton qui ouvre la connexion dans le navigateur.
  // Un seul panneau ouvert sous les icônes, l'icône correspondante est marquée.
  const STEPS = {
    microsoft: {
      title: "Relier Microsoft",
      steps: ["Touche « Se connecter avec Microsoft » : la page de connexion s'ouvre.", "Connecte-toi avec ton compte Microsoft, pro ou personnel.",
        "Accepte l'accès à ton agenda et à ton courrier.", "Tu reviens ici : ton agenda arrive sur la tablette dans la minute."],
      action: "Se connecter avec Microsoft",
    },
    google: {
      title: "Relier Google",
      steps: ["Touche « Se connecter avec Google » : la page de connexion s'ouvre.", "Choisis ton compte Google.",
        "Accepte l'accès à ton agenda.", "Tu reviens ici : ton agenda arrive sur la tablette dans la minute."],
      action: "Se connecter avec Google",
    },
    ics: {
      title: "Relier un agenda par son lien",
      steps: ["Copie le lien ICS de ton agenda (Outlook, Google ou iCloud : voir « Où trouver le lien ICS ? »).", "Colle-le dans le champ ci-dessous.",
        "Touche « Enregistrer » : ses rendez-vous arrivent sur la tablette."],
    },
    gitlab: {
      title: "Relier GitLab",
      steps: ["Touche « Se connecter avec GitLab » : GitLab s'ouvre dans le navigateur.", "Connecte-toi à ton compte GitLab si besoin.",
        "Touche « Autoriser » : UnoMate lit tes MR et tes pipelines.", "Tu reviens ici : les widgets GitLab de la tablette se remplissent."],
      action: "Se connecter avec GitLab",
    },
    jira: {
      title: "Relier Jira",
      steps: ["Crée un jeton d'API sur id.atlassian.com › Sécurité › Jetons d'API.",
        "Ouvre le fichier .env du compagnon et renseigne JIRA_SITE (ton site Atlassian), JIRA_EMAIL et JIRA_API_TOKEN.",
        "Relance UnoMate : tes tickets arrivent dans le widget Jira de la tablette."],
    },
  };
  const PANEL = { microsoft: "agenda", google: "agenda", ics: "agenda", gitlab: "gitlab", jira: "jira", discord: "plugin", claude: "plugin" };
  // Plugin réglé dans la modale d'un autre connecteur : Teams fait partie de Microsoft.
  const PLUGIN_OF = { microsoft: "teams" };
  let openCx = null, openName = null, stepsKey = "";
  const dialog = document.getElementById("cxdialog");
  const TITLES = { agenda: "Agenda", gitlab: "GitLab", jira: "Jira" };
  function openPanel(name, cx) {
    openCx = cx === undefined ? (name === "agenda" ? "ics" : name) : cx;
    openName = name;
    if (!dialog.open) dialog.showModal();
    for (const panel of document.querySelectorAll(".cxpanel")) panel.hidden = panel.id !== name;
    for (const tile of document.querySelectorAll(".cx[data-cx]")) tile.setAttribute("aria-pressed", String(tile.dataset.cx === openCx));
    // Le lien ICS n'a sa place que sous « Lien d'agenda ».
    document.getElementById("ics").hidden = name === "agenda" && openCx !== "ics";
    stepsKey = "";
    renderSteps();
    renderPluginSwitch();
    loadPlugins();
  }
  // Pas relié : titre, étapes et bouton de connexion en tête du panneau ; relié : le compte suffit, les étapes se cachent.
  function renderSteps() {
    const box = document.getElementById("cxsteps"), st = STEPS[openCx], state = cxState[openCx];
    const tile = document.querySelector('.cx[data-cx="' + openCx + '"]');
    const name = tile ? tile.querySelector("b").textContent : TITLES[openName];
    // Titre de la modale : « Relier X » tant que ce n'est pas relié, sinon le nom du service.
    document.getElementById("cxtitle").textContent = st && state !== "on" && state ? st.title : name || "";
    if (!st) box.hidden = true;
    if (!st || !state) return;
    const key = openCx + ":" + state;
    if (key === stepsKey) return;
    stepsKey = key;
    document.getElementById(PANEL[openCx]).prepend(box);
    box.hidden = state === "on";
    const list = document.getElementById("cxlist"), action = document.getElementById("cxaction");
    list.replaceChildren();
    action.replaceChildren();
    const na = state === "na";
    document.getElementById("cxnote").textContent = na ? "Cette connexion n'est pas encore disponible sur cet ordinateur." : "";
    list.hidden = na;
    if (!na) for (const text of st.steps) list.append(Object.assign(document.createElement("li"), { textContent: text }));
    if (st.action && !na) action.append(connectLink(openCx, st.action));
    // Agenda sans connexion possible : le lien ICS reste la voie.
    if (na && PANEL[openCx] === "agenda") {
      const alt = document.createElement("button");
      alt.className = "ghost";
      alt.textContent = "Relier par un lien d'agenda";
      alt.onclick = () => document.querySelector('.cx[data-cx="ics"]').click();
      action.append(alt);
    }
  }
  document.getElementById("cxclose").onclick = () => dialog.close();
  // Clic sur le fond (hors de la boîte) : fermer.
  dialog.addEventListener("click", (e) => { if (e.target === dialog) dialog.close(); });
  dialog.addEventListener("close", () => {
    for (const tile of document.querySelectorAll(".cx[data-cx]")) tile.setAttribute("aria-pressed", "false");
  });
  for (const tile of document.querySelectorAll(".cx[data-cx]")) {
    tile.onclick = () => {
      const cx = tile.dataset.cx;
      openPanel(PANEL[cx], cx);
      if (cx === "ics") {
        document.getElementById("ics").open = true;
        document.getElementById("calurl").focus();
      }
    };
  }

  // Plugins de la tablette : applis (tuiles Discord, Claude Code ; Teams et Jira dans la modale de Microsoft et de Jira) et fonctions du compagnon (liste sous la grille).
  // La liste vient de /local/config/plugins, absentes comprises : une appli non installée garde sa tuile.
  let plugins = [];
  const NOT_FOUND = {
    claude: "Claude Code n'est pas trouvé sur cet ordinateur (Mac seulement) : installe-le pour l'activer.",
    jira: "Jira n'est pas relié : suis les étapes ci-dessus, puis reviens ici pour l'activer.",
  };
  async function loadPlugins() {
    try {
      const res = await fetch("/local/config/plugins");
      if (res.ok) renderPlugins((await res.json()).plugins);
    } catch (e) { /* compagnon arrêté : la page le dit déjà */ }
  }
  async function setPlugin(id, on, msgId) {
    const choices = {};
    choices[id] = on;
    const name = plugins.find((p) => p.id === id).name;
    let ok = false;
    try {
      const res = await fetch("/local/config/plugins", { method: "PUT", headers: { "content-type": "application/json" }, body: JSON.stringify({ choices: choices }) });
      const json = await res.json().catch(() => ({}));
      ok = res.ok;
      if (ok) renderPlugins(json.plugins);
      else pfSay(document.getElementById(msgId), json.error || "Erreur " + res.status, "ko");
    } catch (e) {
      pfSay(document.getElementById(msgId), "Le compagnon ne répond pas", "ko");
    }
    if (!ok) return loadPlugins();
    pfSay(document.getElementById(msgId), name + (on ? " activé." : " coupé."), "ok");
    // Une appli activée s'ajoute à la liste des applis du Deck.
    pfDeckLoadApps();
  }
  function renderPlugins(list) {
    plugins = list;
    for (const p of list) {
      if (p.kind !== "app" || !document.querySelector('.cx[data-cx="' + p.id + '"]') || p.id === "jira") continue;
      if (!p.present) setTile(p.id, "na", "Non installé");
      else setTile(p.id, p.on ? "on" : "off", p.on ? "Actif" : "Coupé");
    }
    const box = document.getElementById("fnlist");
    box.replaceChildren();
    const fns = list.filter((p) => p.kind === "native" && p.present);
    if (fns.length === 0) {
      const row = document.createElement("div");
      row.className = "row";
      row.append(Object.assign(document.createElement("span"), { className: "grow dim", textContent: "Aucune fonction sur cet ordinateur." }));
      box.append(row);
    }
    for (const p of fns) {
      const row = document.createElement("div"), label = document.createElement("label"), sub = document.createElement("span"), input = document.createElement("input");
      row.className = "row";
      input.type = "checkbox";
      input.id = "fn-" + p.id;
      input.checked = p.on;
      input.setAttribute("aria-label", p.name);
      input.onchange = () => setPlugin(p.id, input.checked, "fnmsg");
      label.htmlFor = input.id;
      label.className = "grow";
      label.append(document.createTextNode(p.name));
      sub.className = "pf-sub-row";
      sub.textContent = p.description;
      label.append(sub);
      row.append(label, input);
      box.append(row);
    }
    renderPluginSwitch();
  }
  // Interrupteur de la modale ouverte ; l'appli absente le laisse éteint et dit comment l'activer.
  function renderPluginSwitch() {
    const id = PLUGIN_OF[openCx] || openCx;
    const p = plugins.find((x) => x.id === id && x.kind === "app");
    document.getElementById("cxplug").hidden = !p;
    if (!p) return;
    const input = document.getElementById("plugon");
    input.checked = p.on && p.present;
    input.disabled = !p.present;
    input.setAttribute("aria-label", p.name + " actif sur la tablette");
    // Dans la modale d'un autre connecteur, l'interrupteur dit de quelle appli il s'agit.
    document.getElementById("pluglabel").textContent = id === openCx ? "Actif sur la tablette" : p.name + " sur la tablette";
    input.onchange = () => setPlugin(p.id, input.checked, "plugmsg");
    document.getElementById("plugindesc").textContent = p.description + ".";
    const note = document.getElementById("plugmsg");
    if (p.present) pfSay(note, "", "");
    else pfSay(note, NOT_FOUND[p.id] || p.name + " n'est pas installé sur cet ordinateur : installe-le pour l'activer.", "");
  }
  loadPlugins();

  // Sans tablette, « Connecter une tablette » passe en tête de page ; avec, elle rejoint « Cet ordinateur ».
  let connectTop = null;
  function placeConnect(tablet) {
    if (connectTop === !tablet) return;
    connectTop = !tablet;
    const connect = document.getElementById("connect");
    if (connectTop) document.getElementById("tabletcard").before(connect);
    else document.getElementById("connecteurs").before(connect);
    connect.dataset.group = connectTop ? "" : "Cet ordinateur";
    connect.classList.toggle("waiting", connectTop);
    document.getElementById("connecttitle").textContent = tablet ? "Connecter une autre tablette" : "Connecter une tablette";
  }

  // Barre de gauche : une entrée par section visible de la page, dans l'ordre ; le trait suit la section lue.
  const ICONS = {
    tablet: "M21 4H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h18c1.1 0 1.99-.9 1.99-2L23 6c0-1.1-.9-2-2-2zm-2 14H5V6h14v12z",
    grid: "M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z",
    keys: "M20 5H4c-1.1 0-1.99.9-1.99 2L2 17c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm-9 3h2v2h-2V8zm0 3h2v2h-2v-2zM8 8h2v2H8V8zm0 3h2v2H8v-2zm-1 2H5v-2h2v2zm0-3H5V8h2v2zm9 7H8v-2h8v2zm0-4h-2v-2h2v2zm0-3h-2V8h2v2zm3 3h-2v-2h2v2zm0-3h-2V8h2v2z",
    plug: "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z",
    apps: "M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm-5 14H4v-4h11v4zm0-5H4V9h11v4zm5 5h-4V9h4v9z",
    bars: "M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z",
    link: "M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z",
    add: "M13 7h-2v4H7v2h4v4h2v-4h4v-2h-4V7zm-1-5C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z",
    calendar: "M17 12h-5v5h5v-5zM16 1v2H8V1H6v2H5c-1.11 0-1.99.9-1.99 2L3 19c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2h-1V1h-2zm3 18H5V8h14v11z",
    merge: "M17 20.41L18.41 19 15 15.59 13.59 17 17 20.41zM7.5 8H11v5.59L5.59 19 7 20.41l6-6V8h3.5L12 3.5 7.5 8z",
    extension: "M20.5 11H19V7c0-1.1-.9-2-2-2h-4V3.5C13 2.12 11.88 1 10.5 1S8 2.12 8 3.5V5H4c-1.1 0-1.99.9-1.99 2v3.8H3.5c1.49 0 2.7 1.21 2.7 2.7s-1.21 2.7-2.7 2.7H2V20c0 1.1.9 2 2 2h3.8v-1.5c0-1.49 1.21-2.7 2.7-2.7 1.49 0 2.7 1.21 2.7 2.7V22H17c1.1 0 2-.9 2-2v-4h1.5c1.38 0 2.5-1.12 2.5-2.5S21.88 11 20.5 11z",
    power: "M13 3h-2v10h2V3zm4.83 2.17l-1.42 1.42C17.99 7.86 19 9.81 19 12c0 3.87-3.13 7-7 7s-7-3.13-7-7c0-2.19 1.01-4.14 2.58-5.42L6.17 5.17C4.23 6.82 3 9.26 3 12c0 4.97 4.03 9 9 9s9-4.03 9-9c0-2.74-1.23-5.18-3.17-6.83z",
  };
  const SVG_NS = "http://www.w3.org/2000/svg";
  let railKey = "";
  function buildRail() {
    const sections = [...document.querySelectorAll("main > section[data-title]")].filter((s) => !s.hidden);
    const key = sections.map((s) => s.id + ":" + (s.dataset.group || "")).join(",");
    if (key === railKey) return;
    railKey = key;
    const menu = document.getElementById("menu");
    menu.querySelectorAll("a, .grp").forEach((e) => e.remove());
    let group = "";
    for (const sec of sections) {
      const g = sec.dataset.group || "";
      if (g !== group && g) {
        const label = document.createElement("span");
        label.className = "grp";
        label.textContent = g;
        menu.append(label);
      }
      group = g;
      const a = document.createElement("a");
      a.href = "#" + sec.id;
      a.dataset.sec = sec.id;
      const svg = document.createElementNS(SVG_NS, "svg"), path = document.createElementNS(SVG_NS, "path");
      svg.setAttribute("viewBox", "0 0 24 24");
      svg.setAttribute("class", "i");
      svg.setAttribute("aria-hidden", "true");
      path.setAttribute("d", ICONS[sec.dataset.icon] || ICONS.plug);
      svg.append(path);
      a.append(svg, sec.dataset.title);
      menu.append(a);
    }
    followScroll();
  }
  function followScroll() {
    const links = [...document.querySelectorAll("#menu a")], trait = document.getElementById("trait");
    trait.hidden = !links.length;
    if (!links.length) return;
    const atEnd = window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 4;
    let current = links[0];
    for (const a of links) if (document.getElementById(a.dataset.sec).getBoundingClientRect().top <= 140) current = a;
    if (atEnd) current = links[links.length - 1];
    for (const a of links) a.setAttribute("aria-current", String(a === current));
    trait.style.transform = "translateY(" + (current.offsetTop + (current.offsetHeight - 20) / 2) + "px)";
  }
  let scrollFrame = 0;
  addEventListener("scroll", () => { if (!scrollFrame) scrollFrame = requestAnimationFrame(() => { scrollFrame = 0; followScroll(); }); }, { passive: true });
  addEventListener("resize", followScroll);
  // Ouverte depuis la tablette ou au retour d'une connexion : le panneau concerné est ouvert et mis en avant.
  const section = new URLSearchParams(location.search).get("section");
  if (accountResult) openPanel("agenda", null);
  if (gitlabResult) openPanel("gitlab", "gitlab");
  if (section === "agenda" || section === "gitlab") {
    openPanel(section, section === "agenda" ? "ics" : "gitlab");
    document.getElementById(section).classList.add("focus");
  }
  ${CONFIG_JS}
  tickTwin();
  setInterval(tickTwin, 15000);
  refresh();
  loadPanel();
  setInterval(refresh, 1500);
</script>
</body>
</html>
`;

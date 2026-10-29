/**
 * Style Cadran des pages web du compagnon (page locale, visualiseur /camera), d'après la DA de docs/ui-ux/da :
 * mêmes tokens jour et nuit que l'appli (app/src/main/res/values/palettes.xml), police Outfit servie par le
 * compagnon lui-même (assets/web, GET /web/:file) pour marcher hors ligne.
 */

/** Polices servies par GET /web/:file (routes/local.ts) ; copiées dans le paquet (scripts/package.ts). */
export const WEB_FILES = ["outfit-300.ttf", "outfit-400.ttf", "outfit-500.ttf"];

const FONTS = [300, 400, 500]
  .map(
    (w) => `@font-face { font-family: Outfit; font-weight: ${w}; font-display: block; src: url(/web/outfit-${w}.ttf) format("truetype"); }`,
  )
  .join("\n  ");

const DAY =
  "--bg: #FAF2F1; --tile: #FFFFFF; --tile-high: #EEE6E6; --ink: #1E1B1B; --ink-2: #4D4545; --ink-3: #7C7272; --line: #D0C4C3; --now: #CA0538; --on-now: #FFFFFF; --now-soft: #FFDADA; --ok: #31685A; --ko: #BA1A1A;";
const NIGHT =
  "--bg: #100D0D; --tile: #221F1F; --tile-high: #282525; --ink: #E9E1E0; --ink-2: #D0C4C3; --ink-3: #998E8E; --line: #4D4545; --now: #FF5165; --on-now: #2A0006; --now-soft: #680018; --ok: #9AD2C1; --ko: #FFB4AB;";

/** Jour ou nuit selon le mode clair ou sombre de l'ordinateur. */
export const CADRAN_CSS = `${FONTS}
  :root { color-scheme: light dark; ${DAY} }
  @media (prefers-color-scheme: dark) { :root { ${NIGHT} } }`;

/** Toujours la nuit (visualiseur : l'image se regarde sur fond sombre). */
export const CADRAN_NIGHT_CSS = `${FONTS}
  :root { color-scheme: dark; ${NIGHT} }`;

/** Icône Cadran (docs/ui-ux/da/icone.svg) : en tête de page et en favicon. */
export const ICON_SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" aria-hidden="true"><rect width="256" height="256" rx="60" fill="#CA0538"/><path d="M48 128 H152" stroke="#FFFFFF" stroke-width="14" stroke-linecap="round"/><path d="M152 128 H208" stroke="#FFFFFF" stroke-opacity=".4" stroke-width="14" stroke-linecap="round"/><circle cx="152" cy="128" r="40" fill="none" stroke="#FFFFFF" stroke-opacity=".55" stroke-width="7"/><circle cx="152" cy="128" r="20" fill="#FFFFFF"/></svg>`;

/** Favicon en data: : aucune requête (le navigateur la demanderait sans jeton). */
export const FAVICON = `<link rel="icon" href="data:image/svg+xml,${encodeURIComponent(ICON_SVG)}">`;

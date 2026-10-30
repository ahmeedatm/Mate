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
  "--bg: #FAF2F1; --tile: #FFFFFF; --tile-high: #EEE6E6; --ink: #1E1B1B; --ink-2: #4D4545; --ink-3: #6E6464; --line: #D0C4C3; --now: #CA0538; --on-now: #FFFFFF; --now-soft: #FFDADA; --ok: #31685A; --ok-soft: #D6EDE6; --ko: #BA1A1A; --rail: #F3E9E8;";
const NIGHT =
  "--bg: #100D0D; --tile: #221F1F; --tile-high: #282525; --ink: #E9E1E0; --ink-2: #D0C4C3; --ink-3: #998E8E; --line: #4D4545; --now: #FF5165; --on-now: #2A0006; --now-soft: #680018; --ok: #9AD2C1; --ok-soft: #1B3A32; --ko: #FFB4AB; --rail: #181414;";

/** Jour ou nuit selon le mode clair ou sombre de l'ordinateur. */
export const CADRAN_CSS = `${FONTS}
  :root { color-scheme: light dark; ${DAY} }
  @media (prefers-color-scheme: dark) { :root { ${NIGHT} } }`;

/** Toujours la nuit (visualiseur : l'image se regarde sur fond sombre). */
export const CADRAN_NIGHT_CSS = `${FONTS}
  :root { color-scheme: dark; ${NIGHT} }`;

/** Icône Mate « visage » (docs/ui-ux/da/icone-visage.svg) : en tête de page et en favicon. */
export const ICON_SVG = `<svg xmlns="http://www.w3.org/2000/svg" aria-hidden="true" viewBox="0 0 512 512"><rect x="2" y="2" width="508" height="508" rx="112" fill="#CA0730"/><g transform="translate(95.12,132.98) scale(1.5544)"><rect x="47.5" y="0" width="22" height="43.5" rx="4" fill="#FFFFFF"/><rect x="137.5" y="0" width="22" height="43.5" rx="4" fill="#FFFFFF"/><path d="M9,4 L27.5,4 L27.5,36 L103.5,74.5 L179.5,36 L179.5,4 L198,4 L198,48 L103.5,96.4 L9,48 Z" transform="translate(0,68)" fill="#FFFFFF" stroke="#FFFFFF" stroke-width="4" stroke-linejoin="round"/></g></svg>`;

/** Favicon en data: : aucune requête (le navigateur la demanderait sans jeton). */
export const FAVICON = `<link rel="icon" href="data:image/svg+xml,${encodeURIComponent(ICON_SVG)}">`;

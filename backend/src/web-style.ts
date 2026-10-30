/**
 * Style Console douce des pages web du compagnon (page locale, visualiseur /camera), d'après la DA de docs/ui-ux/da :
 * mêmes tokens jour et nuit que l'appli (app/src/main/res/values/palettes.xml), police Figtree servie par le
 * compagnon lui-même (assets/web, GET /web/:file) pour marcher hors ligne. `--now` est l'accent (vert acide).
 */

/** Polices servies par GET /web/:file (routes/local.ts) ; copiées dans le paquet (scripts/package.ts). */
export const WEB_FILES = ["figtree-300.ttf", "figtree-400.ttf", "figtree-500.ttf"];

const FONTS = [300, 400, 500]
  .map(
    (w) =>
      `@font-face { font-family: Figtree; font-weight: ${w}; font-display: block; src: url(/web/figtree-${w}.ttf) format("truetype"); }`,
  )
  .join("\n  ");

const DAY =
  "--bg: #F4F2EE; --tile: #FFFFFF; --tile-high: #EFECE6; --ink: #1B1C1F; --ink-2: #474B54; --ink-3: #5E626C; --line: #D8D3CA; --now: #3F6B00; --on-now: #FFFFFF; --now-soft: #DFF5B8; --ok: #1F7A4D; --ok-soft: #DFF3E8; --ko: #C62828; --rail: #ECE9E3;";
const NIGHT =
  "--bg: #17181B; --tile: #23252A; --tile-high: #2A2D33; --ink: #ECEDEF; --ink-2: #B4B8C0; --ink-3: #9095A0; --line: #3A3D44; --now: #B8F34A; --on-now: #16210A; --now-soft: #2C3A12; --ok: #7FD8A6; --ok-soft: #1C3A2B; --ko: #FF7A70; --rail: #17181B;";

/** Jour ou nuit selon le mode clair ou sombre de l'ordinateur. */
export const CONSOLE_CSS = `${FONTS}
  :root { color-scheme: light dark; ${DAY} }
  @media (prefers-color-scheme: dark) { :root { ${NIGHT} } }`;

/** Toujours la nuit (visualiseur : l'image se regarde sur fond sombre). */
export const CONSOLE_NIGHT_CSS = `${FONTS}
  :root { color-scheme: dark; ${NIGHT} }`;

/** Icône Mate « visage » (docs/ui-ux/da/icone-visage.svg) : en tête de page et en favicon. */
export const ICON_SVG = `<svg xmlns="http://www.w3.org/2000/svg" aria-hidden="true" viewBox="0 0 512 512"><rect x="2" y="2" width="508" height="508" rx="112" fill="#17181B"/><g transform="translate(95.12,132.98) scale(1.5544)"><rect x="47.5" y="0" width="22" height="43.5" rx="4" fill="#B8F34A"/><rect x="137.5" y="0" width="22" height="43.5" rx="4" fill="#B8F34A"/><path d="M9,4 L27.5,4 L27.5,36 L103.5,74.5 L179.5,36 L179.5,4 L198,4 L198,48 L103.5,96.4 L9,48 Z" transform="translate(0,68)" fill="#B8F34A" stroke="#B8F34A" stroke-width="4" stroke-linejoin="round"/></g></svg>`;

/** Favicon en data: : aucune requête (le navigateur la demanderait sans jeton). */
export const FAVICON = `<link rel="icon" href="data:image/svg+xml,${encodeURIComponent(ICON_SVG)}">`;

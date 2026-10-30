import type { ServerResponse } from "node:http";
import { CONSOLE_NIGHT_CSS, FAVICON } from "./web-style.ts";

/**
 * Visualiseur : la tablette, posée au-dessus d'une feuille, envoie des JPEG 720p (POST /camera/frame)
 * que l'ordinateur rediffuse en MJPEG (multipart/x-mixed-replace) à la page plein écran GET /camera,
 * à projeter. Seule la dernière image est gardée : un spectateur lent saute des images au lieu de prendre du retard.
 */

export const CAMERA_STATUSES = ["live", "frozen", "off"] as const;
export type CameraStatus = (typeof CAMERA_STATUSES)[number];

const BOUNDARY = "frame";
/** Sans image depuis ce délai, la tablette a quitté le mode ou perdu la connexion. */
const STALE_MS = 3_000;

let latest: Buffer | undefined;
let latestAt = 0;
let status: CameraStatus = "off";
/** Rotation (degrés) pour remettre l'image à l'endroit, selon le sens de la tablette. */
let rotation = 0;
const viewers = new Set<ServerResponse>();

function writeFrame(res: ServerResponse, frame: Buffer) {
  // Spectateur qui n'a pas fini de lire l'image précédente : on saute celle-ci.
  if (res.writableNeedDrain) return;
  res.write(`--${BOUNDARY}\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.length}\r\n\r\n`);
  res.write(frame);
  res.write("\r\n");
}

export function pushFrame(frame: Buffer) {
  latest = frame;
  latestAt = Date.now();
  if (status === "off") status = "live";
  for (const res of viewers) writeFrame(res, frame);
}

export function setCameraState(next: CameraStatus, nextRotation = rotation) {
  status = next;
  rotation = nextRotation;
}

export function cameraStatus() {
  const stale = status === "live" && Date.now() - latestAt > STALE_MS;
  return { status: stale ? ("off" as const) : status, rotation, viewers: viewers.size, hasFrame: latest !== undefined };
}

/** Ouvre le flux MJPEG sur une réponse brute (la route doit avoir appelé reply.hijack()). */
export function addViewer(res: ServerResponse) {
  res.writeHead(200, {
    "Content-Type": `multipart/x-mixed-replace; boundary=${BOUNDARY}`,
    "Cache-Control": "no-cache, no-store",
    Connection: "close",
  });
  viewers.add(res);
  res.on("close", () => viewers.delete(res));
  if (latest) writeFrame(res, latest);
}

/** Page à projeter : l'image en grand, l'état en pastille, Pivoter et Plein écran (touches R et F). */
export const VIEWER_PAGE = `<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Visualiseur</title>
${FAVICON}
<style>
  ${CONSOLE_NIGHT_CSS}
  /* Noms du visualiseur rapportés aux tokens Console (nuit) (web-style.ts). */
  :root { --pill: rgba(35, 37, 42, .88); --text: var(--ink); --dim: var(--ink-3); --faint: var(--line); --warn: #FFC24B; --accent: var(--now);
    --surface: var(--tile); --raised: var(--tile-high); --pressed: #31343B; }
  * { box-sizing: border-box; }
  html, body { margin: 0; height: 100%; background: var(--bg); color: var(--text); font: 400 16px/1.4 Figtree, system-ui, sans-serif; -webkit-font-smoothing: antialiased; overflow: hidden; }
  #stage { position: fixed; inset: 0; display: flex; align-items: center; justify-content: center; }
  #frame { max-width: 100%; max-height: 100%; object-fit: contain; transition: transform .2s; }
  #frame.sideways { max-width: 100vh; max-height: 100vw; }
  #empty { position: fixed; inset: 0; display: none; align-items: center; justify-content: center; flex-direction: column; gap: 8px; color: var(--dim); text-align: center; padding: 16px; font-size: 20px; }
  #empty svg { width: min(360px, 60vw); height: auto; margin-bottom: 24px; }
  #empty strong { color: var(--text); font-size: 36px; font-weight: 500; }
  .z { animation: doze 3s ease-in-out infinite; }
  .z2 { animation-delay: .4s; }
  .z3 { animation-delay: .8s; }
  @keyframes doze { 0%, 100% { opacity: .25; transform: translateY(4px); } 50% { opacity: 1; transform: translateY(0); } }
  @media (prefers-reduced-motion: reduce) { .z { animation: none; } }
  .bar { position: fixed; top: 16px; display: flex; gap: 8px; transition: opacity .3s; }
  #status { left: 16px; }
  #tools { right: 16px; }
  .pill { display: inline-flex; align-items: center; gap: 8px; height: 48px; padding: 0 22px; border-radius: 24px; background: var(--pill); border: 0; color: var(--text); font: 500 15px Figtree, sans-serif; }
  button.pill { cursor: pointer; }
  button.pill:hover { background: var(--tile-high); }
  .dot { width: 10px; height: 10px; border-radius: 50%; background: var(--dim); }
  .live .dot { background: var(--now); }
  .frozen .dot { background: var(--warn); }
  body.idle .bar { opacity: 0; }
  body.idle { cursor: none; }
</style>
</head>
<body>
<div id="stage"><img id="frame" alt=""></div>
<div id="empty">
  <!-- Caméra endormie : même dessin que app/src/main/res/drawable/ill_camera_sleep.xml -->
  <svg viewBox="0 0 200 160" aria-hidden="true">
    <ellipse cx="92" cy="146" rx="62" ry="6" fill="var(--raised)"/>
    <path fill="var(--pressed)" d="M58,36 h30 a6,6 0 0 1 6,6 v12 h-42 v-12 a6,6 0 0 1 6,-6z"/>
    <path fill="var(--pressed)" d="M140,78 L170,60 L170,120 L140,102z"/>
    <rect x="30" y="50" width="110" height="80" rx="16" fill="var(--raised)" stroke="var(--pressed)" stroke-width="3"/>
    <circle cx="85" cy="90" r="24" fill="var(--surface)" stroke="var(--faint)" stroke-width="3"/>
    <path d="M72,90 Q85,101 98,90" fill="none" stroke="var(--dim)" stroke-width="3.5" stroke-linecap="round"/>
    <circle cx="122" cy="66" r="4" fill="var(--faint)"/>
    <g fill="none" stroke="var(--accent)" stroke-linecap="round" stroke-linejoin="round">
      <path class="z" stroke-width="2.5" d="M132,40 h8 l-8,9 h8"/>
      <path class="z z2" stroke-width="3" d="M148,22 h12 l-12,14 h12"/>
      <path class="z z3" stroke-width="3.5" d="M170,2 h16 l-16,18 h16"/>
    </g>
  </svg>
  <strong>Caméra en veille</strong>
  <span>Ouvre le mode Caméra sur la tablette pour la réveiller.</span>
</div>
<div class="bar" id="status"><span class="pill" id="badge"><span class="dot"></span><span id="label">Connexion…</span></span></div>
<div class="bar" id="tools">
  <button class="pill" id="rotate" title="Pivoter (R)">Pivoter</button>
  <button class="pill" id="full" title="Plein écran (F)">Plein écran</button>
</div>
<script>
  const frame = document.getElementById("frame");
  const empty = document.getElementById("empty");
  const badge = document.getElementById("badge");
  const label = document.getElementById("label");
  const LABELS = { live: "En direct", frozen: "Image figée", off: "En veille" };
  let tabletRotation = 0;
  let flip = 0;
  try { flip = Number(localStorage.getItem("flip")) || 0; } catch {}

  function applyRotation() {
    const deg = (tabletRotation + flip) % 360;
    frame.style.transform = deg ? "rotate(" + deg + "deg)" : "";
    frame.classList.toggle("sideways", deg % 180 !== 0);
  }

  // Le flux n'est ouvert que quand la caméra tourne : en veille, aucune connexion ne reste pendante.
  let streaming = false;
  function setStreaming(on) {
    if (on === streaming) return;
    streaming = on;
    if (on) frame.src = "/camera/stream?t=" + Date.now();
    else frame.removeAttribute("src");
  }
  // Backend redémarré ou coupure : on se reconnecte au prochain état reçu.
  frame.onerror = () => { streaming = false; };

  async function refresh() {
    try {
      const s = await (await fetch("/camera/status", { cache: "no-store" })).json();
      badge.className = "pill " + s.status;
      label.textContent = LABELS[s.status];
      // Caméra coupée : la caméra endormie remplace la dernière image, qui ne bougerait plus.
      const asleep = s.status === "off" || !s.hasFrame;
      empty.style.display = asleep ? "flex" : "none";
      frame.style.visibility = asleep ? "hidden" : "visible";
      setStreaming(!asleep);
      if (s.rotation !== tabletRotation) { tabletRotation = s.rotation; applyRotation(); }
    } catch {
      badge.className = "pill";
      label.textContent = "Compagnon arrêté";
      setStreaming(false);
    }
  }
  setInterval(refresh, 1000);
  refresh();
  applyRotation();

  function rotate() {
    flip = (flip + 180) % 360;
    try { localStorage.setItem("flip", String(flip)); } catch {}
    applyRotation();
  }
  function fullscreen() {
    if (document.fullscreenElement) document.exitFullscreen();
    else document.documentElement.requestFullscreen?.();
  }
  document.getElementById("rotate").onclick = rotate;
  document.getElementById("full").onclick = fullscreen;
  document.addEventListener("keydown", (e) => {
    if (e.key === "r" || e.key === "R") rotate();
    if (e.key === "f" || e.key === "F") fullscreen();
  });

  // Vidéoprojecteur : les commandes disparaissent quand la souris ne bouge plus.
  let idle;
  function wake() {
    document.body.classList.remove("idle");
    clearTimeout(idle);
    idle = setTimeout(() => document.body.classList.add("idle"), 3000);
  }
  document.addEventListener("mousemove", wake);
  wake();
</script>
</body>
</html>
`;

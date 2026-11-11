#!/usr/bin/env node
// Maquettes HTML sur la tablette (skill ecran-design) : sert un dossier de maquettes sur le réseau local, avec un
// sélecteur ; un clic sur une maquette l'ouvre en plein écran sur chaque tablette connectée à un compagnon de ce Mac
// (ordre `page`, route locale POST /local/tablet/page : 127.0.0.1, sans jeton). Sans dépendance.
//
//   node scripts/maquettes.mjs [sujet | dossier] [--port 4300] [--compagnon 3000]… [--sans-onglet] [--sans-adb]
//
// Une maquette = un fichier .html à la racine du dossier (index.html exclu). Sujet seul : /tmp/mate-maquettes/<sujet>/,
// jamais commité. Adresse donnée à chaque tablette : celle du Mac sur le WiFi, ou 127.0.0.1 si son compagnon n'écoute
// qu'en local (émulateur, tablette en USB) ; `adb reverse` est alors posé sur chaque appareil adb. Le sélecteur s'ouvre
// dans un onglet Orca à droite (ORCA_CLI_COMMAND).
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";

const DEFAULT_ROOT = "/tmp/mate-maquettes";
const LOOPBACK = new Set(["127.0.0.1", "::1", "::ffff:127.0.0.1"]);
const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".webp": "image/webp",
  ".woff2": "font/woff2",
  ".ttf": "font/ttf",
};

function parseArgs(argv) {
  const opts = {
    target: "essai",
    port: 4300,
    companions: [],
    tab: true,
    adb: true,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--port") opts.port = Number(argv[++i]);
    else if (a === "--compagnon") opts.companions.push(Number(argv[++i]));
    else if (a === "--sans-onglet") opts.tab = false;
    else if (a === "--sans-adb") opts.adb = false;
    else if (a.startsWith("--")) throw new Error(`Option inconnue : ${a}`);
    else opts.target = a;
  }
  if (opts.companions.length === 0) opts.companions.push(3000);
  for (const p of [opts.port, ...opts.companions]) {
    if (!Number.isInteger(p) || p < 0 || p > 65535)
      throw new Error(`Port invalide : ${p}`);
  }
  opts.dir = path.resolve(
    opts.target.includes("/")
      ? opts.target
      : path.join(DEFAULT_ROOT, opts.target),
  );
  return opts;
}

const variants = (dir) =>
  fs
    .readdirSync(dir)
    .filter((f) => f.endsWith(".html") && f !== "index.html")
    .sort()
    .map((file) => {
      const title = /<title>([^<]*)<\/title>/i
        .exec(fs.readFileSync(path.join(dir, file), "utf8"))?.[1]
        ?.trim();
      return { file, title: title || file.replace(/\.html$/, "") };
    });

/** Compagnon local : tablette connectée ? Adresse par laquelle elle joint ce Mac. */
async function companionTarget(port, servePort) {
  const res = await fetch(`http://127.0.0.1:${port}/local/status`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const status = await res.json();
  if (!status.tablet) return null;
  const lan = (status.addresses ?? []).find((a) => !a.startsWith("127."));
  return `http://${lan ?? "127.0.0.1"}:${servePort}/`;
}

/** Envoie l'ordre `page` (ou `null` : fermer) à la tablette de chaque compagnon ; une ligne de résultat par compagnon. */
async function sendPage(companions, servePort, file) {
  return Promise.all(
    companions.map(async (port) => {
      try {
        const base = await companionTarget(port, servePort);
        if (!base)
          return { port, ok: false, detail: "pas de tablette connectée" };
        const url = file === null ? null : base + encodeURIComponent(file);
        const res = await fetch(`http://127.0.0.1:${port}/local/tablet/page`, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ url }),
        });
        const json = await res.json().catch(() => ({}));
        return res.ok
          ? { port, ok: true, detail: url ?? "fermée" }
          : {
              port,
              ok: false,
              detail: json.message ?? json.error ?? `HTTP ${res.status}`,
            };
      } catch (e) {
        return {
          port,
          ok: false,
          detail: `compagnon injoignable (${e.cause?.code ?? e.message})`,
        };
      }
    }),
  );
}

function selectorPage(list) {
  const escape = (s) =>
    s.replace(
      /[&<>"]/g,
      (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c],
    );
  const cards = list
    .map(
      (v) => `<button class="card" data-file="${escape(v.file)}">
  <span class="thumb"><iframe src="/${encodeURIComponent(v.file)}" tabindex="-1" loading="lazy"></iframe></span>
  <span class="name">${escape(v.title)}</span>
</button>`,
    )
    .join("\n");
  return `<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><title>Maquettes</title>
<style>
  body { margin: 0; padding: 24px; font: 15px/1.4 system-ui, sans-serif; background: #15130f; color: #f2ede4; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  p { margin: 0 0 20px; color: #b9b0a2; }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 20px; }
  .card { all: unset; cursor: pointer; display: block; border-radius: 10px; outline: 2px solid #3a342b; overflow: hidden; background: #211d17; }
  .card:hover, .card:focus-visible { outline-color: #e8a33d; }
  .card.on { outline: 3px solid #e8a33d; }
  .thumb { display: block; width: 100%; aspect-ratio: 1280 / 800; overflow: hidden; position: relative; }
  .thumb iframe { width: 1280px; height: 800px; border: 0; transform-origin: 0 0; pointer-events: none; position: absolute; }
  .name { display: block; padding: 10px 14px; font-weight: 600; }
  .bar { display: flex; gap: 12px; align-items: center; margin: 20px 0 0; }
  .bar button { font: inherit; padding: 8px 16px; border-radius: 8px; border: 1px solid #3a342b; background: #211d17; color: inherit; cursor: pointer; }
  #status { white-space: pre-line; color: #b9b0a2; }
</style></head>
<body>
<h1>Maquettes</h1>
<p>Clique une maquette : elle s'ouvre en plein écran sur l'émulateur et la tablette.</p>
<div class="grid">${cards || "<p>Aucune maquette .html dans ce dossier.</p>"}</div>
<div class="bar"><button id="close">Fermer sur la tablette</button><span id="status"></span></div>
<script>
(() => {
  const status = document.getElementById("status");
  const fit = () => document.querySelectorAll(".thumb").forEach((t) => {
    t.firstElementChild.style.transform = "scale(" + t.clientWidth / 1280 + ")";
  });
  addEventListener("resize", fit);
  fit();
  const send = async (file) => {
    status.textContent = "Envoi…";
    try {
      const res = await fetch("/choisir", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ file }) });
      const lines = await res.json();
      status.textContent = lines.map((l) => (l.ok ? "✓ " : "✗ ") + "compagnon " + l.port + " : " + l.detail).join("\\n");
    } catch (e) {
      status.textContent = "Serveur des maquettes arrêté ?";
    }
  };
  document.querySelectorAll(".card").forEach((card) => card.addEventListener("click", () => {
    document.querySelectorAll(".card.on").forEach((c) => c.classList.remove("on"));
    card.classList.add("on");
    send(card.dataset.file);
  }));
  document.getElementById("close").addEventListener("click", () => send(null));
})();
</script>
</body></html>`;
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.on("data", (c) => {
      body += c;
      if (body.length > 10_000) reject(new Error("corps trop long"));
    });
    req.on("end", () => {
      try {
        resolve(JSON.parse(body));
      } catch (e) {
        reject(e);
      }
    });
  });
}

function serve(opts) {
  const reply = (res, code, type, body) => {
    res.writeHead(code, { "content-type": type, "cache-control": "no-store" });
    res.end(body);
  };
  return http.createServer(async (req, res) => {
    const url = new URL(req.url, "http://x");
    try {
      if (url.pathname === "/" && req.method === "GET")
        return reply(
          res,
          200,
          TYPES[".html"],
          selectorPage(variants(opts.dir)),
        );
      if (url.pathname === "/choisir" && req.method === "POST") {
        // Le sélecteur seul (ce Mac) envoie des ordres : pas un autre appareil du WiFi.
        if (!LOOPBACK.has(req.socket.remoteAddress))
          return reply(res, 403, "text/plain", "Depuis ce Mac seulement");
        const { file } = await readJson(req);
        if (file !== null && !variants(opts.dir).some((v) => v.file === file))
          return reply(res, 404, "text/plain", "Maquette inconnue");
        const lines = await sendPage(
          opts.companions,
          opts.server.address().port,
          file,
        );
        for (const l of lines)
          console.log(`${l.ok ? "✓" : "✗"} compagnon ${l.port} : ${l.detail}`);
        return reply(res, 200, "application/json", JSON.stringify(lines));
      }
      if (req.method !== "GET")
        return reply(res, 405, "text/plain", "GET seulement");
      // Fichiers du dossier seulement (maquettes, polices, images) : rien au-dessus.
      const file = path.resolve(
        opts.dir,
        "." + decodeURIComponent(url.pathname),
      );
      if (
        !file.startsWith(opts.dir + path.sep) ||
        !fs.statSync(file, { throwIfNoEntry: false })?.isFile()
      ) {
        return reply(res, 404, "text/plain", "Introuvable");
      }
      return reply(
        res,
        200,
        TYPES[path.extname(file).toLowerCase()] ?? "application/octet-stream",
        fs.readFileSync(file),
      );
    } catch (e) {
      return reply(res, 400, "text/plain", String(e.message ?? e));
    }
  });
}

/** Émulateur et tablette en USB : 127.0.0.1:<port> de l'appareil mène au serveur des maquettes de ce Mac. */
function adbReverse(port) {
  const list = spawnSync("adb", ["devices"], { encoding: "utf8" });
  if (list.error)
    return console.log(`adb absent : pas de reverse (${list.error.code})`);
  const serials = list.stdout
    .split("\n")
    .slice(1)
    .map((l) => l.split("\t"))
    .filter(([, state]) => state?.trim() === "device")
    .map(([serial]) => serial);
  for (const serial of serials) {
    const r = spawnSync(
      "adb",
      ["-s", serial, "reverse", `tcp:${port}`, `tcp:${port}`],
      { encoding: "utf8" },
    );
    console.log(
      r.status === 0
        ? `adb reverse posé sur ${serial}`
        : `adb reverse refusé sur ${serial} : ${r.stderr.trim()}`,
    );
  }
}

function openTab(url) {
  const orca = process.env.ORCA_CLI_COMMAND;
  if (!orca) return console.log(`Ouvrir le sélecteur : ${url}`);
  const r = spawnSync(
    orca,
    ["tab", "create", "--url", url, "--worktree", "active", "--split", "right"],
    { encoding: "utf8" },
  );
  if (r.status !== 0)
    console.log(
      `Onglet Orca non ouvert (${(r.stderr || r.error?.message || "").trim()}) : ${url}`,
    );
}

let opts;
try {
  opts = parseArgs(process.argv.slice(2));
  if (!fs.statSync(opts.dir, { throwIfNoEntry: false })?.isDirectory())
    throw new Error(`Dossier introuvable : ${opts.dir}`);
} catch (e) {
  console.error(e.message);
  process.exit(1);
}
opts.server = serve(opts);
opts.server.on("error", (e) => {
  console.error(
    `Serveur des maquettes : ${e.code === "EADDRINUSE" ? `port ${opts.port} déjà pris (--port)` : e.message}`,
  );
  process.exit(1);
});
// Toutes les interfaces : la tablette en WiFi lit les maquettes ; les ordres restent réservés à ce Mac (/choisir).
opts.server.listen(opts.port, () => {
  const port = opts.server.address().port;
  const url = `http://127.0.0.1:${port}/`;
  console.log(
    `Sélecteur : ${url} (${variants(opts.dir).length} maquettes dans ${opts.dir})`,
  );
  if (opts.adb) adbReverse(port);
  if (opts.tab) openTab(url);
});

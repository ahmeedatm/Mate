/**
 * Contrôle de non-régression d'un compagnon RÉEL déjà démarré (installé par paquet, ou serveur de test) :
 * un contrôle par feature, avec la réponse attendue selon le système (501 là où il ne gère pas le pilotage).
 *
 *   node qa/sanity.ts --url http://127.0.0.1:3000 --token <jeton> [--os windows|mac|other] [--hook-secret <secret>] [--junit sanity.xml]
 *
 * Aucune dépendance (modules de Node seulement) : lancé tel quel par le node du paquet, sans `npm ci`.
 * Ce qu'il crée (tâche, rendez-vous, agenda ICS) est retiré à la fin ; un agenda déjà relié n'est pas touché.
 * Appairage sans boîte de dialogue : compagnon démarré avec COMPANION_HEADLESS=1 (instance de test) ou NO_ALERT=1.
 */
import { createHash, randomBytes } from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { pathToFileURL } from "node:url";

export type Os = "mac" | "windows" | "other";
export type Result = { name: string; status: "ok" | "fail" | "skip"; detail: string; ms: number };
type Reply = { status: number; json: any };
type Call = (method: string, route: string, body?: unknown, auth?: boolean) => Promise<Reply>;

class Skip extends Error {}

function expect(cond: unknown, message: string): asserts cond {
  if (!cond) throw new Error(message);
}

function expectStatus(res: Reply, wanted: number, what: string) {
  const said = typeof res.json === "object" ? (res.json?.message ?? res.json?.error) : undefined;
  const error = said ? ` (${said})` : "";
  expect(res.status === wanted, `${what} : ${res.status} au lieu de ${wanted}${error}`);
}

const pad = (n: number) => String(n).padStart(2, "0");

/** Agenda ICS d'un événement aujourd'hui à 12 h, servi en local le temps du contrôle. */
function icsFile(title: string) {
  const d = new Date();
  const day = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`;
  return `BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//sanity//FR\r\nBEGIN:VEVENT\r\nUID:sanity-ics\r\nDTSTAMP:${day}T080000Z\r\nDTSTART:${day}T120000\r\nDTEND:${day}T130000\r\nSUMMARY:${title}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n`;
}

function serveIcs(body: string): Promise<{ url: string; close: () => void }> {
  return new Promise((resolve) => {
    const srv = http.createServer((_req, res) => res.writeHead(200, { "content-type": "text/calendar" }).end(body));
    srv.listen(0, "127.0.0.1", () => {
      const { port } = srv.address() as AddressInfo;
      resolve({ url: `http://127.0.0.1:${port}/agenda.ics`, close: () => srv.close() });
    });
  });
}

type Check = { name: string; run: () => Promise<string | void> };

export async function runSanity(opts: { url: string; token: string; os?: Os; hookSecret?: string }): Promise<Result[]> {
  const base = opts.url.replace(/\/$/, "");
  const local = /^https?:\/\/(127\.0\.0\.1|localhost|\[::1\])(:\d+)?$/i.test(base);
  const call: Call = async (method, route, body, auth = true) => {
    const headers: Record<string, string> = {};
    if (auth) headers.authorization = `Bearer ${opts.token}`;
    if (body !== undefined) headers["content-type"] = "application/json";
    const res = await fetch(base + route, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(45_000),
    });
    const text = await res.text();
    let json: any = text;
    try {
      json = JSON.parse(text);
    } catch {
      // Page HTML : texte brut.
    }
    return { status: res.status, json };
  };

  let os: Os = opts.os ?? "other";
  let id = "";
  let accessibility: boolean | null = null;
  const controlled = () => os !== "other";
  const stamp = `sanity-${Date.now()}`;

  const checks: Check[] = [
    {
      name: "santé et identité",
      run: async () => {
        const open = await call("GET", "/health", undefined, false);
        expectStatus(open, 200, "/health sans jeton");
        expect(open.json.service === "mate", `service inattendu : ${open.json.service}`);
        expect(open.json.auth === false, "/health sans jeton devrait dire auth: false");
        expect(/^(\d+\.\d+\.\d+|dev)$/.test(open.json.version ?? ""), `version du paquet inattendue : ${open.json.version}`);
        if (opts.os) expect(open.json.os === opts.os, `système annoncé « ${open.json.os} », attendu « ${opts.os} »`);
        os = open.json.os === "mac" || open.json.os === "windows" ? open.json.os : "other";
        id = open.json.id;
        accessibility = open.json.accessibility ?? null;
        expect(typeof id === "string" && id.length > 0, "identifiant de l'ordinateur absent");
        expect((await call("GET", "/health")).json.auth === true, "le jeton donné n'est pas accepté");
        expectStatus(await call("GET", "/tasks", undefined, false), 401, "route protégée sans jeton");
        return `« ${open.json.name} », ${os}, port ${open.json.port}`;
      },
    },
    {
      name: "appairage par code",
      run: async () => {
        // Comme une tablette en USB : HTTP local, donc empreinte vide dans le calcul du code (src/pairing.ts).
        const secret = randomBytes(32).toString("base64url");
        const commit = createHash("sha256").update(secret).digest("base64url");
        const req = await call("POST", "/pair/request", { tablet: "Sanity CI", commit }, false);
        if (req.status === 409) throw new Skip("un autre appairage est en cours");
        expectStatus(req, 200, "/pair/request");
        expectStatus(await call("POST", "/pair/reveal", { request: req.json.request, nonce: secret }, false), 200, "/pair/reveal");
        const status = await call("GET", "/local/status");
        expectStatus(status, 200, "/local/status");
        expect(status.json.pairing?.tablet === "Sanity CI", "la page locale n'annonce pas la tablette qui attend");
        expect(!/\d{6}/.test(JSON.stringify(status.json.pairing)), "la page locale ne doit jamais montrer le code");
        const n = createHash("sha256").update(`mate-pair-1\n\n${secret}\n${req.json.nonce}`).digest().readUInt32BE(0) % 1_000_000;
        expectStatus(await call("POST", "/local/pair", { code: String(n).padStart(6, "0") }, false), 200, "/local/pair");
        const ok = await call("POST", "/pair/confirm", { request: req.json.request }, false);
        expectStatus(ok, 200, "/pair/confirm");
        expect(ok.json.token === opts.token, "le jeton donné à l'appairage n'est pas celui de l'ordinateur");
        expect(ok.json.id === id, "l'appairage renvoie une autre identité que /health");
        if (ok.json.tlsPort) expect(/^[0-9a-f]{64}$/.test(ok.json.fingerprint), "empreinte du certificat absente");
      },
    },
    {
      name: "deck et deck de départ filtré",
      run: async () => {
        const deck = await call("GET", "/deck");
        expectStatus(deck, 200, "/deck");
        const pages: { title: string; apps: boolean; editable: boolean; tiles: { app: string | null }[] }[] = deck.json.pages;
        expect(
          pages.some((p) => p.editable && p.title === "Perso"),
          "page Perso absente",
        );
        expect(!pages.some((p) => /^(word|excel|powerpoint|outlook)$/i.test(p.title)), "page Office encore au deck");
        // Sans pilotage, le deck se lit mais ce qui touche à l'ordinateur répond 501.
        if (!controlled()) return expectStatus(await call("GET", "/deck/catalog"), 501, "/deck/catalog");
        const catalog = await call("GET", "/deck/catalog");
        expectStatus(catalog, 200, "/deck/catalog");
        const installed = (catalog.json.apps as string[]).map((a) => a.toLowerCase());
        const shown = pages.filter((p) => p.apps).flatMap((p) => p.tiles.map((t) => t.app ?? ""));
        // Profil de base : seulement les applis installées (Windows sans bureau : aucune).
        const missing = shown.filter((a) => !installed.some((i) => i === a.toLowerCase() || i.includes(a.toLowerCase())));
        expect(missing.length === 0, `applis du deck absentes de l'ordinateur : ${missing.join(", ")}`);
        return `${pages.length} pages, ${shown.length} applis sur ${installed.length} installées`;
      },
    },
    {
      name: "réunion en cours (profil Réunion de la tablette)",
      run: async () => {
        // Deck V2 : la veille des réunions (src/meeting-watch.ts) lit les applis en appel comme /deck/live.
        // L'ordre meeting n'est poussé que par l'ordinateur : refusé s'il vient de l'extérieur.
        expectStatus(
          await call("POST", "/tablet/command", { type: "meeting", state: "start", app: "Sanity" }),
          400,
          "ordre meeting de l'extérieur",
        );
        const live = await call("GET", "/deck/live");
        // Hors Mac et Windows, le pilotage répond 501 : pas de veille des réunions.
        if (!controlled()) {
          expectStatus(live, 501, "/deck/live hors Mac et Windows");
          return "pas de pilotage ici (501)";
        }
        expectStatus(live, 200, "/deck/live");
        expect(Array.isArray(live.json.apps) && Array.isArray(live.json.open), "/deck/live sans listes apps et open");
        return live.json.apps.length ? `en appel : ${live.json.apps.join(", ")}` : "aucun appel en cours";
      },
    },
    {
      name: "écran de jeu (appli au premier plan)",
      run: async () => {
        // La veille (src/foreground-watch.ts) pousse l'appli devant ; la tablette la compare à sa liste de jeux, choisie
        // parmi les applis du catalogue. L'ordre foreground est refusé s'il vient de l'extérieur.
        expectStatus(
          await call("POST", "/tablet/command", { type: "foreground", app: "Sanity", bundleId: "sanity" }),
          400,
          "ordre foreground de l'extérieur",
        );
        const catalog = await call("GET", "/deck/catalog");
        expectStatus(catalog, 200, "/deck/catalog");
        expect(Array.isArray(catalog.json.apps), "/deck/catalog sans liste d'applis");
        return `${catalog.json.apps.length} applis à choisir`;
      },
    },
    {
      name: "ordinateur verrouillé (écran bloquant de la tablette)",
      run: async () => {
        // L'ordre lock n'est poussé que par la veille du verrouillage (src/screen-lock.ts) : refusé de l'extérieur.
        expectStatus(await call("POST", "/tablet/command", { type: "lock", locked: true }), 400, "ordre lock de l'extérieur");
        // Instance de test : jamais verrouillée, l'API reste ouverte (423 sinon).
        expectStatus(await call("GET", "/deck"), 200, "GET /deck (ordinateur vu verrouillé ?)");
        return "API ouverte";
      },
    },
    {
      name: "Ne pas déranger à la main (pastille de l'Accueil)",
      run: async () => {
        expectStatus(await call("PUT", "/system/dnd", { on: "oui" }), 400, "PUT /system/dnd invalide");
        const read = await call("GET", "/system/dnd");
        if (!controlled()) {
          expectStatus(read, 501, "/system/dnd hors Mac et Windows");
          return "pas de pilotage ici (501)";
        }
        expectStatus(read, 200, "GET /system/dnd");
        if (typeof read.json.on !== "boolean") return "état illisible sur cet ordinateur";
        // L'état actuel reposé tel quel : la route marche sans rien changer pour l'utilisateur.
        const put = await call("PUT", "/system/dnd", { on: read.json.on });
        expectStatus(put, 200, "PUT /system/dnd");
        expect(put.json.on === read.json.on, "PUT /system/dnd n'a pas rendu l'état demandé");
        return read.json.on ? "activé" : "désactivé";
      },
    },
    {
      name: "plugins de première utilisation",
      run: async () => {
        const res = await call("GET", "/plugins");
        expectStatus(res, 200, "/plugins");
        const plugins: { id: string; kind: string; on: boolean }[] = res.json.plugins;
        const natives = plugins.filter((p) => p.kind === "native").map((p) => p.id);
        // Ordinateur non pilotable : aucun plugin, et /deck/features répond 501.
        if (!controlled()) return expect(plugins.length === 0, "plugins proposés sur un ordinateur non pilotable");
        expect(
          ["open", "mixer", "pad"].every((id) => natives.includes(id)),
          `plugins intégrés manquants : ${natives.join(", ")}`,
        );
        const features = await call("GET", "/deck/features");
        expect(Array.isArray(features.json.off), "/deck/features sans la liste des plugins coupés");
        const apps = plugins.filter((p) => p.kind === "app").map((p) => p.id);
        return `${natives.length} intégrés, applis trouvées : ${apps.join(", ") || "aucune"}`;
      },
    },
    {
      name: "touches perso au-delà de 14",
      run: async () => {
        const slot = 14;
        const label = "Sanity touche 15";
        try {
          expectStatus(
            await call("PUT", `/deck/keys/${slot}`, { label, steps: [{ type: "text", text: "sanity" }] }),
            200,
            "PUT /deck/keys/14",
          );
          const deck = await call("GET", "/deck");
          expectStatus(deck, 200, "/deck");
          const perso = (deck.json.pages as { editable: boolean; tiles: { id: string; label?: string }[] }[]).find((p) => p.editable);
          expect(perso?.tiles.find((t) => t.id === `k-${slot}`)?.label === label, "touche k-14 absente de la page Perso");
        } finally {
          expectStatus(await call("DELETE", `/deck/keys/${slot}`), 200, "DELETE /deck/keys/14");
        }
        const gone = await call("GET", `/deck/keys/${slot}`);
        expectStatus(gone, 200, "GET /deck/keys/14");
        expect(gone.json.key === null, "la touche 14 n'est pas vidée");
      },
    },
    {
      name: "agents Claude : hooks et état",
      run: async () => {
        const agents = await call("GET", "/agents");
        // Mac et Windows : contrôle complet ; ailleurs, 501.
        if (!controlled()) {
          expectStatus(agents, 501, "/agents hors Mac et Windows");
          return "501 hors Mac et Windows";
        }
        expectStatus(agents, 200, "/agents");
        expect(Array.isArray(agents.json.sessions) && typeof agents.json.counts?.working === "number", "forme de /agents inattendue");
        // Micro des sessions : vrai seulement avec whisper-cli sur l'ordinateur (brew install whisper-cpp).
        expect(typeof agents.json.voice === "boolean", "`voice` absent de /agents");
        // Usage Claude (Orca ou statusline) : absent tant qu'aucune source ne l'a donné, sinon des pourcentages.
        const quota = agents.json.quota;
        const pct = (w: any) => w === null || (Number.isInteger(w?.used) && w.used >= 0 && w.used <= 100);
        expect(quota === null || (pct(quota.fiveHour) && pct(quota.sevenDay) && typeof quota.ageMs === "number"), "usage Claude mal formé");
        const hook = (body: object, secret?: string) =>
          fetch(`${base}/agents/hook`, {
            method: "POST",
            headers: { "content-type": "application/json", ...(secret ? { "x-companion-hook": secret } : {}) },
            body: JSON.stringify({ session_id: stamp, cwd: "/sanity/agents", ...body }),
            signal: AbortSignal.timeout(10_000),
          });
        // Le hook demande son secret propre : sans lui, refusé (le jeton d'appairage n'y change rien).
        expect((await hook({ hook_event_name: "Stop" })).status === 401, "un hook sans secret devrait être refusé (401)");
        const usage = quota ? `usage ${quota.fiveHour?.used ?? "?"} % sur 5 h` : "usage inconnu";
        if (!opts.hookSecret) return `${usage} ; hook refusé sans secret ; événements non essayés (pas de --hook-secret)`;
        const find = async () => (await call("GET", "/agents")).json.sessions.find((s: any) => s.id === stamp);
        try {
          expect((await hook({ hook_event_name: "UserPromptSubmit" }, opts.hookSecret)).status === 200, "hook UserPromptSubmit refusé");
          expect((await find())?.state === "working", "la session devrait travailler");
          await hook({ hook_event_name: "Stop", last_assistant_message: "Fini." }, opts.hookSecret);
          const done = await find();
          expect(done?.state === "done" && done.lines[0] === "Fini.", "la session devrait être finie, avec son résumé");
        } finally {
          await hook({ hook_event_name: "SessionEnd" }, opts.hookSecret).catch(() => {});
        }
        expect(!(await find()), "la session de test devrait être retirée");
        return `${usage} ; travaille, fini, retirée`;
      },
    },
    {
      name: "son",
      run: async () => {
        const audio = await call("GET", "/system/audio");
        if (!controlled()) return expectStatus(audio, 501, "/system/audio");
        expectStatus(audio, 200, "/system/audio");
        expect(typeof audio.json.output === "number", "volume de sortie absent");
        return `volume ${audio.json.output}${audio.json.muted ? ", coupé" : ""}`;
      },
    },
    {
      name: "mélangeur",
      run: async () => {
        const res = await call("GET", "/system/mixer");
        if (!controlled()) return expectStatus(res, 501, "/system/mixer");
        expectStatus(res, 200, "/system/mixer");
        expect(res.json.master?.id === "master" && res.json.mic?.id === "mic", "sortie ou micro absent");
        const scenes = await call("GET", "/system/mixer/scenes");
        expectStatus(scenes, 200, "/system/mixer/scenes");
        return res.json.apps ? `${res.json.apps.length} applis avec du son` : "sortie et micro (pas de volume par appli)";
      },
    },
    {
      name: "performances",
      run: async () => {
        const perf = await call("GET", "/system/perf");
        if (!controlled()) return expectStatus(perf, 501, "/system/perf");
        expectStatus(perf, 200, "/system/perf");
        const { cpu, ram, gpu, cpuMhz, disk, net } = perf.json;
        expect(typeof cpu === "number" && cpu >= 0 && cpu <= 100, `processeur hors 0-100 : ${cpu}`);
        expect(ram?.total > 0, "mémoire totale absente");
        // Champs facultatifs : absents si illisibles, mais bien typés et bornés quand ils sont là.
        const count = (n: unknown) => Number.isInteger(n) && (n as number) >= 0;
        expect(cpuMhz === undefined || (count(cpuMhz) && cpuMhz > 0), `fréquence invalide : ${cpuMhz}`);
        expect(
          gpu?.vramUsedMb === undefined || (count(gpu.vramUsedMb) && gpu.vramUsedMb <= (gpu.vramTotalMb ?? Infinity)),
          `mémoire vidéo utilisée invalide : ${gpu?.vramUsedMb}`,
        );
        expect(
          gpu?.vramTotalMb === undefined || (count(gpu.vramTotalMb) && gpu.vramTotalMb > 0),
          `mémoire vidéo totale invalide : ${gpu?.vramTotalMb}`,
        );
        expect(
          disk === undefined ||
            (typeof disk.freeGb === "number" &&
              disk.totalGb > 0 &&
              disk.freeGb >= 0 &&
              disk.freeGb <= disk.totalGb &&
              (disk.activity === undefined || (count(disk.activity) && disk.activity <= 100))),
          `disque invalide : ${JSON.stringify(disk)}`,
        );
        expect(net === undefined || (count(net.upKBps) && count(net.downKBps)), `réseau invalide : ${JSON.stringify(net)}`);
        const extras = [
          cpuMhz && `${cpuMhz} MHz`,
          disk && `disque ${disk.freeGb}/${disk.totalGb} Go`,
          net && `réseau ${net.downKBps}/${net.upKBps} Ko/s`,
          gpu?.vramTotalMb && `vram ${gpu.vramUsedMb ?? "?"}/${gpu.vramTotalMb} Mo`,
        ].filter(Boolean);
        return `cpu ${cpu} %, ram ${ram.percent} %${gpu ? `, gpu ${gpu.usage} %` : ""}${extras.length ? ` (${extras.join(", ")})` : ""}`;
      },
    },
    {
      name: "presse-papiers",
      run: async () => {
        const first = await call("GET", "/system/clipboard");
        if (!controlled()) return expectStatus(first, 501, "/system/clipboard");
        // Seul le 503 du compagnon (aucune session de bureau : conteneur Windows, service sans session ouverte) passe
        // son tour ; toute autre erreur, un script Mac cassé (500) par exemple, fait échouer le contrôle.
        if (first.status === 503) throw new Skip(first.json?.message ?? "presse-papiers indisponible");
        expectStatus(first, 200, "GET /system/clipboard");
        const old = first.json;
        // Image, contenu sensible, fichiers, ou texte coupé : pas écrasé, lecture seulement. Le contenu n'est jamais affiché.
        if ((old.kind !== "text" && old.kind !== "empty") || old.truncated) return `lecture seule (${old.kind})`;
        // Accents compris : le texte doit revenir à l'identique (encodage de pbcopy / du pilote).
        const marker = "sanity-presse-papiers é à ç";
        expectStatus(await call("POST", "/system/clipboard", { text: marker }), 200, "POST /system/clipboard");
        try {
          const read = await call("GET", "/system/clipboard");
          expectStatus(read, 200, "relecture du presse-papiers");
          expect(read.json.kind === "text" && read.json.text === marker, `texte écrit mal relu (${read.json.kind})`);
        } finally {
          // L'API ne sait pas vider le presse-papiers : s'il l'était, le texte du contrôle y reste.
          if (old.kind === "text") await call("POST", "/system/clipboard", { text: old.text });
        }
      },
    },
    {
      name: "fenêtres ouvertes",
      run: async () => {
        // Mac sans Accessibilité (install-mac.sh) : System Events demanderait une autorisation à l'écran.
        if (os === "mac" && accessibility === false) throw new Skip("Accessibilité non accordée");
        const open = await call("GET", "/windows/open");
        if (!controlled()) return expectStatus(open, 501, "/windows/open");
        expectStatus(open, 200, "/windows/open");
        expect(Array.isArray(open.json.apps) && Array.isArray(open.json.displays), "réponse sans apps ni displays");
        // Sans bureau (conteneur Windows), la liste est vide : accepté.
        return `${open.json.apps.length} applis, ${open.json.displays.length} écrans`;
      },
    },
    {
      name: "absence : état gardé (lecture seule)",
      run: async () => {
        // Jamais de start ni de resume ici : ils pilotent les fenêtres réelles.
        const res = await call("GET", "/away");
        expectStatus(res, 200, "GET /away");
        expect(typeof res.json.saved === "boolean" && typeof res.json.count === "number", "réponse sans saved ni count");
        return res.json.saved ? `${res.json.count} fenêtres gardées` : "rien de gardé";
      },
    },
    {
      name: "GitLab : MR (lecture seule)",
      run: async () => {
        const mrs = await call("GET", "/gitlab/mrs");
        if (!controlled()) return expectStatus(mrs, 501, "/gitlab/mrs");
        const features = await call("GET", "/deck/features");
        expectStatus(features, 200, "/deck/features");
        expect(typeof features.json.gitlab === "boolean", "/deck/features sans booléen gitlab");
        // Jeton refusé ou GitLab injoignable depuis cet ordinateur : pas une panne du compagnon.
        if (mrs.status === 502) throw new Skip(`GitLab inaccessible depuis l'ordinateur (${mrs.json?.error})`);
        expectStatus(mrs, 200, "/gitlab/mrs");
        expect(Array.isArray(mrs.json.mine) && Array.isArray(mrs.json.review), "forme de /gitlab/mrs inattendue");
        // Ni compte connecté ni GITLAB_TOKEN (CI, tests) : réponse normale « pas connecté », jamais de 401. Jamais de fusion ni d'ouverture ici.
        if (mrs.json.connected === false) return "pas connecté à GitLab";
        expect(features.json.gitlab === true, "GitLab connecté mais gitlab absent de /deck/features");
        return `${mrs.json.mine.length} MR à moi, ${mrs.json.review.length} à relire`;
      },
    },
    {
      name: "GitLab : pipelines (lecture seule)",
      run: async () => {
        const res = await call("GET", "/gitlab/pipelines");
        if (!controlled()) return expectStatus(res, 501, "/gitlab/pipelines");
        if (res.status === 502) throw new Skip(`GitLab inaccessible depuis l'ordinateur (${res.json?.error})`);
        expectStatus(res, 200, "/gitlab/pipelines");
        expect(Array.isArray(res.json.pipelines), "forme de /gitlab/pipelines inattendue");
        if (res.json.connected === false) return "pas connecté à GitLab";
        return `${res.json.pipelines.length} pipelines`;
      },
    },
    {
      name: "pointeur",
      run: async () => {
        // Déplacement nul seulement : jamais de clic sur un ordinateur qui sert à autre chose.
        if (os === "mac" && accessibility === false) throw new Skip("Accessibilité non accordée");
        const res = await call("POST", "/pointer/move", { dx: 0, dy: 0 });
        if (!controlled()) return expectStatus(res, 501, "/pointer/move");
        expectStatus(res, 200, "POST /pointer/move");
      },
    },
    {
      name: "présentation (action inconnue refusée)",
      run: async () => {
        // Jamais une vraie action : une appli de diapos ouverte sur l'ordinateur recevrait des touches.
        // Refusée par la validation avant tout pilotage : 400 aussi sur un ordinateur non pilotable.
        expectStatus(await call("POST", "/present/inconnue"), 400, "/present/inconnue");
      },
    },
    {
      name: "garde de /docs/open (refuse un .exe)",
      run: async () => {
        const res = await call("POST", "/docs/open", { path: "sanity.exe" });
        if (!controlled()) return expectStatus(res, 501, "/docs/open");
        expectStatus(res, 400, "/docs/open d'un .exe");
        // Message en français dans `message` (erreur 400 de Fastify), lu en premier par la tablette.
        const message = res.json.message ?? res.json.error;
        expect(/programme ou un script/.test(message ?? ""), `refus inattendu : ${message}`);
      },
    },
    {
      name: "cadre photo : liste du dossier",
      run: async () => {
        const res = await call("GET", "/frame");
        expectStatus(res, 200, "GET /frame");
        expect(Array.isArray(res.json.photos), "réponse sans photos");
        return `${res.json.photos.length} photos`;
      },
    },
    {
      name: "tâches : créer, lister, cocher",
      run: async () => {
        const added = await call("POST", "/tasks", { label: stamp });
        expectStatus(added, 200, "POST /tasks");
        const taskId: string = added.json.id;
        try {
          const listed = await call("GET", "/tasks");
          expect(
            listed.json.tasks.some((t: { id: string }) => t.id === taskId),
            "tâche créée absente de la liste",
          );
          expectStatus(await call("POST", `/tasks/${taskId}/done`, { done: true }), 200, "cocher la tâche");
          const after = await call("GET", "/tasks");
          expect(after.json.tasks.find((t: { id: string }) => t.id === taskId)?.done === true, "tâche cochée mais pas faite");
        } finally {
          await call("DELETE", `/tasks/${taskId}`);
        }
      },
    },
    {
      name: "Jira : tickets",
      run: async () => {
        // Lecture seule : jamais de transition, ce sont les vrais tickets de l'utilisateur.
        // Hors Mac et Windows, /deck/features répond 501 : on ne sait plus si Jira est configuré.
        const features = await call("GET", "/deck/features");
        const configured = features.status === 200 ? features.json.jira : undefined;
        const issues = await call("GET", "/jira/issues");
        if (configured === false || (configured === undefined && issues.status === 501)) {
          return expectStatus(issues, 501, "/jira/issues sans Jira configuré");
        }
        expectStatus(issues, 200, "/jira/issues");
        expect(Array.isArray(issues.json.columns), "forme de /jira/issues inattendue");
        expect(typeof issues.json.boardsChosen === "boolean", "boardsChosen absent de /jira/issues");
        const boards = await call("GET", "/jira/boards");
        expectStatus(boards, 200, "/jira/boards");
        expect(Array.isArray(boards.json.boards) && Array.isArray(boards.json.selected), "forme de /jira/boards inattendue");
      },
    },
    {
      name: "agenda personnel",
      run: async () => {
        const startMs = Date.now() + 5 * 60_000;
        const added = await call("POST", "/calendar/events", { title: stamp, startMs, allDay: false });
        expectStatus(added, 200, "POST /calendar/events");
        try {
          const cal = await call("GET", "/calendar?days=2");
          expectStatus(cal, 200, "/calendar");
          expect(
            cal.json.events.some((e: { title: string }) => e.title === stamp),
            "rendez-vous absent de l'agenda",
          );
        } finally {
          expectStatus(await call("DELETE", `/calendar/events/${added.json.id}`), 200, "supprimer le rendez-vous");
        }
      },
    },
    {
      name: "agenda ICS (fichier de test local)",
      run: async () => {
        if (!local) throw new Skip("compagnon distant : le fichier ICS de test n'est servi qu'en local");
        const status = await call("GET", "/local/status");
        expectStatus(status, 200, "/local/status");
        if (status.json.calendar.saved.length > 0) throw new Skip("un agenda est déjà relié sur cet ordinateur : pas touché");
        const title = `${stamp}-ics`;
        const ics = await serveIcs(icsFile(title));
        try {
          const put = await call("PUT", "/local/calendar", { urls: [ics.url] });
          expectStatus(put, 200, "PUT /local/calendar");
          expect(put.json.ok === true, `agenda ICS illisible : ${put.json.error}`);
          const cal = await call("GET", "/calendar?days=1");
          expect(
            cal.json.events.some((e: { title: string }) => e.title === title),
            "événement du fichier ICS absent",
          );
        } finally {
          await call("PUT", "/local/calendar", { urls: [] });
          ics.close();
        }
      },
    },
    {
      name: "essai du backend",
      run: async () => {
        const state = await call("GET", "/companion/backend");
        expectStatus(state, 200, "/companion/backend");
        expect(state.json.mode === "origin" && typeof state.json.packaged === "boolean", `état inattendu : ${JSON.stringify(state.json)}`);
        // Rien à restaurer : ni changement, ni redémarrage.
        const origin = await call("POST", "/companion/backend/origin", {});
        expectStatus(origin, 200, "/companion/backend/origin");
        expect(origin.json.changed === false, "le compagnon dit avoir restauré un backend");
      },
    },
    {
      name: "mise à jour du backend depuis la tablette",
      run: async () => {
        const health = await call("GET", "/health");
        const packaged = (await call("GET", "/companion/backend")).json.packaged;
        expect(health.json.backendUpdate === packaged, `backendUpdate ${health.json.backendUpdate}, paquet ${packaged}`);
        // Version 0.0.0 : jamais plus récente, refusée avant de lire l'archive (ou lancé depuis le dépôt), rien n'est posé.
        const res = await fetch(`${base}/companion/backend/update?version=0.0.0&sha256=${"0".repeat(64)}`, {
          method: "POST",
          headers: { "content-type": "application/gzip", authorization: `Bearer ${opts.token}` },
          body: "x",
          signal: AbortSignal.timeout(45_000),
        });
        expect(res.status === 409, `mise à jour en 0.0.0 : HTTP ${res.status}, 409 attendu`);
        return packaged ? "installé par paquet" : "lancé depuis le dépôt";
      },
    },
    {
      name: "visualiseur /camera",
      run: async () => {
        const page = await call("GET", "/camera");
        expectStatus(page, 200, "/camera");
        expect(/<title>Visualiseur<\/title>/.test(page.json), "page du visualiseur inattendue");
        const status = await call("GET", "/camera/status");
        expectStatus(status, 200, "/camera/status");
      },
    },
    {
      name: "page locale",
      run: async () => {
        const page = await call("GET", "/", undefined, local ? false : true);
        expectStatus(page, 200, "/");
        expect(/<title>Mate<\/title>/.test(page.json), "page locale inattendue");
        const status = await call("GET", "/local/status");
        expectStatus(status, 200, "/local/status");
        expect(Array.isArray(status.json.addresses) && status.json.addresses.length > 0, "aucune adresse affichée");
        expect("tabletInfo" in status.json, "état de la tablette absent de /local/status");
        // Réglage vide : refusé avant d'atteindre une tablette peut-être réelle.
        expectStatus(await call("PUT", "/local/tablet/settings", {}), 400, "PUT /local/tablet/settings vide");
        expectStatus(await call("POST", "/local/tablet/update", { action: "rollback" }), 400, "POST /local/tablet/update inconnue");
      },
    },
    {
      name: "porte MCP (IA de bureau, lecture seule)",
      run: async () => {
        if (!local) throw new Skip("porte MCP en local seulement");
        const res = await fetch(base + "/mcp", {
          method: "POST",
          headers: { "content-type": "application/json", accept: "application/json, text/event-stream" },
          body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list" }),
          signal: AbortSignal.timeout(15_000),
        });
        expect(res.status === 200, `POST /mcp : ${res.status} au lieu de 200`);
        const names = ((await res.json()).result?.tools ?? []).map((t: { name: string }) => t.name);
        for (const name of ["tablette_regler", "profils_ecrire", "deck_touche_affecter"])
          expect(names.includes(name), `outil ${name} absent`);
        const status = await call("GET", "/local/status");
        expect(status.json.mcp?.url === base + "/mcp", "adresse de la porte MCP absente de /local/status");
        return `${names.length} outils`;
      },
    },
    {
      name: "politique de confidentialité",
      run: async () => {
        const res = await call("GET", "/local/confidentialite", undefined, local ? false : true);
        expectStatus(res, 200, "/local/confidentialite");
        expect(String(res.json).startsWith("Mate : politique de confidentialité"), "politique de confidentialité inattendue");
      },
    },
    {
      name: "mentions de licences",
      run: async () => {
        // Compagnon installé : LICENCES-TIERS.txt joint par scripts/package.ts ; lancé depuis le dépôt, pas de fichier.
        const status = await call("GET", "/local/status");
        const res = await call("GET", "/local/licences", undefined, local ? false : true);
        if (!status.json.licences) {
          expectStatus(res, 404, "/local/licences sans fichier");
          throw new Skip("compagnon lancé depuis le dépôt : licences jointes à l'appli portable seulement");
        }
        expectStatus(res, 200, "/local/licences");
        const text = String(res.json);
        expect(text.startsWith("Mate : composants tiers et licences"), "fichier de licences inattendu");
        expect(/^- Node\.js v/m.test(text) && /^- fastify /m.test(text), "node ou fastify absents des licences");
        return `${text.split("\n").filter((l) => /^- /.test(l)).length} lignes de composants`;
      },
    },
  ];

  const results: Result[] = [];
  for (const check of checks) {
    const start = Date.now();
    try {
      const detail = await check.run();
      results.push({ name: check.name, status: "ok", detail: detail ?? "", ms: Date.now() - start });
    } catch (e) {
      const status = e instanceof Skip ? "skip" : "fail";
      results.push({ name: check.name, status, detail: (e as Error).message, ms: Date.now() - start });
    }
  }
  return results;
}

const xml = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

export function junit(results: Result[], suite: string): string {
  const failures = results.filter((r) => r.status === "fail").length;
  const skipped = results.filter((r) => r.status === "skip").length;
  const time = results.reduce((t, r) => t + r.ms, 0) / 1000;
  const cases = results.map((r) => {
    const open = `  <testcase classname="${xml(suite)}" name="${xml(r.name)}" time="${r.ms / 1000}"`;
    if (r.status === "ok") return `${open}/>`;
    const inner = r.status === "skip" ? `<skipped message="${xml(r.detail)}"/>` : `<failure message="${xml(r.detail)}"/>`;
    return `${open}>${inner}</testcase>`;
  });
  return `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="${xml(suite)}" tests="${results.length}" failures="${failures}" skipped="${skipped}" time="${time}">\n${cases.join("\n")}\n</testsuite>\n`;
}

function arg(name: string): string | undefined {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? process.argv[i + 1] : undefined;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const url = arg("url") ?? "http://127.0.0.1:3000";
  const token = arg("token") ?? process.env.COMPANION_TOKEN ?? "";
  const os = arg("os") as Os | undefined;
  const hookSecret = arg("hook-secret");
  if (!token || (os && !["mac", "windows", "other"].includes(os))) {
    console.error(
      "Usage : node qa/sanity.ts --url http://127.0.0.1:3000 --token <jeton> [--os windows|mac|other] [--hook-secret <secret>] [--junit fichier.xml]",
    );
    process.exit(2);
  }
  const results = await runSanity({ url, token, os, hookSecret });
  const mark = { ok: "OK  ", fail: "ECHEC", skip: "SAUTE" };
  for (const r of results) console.log(`${mark[r.status]} ${r.name}${r.detail ? ` : ${r.detail}` : ""}`);
  const failed = results.filter((r) => r.status === "fail").length;
  console.log(`\n${results.length - failed} / ${results.length} contrôles sans échec`);
  const file = arg("junit");
  if (file) fs.writeFileSync(file, junit(results, `sanity-${os ?? "auto"}`));
  process.exit(failed ? 1 : 0);
}

import { createReadStream, existsSync } from "node:fs";
import fs from "node:fs/promises";
import path from "node:path";
import { createHash, timingSafeEqual } from "node:crypto";
import Fastify from "fastify";
import multipart from "@fastify/multipart";
import { config } from "./config.ts";
import { liveProviders, mockProviders, type ChatMessage } from "./providers.ts";
import {
  addDeckApp, addDeckLink, deckLinks, findTile, homeFeatures, LINK_SUGGESTIONS, loadDeck, removeDeckApp, removeDeckLink, resetDeckApps,
  appIconFile, runAction, tileIcon,
} from "./deck.ts";
import {
  accessibility, appAudio, browserPage, isAppRunning, listButtons, openAccessibility, canControl, focusWindow, getAudio, listApps, mediaControl, mediaSessions, mediaThumb, listDisplays, listOpenWindows, listShortcuts, listWindows, controlOpenWindow, openTarget, osName, placeWindow, setAudio, voiceState,
} from "./os.ts";
import type { Layout, OpenAction, WindowTarget } from "./screens.ts";
import { keySchema, loadKeys, saveKey, SLOTS, type PersoKey } from "./perso.ts";
import { confirmPairing, identity, lanAddresses, PairingError, pendingCode, requestPairing, startDiscovery } from "./pairing.ts";
import { LOCAL_PAGE } from "./local-page.ts";
import { listUpdates, updateFile } from "./updates.ts";
import { docEntry, kindOf, listDocs, resolveDoc, savePhoto } from "./docs.ts";
import { openMediaLink } from "./music.ts";
import { calendarSources, dayRange, getEvents, invalidateCalendar, linkedCalendars, saveCalendarUrls, savedCalendarUrls, unlinkCalendar } from "./calendar.ts";
import { addEvent, deleteEvent, startEventReminders, updateEvent, type EventInput } from "./events.ts";
import { availableProviders, connectedAccounts, disconnect, finishLogin, isProvider, loginUrl } from "./accounts.ts";
import { autoJoinEnabled, joinMeeting, setAutoJoin, startAutoJoin } from "./meetings.ts";
import { addTask, deleteTask, listRecurring, listTasks, setTaskDone, startReminders, taskUrl } from "./tasks.ts";
import { addViewer, CAMERA_STATUSES, cameraStatus, pushFrame, setCameraState, VIEWER_PAGE, type CameraStatus } from "./camera.ts";
import { completeCommand, FOCUS_ACTIONS, MODES, NOISES, pollCommands, reportState, sendToTablet, tabletStatus, type TabletCommand } from "./tablet.ts";

// Ce serveur pilote l'ordinateur : hors loopback (USB + adb reverse), un jeton est obligatoire.
if (!config.loopbackOnly && !config.token) {
  console.error(`HOST=${config.host} expose le serveur sur le réseau : définis COMPANION_TOKEN dans .env.`);
  process.exit(1);
}

const providers = config.mode === "live" ? liveProviders : mockProviders;
const app = Fastify({ logger: { level: "info" } });
await app.register(multipart, { limits: { fileSize: 20 * 1024 * 1024, files: 1 } });

const expectedAuth = config.token ? Buffer.from(`Bearer ${config.token}`) : undefined;
const LOOPBACK = new Set(["127.0.0.1", "::1", "::ffff:127.0.0.1"]);
/** Nom demandé par le navigateur : un site qui ferait pointer son domaine vers 127.0.0.1 (DNS rebinding) est refusé. */
const LOCAL_HOST = /^(127\.0\.0\.1|localhost|\[::1\])(:\d+)?$/i;
/**
 * Pages ouvertes dans le navigateur de l'ordinateur (visualiseur, page d'accueil avec le code d'appairage) :
 * pas de jeton possible, lecture seule et en local.
 */
const isLocalPage = (method: string, url: string, ip: string, host: string | undefined) =>
  LOOPBACK.has(ip) &&
  LOCAL_HOST.test(host ?? "") &&
  ((method === "GET" && /^\/(camera(\/stream|\/status)?|local\/status|oauth\/(microsoft|google)\/(start|callback))?(\?|$)/.test(url)) ||
    (method === "PUT" && url === "/local/calendar") ||
    (method === "POST" && url === "/system/accessibility") ||
    (method === "DELETE" && /^\/local\/accounts\/(microsoft|google)$/.test(url)));
/** Sans jeton : identité (la tablette reconnaît l'ordinateur) et appairage par code. */
const PUBLIC = /^\/(health|pair\/request|pair\/confirm)(\?|$)/;
const authorized = (header: string | undefined) => {
  if (!expectedAuth) return true;
  const given = Buffer.from(header ?? "");
  return given.length === expectedAuth.length && timingSafeEqual(given, expectedAuth);
};
app.addHook("onRequest", async (req, reply) => {
  if (PUBLIC.test(req.url) || isLocalPage(req.method, req.url, req.ip, req.headers.host)) return;
  if (!authorized(req.headers.authorization)) return reply.code(401).send({ error: "Jeton invalide : appairer à nouveau la tablette" });
});

/** `auth` : le jeton envoyé est le bon (la tablette sait s'il faut refaire l'appairage). */
app.get("/health", async (req) => ({
  ok: true, mode: config.mode, ...identity(), auth: authorized(req.headers.authorization),
  // Mac : false = fenêtres et raccourcis bloqués tant que l'Accessibilité n'est pas accordée (la tablette prévient).
  accessibility: await accessibility().catch(() => null),
}));

/** Ouvre sur le Mac le réglage Accessibilité (depuis la tablette, ou la page locale). */
app.post("/system/accessibility", async () => {
  await openAccessibility();
  return { ok: true };
});

// --- Appairage (voir src/pairing.ts) et page d'accueil locale

/**
 * Erreurs montrées sur la tablette : nos messages (en français) passent tels quels ; le texte technique
 * (sortie d'osascript, erreur système de Node, requête mal formée) reste dans le journal de l'ordinateur.
 */
app.setErrorHandler((err: Error & { statusCode?: number; code?: unknown; validation?: unknown; technical?: boolean }, req, reply) => {
  if (err instanceof PairingError) return reply.code(err.statusCode).send({ error: err.message });
  if (err.validation) return reply.code(400).send({ error: "Demande invalide : mets à jour l'appli de la tablette" });
  const status = err.statusCode && err.statusCode >= 400 ? err.statusCode : 500;
  if (status >= 500 && (err.technical || (typeof err.code === "string" && /^E[A-Z]/.test(err.code)))) {
    req.log.error(err);
    return reply.code(status).send({ error: "L'ordinateur n'a pas pu le faire (détail dans son journal)" });
  }
  reply.send(err);
});

app.post<{ Body: { tablet?: string } }>(
  "/pair/request",
  { schema: { body: { type: "object", properties: { tablet: { type: "string", maxLength: 100 } } } } },
  async (req) => requestPairing(req.body?.tablet || "tablette"),
);

app.post<{ Body: { request: string; code: string } }>(
  "/pair/confirm",
  {
    schema: {
      body: {
        type: "object",
        required: ["request", "code"],
        properties: { request: { type: "string", maxLength: 100 }, code: { type: "string", maxLength: 20 } },
      },
    },
  },
  async (req) => confirmPairing(req.body.request, req.body.code),
);

app.get("/", async (_req, reply) => reply.type("text/html; charset=utf-8").send(LOCAL_PAGE));

app.get("/local/status", async () => ({
  ...identity(),
  accessibility: await accessibility().catch(() => null),
  addresses: config.loopbackOnly ? ["127.0.0.1"] : lanAddresses(),
  tablet: tabletStatus().connected,
  pairing: pendingCode(),
  // Hôtes seulement : un lien ICS publié donne accès à l'agenda, il ne s'affiche pas en entier.
  calendar: {
    env: config.icsUrls.length,
    saved: (await savedCalendarUrls()).map((u) => (u === "demo" ? "agenda d'exemple" : URL.parse(u)?.host ?? "lien invalide")),
    accounts: await connectedAccounts(),
    providers: await availableProviders(),
  },
}));

/** Lien(s) d'agenda collé(s) dans la page locale ; webcal:// devient https://. Liste vide : retire l'agenda. */
app.put<{ Body: { urls: string[] } }>(
  "/local/calendar",
  {
    schema: {
      body: {
        type: "object",
        required: ["urls"],
        additionalProperties: false,
        properties: { urls: { type: "array", maxItems: 5, items: { type: "string", pattern: "^(https?|webcal)://|^demo$", maxLength: 2000 } } },
      },
    },
  },
  async (req) => {
    await saveCalendarUrls(req.body.urls.map((u) => u.trim().replace(/^webcal:/i, "https:")));
    const sources = await calendarSources();
    // Lu tout de suite : un lien faux se voit sur la page plutôt que sur la tablette.
    const check = await getEvents(...dayRange(1)).then(
      (r) => ({ ok: true, today: r.events.length }),
      (e: Error) => ({ ok: false, error: e.message }),
    );
    return { sources: sources.length, ...check };
  },
);

// --- Assistant IA

app.post<{ Body: { messages: ChatMessage[] } }>(
  "/chat",
  {
    schema: {
      body: {
        type: "object",
        required: ["messages"],
        properties: {
          messages: {
            type: "array",
            minItems: 1,
            maxItems: 100,
            items: {
              type: "object",
              required: ["role", "content"],
              properties: {
                role: { enum: ["user", "assistant"] },
                content: { type: "string", maxLength: 8000 },
              },
            },
          },
        },
      },
    },
  },
  async (req) => ({ reply: await providers.chat(req.body.messages) }),
);

app.post("/transcribe", async (req, reply) => {
  const file = await req.file();
  if (!file) return reply.code(400).send({ error: "Champ multipart 'audio' manquant" });
  const audio = await file.toBuffer();
  return { text: await providers.transcribe(audio, file.mimetype) };
});

// --- Pilotage de l'ordinateur (deck d'applis et de raccourcis, son, micro)

app.addHook("preHandler", async (req, reply) => {
  if (!canControl && (req.url.startsWith("/deck") || req.url.startsWith("/system"))) {
    return reply.code(501).send({ error: "Pilotage disponible sur macOS et Windows seulement" });
  }
});

app.get("/deck", async () => {
  const pages = await loadDeck();
  return {
    pages: pages.map((p) => ({
      title: p.title,
      editable: p.editable ?? false,
      apps: p.apps ?? false,
      meeting: p.meeting ?? false,
      app: p.app ?? null,
      media: p.media ?? false,
      tiles: p.tiles.map((t) =>
        "empty" in t
          ? { id: t.id, label: "", type: "empty", glyph: null, color: null }
          : {
              id: t.id,
              label: t.label,
              type: t.action.type,
              glyph: t.glyph ?? null,
              color: t.color ?? null,
              when: t.when ?? null,
              // Nom de l'appli (page Apps : liste des applis déjà ajoutées).
              app: t.action.type === "app" ? t.action.name : null,
            },
      ),
    })),
  };
});

app.get<{ Params: { id: string } }>("/deck/icon/:id", async (req, reply) => {
  const tile = await findTile(req.params.id);
  const icon = tile && (await tileIcon(tile));
  if (!icon) return reply.code(404).send({ error: "Pas d'icône" });
  reply.header("Cache-Control", "max-age=86400");
  return reply.type("image/png").send(createReadStream(icon));
});

const windowTarget = {
  type: "object",
  required: ["index", "title"],
  // Mac : index 0 = fenêtre d'un autre bureau (menu Fenêtre), retrouvée par son titre. Windows : handle.
  properties: { index: { type: "integer", minimum: 0 }, title: { type: "string", maxLength: 500 } },
};

// --- Vue « Ouvertes » : toutes les applis ouvertes sur l'ordinateur et l'écran de chacune de leurs fenêtres.

app.get("/windows/open", async () => listOpenWindows());

app.get<{ Querystring: { name: string } }>(
  "/windows/icon",
  { schema: { querystring: { type: "object", required: ["name"], properties: { name: { type: "string", minLength: 1, maxLength: 200 } } } } },
  async (req, reply) => {
    const icon = await appIconFile(req.query.name);
    if (!icon) return reply.code(404).send({ error: "Pas d'icône" });
    reply.header("Cache-Control", "max-age=86400");
    return reply.type("image/png").send(createReadStream(icon));
  },
);

app.post<{ Body: { key: string; action: OpenAction; window: WindowTarget; display?: number; layout?: Layout } }>(
  "/windows/open",
  {
    schema: {
      body: {
        type: "object",
        required: ["key", "action", "window"],
        additionalProperties: false,
        properties: {
          key: { type: "string", maxLength: 1000 },
          action: { enum: ["focus", "place", "minimize", "zoom", "close"] },
          window: windowTarget,
          display: { type: "integer" },
          layout: { enum: ["keep", "left", "right", "max"] },
        },
      },
    },
  },
  async (req) => {
    const { key, action, window, display, layout } = req.body;
    await controlOpenWindow(key, window, action, action === "place" ? { display, layout: layout ?? "keep" } : undefined);
    return listOpenWindows();
  },
);

app.post<{ Body: { id: string } }>(
  "/deck/run",
  { schema: { body: { type: "object", required: ["id"], properties: { id: { type: "string", maxLength: 20 } } } } },
  async (req, reply) => {
    const tile = await findTile(req.body.id);
    if (!tile) return reply.code(404).send({ error: "Tuile inconnue" });
    // Appli déjà ouverte avec plusieurs fenêtres : la tablette propose de choisir au lieu d'activer.
    if (tile.action.type === "app") {
      const info = await listWindows(tile.action.name).catch(() => undefined);
      if (info && info.windows.length > 1) return { ok: true, windows: info };
    }
    // Page Média : l'appli du site si elle est installée, et la musique démarre seule.
    if (tile.id.startsWith("l-") && tile.action.type === "url") return { ok: true, ...(await openMediaLink(tile.action.url)) };
    await runAction(tile.action);
    return { ok: true };
  },
);

// --- Page Apps : profil de base, complété depuis la tablette par les applis installées sur le Mac

app.post<{ Body: { name: string } }>(
  "/deck/apps",
  { schema: { body: { type: "object", required: ["name"], properties: { name: { type: "string", minLength: 1, maxLength: 200 } } } } },
  async (req, reply) => {
    if (!(await listApps()).includes(req.body.name)) return reply.code(404).send({ error: "Appli introuvable sur l'ordinateur" });
    return { ok: true, id: await addDeckApp(req.body.name) };
  },
);

app.delete<{ Params: { id: string } }>("/deck/apps/:id", async (req) => {
  await removeDeckApp(req.params.id);
  return { ok: true };
});

// --- Page Média : sites ajoutés depuis la tablette (vide au départ), avec une liste de sites proposés

app.get("/deck/links", async () => ({ links: await deckLinks(), suggestions: LINK_SUGGESTIONS }));

app.post<{ Body: { url: string; label?: string } }>(
  "/deck/links",
  {
    schema: {
      body: {
        type: "object",
        required: ["url"],
        properties: { url: { type: "string", minLength: 1, maxLength: 2000 }, label: { type: "string", maxLength: 40 } },
      },
    },
  },
  async (req, reply) => {
    try {
      return { ok: true, id: await addDeckLink(req.body.url, req.body.label) };
    } catch {
      return reply.code(400).send({ error: "Adresse invalide (ex. netflix.com)" });
    }
  },
);

app.delete<{ Params: { id: string } }>("/deck/links/:id", async (req) => {
  await removeDeckLink(req.params.id);
  return { ok: true };
});

/** Cartes de l'Accueil à afficher selon les applis du deck. */
app.get("/deck/features", () => homeFeatures());

// Applis des pages d'appli (Discord, Teams) en appel en ce moment (micro utilisé) : la tablette leur donne un onglet.
app.get("/deck/live", { logLevel: "warn" }, async () => {
  const apps = [...new Set((await loadDeck()).flatMap((p) => (p.app ? [p.app] : [])))];
  // `open` : l'appli tourne (onglet affiché) ; `apps` : elle utilise le micro, donc en appel (Windows seulement).
  const [open, live] = await Promise.all([
    Promise.all(apps.map((a) => isAppRunning(a).catch(() => false))),
    Promise.all(apps.map((a) => (async () => (await appAudio(a))?.capturing === true)().catch(() => false))),
  ]);
  return { apps: apps.filter((_, i) => live[i]), open: apps.filter((_, i) => open[i] || live[i]) };
});

/** Boutons cliquables d'une appli ouverte (Windows) : de quoi écrire une étape « appuyer sur un bouton ». */
app.get<{ Querystring: { app: string; detail?: boolean } }>(
  "/deck/buttons",
  {
    schema: {
      querystring: {
        type: "object",
        required: ["app"],
        // detail : diagnostic, tous les éléments avec leur classe.
        properties: { app: { type: "string", minLength: 1, maxLength: 100 }, detail: { type: "boolean" } },
      },
    },
  },
  async (req) => listButtons(req.query.app, req.query.detail === true),
);

/** Page d'une appli d'appel (Discord) : salon ou appel en cours, participants, micro ; null si illisible (Mac, fermée). */
app.get<{ Querystring: { app: string } }>(
  "/deck/voice",
  { logLevel: "warn", schema: { querystring: { type: "object", required: ["app"], properties: { app: { type: "string", minLength: 1, maxLength: 100 } } } } },
  async (req) => ({ voice: (await voiceState(req.query.app).catch(() => null)) ?? null }),
);

/** Retour au profil de base. */
app.delete("/deck/apps", async () => {
  await resetDeckApps();
  return { ok: true };
});

// --- Touches perso (page Perso du deck), modifiables depuis la tablette et par Claude

const slotParam = { type: "object", required: ["slot"], properties: { slot: { type: "integer", minimum: 0, maximum: SLOTS - 1 } } };

/** De quoi remplir l'éditeur de touche : applis installées, raccourcis Apple (Mac), écrans. */
app.get("/deck/catalog", async () => {
  const [apps, shortcuts, displays] = await Promise.all([listApps(), listShortcuts(), Promise.resolve().then(listDisplays).catch(() => [])]);
  return { os: osName, apps, shortcuts, displays: displays.map((d) => d.label) };
});

app.get<{ Params: { slot: number } }>("/deck/keys/:slot", { schema: { params: slotParam } }, async (req) => ({
  key: (await loadKeys())[req.params.slot],
}));

app.put<{ Params: { slot: number }; Body: PersoKey }>(
  "/deck/keys/:slot",
  { schema: { params: slotParam, body: keySchema } },
  async (req) => {
    await saveKey(req.params.slot, req.body);
    return { ok: true };
  },
);

app.delete<{ Params: { slot: number } }>("/deck/keys/:slot", { schema: { params: slotParam } }, async (req) => {
  await saveKey(req.params.slot, null);
  return { ok: true };
});

/** Fenêtres de l'appli d'une tuile, et écrans de l'ordinateur. */
function tileApp(tile: Awaited<ReturnType<typeof findTile>>) {
  return tile?.action.type === "app" ? tile.action.name : undefined;
}

app.get<{ Params: { id: string } }>("/deck/windows/:id", async (req, reply) => {
  const name = tileApp(await findTile(req.params.id));
  if (!name) return reply.code(404).send({ error: "Pas une tuile d'appli" });
  return listWindows(name);
});

app.post<{ Params: { id: string }; Body: { action: "focus" | "place"; window?: WindowTarget; display?: number; layout?: Layout } }>(
  "/deck/windows/:id",
  {
    schema: {
      body: {
        type: "object",
        required: ["action"],
        additionalProperties: false,
        properties: {
          action: { enum: ["focus", "place"] },
          // Sans `window` : toutes les fenêtres de l'appli.
          window: windowTarget,
          display: { type: "integer" },
          layout: { enum: ["keep", "left", "right", "max"] },
        },
      },
    },
  },
  async (req, reply) => {
    const name = tileApp(await findTile(req.params.id));
    if (!name) return reply.code(404).send({ error: "Pas une tuile d'appli" });
    const { action, window, display, layout } = req.body;
    if (action === "place") return placeWindow(name, { window, display, layout: layout ?? "keep" });
    // Activer l'appli entière : la relance aussi si elle a été fermée entre-temps.
    if (!window) await runAction({ type: "app", name });
    return window ? focusWindow(name, window) : listWindows(name);
  },
);

app.get("/system/audio", async () => getAudio());

app.post<{ Body: { output?: number; outputDelta?: number; muted?: boolean; micMuted?: boolean } }>(
  "/system/audio",
  {
    schema: {
      body: {
        type: "object",
        additionalProperties: false,
        properties: {
          output: { type: "integer", minimum: 0, maximum: 100 },
          outputDelta: { type: "integer", minimum: -100, maximum: 100 },
          muted: { type: "boolean" },
          micMuted: { type: "boolean" },
        },
      },
    },
  },
  async (req) => setAudio(req.body),
);

// Son d'une appli (page du deck liée à une appli : Discord…). Lu en boucle par la tablette tant que la page est affichée.
const appName = { type: "string", minLength: 1, maxLength: 100 } as const;

app.get<{ Querystring: { name: string } }>(
  "/system/audio/app",
  { logLevel: "warn", schema: { querystring: { type: "object", required: ["name"], properties: { name: appName } } } },
  async (req) => appAudio(req.query.name),
);

app.post<{ Body: { name: string; volume?: number; muted?: boolean } }>(
  "/system/audio/app",
  {
    schema: {
      body: {
        type: "object",
        required: ["name"],
        additionalProperties: false,
        properties: { name: appName, volume: { type: "integer", minimum: 0, maximum: 100 }, muted: { type: "boolean" } },
      },
    },
  },
  async (req) => appAudio(req.body.name, { volume: req.body.volume, muted: req.body.muted }),
);

// Lecture en cours (YouTube, Twitch, Spotify… : contrôles multimédias de Windows). Relue chaque seconde par la
// tablette tant que la page Média est affichée.
app.get("/system/media", { logLevel: "warn" }, async () => {
  const sessions = (await mediaSessions()) ?? [];
  // `thumb` : clé de la vignette, change avec le morceau ou la vidéo (la tablette ne la recharge qu'alors).
  return {
    sessions: sessions.map((s) => ({
      ...s,
      thumb: s.hasThumb ? createHash("sha1").update(`${s.app}|${s.title}|${s.artist}`).digest("hex").slice(0, 16) : null,
    })),
  };
});

app.post<{ Body: { action: "play-pause" | "next" | "previous" | "seek"; app?: string; positionMs?: number } }>(
  "/system/media",
  {
    schema: {
      body: {
        type: "object",
        required: ["action"],
        additionalProperties: false,
        properties: {
          action: { enum: ["play-pause", "next", "previous", "seek"] },
          app: { type: "string", maxLength: 200 },
          positionMs: { type: "integer", minimum: 0 },
        },
      },
    },
  },
  async (req) => ({ ok: await mediaControl(req.body.action, req.body.app, req.body.positionMs) }),
);

app.get<{ Querystring: { app: string; key: string } }>(
  "/system/media/thumb",
  {
    logLevel: "warn",
    schema: {
      querystring: {
        type: "object",
        required: ["app", "key"],
        properties: { app: { type: "string", maxLength: 200 }, key: { type: "string", pattern: "^[0-9a-f]{16}$" } },
      },
    },
  },
  async (req, reply) => {
    const file = path.join(config.cacheDir, "media", req.query.key);
    if (!existsSync(file)) {
      await fs.mkdir(path.dirname(file), { recursive: true });
      if (!(await mediaThumb(req.query.app, file))) return reply.code(404).send({ error: "Pas de vignette" });
    }
    const data = await fs.readFile(file);
    const type = data[0] === 0x89 ? "image/png" : data[0] === 0xff ? "image/jpeg" : "application/octet-stream";
    return reply.type(type).send(data);
  },
);

// --- Documents partagés (dossier DOCS_DIR sur l'ordinateur)

app.get<{ Querystring: { dir?: string } }>("/docs", async (req) => listDocs(req.query.dir ?? ""));

app.get<{ Querystring: { path: string } }>("/docs/file", async (req, reply) => {
  const full = resolveDoc(req.query.path ?? "");
  const stat = await fs.stat(full).catch(() => undefined);
  if (!stat?.isFile()) return reply.code(404).send({ error: "Fichier introuvable" });
  const types = { pdf: "application/pdf", image: "image/*", text: "text/plain; charset=utf-8" } as const;
  const kind = kindOf(full);
  reply.header("Content-Length", stat.size);
  return reply.type(kind in types ? types[kind as keyof typeof types] : "application/octet-stream").send(createReadStream(full));
});

/**
 * Redémarre le compagnon après une mise à jour de ses fichiers : le lancement automatique (boucle de
 * scripts/run-windows.ps1, LaunchAgent sur Mac) le relance quelques secondes plus tard. Sous `npm run dev`, tsx watch
 * attend un changement de fichier avant de relancer.
 */
app.post("/companion/restart", async (req) => {
  req.log.info("redémarrage demandé");
  setTimeout(() => process.exit(0), 300);
  return { ok: true };
});

// --- Mises à jour de la tablette (« serveur Unowhy » simulé, voir updates.ts)

app.get("/updates", async () => ({ updates: await listUpdates() }));

app.get<{ Params: { file: string } }>("/updates/file/:file", async (req, reply) => {
  const full = await updateFile(req.params.file);
  if (!full) return reply.code(404).send({ error: "Mise à jour inconnue" });
  const stat = await fs.stat(full);
  reply.header("Content-Length", stat.size);
  return reply.type("application/vnd.android.package-archive").send(createReadStream(full));
});

// --- Page web du navigateur de l'ordinateur, affichée sur la tablette (tutos à côté d'un jeu)

/** Adresse web lisible : la barre d'adresse de Chrome cache souvent « https:// ». Autre protocole : refusé. */
function webUrl(raw: string): string | undefined {
  try {
    const url = new URL(/^[a-z][a-z0-9+.-]*:/i.test(raw) ? raw : "https://" + raw);
    return url.protocol === "http:" || url.protocol === "https:" ? url.href : undefined;
  } catch {
    return undefined;
  }
}

app.get("/browser/page", async (req, reply) => {
  const page = await browserPage();
  const url = page.found && page.url ? webUrl(page.url) : undefined;
  if (!url) {
    // État du presse-papiers (Windows) : vide, autre chose qu'une adresse, ou illisible ; jamais son contenu.
    const clipboard = (page as { clipboard?: string }).clipboard;
    if (clipboard?.startsWith("error")) req.log.warn({ clipboard }, "presse-papiers illisible");
    const copy = osName === "mac" ? "⌘L puis ⌘C" : "Ctrl+L puis Ctrl+C";
    return reply.code(404).send({
      error: clipboard === "not-url"
        ? `Le lien copié n'est pas une adresse web : dans le navigateur de l'ordinateur, fais ${copy} et réessaie`
        : `Adresse illisible : dans le navigateur de l'ordinateur, copie l'adresse (${copy}) et réessaie`,
      clipboard: clipboard?.startsWith("error") ? "error" : clipboard,
    });
  }
  // Titre de fenêtre Windows : « Page - Opera », « Page - Personnel - Microsoft Edge », « Page — Mozilla Firefox ».
  const title = (page.title ?? "")
    .replace(/\s+[-—–]\s+(?:[^-—–]+\s+[-—–]\s+)?(?:Google Chrome|Microsoft\u200b?\s*Edge|Opera(?: GX)?|Brave|Vivaldi|Mozilla Firefox)\s*$/i, "")
    .trim();
  return { url, title: title || new URL(url).hostname, browser: page.browser ?? null };
});

/** Rouvre sur l'ordinateur la page affichée sur la tablette (elle a pu y changer de page). */
app.post<{ Body: { url: string } }>(
  "/browser/open",
  { schema: { body: { type: "object", required: ["url"], properties: { url: { type: "string", maxLength: 4000 } } } } },
  async (req, reply) => {
    const url = webUrl(req.body.url);
    if (!url) return reply.code(400).send({ error: "Adresse web attendue" });
    await openTarget(url);
    return { ok: true };
  },
);

app.post<{ Body: { path: string } }>(
  "/docs/open",
  { schema: { body: { type: "object", required: ["path"], properties: { path: { type: "string" } } } } },
  async (req) => {
    await openTarget(resolveDoc(req.body.path));
    return { ok: true };
  },
);

app.post("/docs/upload", async (req, reply) => {
  const file = await req.file();
  if (!file) return reply.code(400).send({ error: "Champ multipart 'photo' manquant" });
  const ext = file.mimetype === "image/png" ? ".png" : ".jpg";
  return { path: await savePhoto(await file.toBuffer(), ext) };
});

// --- Visualiseur (caméra de la tablette au-dessus d'une feuille, image projetée depuis l'ordinateur)

app.addContentTypeParser("image/jpeg", { parseAs: "buffer", bodyLimit: 2 * 1024 * 1024 }, (_req, body, done) => done(null, body));

app.post<{ Body: Buffer }>("/camera/frame", { logLevel: "warn" }, async (req, reply) => {
  if (!Buffer.isBuffer(req.body) || req.body.length === 0) return reply.code(415).send({ error: "Corps image/jpeg attendu" });
  pushFrame(req.body);
  return { ok: true };
});

app.post<{ Body: { status: CameraStatus; rotation?: number } }>(
  "/camera/state",
  {
    logLevel: "warn",
    schema: {
      body: {
        type: "object",
        required: ["status"],
        additionalProperties: false,
        properties: { status: { enum: CAMERA_STATUSES }, rotation: { enum: [0, 90, 180, 270] } },
      },
    },
  },
  async (req) => {
    setCameraState(req.body.status, req.body.rotation);
    return cameraStatus();
  },
);

app.get("/camera/status", { logLevel: "warn" }, async () => cameraStatus());

app.get("/camera", async (_req, reply) => reply.type("text/html; charset=utf-8").send(VIEWER_PAGE));

app.get("/camera/stream", async (_req, reply) => {
  reply.hijack();
  addViewer(reply.raw);
});

// Connexion d'un compte d'agenda (src/accounts.ts), depuis la page locale : le navigateur de l'ordinateur part chez
// Microsoft ou Google et revient sur /oauth/…/callback (adresse déclarée telle quelle dans l'appli enregistrée).
app.get<{ Params: { provider: string } }>("/oauth/:provider/start", async (req, reply) => {
  if (!isProvider(req.params.provider) || !(await availableProviders()).some((p) => p.provider === req.params.provider)) {
    return reply.code(404).send({ error: "Connexion non disponible pour ce compte" });
  }
  // Microsoft : localhost (seule adresse de retour http acceptée) ; Google : 127.0.0.1 (recommandé pour les applis de bureau).
  const host = req.params.provider === "microsoft" ? "localhost" : "127.0.0.1";
  return reply.redirect(await loginUrl(req.params.provider, `http://${host}:${config.port}/oauth/${req.params.provider}/callback`));
});

app.get<{ Params: { provider: string }; Querystring: { code?: string; state?: string; error?: string; error_description?: string } }>(
  "/oauth/:provider/callback",
  async (req, reply) => {
    const back = (result: string) => reply.redirect(`http://127.0.0.1:${config.port}/?section=agenda&account=${encodeURIComponent(result)}`);
    if (req.query.error || !req.query.code || !req.query.state) {
      req.log.warn(`Connexion refusée : ${req.query.error} ${req.query.error_description ?? ""}`);
      return back(req.query.error === "access_denied" ? "refused" : "error");
    }
    try {
      await finishLogin(req.query.state, req.query.code);
      invalidateCalendar();
      return back("ok");
    } catch (e) {
      req.log.error(e);
      return back("error");
    }
  },
);

app.delete<{ Params: { provider: string } }>("/local/accounts/:provider", async (req, reply) => {
  if (!isProvider(req.params.provider)) return reply.code(404).send({ error: "Compte inconnu" });
  await disconnect(req.params.provider);
  invalidateCalendar();
  return { ok: true };
});

/** Agendas reliés à cet ordinateur, pour la tablette (Accueil > Mes agendas) : voir et délier sans aller sur l'ordinateur. */
app.get("/calendar/linked", async () => ({ calendars: await linkedCalendars() }));

app.delete<{ Params: { id: string } }>("/calendar/linked/:id", async (req, reply) => {
  if (!(await unlinkCalendar(req.params.id))) return reply.code(404).send({ error: "Cet agenda n'est plus relié" });
  return { ok: true };
});

/** Ouvre la page du compagnon sur l'ordinateur, depuis la tablette (« Relier mon agenda » de l'Accueil). */
app.post<{ Body: { section?: "agenda" } }>(
  "/local/open",
  { schema: { body: { type: "object", additionalProperties: false, properties: { section: { enum: ["agenda"] } } } } },
  async (req) => {
    await openTarget(`http://127.0.0.1:${config.port}/${req.body?.section ? `?section=${req.body.section}` : ""}`);
    return { ok: true };
  },
);

/** Ouvre la page du visualiseur dans le navigateur de l'ordinateur (à envoyer ensuite sur le vidéoprojecteur). */
app.post("/camera/open", async () => {
  await openTarget(`http://127.0.0.1:${config.port}/camera`);
  return { ok: true };
});

// --- Agenda

app.get<{ Querystring: { days?: string } }>("/calendar", async (req) => {
  const days = Math.min(Math.max(Number.parseInt(req.query.days ?? "1", 10) || 1, 1), 14);
  const { source, events, error } = await getEvents(...dayRange(days));
  // Le lien Teams reste sur l'ordinateur : la tablette demande à rejoindre par identifiant.
  return { source, error, events: events.map(({ joinUrl, ...e }) => ({ ...e, joinable: !!joinUrl })) };
});

// Agenda personnel (src/events.ts) : rendez-vous notés depuis la tablette.
const eventSchema = {
  type: "object",
  required: ["title", "startMs", "allDay"],
  additionalProperties: false,
  properties: {
    title: { type: "string", minLength: 1, maxLength: 200 },
    startMs: { type: "integer" },
    endMs: { type: "integer" },
    allDay: { type: "boolean" },
    remindBefore: { type: ["integer", "null"], minimum: 0, maximum: 1440 },
  },
};

app.post<{ Body: EventInput }>("/calendar/events", { schema: { body: eventSchema } }, async (req) => addEvent(req.body));

app.put<{ Params: { id: string }; Body: EventInput }>("/calendar/events/:id", { schema: { body: eventSchema } }, async (req, reply) =>
  (await updateEvent(req.params.id, req.body)) ?? reply.code(404).send({ error: "Événement introuvable" }),
);

app.delete<{ Params: { id: string } }>("/calendar/events/:id", async (req, reply) =>
  (await deleteEvent(req.params.id)) ? { ok: true } : reply.code(404).send({ error: "Événement introuvable" }),
);

app.post<{ Body: { id: string } }>(
  "/calendar/join",
  { schema: { body: { type: "object", required: ["id"], properties: { id: { type: "string", maxLength: 500 } } } } },
  async (req, reply) => {
    const joined = await joinMeeting(req.body.id);
    if (!joined) return reply.code(404).send({ error: "Cette réunion n'a pas de lien de visio" });
    // `kind` : la tablette n'ouvre la page Réunion du deck (raccourcis Teams) que pour Teams.
    return { ok: true, title: joined.event.title, kind: joined.kind ?? null };
  },
);

app.get("/calendar/autojoin", async () => ({ enabled: await autoJoinEnabled() }));

app.put<{ Body: { enabled: boolean } }>(
  "/calendar/autojoin",
  { schema: { body: { type: "object", required: ["enabled"], properties: { enabled: { type: "boolean" } } } } },
  async (req) => {
    await setAutoJoin(req.body.enabled);
    return { enabled: req.body.enabled };
  },
);

// --- Tâches (quotidiennes dans config/tasks.json, ponctuelles ajoutées par la tablette ou Claude)

app.get<{ Querystring: { refresh?: string } }>("/tasks", async (req) => ({ tasks: await listTasks(req.query.refresh === "1") }));

app.get("/tasks/recurring", async () => ({ tasks: await listRecurring() }));

/** `days` : récurrente ces jours-là (1 = lundi) ; `remindAt` : rappel parlé à cette heure ("HH:MM"). */
app.post<{ Body: { label: string; remindAt?: string; days?: number[] } }>(
  "/tasks",
  {
    schema: {
      body: {
        type: "object",
        required: ["label"],
        properties: {
          label: { type: "string", minLength: 1, maxLength: 200 },
          remindAt: { type: "string", pattern: "^([01][0-9]|2[0-3]):[0-5][0-9]$" },
          days: { type: "array", maxItems: 7, items: { type: "integer", minimum: 1, maximum: 7 } },
        },
      },
    },
  },
  async (req) => addTask(req.body.label, { remindAt: req.body.remindAt, days: req.body.days }),
);

app.post<{ Params: { id: string }; Body: { done: boolean } }>(
  "/tasks/:id/done",
  { schema: { body: { type: "object", required: ["done"], properties: { done: { type: "boolean" } } } } },
  async (req, reply) => ((await setTaskDone(req.params.id, req.body.done)) ? { ok: true } : reply.code(404).send({ error: "Tâche inconnue" })),
);

app.delete<{ Params: { id: string } }>("/tasks/:id", async (req, reply) =>
  (await deleteTask(req.params.id)) ? { ok: true } : reply.code(404).send({ error: "Tâche inconnue (ou définie dans le fichier de l'ordinateur)" }),
);

app.post<{ Params: { id: string } }>("/tasks/:id/open", async (req, reply) => {
  const url = await taskUrl(req.params.id);
  if (!url) return reply.code(404).send({ error: "Pas de lien pour cette tâche" });
  await runAction({ type: "url", url });
  return { ok: true };
});

// Rappel reçu dès que la tablette l'a affiché, même sans voix française : sinon il serait renvoyé chaque minute.
const remind = async (text: string) => (await sendToTablet({ type: "message", text, speak: true, seconds: 120 })).received === true;
startReminders(remind);
startEventReminders(remind);
if (canControl) startAutoJoin((text) => sendToTablet({ type: "message", text, seconds: 60 }));

// --- Canal ordinateur → tablette (utilisé par le serveur MCP : Claude pilote la tablette, voir src/mcp.ts)

app.post<{ Body: { state?: unknown } }>(
  "/tablet/poll",
  { logLevel: "warn", schema: { body: { type: "object", properties: { state: { type: "object" } } } } },
  async (req, reply) => {
    const closed = new AbortController();
    reply.raw.on("close", () => closed.abort());
    return { commands: await pollCommands(req.body.state ?? null, 25_000, closed.signal) };
  },
);

app.post<{ Body: { id: string; ok: boolean; error?: string; data?: Record<string, unknown>; state?: unknown } }>(
  "/tablet/result",
  {
    logLevel: "warn",
    bodyLimit: 10 * 1024 * 1024, // capture d'écran en base64
    schema: {
      body: {
        type: "object",
        required: ["id", "ok"],
        properties: {
          id: { type: "string" },
          ok: { type: "boolean" },
          error: { type: "string" },
          data: { type: "object" },
          state: { type: "object" },
        },
      },
    },
  },
  async (req) => {
    const { id, ok, error, data, state } = req.body;
    if (state) reportState(state);
    return { known: completeCommand(id, { ok, error, data }) };
  },
);

app.get("/tablet", async () => tabletStatus());

type CommandBody = {
  type: TabletCommand["type"];
  mode?: (typeof MODES)[number];
  text?: string;
  speak?: boolean;
  seconds?: number;
  path?: string;
  action?: (typeof FOCUS_ACTIONS)[number];
  focusMinutes?: number;
  breakMinutes?: number;
  kind?: (typeof NOISES)[number];
  volume?: number;
};

app.post<{ Body: CommandBody }>(
  "/tablet/command",
  {
    schema: {
      body: {
        type: "object",
        required: ["type"],
        additionalProperties: false,
        properties: {
          type: { enum: ["mode", "message", "document", "focus", "noise", "screenshot"] },
          mode: { enum: MODES },
          text: { type: "string", minLength: 1, maxLength: 1000 },
          speak: { type: "boolean" },
          seconds: { type: "integer", minimum: 2, maximum: 600 },
          path: { type: "string" },
          action: { enum: FOCUS_ACTIONS },
          focusMinutes: { type: "integer", minimum: 1, maximum: 180 },
          breakMinutes: { type: "integer", minimum: 1, maximum: 60 },
          kind: { enum: NOISES },
          volume: { type: "number", minimum: 0, maximum: 1 },
        },
      },
    },
  },
  async (req, reply) => {
    const b = req.body;
    const missing = (field: string) => reply.code(400).send({ error: `Champ '${field}' requis pour '${b.type}'` });
    let command: TabletCommand;
    switch (b.type) {
      case "mode":
        if (!b.mode) return missing("mode");
        command = { type: "mode", mode: b.mode };
        break;
      case "message":
        if (!b.text) return missing("text");
        command = { type: "message", text: b.text, speak: b.speak, seconds: b.seconds };
        break;
      case "document": {
        // Sans chemin : ferme le document affiché.
        const doc = b.path ? await docEntry(b.path) : null;
        if (doc === undefined) return reply.code(404).send({ error: "Fichier introuvable dans le dossier partagé" });
        command = { type: "document", doc };
        break;
      }
      case "focus":
        if (!b.action) return missing("action");
        command = { type: "focus", action: b.action, focusMinutes: b.focusMinutes, breakMinutes: b.breakMinutes };
        break;
      case "noise":
        if (!b.kind) return missing("kind");
        command = { type: "noise", kind: b.kind, volume: b.volume };
        break;
      case "screenshot":
        command = { type: "screenshot" };
        break;
    }
    const result = await sendToTablet(command, b.type === "screenshot" ? 30_000 : 15_000);
    return result.ok ? result : reply.code(502).send(result);
  },
);

await app.listen({ port: config.port, host: config.host });
startDiscovery((msg) => app.log.warn(msg));
const where = config.loopbackOnly ? "USB seulement (adb reverse)" : `WiFi : ${lanAddresses().map((a) => `${a}:${config.port}`).join(", ")}`;
app.log.info(`Compagnon prêt sur « ${identity().name} » (${osName}), ${where}, documents dans ${config.docsDir}`);
app.log.info(`Appairer une tablette : Réglages > Ordinateurs > Ajouter ; page d'aide : http://127.0.0.1:${config.port}/`);

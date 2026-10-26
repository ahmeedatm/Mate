import { createReadStream, existsSync } from "node:fs";
import fs from "node:fs/promises";
import path from "node:path";
import { createHash, timingSafeEqual } from "node:crypto";
import Fastify from "fastify";
import multipart from "@fastify/multipart";
import { config } from "./config.ts";
import {
  addDeckApp, addDeckLink, deckLinks, findTile, homeFeatures, LINK_SUGGESTIONS, loadDeck, removeDeckApp, removeDeckLink, resetDeckApps,
  appIconFile, runAction, tileIcon,
} from "./deck.ts";
import {
  accessibility, appAudio, browserPage, browserTabs, isAppInCall, isAppRunning, listButtons, openAccessibility, canControl, focusWindow, getAudio, getDnd, launchApp, listApps, mediaControl, mediaSessions, mediaThumb, listDisplays, listOpenWindows, listShortcuts, listWindows, controlOpenWindow, openTarget, osName, placeWindow, setAudio, typeText, voiceState,
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
import { answerCall, declineCall, hangUp, startCallWatch } from "./calls.ts";
import { focusDnd } from "./dnd.ts";
import tabletRoutes from "./routes/tablet.ts";
import callRoutes from "./routes/calls.ts";
import taskRoutes from "./routes/tasks.ts";
import calendarRoutes from "./routes/calendar.ts";
import updateRoutes from "./routes/updates.ts";
import docRoutes from "./routes/docs.ts";
import browserRoutes from "./routes/browser.ts";
import cameraRoutes from "./routes/camera.ts";
import localRoutes from "./routes/local.ts";
import { setLogger } from "./log.ts";
import { addTask, deleteTask, listRecurring, listTasks, setTaskDone, startReminders, taskUrl } from "./tasks.ts";
import { receiveTabs, type ExtensionReport } from "./browser-extension.ts";
import { addViewer, CAMERA_STATUSES, cameraStatus, pushFrame, setCameraState, VIEWER_PAGE, type CameraStatus } from "./camera.ts";
import { completeCommand, FOCUS_ACTIONS, MODES, NOISES, pollCommands, reportState, sendToTablet, tabletStatus, type TabletCommand } from "./tablet.ts";

// Ce serveur pilote l'ordinateur : hors loopback (USB + adb reverse), un jeton est obligatoire.
if (!config.loopbackOnly && !config.token) {
  console.error(`HOST=${config.host} expose le serveur sur le réseau : définis COMPANION_TOKEN dans .env.`);
  process.exit(1);
}

const app = Fastify({ logger: { level: "info" } });
setLogger({ warn: (msg) => app.log.warn(msg) });
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
    // Extension du navigateur (backend/extension) : elle n'a pas le jeton ; origine vérifiée par la route.
    (method === "POST" && url === "/browser/extension") ||
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

// Pilotage de l'ordinateur (deck d'applis et de raccourcis, son, micro) : macOS et Windows seulement.
app.addHook("preHandler", async (req, reply) => {
  if (!canControl && (req.url.startsWith("/deck") || req.url.startsWith("/system"))) {
    return reply.code(501).send({ error: "Pilotage disponible sur macOS et Windows seulement" });
  }
});

// Images du visualiseur (POST /camera/frame).
app.addContentTypeParser("image/jpeg", { parseAs: "buffer", bodyLimit: 2 * 1024 * 1024 }, (_req, body, done) => done(null, body));

// --- Routes, par domaine (src/routes/)

app.register(tabletRoutes);
app.register(callRoutes);
app.register(taskRoutes);
app.register(calendarRoutes);
app.register(updateRoutes);
app.register(docRoutes);
app.register(browserRoutes);
app.register(cameraRoutes);
app.register(localRoutes);
// @@PLUGINS

/** `auth` : le jeton envoyé est le bon (la tablette sait s'il faut refaire l'appairage). */
app.get("/health", async (req) => ({
  ok: true, ...identity(), auth: authorized(req.headers.authorization),
  // Mac : false = fenêtres et raccourcis bloqués tant que l'Accessibilité n'est pas accordée (la tablette prévient).
  accessibility: await accessibility().catch(() => null),
}));

/** Ouvre sur le Mac le réglage Accessibilité (depuis la tablette, ou la page locale). */
app.post("/system/accessibility", async () => {
  await openAccessibility();
  return { ok: true };
});

/** Dictée de la tablette (« écris : … ») : texte collé dans l'appli au premier plan, comme l'étape `text` du deck. */
app.post<{ Body: { text: string } }>(
  "/system/type",
  { schema: { body: { type: "object", required: ["text"], properties: { text: { type: "string", minLength: 1, maxLength: 2000 } } } } },
  async (req) => {
    await typeText(req.body.text);
    return { ok: true };
  },
);

// --- Appairage (voir src/pairing.ts) et page d'accueil locale

app.post<{ Body: { tablet?: string } }>(
  "/pair/request",
  { schema: { body: { type: "object", properties: { tablet: { type: "string", maxLength: 100 } } } } },
  async (req) => requestPairing(req.body?.tablet || "tablette", req.ip),
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
          action: { enum: ["focus", "place", "minimize", "zoom", "fullscreen", "close"] },
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

/** Commande vocale « ouvre X » : appli installée qui n'est pas dans le deck. */
app.post<{ Body: { name: string } }>(
  "/apps/launch",
  { schema: { body: { type: "object", required: ["name"], properties: { name: { type: "string", minLength: 1, maxLength: 200 } } } } },
  async (req, reply) => {
    if (!(await listApps()).includes(req.body.name)) return reply.code(404).send({ error: "Appli introuvable sur l'ordinateur" });
    await launchApp(req.body.name);
    return { ok: true };
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
  // `open` : l'appli tourne (onglet affiché) ; `apps` : elle utilise le micro, donc en appel.
  const [open, live] = await Promise.all([
    Promise.all(apps.map((a) => isAppRunning(a).catch(() => false))),
    Promise.all(apps.map((a) => isAppInCall(a).catch(() => false))),
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

// Ne pas déranger de l'ordinateur pendant le Focus de la tablette (voir src/dnd.ts).
app.get("/system/dnd", async () => ({ on: await getDnd() }));

app.post<{ Body: { on: boolean; minutes?: number } }>(
  "/system/dnd",
  {
    schema: {
      body: {
        type: "object",
        additionalProperties: false,
        required: ["on"],
        properties: { on: { type: "boolean" }, minutes: { type: "integer", minimum: 1, maximum: 240 } },
      },
    },
  },
  async (req) => focusDnd(req.body.on, req.body.minutes),
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

// Rappel reçu dès que la tablette l'a affiché, même sans voix française : sinon il serait renvoyé chaque minute.
const remind = async (text: string) => (await sendToTablet({ type: "message", text, speak: true, seconds: 120 })).received === true;
startReminders(remind);
startEventReminders(remind);
// Appel entrant : prévenir la tablette sans attendre sa réponse (la sonnerie ne doit pas bloquer la surveillance).
void startCallWatch((event) => void sendToTablet({ type: "call", ...event }, 30_000));
if (canControl) startAutoJoin((text) => sendToTablet({ type: "message", text, seconds: 60 }));

await app.listen({ port: config.port, host: config.host });
startDiscovery((msg) => app.log.warn(msg));
const where = config.loopbackOnly ? "USB seulement (adb reverse)" : `WiFi : ${lanAddresses().map((a) => `${a}:${config.port}`).join(", ")}`;
app.log.info(`Compagnon prêt sur « ${identity().name} » (${osName}), ${where}, documents dans ${config.docsDir}`);
app.log.info(`Appairer une tablette : Réglages > Ordinateurs > Ajouter ; page d'aide : http://127.0.0.1:${config.port}/`);

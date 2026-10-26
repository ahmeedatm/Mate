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
import windowRoutes from "./routes/windows.ts";
import deckRoutes from "./routes/deck.ts";
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
app.register(windowRoutes);
app.register(deckRoutes);
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

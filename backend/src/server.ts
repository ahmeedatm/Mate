import { timingSafeEqual } from "node:crypto";
import http from "node:http";
import https from "node:https";
import Fastify from "fastify";
import multipart from "@fastify/multipart";
import { config } from "./config.ts";
import { startCallWatch } from "./calls.ts";
import { clipboardHistory, watchClipboard } from "./clipboard-history.ts";
import { startEventReminders } from "./events.ts";
import { GitHubError } from "./github.ts";
import { GitLabError } from "./gitlab.ts";
import { setLogger } from "./log.ts";
import { startAutoJoin } from "./meetings.ts";
import { notificationHistory, setNotificationsRead, watchNotifications } from "./notifications.ts";
import { canControl, clipboardStamp, frontApp, getClipboard, isMac, osName, readNotifications, screenLocked } from "./os.ts";
import { identity, lanAddresses, PairingError, startDiscovery } from "./pairing.ts";
import { sendToTablet, tabletStatus } from "./tablet.ts";
import { startForegroundWatch } from "./foreground-watch.ts";
import { startMeetingWatch } from "./meeting-watch.ts";
import { isScreenLocked, startScreenLockWatch } from "./screen-lock.ts";
import { callingDeckApps } from "./deck.ts";
import { startMenuBar } from "./mac/menubar.ts";
import { loadTls } from "./tls.ts";
import { startReminders } from "./tasks.ts";
import agentRoutes from "./routes/agents.ts";
import awayRoutes from "./routes/away.ts";
import browserRoutes from "./routes/browser.ts";
import calendarRoutes from "./routes/calendar.ts";
import callRoutes from "./routes/calls.ts";
import cameraRoutes from "./routes/camera.ts";
import deckRoutes from "./routes/deck.ts";
import docRoutes from "./routes/docs.ts";
import githubRoutes from "./routes/github.ts";
import gitlabRoutes from "./routes/gitlab.ts";
import jiraRoutes from "./routes/jira.ts";
import localRoutes from "./routes/local.ts";
import localConfigRoutes from "./routes/local-config.ts";
import localProfileRoutes from "./routes/local-profiles.ts";
import mcpRoutes from "./routes/mcp.ts";
import notificationRoutes from "./routes/notifications.ts";
import pairingRoutes from "./routes/pairing.ts";
import pluginRoutes from "./routes/plugins.ts";
import pointerRoutes from "./routes/pointer.ts";
import presentRoutes from "./routes/present.ts";
import portableRoutes from "./routes/portable.ts";
import systemRoutes from "./routes/system.ts";
import tabletRoutes from "./routes/tablet.ts";
import taskRoutes from "./routes/tasks.ts";
import updateRoutes from "./routes/updates.ts";
import windowRoutes from "./routes/windows.ts";

// Ce serveur pilote l'ordinateur : hors loopback (USB + adb reverse), un jeton est obligatoire.
if (!config.loopbackOnly && !config.token) {
  console.error(`HOST=${config.host} expose le serveur sur le réseau : définis COMPANION_TOKEN dans .env.`);
  process.exit(1);
}

/**
 * WiFi : HTTPS seulement (certificat épinglé par la tablette à l'appairage, src/tls.ts) ; le HTTP en clair n'écoute plus
 * que sur 127.0.0.1 (page locale, extension du navigateur, hooks, MCP, tablette en USB par adb reverse) : rien ne passe
 * en clair sur le réseau. En local seulement (HOST=127.0.0.1) : HTTP comme avant.
 */
const tls = config.loopbackOnly ? null : loadTls(config.dataDir);
let localHttp: http.Server | undefined;
const app = Fastify({
  logger: { level: "info" },
  ...(tls && {
    serverFactory: (handler: http.RequestListener) => {
      localHttp = http.createServer(handler);
      return https.createServer({ key: tls.key, cert: tls.cert }, handler);
    },
  }),
});
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
  ((method === "GET" &&
    /^\/(camera(\/stream|\/status)?|local\/(status|licences|confidentialite|panel|update)|web\/figtree-[345]00\.ttf|oauth\/(microsoft|google|gitlab)\/(start|callback)|oauth\/github\/start)?(\?|$)/.test(
      url,
    )) ||
    (method === "PUT" && (url === "/local/calendar" || url === "/local/tablet/settings")) ||
    // Panneau de configuration (routes/local-profiles.ts, routes/local-config.ts) : écritures en JSON seulement (PUT,
    // DELETE et POST à corps objet), pas envoyables par un autre site sans pré-vérification CORS.
    (method === "GET" && /^\/local\/(tablet\/layout|config\/[a-z0-9/-]+)(\?|$)/.test(url)) ||
    (method === "PUT" && url === "/local/tablet/layout") ||
    (/^(POST|PUT|DELETE)$/.test(method) && /^\/local\/config\/[a-z0-9/-]+$/.test(url)) ||
    (method === "POST" && (url === "/system/accessibility" || url === "/local/pair" || url === "/local/tablet/update")) ||
    // Panneau de l'appli portable (routes/portable.ts) : corps JSON exigé, pas envoyable par un autre site.
    (method === "POST" && /^\/local\/(autostart|firewall|capteurs|forget|quit|update)$/.test(url)) ||
    // Extension du navigateur (backend/extension) : elle n'a pas le jeton ; origine vérifiée par la route.
    (method === "POST" && (url === "/browser/extension" || url === "/browser/extension/commands")) ||
    // Porte MCP des IA de bureau (routes/mcp.ts) : corps JSON exigé par le SDK, pas envoyable par un autre site.
    (/^(GET|POST|DELETE)$/.test(method) && url === "/mcp") ||
    // Hooks de Claude Code : pas de jeton d'appairage, un secret propre vérifié par la route (src/agents-sources.ts).
    (method === "POST" && (url === "/agents/hook" || url === "/agents/statusline")) ||
    (method === "DELETE" && /^\/local\/accounts\/(microsoft|google|gitlab|github)$/.test(url)));
/** Sans jeton : identité (la tablette reconnaît l'ordinateur) et appairage par code. */
const PUBLIC = /^\/(health|pair\/request|pair\/reveal|pair\/confirm)(\?|$)/;
/**
 * Routes sans jeton reçues en local : nom local, ou 10.0.2.2 (émulateur). Un site qui ferait pointer son domaine vers
 * 127.0.0.1 (DNS rebinding) ne peut pas s'appairer en faisant taper son code.
 */
const PUBLIC_LOCAL_HOST = /^(127\.0\.0\.1|localhost|\[::1\]|10\.0\.2\.2)(:\d+)?$/i;
const authorized = (header: string | undefined) => {
  if (!expectedAuth) return true;
  const given = Buffer.from(header ?? "");
  return given.length === expectedAuth.length && timingSafeEqual(given, expectedAuth);
};
/** Ordinateur verrouillé : seul le canal /tablet reste ouvert (la tablette y apprend le déverrouillage). */
const WHILE_LOCKED = /^\/tablet\/(poll|result)(\?|$)/;
app.addHook("onRequest", async (req, reply) => {
  if (PUBLIC.test(req.url)) {
    if (LOOPBACK.has(req.ip) && !PUBLIC_LOCAL_HOST.test(req.headers.host ?? "")) return reply.code(403).send({ error: "Hôte refusé" });
    return;
  }
  if (isLocalPage(req.method, req.url, req.ip, req.headers.host)) return;
  if (!authorized(req.headers.authorization)) return reply.code(401).send({ error: "Jeton invalide : appairer à nouveau la tablette" });
  if (isScreenLocked() && !WHILE_LOCKED.test(req.url)) {
    return reply.code(423).send({ error: "Ordinateur verrouillé : déverrouille-le pour continuer", locked: true });
  }
});

/**
 * Erreurs montrées sur la tablette : nos messages (en français) passent tels quels ; le texte technique
 * (sortie d'osascript, erreur système de Node, requête mal formée) reste dans le journal de l'ordinateur.
 */
app.setErrorHandler((err: Error & { statusCode?: number; code?: unknown; validation?: unknown; technical?: boolean }, req, reply) => {
  if (err instanceof PairingError || err instanceof GitLabError || err instanceof GitHubError)
    return reply.code(err.statusCode).send({ error: err.message });
  if (err.validation) return reply.code(400).send({ error: "Demande invalide : mets à jour l'appli de la tablette" });
  const status = err.statusCode && err.statusCode >= 400 ? err.statusCode : 500;
  if (status >= 500 && (err.technical || (typeof err.code === "string" && /^E[A-Z]/.test(err.code)))) {
    req.log.error(err);
    return reply.code(status).send({ error: "L'ordinateur n'a pas pu le faire (détail dans son journal)" });
  }
  reply.send(err);
});

// Pilotage de l'ordinateur (deck d'applis et de raccourcis, son, micro) : macOS et Windows seulement.
// Ailleurs, le deck se lit et ses touches perso se modifient ; toucher une touche répond 501 (os.ts : unsupported).
const DECK_ANYWHERE = /^\/deck(\/keys\/\d+|\/run)?(\?|$)/;
app.addHook("preHandler", async (req, reply) => {
  if (
    !canControl &&
    ((req.url.startsWith("/deck") && !DECK_ANYWHERE.test(req.url)) ||
      req.url.startsWith("/system") ||
      req.url.startsWith("/pointer") ||
      req.url.startsWith("/present") ||
      req.url.startsWith("/gitlab") ||
      req.url.startsWith("/github") ||
      req.url.startsWith("/notifications"))
  ) {
    return reply.code(501).send({ error: "Pilotage disponible sur macOS et Windows seulement" });
  }
});

// Images du visualiseur (POST /camera/frame).
app.addContentTypeParser("image/jpeg", { parseAs: "buffer", bodyLimit: 2 * 1024 * 1024 }, (_req, body, done) => done(null, body));
// Dictée d'un prompt pour une session Claude (POST /agents/transcribe), 60 s au plus.
app.addContentTypeParser("audio/wav", { parseAs: "buffer", bodyLimit: 2 * 1024 * 1024 }, (_req, body, done) => done(null, body));
// Archive du backend : essai d'une MR (POST /companion/backend/essai) ou release (POST /companion/backend/update).
app.addContentTypeParser("application/gzip", { parseAs: "buffer", bodyLimit: 100 * 1024 * 1024 }, (_req, body, done) => done(null, body));

// --- Routes, une par domaine dans src/routes/ (les hooks ci-dessus valent pour toutes)

app.register(pairingRoutes, { authorized, fingerprint: tls?.fingerprint ?? null });
app.register(localRoutes);
app.register(localProfileRoutes);
app.register(localConfigRoutes);
app.register(mcpRoutes);
app.register(portableRoutes);
app.register(deckRoutes);
app.register(pluginRoutes);
app.register(windowRoutes);
app.register(awayRoutes);
app.register(systemRoutes);
app.register(pointerRoutes);
app.register(presentRoutes);
app.register(notificationRoutes);
app.register(docRoutes);
app.register(updateRoutes);
app.register(browserRoutes);
app.register(cameraRoutes);
app.register(calendarRoutes);
app.register(taskRoutes);
app.register(jiraRoutes);
app.register(callRoutes);
app.register(agentRoutes);
app.register(gitlabRoutes);
app.register(githubRoutes);
app.register(tabletRoutes);

// --- Tâches de fond

// Rappel reçu dès que la tablette l'a affiché, même sans voix française : sinon il serait renvoyé chaque minute.
const remind = async (text: string) => (await sendToTablet({ type: "message", text, speak: true, seconds: 120 })).received === true;
startReminders(remind);
startEventReminders(remind);
// Appel entrant : prévenir la tablette sans attendre sa réponse (la sonnerie ne doit pas bloquer la surveillance).
void startCallWatch((event) => void sendToTablet({ type: "call", ...event }, 30_000));
// Réunion en cours : la tablette passe sur son profil Réunion (et en revient), seulement tant qu'elle est connectée.
if (canControl) {
  startMeetingWatch((event) => void sendToTablet({ type: "meeting", ...event }), {
    connected: () => tabletStatus().connected,
    liveApps: callingDeckApps,
  });
  // Appli au premier plan : la tablette passe sur son écran de jeu quand c'en est une de sa liste (et en revient).
  startForegroundWatch((event) => void sendToTablet({ type: "foreground", ...event }), {
    connected: () => tabletStatus().connected,
    front: frontApp,
  });
}
// Ordinateur verrouillé : la tablette se bloque, l'API se ferme (hook ci-dessus). Relu même sans tablette.
startScreenLockWatch(async (locked) => (await sendToTablet({ type: "lock", locked })).received === true, {
  read: screenLocked,
  connected: () => tabletStatus().connected,
  tabletLocked: () => {
    const shown = (tabletStatus().state as { locked?: unknown } | null)?.locked;
    return typeof shown === "boolean" ? shown : undefined;
  },
  intervalMs: Number(process.env.SCREEN_LOCK_POLL_MS) || undefined,
});
// Notifications de l'ordinateur : base du système relue toutes les 2 s, chaque nouvelle envoyée à la tablette sans attendre.
if (canControl) {
  // Les supprimées (empreintes seulement) tiennent après un redémarrage : sinon le 1er passage les remonterait du système.
  notificationHistory.setStore(config.dataDir);
  watchNotifications(
    readNotifications,
    notificationHistory,
    ({ id, app: name, title, body, at }) => void sendToTablet({ type: "notification", id, app: name, title, body, at }),
    Number(process.env.NOTIFICATIONS_POLL_MS) || 2000,
    (e) => app.log.warn(`Notifications de l'ordinateur illisibles : ${e.message}`),
    (ok, e) => setNotificationsRead(ok, e),
  );
}
// Historique du presse-papiers : repère relu chaque seconde (2 s sur Mac, un osascript à chaque fois).
if (canControl) {
  const pollMs = Number(process.env.CLIPBOARD_POLL_MS) || (isMac ? 2000 : 1000);
  watchClipboard({ stamp: clipboardStamp, read: getClipboard }, clipboardHistory, pollMs);
}
if (canControl) startAutoJoin((text) => sendToTablet({ type: "message", text, seconds: 60 }));

if (localHttp) {
  const server = localHttp;
  app.addHook("onClose", (_app, done) => void server.close(() => done()));
  await new Promise<void>((resolve, reject) => server.once("error", reject).listen(config.port, "127.0.0.1", resolve));
  await app.listen({ port: config.tlsPort, host: config.host });
} else {
  await app.listen({ port: config.port, host: config.host });
}
startDiscovery((msg) => app.log.warn(msg));
if (isMac) startMenuBar((msg) => app.log.warn(msg));
const where = config.loopbackOnly
  ? "USB seulement (adb reverse)"
  : `WiFi en HTTPS : ${lanAddresses()
      .map((a) => `${a}:${config.tlsPort}`)
      .join(", ")}, empreinte ${tls!.fingerprint.slice(0, 16)}…`;
app.log.info(`Compagnon prêt sur « ${identity().name} » (${osName}), ${where}, documents dans ${config.docsDir}`);
app.log.info(`Appairer une tablette : Réglages > Ordinateurs > Ajouter ; page d'aide : http://127.0.0.1:${config.port}/`);

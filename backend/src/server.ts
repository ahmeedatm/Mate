import { timingSafeEqual } from "node:crypto";
import http from "node:http";
import https from "node:https";
import Fastify from "fastify";
import multipart from "@fastify/multipart";
import { config } from "./config.ts";
import { startCallWatch } from "./calls.ts";
import { clipboardHistory, watchClipboard } from "./clipboard-history.ts";
import { startEventReminders } from "./events.ts";
import { setLogger } from "./log.ts";
import { startAutoJoin } from "./meetings.ts";
import { notificationHistory, watchNotifications } from "./notifications.ts";
import { canControl, clipboardStamp, getClipboard, isMac, osName, readNotifications } from "./os.ts";
import { identity, lanAddresses, PairingError, startDiscovery } from "./pairing.ts";
import { sendToTablet } from "./tablet.ts";
import { loadTls } from "./tls.ts";
import { startReminders } from "./tasks.ts";
import agentRoutes from "./routes/agents.ts";
import browserRoutes from "./routes/browser.ts";
import calendarRoutes from "./routes/calendar.ts";
import callRoutes from "./routes/calls.ts";
import cameraRoutes from "./routes/camera.ts";
import deckRoutes from "./routes/deck.ts";
import docRoutes from "./routes/docs.ts";
import jiraRoutes from "./routes/jira.ts";
import localRoutes from "./routes/local.ts";
import mailRoutes from "./routes/mail.ts";
import notificationRoutes from "./routes/notifications.ts";
import pairingRoutes from "./routes/pairing.ts";
import pointerRoutes from "./routes/pointer.ts";
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
    /^\/(camera(\/stream|\/status)?|local\/(status|licences|confidentialite)|oauth\/(microsoft|google)\/(start|callback))?(\?|$)/.test(
      url,
    )) ||
    (method === "PUT" && url === "/local/calendar") ||
    (method === "POST" && (url === "/system/accessibility" || url === "/local/pair")) ||
    // Extension du navigateur (backend/extension) : elle n'a pas le jeton ; origine vérifiée par la route.
    (method === "POST" && (url === "/browser/extension" || url === "/browser/extension/commands")) ||
    // Hooks de Claude Code : pas de jeton d'appairage, un secret propre vérifié par la route (src/agents-sources.ts).
    (method === "POST" && (url === "/agents/hook" || url === "/agents/statusline")) ||
    (method === "DELETE" && /^\/local\/accounts\/(microsoft|google)$/.test(url)));
/** Sans jeton : identité (la tablette reconnaît l'ordinateur) et appairage par code. */
const PUBLIC = /^\/(health|pair\/request|pair\/reveal|pair\/confirm)(\?|$)/;
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
// Ailleurs, le deck se lit et ses touches perso se modifient ; toucher une touche répond 501 (os.ts : unsupported).
const DECK_ANYWHERE = /^\/deck(\/keys\/\d+|\/run)?(\?|$)/;
app.addHook("preHandler", async (req, reply) => {
  if (
    !canControl &&
    ((req.url.startsWith("/deck") && !DECK_ANYWHERE.test(req.url)) ||
      req.url.startsWith("/system") ||
      req.url.startsWith("/pointer") ||
      req.url.startsWith("/notifications"))
  ) {
    return reply.code(501).send({ error: "Pilotage disponible sur macOS et Windows seulement" });
  }
});

// Images du visualiseur (POST /camera/frame).
app.addContentTypeParser("image/jpeg", { parseAs: "buffer", bodyLimit: 2 * 1024 * 1024 }, (_req, body, done) => done(null, body));
// Archive du backend : essai d'une MR (POST /companion/backend/essai) ou release (POST /companion/backend/update).
app.addContentTypeParser("application/gzip", { parseAs: "buffer", bodyLimit: 100 * 1024 * 1024 }, (_req, body, done) => done(null, body));

// --- Routes, une par domaine dans src/routes/ (les hooks ci-dessus valent pour toutes)

app.register(pairingRoutes, { authorized, fingerprint: tls?.fingerprint ?? null });
app.register(localRoutes);
app.register(deckRoutes);
app.register(windowRoutes);
app.register(systemRoutes);
app.register(pointerRoutes);
app.register(notificationRoutes);
app.register(docRoutes);
app.register(updateRoutes);
app.register(browserRoutes);
app.register(cameraRoutes);
app.register(calendarRoutes);
app.register(mailRoutes);
app.register(taskRoutes);
app.register(jiraRoutes);
app.register(callRoutes);
app.register(agentRoutes);
app.register(tabletRoutes);

// --- Tâches de fond

// Rappel reçu dès que la tablette l'a affiché, même sans voix française : sinon il serait renvoyé chaque minute.
const remind = async (text: string) => (await sendToTablet({ type: "message", text, speak: true, seconds: 120 })).received === true;
startReminders(remind);
startEventReminders(remind);
// Appel entrant : prévenir la tablette sans attendre sa réponse (la sonnerie ne doit pas bloquer la surveillance).
void startCallWatch((event) => void sendToTablet({ type: "call", ...event }, 30_000));
// Notifications de l'ordinateur : base du système relue toutes les 2 s, chaque nouvelle envoyée à la tablette sans attendre.
if (canControl) {
  watchNotifications(
    readNotifications,
    notificationHistory,
    ({ id, app: name, title, body, at }) => void sendToTablet({ type: "notification", id, app: name, title, body, at }),
    Number(process.env.NOTIFICATIONS_POLL_MS) || 2000,
    (e) => app.log.warn(`Notifications de l'ordinateur illisibles : ${e.message}`),
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
const where = config.loopbackOnly
  ? "USB seulement (adb reverse)"
  : `WiFi en HTTPS : ${lanAddresses()
      .map((a) => `${a}:${config.tlsPort}`)
      .join(", ")}, empreinte ${tls!.fingerprint.slice(0, 16)}…`;
app.log.info(`Compagnon prêt sur « ${identity().name} » (${osName}), ${where}, documents dans ${config.docsDir}`);
app.log.info(`Appairer une tablette : Réglages > Ordinateurs > Ajouter ; page d'aide : http://127.0.0.1:${config.port}/`);

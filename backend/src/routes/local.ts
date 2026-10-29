import { readFile } from "node:fs/promises";
import path from "node:path";
import type { FastifyInstance } from "fastify";
import { availableProviders, connectedAccounts, disconnect, finishLogin, isProvider, loginUrl } from "../accounts.ts";
import { calendarSources, dayRange, getEvents, invalidateCalendar, saveCalendarUrls, savedCalendarUrls } from "../calendar.ts";
import { backendRoot, config } from "../config.ts";
import { LOCAL_PAGE } from "../local-page.ts";
import { WEB_FILES } from "../web-style.ts";
import { accessibility, openTarget } from "../os.ts";
import { identity, lanAddresses, pendingCode } from "../pairing.ts";
import { sendToTablet, tabletStatus, THEMES, type TabletSettings } from "../tablet.ts";

const SETTINGS = ["theme", "brightness", "volume", "nightDim", "focusDnd"] satisfies (keyof TabletSettings)[];

/** Page d'accueil locale (ouverte dans le navigateur de l'ordinateur) et connexion des comptes d'agenda. */
export default async function localRoutes(app: FastifyInstance) {
  app.get("/", async (_req, reply) => reply.type("text/html; charset=utf-8").send(LOCAL_PAGE));

  /** Polices des pages web (web-style.ts) : liste fermée, jamais un chemin venu de la requête. */
  app.get<{ Params: { file: string } }>("/web/:file", async (req, reply) => {
    if (!WEB_FILES.includes(req.params.file)) return reply.code(404).send({ error: "Fichier inconnu" });
    const data = await readFile(path.join(backendRoot, "assets", "web", req.params.file));
    return reply.type("font/ttf").header("cache-control", "max-age=86400").send(data);
  });

  app.get("/local/status", async () => ({
    ...identity(),
    accessibility: await accessibility().catch(() => null),
    addresses: config.loopbackOnly ? ["127.0.0.1"] : lanAddresses(),
    tablet: tabletStatus().connected,
    tabletInfo: tabletInfo(),
    pairing: pendingCode(),
    // Hôtes seulement : un lien ICS publié donne accès à l'agenda, il ne s'affiche pas en entier.
    calendar: {
      env: config.icsUrls.length,
      saved: (await savedCalendarUrls()).map((u) => (u === "demo" ? "agenda d'exemple" : (URL.parse(u)?.host ?? "lien invalide"))),
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
          properties: {
            urls: { type: "array", maxItems: 5, items: { type: "string", pattern: "^(https?|webcal)://|^demo$", maxLength: 2000 } },
          },
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

  /** Panneau Tablette : réglages envoyés à la tablette par son canal (ordre `settings`, appliqué par RemoteChannel). */
  app.put<{ Body: TabletSettings }>(
    "/local/tablet/settings",
    {
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          // Au moins un réglage (minProperties ne voit pas les champs inconnus retirés par Fastify).
          anyOf: SETTINGS.map((key) => ({ required: [key] })),
          properties: {
            theme: { enum: THEMES },
            brightness: { type: "number", minimum: 0.05, maximum: 1 },
            volume: { type: "number", minimum: 0, maximum: 1 },
            nightDim: { type: "boolean" },
            focusDnd: { type: "boolean" },
          },
        },
      },
    },
    async (req, reply) => {
      if (!tabletStatus().connected) return reply.code(409).send({ error: "Tablette non connectée" });
      const result = await sendToTablet({ type: "settings", ...req.body });
      return result.ok ? { ok: true, tablet: tabletInfo() } : reply.code(502).send({ error: result.error });
    },
  );

  /**
   * Mise à jour de l'appli de la tablette (ordre `update`, RemoteUpdate côté tablette). Chercher interroge l'ordinateur et
   * le serveur central depuis la tablette ; installer rend la main dès le téléchargement lancé.
   */
  app.post<{ Body: { action: "check" | "install" } }>(
    "/local/tablet/update",
    {
      schema: {
        body: { type: "object", required: ["action"], additionalProperties: false, properties: { action: { enum: ["check", "install"] } } },
      },
    },
    async (req, reply) => {
      if (!tabletStatus().connected) return reply.code(409).send({ error: "Tablette non connectée" });
      const result = await sendToTablet({ type: "update", action: req.body.action }, 45_000);
      return result.ok ? { ok: true, tablet: tabletInfo() } : reply.code(502).send({ error: result.error });
    },
  );

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
      const back = (result: string) =>
        reply.redirect(`http://127.0.0.1:${config.port}/?section=agenda&account=${encodeURIComponent(result)}`);
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

  /** Ouvre la page du compagnon sur l'ordinateur, depuis la tablette (« Relier mon agenda » de l'Accueil). */
  app.post<{ Body: { section?: "agenda" } }>(
    "/local/open",
    { schema: { body: { type: "object", additionalProperties: false, properties: { section: { enum: ["agenda"] } } } } },
    async (req) => {
      await openTarget(`http://127.0.0.1:${config.port}/${req.body?.section ? `?section=${req.body.section}` : ""}`);
      return { ok: true };
    },
  );
}

/**
 * Ce que la page locale montre de la tablette connectée, tiré de l'état qu'elle envoie à chaque poll.
 * `settings` null : appli trop ancienne pour être réglée depuis l'ordinateur.
 */
function tabletInfo() {
  const { connected, state } = tabletStatus();
  if (!connected || !state || typeof state !== "object") return null;
  const s = state as Record<string, unknown>;
  return {
    app: s.app ?? null,
    model: s.model ?? null,
    screen: s.screen ?? null,
    battery: s.battery ?? null,
    settings: s.settings ?? null,
    // Absent : appli trop ancienne pour être mise à jour d'ici.
    update: s.update ?? null,
  };
}

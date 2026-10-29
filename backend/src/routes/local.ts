import fs from "node:fs";
import path from "node:path";
import type { FastifyInstance } from "fastify";
import { availableProviders, connectedAccounts, disconnect, finishLogin, isProvider, loginUrl } from "../accounts.ts";
import { calendarSources, dayRange, getEvents, invalidateCalendar, saveCalendarUrls, savedCalendarUrls } from "../calendar.ts";
import { backendRoot, config } from "../config.ts";
import { LOCAL_PAGE } from "../local-page.ts";
import { accessibility, openTarget } from "../os.ts";
import { identity, lanAddresses, pendingCode } from "../pairing.ts";
import { tabletStatus } from "../tablet.ts";

const LICENCES_FILE = path.join(backendRoot, "LICENCES-TIERS.txt");
/** Politique de confidentialité (même texte dans l'appli, assets/confidentialite.txt ; docs/confidentialite.md). */
const PRIVACY_FILE = path.join(backendRoot, "config", "confidentialite.txt");

/** Page d'accueil locale (ouverte dans le navigateur de l'ordinateur) et connexion des comptes d'agenda. */
export default async function localRoutes(app: FastifyInstance) {
  app.get("/", async (_req, reply) => reply.type("text/html; charset=utf-8").send(LOCAL_PAGE));

  app.get("/local/confidentialite", async (_req, reply) => reply.type("text/plain; charset=utf-8").send(fs.createReadStream(PRIVACY_FILE)));

  /** Mentions de licences des composants tiers : écrites dans le compagnon installé par scripts/package.ts. */
  app.get("/local/licences", async (_req, reply) => {
    if (!fs.existsSync(LICENCES_FILE)) return reply.code(404).send({ error: "Licences jointes au compagnon installé seulement" });
    return reply.type("text/plain; charset=utf-8").send(fs.createReadStream(LICENCES_FILE));
  });

  app.get("/local/status", async () => ({
    ...identity(),
    accessibility: await accessibility().catch(() => null),
    addresses: config.loopbackOnly ? ["127.0.0.1"] : lanAddresses(),
    tablet: tabletStatus().connected,
    pairing: pendingCode(),
    licences: fs.existsSync(LICENCES_FILE),
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

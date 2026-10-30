import fs from "node:fs";
import { readFile } from "node:fs/promises";
import path from "node:path";
import type { FastifyInstance } from "fastify";
import { availableProviders, connectedAccounts, disconnect, finishLogin, isProvider, loginUrl } from "../accounts.ts";
import { calendarSources, dayRange, getEvents, invalidateCalendar, saveCalendarUrls, savedCalendarUrls } from "../calendar.ts";
import { backendRoot, config } from "../config.ts";
import { clearGitlabCache } from "../gitlab.ts";
import { jiraConfigured, jiraSite } from "../jira.ts";
import { disconnectGitlab, finishGitlabLogin, gitlabAccount, gitlabClientId, gitlabLoginUrl } from "../gitlab-auth.ts";
import { LOCAL_PAGE } from "../local-page.ts";
import { WEB_FILES } from "../web-style.ts";
import { accessibility, openTarget } from "../os.ts";
import { identity, lanAddresses, pendingCode } from "../pairing.ts";
import { sendToTablet, tabletStatus, THEMES, type TabletSettings } from "../tablet.ts";

const SETTINGS = ["theme", "brightness", "volume", "nightDim", "focusDnd"] satisfies (keyof TabletSettings)[];

const LICENCES_FILE = path.join(backendRoot, "LICENCES-TIERS.txt");
/** Politique de confidentialité (même texte dans l'appli, assets/confidentialite.txt ; docs/confidentialite.md). */
const PRIVACY_FILE = path.join(backendRoot, "config", "confidentialite.txt");

/** Page d'accueil locale (ouverte dans le navigateur de l'ordinateur), connexion des comptes d'agenda et du compte GitLab. */
export default async function localRoutes(app: FastifyInstance) {
  app.get("/", async (_req, reply) => reply.type("text/html; charset=utf-8").send(LOCAL_PAGE));

  /** Polices des pages web (web-style.ts) : liste fermée, jamais un chemin venu de la requête. */
  app.get<{ Params: { file: string } }>("/web/:file", async (req, reply) => {
    if (!WEB_FILES.includes(req.params.file)) return reply.code(404).send({ error: "Fichier inconnu" });
    const data = await readFile(path.join(backendRoot, "assets", "web", req.params.file));
    return reply.type("font/ttf").header("cache-control", "max-age=86400").send(data);
  });

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
    tabletInfo: tabletInfo(),
    pairing: pendingCode(),
    licences: fs.existsSync(LICENCES_FILE),
    // Hôtes seulement : un lien ICS publié donne accès à l'agenda, il ne s'affiche pas en entier.
    calendar: {
      env: config.icsUrls.length,
      saved: (await savedCalendarUrls()).map((u) => (u === "demo" ? "agenda d'exemple" : (URL.parse(u)?.host ?? "lien invalide"))),
      accounts: await connectedAccounts(),
      providers: await availableProviders(),
    },
    // `available` : l'appli OAuth est réglée (bouton de connexion) ; `token` : GITLAB_TOKEN dans .env (secours).
    gitlab: {
      available: !!(await gitlabClientId()),
      token: !!process.env.GITLAB_TOKEN?.trim(),
      account: (await gitlabAccount()) ?? null,
    },
    // Jira se règle dans le .env de l'ordinateur : la page dit seulement s'il est relié, et à quel site.
    jira: { configured: jiraConfigured, site: jiraSite },
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

  // Connexion GitLab (src/gitlab-auth.ts) : même parcours, retour sur la section GitLab de la page. Routes à part : GitLab
  // n'est pas un fournisseur d'agenda. 127.0.0.1 : adresse déclarée dans l'appli (la variante localhost l'est aussi).
  app.get("/oauth/gitlab/start", async (_req, reply) => {
    if (!(await gitlabClientId())) return reply.code(404).send({ error: "Connexion non disponible pour ce compte" });
    return reply.redirect(await gitlabLoginUrl(`http://127.0.0.1:${config.port}/oauth/gitlab/callback`));
  });

  app.get<{ Querystring: { code?: string; state?: string; error?: string; error_description?: string } }>(
    "/oauth/gitlab/callback",
    async (req, reply) => {
      const back = (result: string) =>
        reply.redirect(`http://127.0.0.1:${config.port}/?section=gitlab&gitlab=${encodeURIComponent(result)}`);
      if (req.query.error || !req.query.code || !req.query.state) {
        req.log.warn(`Connexion GitLab refusée : ${req.query.error}`);
        return back(req.query.error === "access_denied" ? "refused" : "error");
      }
      try {
        await finishGitlabLogin(req.query.state, req.query.code);
        clearGitlabCache();
        return back("ok");
      } catch (e) {
        req.log.error(e);
        return back("error");
      }
    },
  );

  app.delete("/local/accounts/gitlab", async () => {
    await disconnectGitlab();
    clearGitlabCache();
    return { ok: true };
  });

  app.delete<{ Params: { provider: string } }>("/local/accounts/:provider", async (req, reply) => {
    if (!isProvider(req.params.provider)) return reply.code(404).send({ error: "Compte inconnu" });
    await disconnect(req.params.provider);
    invalidateCalendar();
    return { ok: true };
  });

  /**
   * Ouvre le compagnon sur l'ordinateur, depuis la tablette : sa page (« Relier mon agenda » de l'Accueil, « Régler depuis
   * l'ordi » des Réglages), ou la page d'autorisation de GitLab (`gitlab`, carte « Connecter GitLab » de l'onglet GitLab).
   */
  app.post<{ Body: { section?: "agenda" | "gitlab" | "tablette" } }>(
    "/local/open",
    {
      schema: {
        body: { type: "object", additionalProperties: false, properties: { section: { enum: ["agenda", "gitlab", "tablette"] } } },
      },
    },
    async (req) => {
      const section = req.body?.section;
      const path = section === "gitlab" ? "oauth/gitlab/start" : section ? `?section=${section}` : "";
      await openTarget(`http://127.0.0.1:${config.port}/${path}`);
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
    // Nom donné à l'appairage (« Tablette <modèle> ») ; absent d'une appli plus ancienne : le modèle.
    name: s.name ?? s.model ?? null,
    model: s.model ?? null,
    screen: s.screen ?? null,
    battery: s.battery ?? null,
    settings: s.settings ?? null,
    // Absent : appli trop ancienne pour être mise à jour d'ici.
    update: s.update ?? null,
  };
}

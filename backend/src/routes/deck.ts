import { createReadStream } from "node:fs";
import type { FastifyInstance } from "fastify";
import {
  addDeckApp, addDeckLink, deckLinks, findTile, homeFeatures, LINK_SUGGESTIONS, loadDeck, removeDeckApp, removeDeckLink, resetDeckApps, runAction, tileIcon,
} from "../deck.ts";
import { openMediaLink } from "../music.ts";
import {
  focusWindow, isAppInCall, isAppRunning, launchApp, listApps, listButtons, listDisplays, listShortcuts, listWindows, osName, placeWindow, voiceState,
} from "../os.ts";
import { keySchema, loadKeys, saveKey, SLOTS, type PersoKey } from "../perso.ts";
import type { Layout, WindowTarget } from "../screens.ts";
import { windowTarget } from "./windows.ts";

/** Deck : pages et tuiles, page Apps, page Média, touches perso, fenêtres de l'appli d'une tuile, commande vocale « ouvre X ». */
export default async function deckRoutes(app: FastifyInstance) {
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

  app.post<{ Body: { id: string } }>(
    "/deck/run",
    { schema: { body: { type: "object", required: ["id"], properties: { id: { type: "string", maxLength: 20 } } } } },
    async (req, reply) => {
      const tile = await findTile(req.body.id);
      if (!tile) return reply.code(404).send({ error: "Tuile inconnue" });
      req.log.info({ tile: tile.id, action: tile.action.type }, "Touche du deck");
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
}

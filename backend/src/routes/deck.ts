import { TILE_SYMBOLS } from "../screens.ts";
import { createReadStream } from "node:fs";
import type { FastifyInstance } from "fastify";
import {
  addDeckApp,
  addDeckLink,
  deckLinks,
  findTile,
  holdTile,
  homeFeatures,
  isPush,
  LINK_SUGGESTIONS,
  liveDeckApps,
  loadDeck,
  releaseAllPushed,
  removeDeckApp,
  removeDeckLink,
  resetDeckApps,
  runAction,
  tileIcon,
} from "../deck.ts";
import { openMediaLink } from "../music.ts";
import {
  focusWindow,
  launchApp,
  listApps,
  listButtons,
  listDisplays,
  listShortcuts,
  listWindows,
  osName,
  placeWindow,
  voiceState,
} from "../os.ts";
import {
  addKey,
  duplicateKey,
  duplicateKeyPage,
  keySchema,
  loadKeyPages,
  loadKeys,
  saveKey,
  saveKeyPage,
  MAX_PAGES,
  MAX_SLOTS,
  type PersoKey,
} from "../perso.ts";
import type { Layout, WindowTarget } from "../screens.ts";
import { windowTarget } from "./windows.ts";

export const appNameBody = {
  type: "object",
  required: ["name"],
  properties: { name: { type: "string", minLength: 1, maxLength: 200 } },
};

export const linkBody = {
  type: "object",
  required: ["url"],
  properties: { url: { type: "string", minLength: 1, maxLength: 2000 }, label: { type: "string", maxLength: 40 } },
};

export const slotParam = {
  type: "object",
  required: ["slot"],
  properties: { slot: { type: "integer", minimum: 0, maximum: MAX_SLOTS - 1 } },
};

export const pageSlotParams = {
  type: "object",
  required: ["page", "slot"],
  properties: { page: { type: "integer", minimum: 0, maximum: MAX_PAGES - 1 }, slot: slotParam.properties.slot },
};
export type PageSlot = { page: number; slot: number };

const pageRank = { type: "integer", minimum: 0, maximum: MAX_PAGES - 1 };
export const pageParam = { type: "object", required: ["page"], properties: { page: pageRank } };
/** `page` et `to` absents : Perso. */
export const duplicateKeyBody = {
  type: "object",
  additionalProperties: false,
  required: ["slot"],
  properties: { page: pageRank, slot: slotParam.properties.slot, to: pageRank },
};
export type DuplicateKeyBody = { page?: number; slot: number; to?: number };

/** De quoi remplir l'éditeur de touche : applis installées, raccourcis Apple (Mac), écrans. */
export async function deckCatalog() {
  const [apps, shortcuts, displays] = await Promise.all([
    listApps(),
    listShortcuts(),
    Promise.resolve()
      .then(listDisplays)
      .catch(() => []),
  ]);
  return { os: osName, apps, shortcuts, displays: displays.map((d) => d.label) };
}

/** Deck : pages et tuiles, page Apps, page Média, touches perso, fenêtres de l'appli d'une tuile, commande vocale « ouvre X ». */
export default async function deckRoutes(app: FastifyInstance) {
  app.get("/deck", async () => {
    const pages = await loadDeck();
    return {
      pages: pages.map((p) => ({
        title: p.title,
        editable: p.editable ?? false,
        keyPage: p.keyPage ?? null,
        apps: p.apps ?? false,
        meeting: p.meeting ?? false,
        app: p.app ?? null,
        media: p.media ?? false,
        sound: p.sound ?? true,
        tiles: p.tiles.map((t) =>
          "empty" in t
            ? { id: t.id, label: "", type: "empty", glyph: null, color: null }
            : {
                id: t.id,
                label: t.label,
                type: t.action.type,
                symbol: t.symbol && (TILE_SYMBOLS as readonly string[]).includes(t.symbol) ? t.symbol : null,
                glyph: t.glyph ?? null,
                color: t.color ?? null,
                when: t.when ?? null,
                lit: t.lit ?? null,
                state: t.state ?? null,
                ...(t.hold ? { hold: true } : {}),
                ...(isPush(t) ? { push: true } : {}),
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

  app.addHook("onClose", () => releaseAllPushed());

  app.post<{ Body: { id: string; hold?: boolean; phase?: "down" | "up" } }>(
    "/deck/run",
    {
      schema: {
        body: {
          type: "object",
          required: ["id"],
          properties: { id: { type: "string", maxLength: 20 }, hold: { type: "boolean" }, phase: { enum: ["down", "up"] } },
        },
      },
    },
    async (req, reply) => {
      const tile = await findTile(req.body.id);
      if (!tile) return reply.code(404).send({ error: "Tuile inconnue" });
      // Touche à maintenir : appui (`down`) et relâchement (`up`) arrivent séparément.
      if (req.body.phase) {
        if (!isPush(tile)) return reply.code(409).send({ error: "Cette touche ne se maintient pas" });
        await holdTile(tile, req.body.phase === "down");
        return { ok: true };
      }
      // Appui long : la seconde action de la touche, sans le détour des fenêtres ni de la page Média.
      if (req.body.hold) {
        if (!tile.hold) return reply.code(409).send({ error: "Cette touche n'a pas de seconde action" });
        req.log.info({ tile: tile.id, action: tile.hold.type }, "Appui long sur une touche du deck");
        await runAction(tile.hold);
        return { ok: true };
      }
      req.log.info({ tile: tile.id, action: tile.action.type }, "Touche du deck");
      // Appli déjà ouverte avec plusieurs fenêtres : la tablette propose de choisir au lieu d'activer.
      if (tile.action.type === "app") {
        const info = await listWindows(tile.action.name).catch(() => undefined);
        if (info && info.windows.length > 1) return { ok: true, windows: info };
      }
      // Page Média : l'appli du site si elle est installée, et la musique démarre seule.
      if (tile.id.startsWith("l-") && tile.action.type === "url") return { ok: true, ...(await openMediaLink(tile.action.url)) };
      await runAction(tile.action, tile.id);
      return { ok: true };
    },
  );

  // --- Page Apps : profil de base, complété depuis la tablette par les applis installées sur le Mac

  app.post<{ Body: { name: string } }>("/deck/apps", { schema: { body: appNameBody } }, async (req, reply) => {
    if (!(await listApps()).includes(req.body.name)) return reply.code(404).send({ error: "Appli introuvable sur l'ordinateur" });
    return { ok: true, id: await addDeckApp(req.body.name) };
  });

  /** Commande vocale « ouvre X » : appli installée qui n'est pas dans le deck. */
  app.post<{ Body: { name: string } }>("/apps/launch", { schema: { body: appNameBody } }, async (req, reply) => {
    if (!(await listApps()).includes(req.body.name)) return reply.code(404).send({ error: "Appli introuvable sur l'ordinateur" });
    await launchApp(req.body.name);
    return { ok: true };
  });

  app.delete<{ Params: { id: string } }>("/deck/apps/:id", async (req) => {
    await removeDeckApp(req.params.id);
    return { ok: true };
  });

  // --- Page Média : sites ajoutés depuis la tablette (vide au départ), avec une liste de sites proposés

  app.get("/deck/links", async () => ({ links: await deckLinks(), suggestions: LINK_SUGGESTIONS }));

  app.post<{ Body: { url: string; label?: string } }>("/deck/links", { schema: { body: linkBody } }, async (req, reply) => {
    try {
      return { ok: true, id: await addDeckLink(req.body.url, req.body.label) };
    } catch {
      return reply.code(400).send({ error: "Adresse invalide (ex. netflix.com)" });
    }
  });

  app.delete<{ Params: { id: string } }>("/deck/links/:id", async (req) => {
    await removeDeckLink(req.params.id);
    return { ok: true };
  });

  /** Cartes de l'Accueil à afficher selon les applis du deck. */
  app.get("/deck/features", () => homeFeatures());

  // Applis des pages d'appli (Discord, Teams) en appel en ce moment (micro utilisé) : la tablette leur donne un onglet.
  app.get("/deck/live", { logLevel: "warn" }, () => liveDeckApps());

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
    {
      logLevel: "warn",
      schema: { querystring: { type: "object", required: ["app"], properties: { app: { type: "string", minLength: 1, maxLength: 100 } } } },
    },
    async (req) => ({ voice: (await voiceState(req.query.app).catch(() => null)) ?? null }),
  );

  /** Retour au profil de base. */
  app.delete("/deck/apps", async () => {
    await resetDeckApps();
    return { ok: true };
  });

  // --- Touches perso (page Perso du deck), modifiables depuis la tablette et par Claude

  app.get("/deck/catalog", deckCatalog);

  app.get<{ Params: { slot: number } }>("/deck/keys/:slot", { schema: { params: slotParam } }, async (req) => ({
    key: (await loadKeys())[req.params.slot] ?? null,
  }));

  // Touche enregistrée depuis le Pavé de la tablette : premier emplacement libre de Perso.
  app.post<{ Body: PersoKey }>("/deck/keys", { schema: { body: keySchema } }, async (req) => ({ slot: await addKey(req.body) }));

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

  // Pages de touches nommées (page `n` de /deck : `keyPage`), mêmes touches que Perso

  app.get<{ Params: PageSlot }>("/deck/pages/:page/keys/:slot", { schema: { params: pageSlotParams } }, async (req, reply) => {
    const page = (await loadKeyPages())[req.params.page];
    if (!page) return reply.code(404).send({ error: "Page inconnue" });
    return { key: page.keys[req.params.slot] ?? null };
  });

  app.put<{ Params: PageSlot; Body: PersoKey }>(
    "/deck/pages/:page/keys/:slot",
    { schema: { params: pageSlotParams, body: keySchema } },
    async (req, reply) => {
      if (!(await saveKeyPage(req.params.page, req.params.slot, req.body))) return reply.code(404).send({ error: "Page inconnue" });
      return { ok: true };
    },
  );

  app.delete<{ Params: PageSlot }>("/deck/pages/:page/keys/:slot", { schema: { params: pageSlotParams } }, async (req, reply) => {
    if (!(await saveKeyPage(req.params.page, req.params.slot, null))) return reply.code(404).send({ error: "Page inconnue" });
    return { ok: true };
  });

  // Dupliquer : une touche vers une page (la première place libre), ou une page entière (copie en fin de liste)

  app.post<{ Body: DuplicateKeyBody }>("/deck/keys/duplicate", { schema: { body: duplicateKeyBody } }, async (req) =>
    duplicateKey({ page: req.body.page, slot: req.body.slot }, req.body.to),
  );

  app.post<{ Params: { page: number } }>("/deck/pages/:page/duplicate", { schema: { params: pageParam } }, async (req) => ({
    page: await duplicateKeyPage(req.params.page),
  }));

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

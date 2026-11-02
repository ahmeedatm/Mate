import type { FastifyInstance } from "fastify";
import { addDeckApp, addDeckLink, deckLinks, LINK_SUGGESTIONS, loadDeck, removeDeckApp, removeDeckLink, resetDeckApps } from "../deck.ts";
import { listApps } from "../os.ts";
import { duplicateKey, duplicateKeyPage, keySchema, loadKeyPages, loadKeys, saveKey, saveKeyPage, type PersoKey } from "../perso.ts";
import { listPlugins } from "../plugins.ts";
import { SYSTEM_COMMANDS, TILE_SYMBOLS } from "../screens.ts";
import {
  deckCatalog,
  appNameBody,
  duplicateKeyBody,
  linkBody,
  pageParam,
  pageSlotParams,
  slotParam,
  type DuplicateKeyBody,
  type PageSlot,
} from "./deck.ts";
import { applyPluginChoices, pluginChoicesBody } from "./plugins.ts";

/**
 * Panneau de configuration de la page locale (src/local-config-deck.ts) : les données du Deck qui vivent sur l'ordinateur
 * (touches et macros, plugins, applis et sites). Mêmes fonctions et mêmes schémas que les routes /deck et /plugins de la
 * tablette ; sans jeton, en local seulement (isLocalPage dans server.ts). Écritures en JSON objet : pas envoyables par un
 * autre site sans pré-vérification CORS.
 */
export default async function localConfigRoutes(app: FastifyInstance) {
  // --- Touches : page Perso et pages nommées, avec de quoi remplir l'éditeur (symboles, commandes système, applis, écrans)

  app.get("/local/config/keys", async () => ({
    perso: { title: "Perso", keys: await loadKeys() },
    pages: await loadKeyPages(),
    symbols: TILE_SYMBOLS,
    systemCommands: SYSTEM_COMMANDS,
    catalog: await deckCatalog(),
  }));

  app.put<{ Params: { slot: number }; Body: PersoKey }>(
    "/local/config/keys/:slot",
    { schema: { params: slotParam, body: keySchema } },
    async (req) => {
      await saveKey(req.params.slot, req.body);
      return { ok: true };
    },
  );

  app.delete<{ Params: { slot: number } }>("/local/config/keys/:slot", { schema: { params: slotParam } }, async (req) => {
    await saveKey(req.params.slot, null);
    return { ok: true };
  });

  app.put<{ Params: PageSlot; Body: PersoKey }>(
    "/local/config/pages/:page/keys/:slot",
    { schema: { params: pageSlotParams, body: keySchema } },
    async (req, reply) => {
      if (!(await saveKeyPage(req.params.page, req.params.slot, req.body))) return reply.code(404).send({ error: "Page inconnue" });
      return { ok: true };
    },
  );

  app.delete<{ Params: PageSlot }>("/local/config/pages/:page/keys/:slot", { schema: { params: pageSlotParams } }, async (req, reply) => {
    if (!(await saveKeyPage(req.params.page, req.params.slot, null))) return reply.code(404).send({ error: "Page inconnue" });
    return { ok: true };
  });

  app.post<{ Body: DuplicateKeyBody }>("/local/config/keys/duplicate", { schema: { body: duplicateKeyBody } }, async (req) =>
    duplicateKey({ page: req.body.page, slot: req.body.slot }, req.body.to),
  );

  app.post<{ Params: { page: number } }>("/local/config/pages/:page/duplicate", { schema: { params: pageParam } }, async (req) => ({
    page: await duplicateKeyPage(req.params.page),
  }));

  // --- Plugins : aussi ceux dont l'appli est absente (`present: false`), pour les tuiles de la section Connecteurs

  app.get("/local/config/plugins", async () => ({ plugins: await listPlugins(true) }));

  app.put<{ Body: { choices: Record<string, boolean> } }>("/local/config/plugins", { schema: { body: pluginChoicesBody } }, async (req) =>
    applyPluginChoices(req.body.choices, true),
  );

  // --- Applis du Deck (page Apps) : liste actuelle et applis installées que l'on peut ajouter

  app.get("/local/config/apps", async () => {
    const page = (await loadDeck()).find((p) => p.apps);
    const apps = (page?.tiles ?? []).flatMap((t) =>
      "empty" in t || t.action.type !== "app" ? [] : [{ id: t.id, label: t.label, name: t.action.name }],
    );
    return { apps, installed: await listApps() };
  });

  app.post<{ Body: { name: string } }>("/local/config/apps", { schema: { body: appNameBody } }, async (req, reply) => {
    if (!(await listApps()).includes(req.body.name)) return reply.code(404).send({ error: "Appli introuvable sur l'ordinateur" });
    return { ok: true, id: await addDeckApp(req.body.name) };
  });

  app.delete<{ Params: { id: string } }>("/local/config/apps/:id", async (req) => {
    await removeDeckApp(req.params.id);
    return { ok: true };
  });

  /** Retour au profil de base. */
  app.delete("/local/config/apps", async () => {
    await resetDeckApps();
    return { ok: true };
  });

  // --- Sites de la page Média

  app.get("/local/config/links", async () => ({ links: await deckLinks(), suggestions: LINK_SUGGESTIONS }));

  app.post<{ Body: { url: string; label?: string } }>("/local/config/links", { schema: { body: linkBody } }, async (req, reply) => {
    try {
      return { ok: true, id: await addDeckLink(req.body.url, req.body.label) };
    } catch {
      return reply.code(400).send({ error: "Adresse invalide (ex. netflix.com)" });
    }
  });

  app.delete<{ Params: { id: string } }>("/local/config/links/:id", async (req) => {
    await removeDeckLink(req.params.id);
    return { ok: true };
  });
}

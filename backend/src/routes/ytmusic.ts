import type { FastifyInstance } from "fastify";
import { MAX_ITEMS, receiveYtMusic, sendYtMusic, YT_MUSIC_OPS, ytMusic, type YtMusicAction, type YtMusicReport } from "../ytmusic.ts";

const itemSchema = {
  type: "object",
  required: ["title"],
  properties: {
    title: { type: "string", maxLength: 500 },
    subtitle: { type: "string", maxLength: 500 },
    cover: { type: ["string", "null"], maxLength: 2000 },
  },
};

export default async function ytMusicRoutes(app: FastifyInstance) {
  /** État de l'onglet YouTube Music, envoyé par l'extension « Mate » du navigateur (en local, sans jeton). */
  app.post<{ Body: YtMusicReport }>(
    "/browser/extension/ytmusic",
    {
      logLevel: "warn",
      schema: {
        body: {
          type: "object",
          required: ["browser", "state"],
          properties: {
            browser: { type: "string", maxLength: 40 },
            state: {
              type: "object",
              required: ["playing", "track", "repeat", "queue", "query", "results"],
              properties: {
                playing: { type: "boolean" },
                track: {
                  type: ["object", "null"],
                  required: ["title", "like"],
                  properties: {
                    title: { type: "string", maxLength: 500 },
                    artist: { type: "string", maxLength: 500 },
                    album: { type: "string", maxLength: 500 },
                    cover: { type: ["string", "null"], maxLength: 2000 },
                    position: { type: "number" },
                    duration: { type: "number" },
                    like: { enum: ["like", "dislike", "none"] },
                  },
                },
                repeat: { enum: ["none", "all", "one", null] },
                queue: {
                  type: "array",
                  maxItems: 500,
                  items: { ...itemSchema, properties: { ...itemSchema.properties, current: { type: "boolean" } } },
                },
                query: { type: ["string", "null"], maxLength: 500 },
                results: { type: "array", maxItems: 500, items: itemSchema },
              },
            },
          },
        },
      },
    },
    async (req, reply) => {
      // Seule une extension (pas une page web ouverte sur l'ordinateur) peut annoncer un onglet.
      if (!/^(chrome|moz)-extension:\/\//.test(req.headers.origin ?? ""))
        return reply.code(403).send({ error: "Réservé à l'extension du navigateur" });
      receiveYtMusic(req.body);
      return { ok: true };
    },
  );

  /** Ce que joue YouTube Music sur l'ordinateur ; `open: false` = aucun onglet (ou extension absente). */
  app.get("/ytmusic", async () => {
    const tab = ytMusic();
    return tab ? { open: true, browser: tab.browser, ...tab.state } : { open: false };
  });

  /** Une action de la liste fermée, exécutée par l'extension dans l'onglet. */
  app.post<{ Body: YtMusicAction }>(
    "/ytmusic/action",
    {
      schema: {
        body: {
          type: "object",
          required: ["op"],
          additionalProperties: false,
          properties: {
            op: { enum: YT_MUSIC_OPS },
            index: { type: "integer", minimum: 0, maximum: MAX_ITEMS - 1 },
            query: { type: "string", minLength: 1, maxLength: 200 },
            seconds: { type: "number", minimum: 0 },
          },
        },
      },
    },
    async (req, reply) => {
      const action = req.body;
      const tab = ytMusic();
      const list = action.op === "play-queue" ? tab?.state.queue : action.op === "play-result" ? tab?.state.results : undefined;
      if (list && (action.index === undefined || action.index >= list.length))
        return reply.code(400).send({ error: "Ce titre n'est plus dans la liste : actualise" });
      if (action.op === "search" && !action.query?.trim()) return reply.code(400).send({ error: "Recherche vide" });
      if (action.op === "seek" && action.seconds === undefined) return reply.code(400).send({ error: "Position manquante" });
      if (!sendYtMusic(action))
        return reply.code(409).send({
          error: tab
            ? "L'extension du navigateur ne répond pas : recharge-la"
            : "Ouvre YouTube Music dans le navigateur de l'ordinateur (avec l'extension Mate)",
        });
      return { ok: true };
    },
  );
}

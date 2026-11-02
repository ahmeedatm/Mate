import type { FastifyInstance } from "fastify";
import { runAction } from "../deck.ts";
import { loadNews, MAX_ITEMS, setNewsFeed, shownLink } from "../news.ts";

export default async function newsRoutes(app: FastifyInstance) {
  /** Derniers titres du flux réglé ; sans flux : liste vide. Flux injoignable ou illisible : 502 avec une phrase. */
  app.get("/news", async (req, reply) => {
    try {
      return await loadNews();
    } catch (e) {
      req.log.warn({ err: e }, "flux d'actualités illisible");
      return reply.code(502).send({ error: "Le flux d'actualités ne répond pas ou n'est pas lisible." });
    }
  });

  /** Règle le flux (`url` à null : le retire). */
  app.post<{ Body: { url: string | null } }>(
    "/news/feed",
    { schema: { body: { type: "object", required: ["url"], properties: { url: { type: ["string", "null"], maxLength: 2000 } } } } },
    async (req, reply) => {
      try {
        await setNewsFeed(req.body.url);
      } catch (e) {
        return reply.code(400).send({ error: e instanceof Error ? e.message : "Adresse du flux non valide" });
      }
      return { ok: true };
    },
  );

  /** Ouvre sur l'ordinateur l'article n° `index` de la dernière liste montrée (jamais une adresse envoyée par la tablette). */
  app.post<{ Body: { index: number } }>(
    "/news/open",
    {
      schema: {
        body: { type: "object", required: ["index"], properties: { index: { type: "integer", minimum: 0, maximum: MAX_ITEMS - 1 } } },
      },
    },
    async (req, reply) => {
      const link = shownLink(req.body.index);
      if (!link) return reply.code(404).send({ error: "Article inconnu : la liste a changé" });
      await runAction({ type: "url", url: link });
      return { ok: true };
    },
  );
}

import type { FastifyInstance } from "fastify";
import { addShopping, clearDoneShopping, deleteShopping, listShopping, setShoppingDone } from "../shopping.ts";

export default async function shoppingRoutes(app: FastifyInstance) {
  app.get("/shopping", async () => ({ items: await listShopping() }));

  /** Un ou plusieurs articles d'un coup (« du lait et du pain »). */
  app.post<{ Body: { labels: string[] } }>(
    "/shopping",
    {
      schema: {
        body: {
          type: "object",
          required: ["labels"],
          properties: {
            labels: { type: "array", minItems: 1, maxItems: 10, items: { type: "string", maxLength: 200 } },
          },
        },
      },
    },
    async (req, reply) => {
      if (!req.body.labels.some((l) => l.trim())) return reply.code(400).send({ error: "Écris au moins un article" });
      const items = await addShopping(req.body.labels);
      return items ? { items } : reply.code(409).send({ error: "La liste de courses est pleine" });
    },
  );

  app.post<{ Params: { id: string }; Body: { done: boolean } }>(
    "/shopping/:id/done",
    { schema: { body: { type: "object", required: ["done"], properties: { done: { type: "boolean" } } } } },
    async (req, reply) =>
      (await setShoppingDone(req.params.id, req.body.done)) ? { ok: true } : reply.code(404).send({ error: "Article inconnu" }),
  );

  app.delete<{ Params: { id: string } }>("/shopping/:id", async (req, reply) =>
    (await deleteShopping(req.params.id)) ? { ok: true } : reply.code(404).send({ error: "Article inconnu" }),
  );

  /** Retire les articles cochés. */
  app.post("/shopping/clear-done", async () => ({ removed: await clearDoneShopping() }));
}

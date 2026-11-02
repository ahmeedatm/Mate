import type { FastifyInstance } from "fastify";
import { HA_ENTITY_ID, haActivate, haConfigured, haEntities } from "../ha.ts";

export default async function haRoutes(app: FastifyInstance) {
  app.addHook("preHandler", async (_req, reply) => {
    if (!haConfigured) return reply.code(501).send({ error: "Home Assistant n'est pas configuré sur l'ordinateur." });
  });

  app.get("/ha/entities", async () => ({ entities: await haEntities() }));

  app.post<{ Params: { id: string } }>(
    "/ha/entities/:id/toggle",
    { schema: { params: { type: "object", required: ["id"], properties: { id: { type: "string", pattern: HA_ENTITY_ID.source } } } } },
    async (req, reply) => {
      if (!(await haActivate(req.params.id)))
        return reply.code(403).send({ error: "Cette entité n'est pas dans la liste de l'ordinateur." });
      return { ok: true };
    },
  );
}

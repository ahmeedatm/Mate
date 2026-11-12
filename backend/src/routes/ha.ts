import type { FastifyInstance } from "fastify";
import { HA_ENTITY_ID, haActivate, haConfigured, haEntities, haSetLight } from "../ha.ts";

const BYTE = { type: "integer", minimum: 0, maximum: 255 } as const;

/** Réglage d'une lumière : au moins la luminosité (1 à 100 % ; on éteint par la bascule) ou la couleur (trois octets RVB), rien d'autre. */
const LIGHT_BODY = {
  type: "object",
  additionalProperties: false,
  minProperties: 1,
  properties: {
    brightness: { type: "integer", minimum: 1, maximum: 100 },
    rgb: { type: "array", minItems: 3, maxItems: 3, items: BYTE },
  },
} as const;

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

  app.post<{ Params: { id: string }; Body: { brightness?: number; rgb?: [number, number, number] } }>(
    "/ha/entities/:id/light",
    {
      schema: {
        params: { type: "object", required: ["id"], properties: { id: { type: "string", pattern: /^light\.[a-z0-9_]+$/.source } } },
        body: LIGHT_BODY,
      },
    },
    async (req, reply) => {
      if (!(await haSetLight(req.params.id, req.body)))
        return reply.code(403).send({ error: "Cette entité n'est pas dans la liste de l'ordinateur." });
      return { ok: true };
    },
  );
}

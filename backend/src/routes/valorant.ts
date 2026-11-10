import { createReadStream } from "node:fs";
import type { FastifyInstance, FastifyReply } from "fastify";
import { valorantState } from "../valorant.ts";
import { agentIcon, mapImage, rankIcon } from "../valorant-assets.ts";

/**
 * Widget Valorant de la tablette : partie en cours, rangs, agents et stats (client Riot de l'ordinateur, Windows), et
 * leurs images, téléchargées par l'ordinateur depuis valorant-api.com : la tablette ne donne qu'un identifiant connu.
 */
export default async function valorantRoutes(app: FastifyInstance) {
  app.get("/valorant", () => valorantState());

  const png = (reply: FastifyReply, file: string | undefined) => {
    if (!file) return reply.code(404).send({ error: "Pas d'image" });
    reply.header("Cache-Control", "max-age=86400");
    return reply.type("image/png").send(createReadStream(file));
  };

  app.get<{ Querystring: { id: string } }>(
    "/valorant/agent",
    {
      schema: {
        querystring: { type: "object", required: ["id"], properties: { id: { type: "string", pattern: "^[0-9a-f-]{36}$" } } },
      },
    },
    async (req, reply) => png(reply, await agentIcon(req.query.id)),
  );

  app.get<{ Querystring: { id: string } }>(
    "/valorant/map",
    {
      schema: {
        querystring: { type: "object", required: ["id"], properties: { id: { type: "string", pattern: "^[0-9a-f-]{36}$" } } },
      },
    },
    async (req, reply) => png(reply, await mapImage(req.query.id)),
  );

  app.get<{ Querystring: { tier: number } }>(
    "/valorant/rank",
    {
      schema: {
        querystring: { type: "object", required: ["tier"], properties: { tier: { type: "integer", minimum: 0, maximum: 27 } } },
      },
    },
    async (req, reply) => png(reply, await rankIcon(req.query.tier)),
  );
}

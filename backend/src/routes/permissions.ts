import type { FastifyInstance } from "fastify";
import { createPermissions, defaultDeps, type Deps } from "../mac/permissions.ts";

/**
 * Autorisations du Mac (src/mac/permissions.ts), pour l'écran « Autorisations du Mac » de la page locale. Routes locales
 * sans jeton comme les autres /local/* ; la demande est un POST à corps JSON, qu'un autre site ne peut pas envoyer à
 * 127.0.0.1. Hors Mac : liste vide, demande refusée (404). `deps` : l'utilitaire natif remplacé dans les tests.
 */
export function permissionRoutes(deps: Deps = defaultDeps()) {
  const permissions = createPermissions(deps);
  return async function permissionPlugin(app: FastifyInstance) {
    app.get("/local/permissions", async () => ({ items: await permissions.list() }));

    app.post<{ Params: { id: string } }>(
      "/local/permissions/:id/request",
      {
        schema: {
          body: { type: "object" },
          params: { type: "object", properties: { id: { type: "string", pattern: "^[A-Za-z0-9.-]{1,80}$" } } },
        },
      },
      async (req, reply) => {
        if (!deps.supported) return reply.code(404).send({ error: "Autorisations du Mac seulement" });
        const result = await permissions.request(req.params.id);
        if (result.unknownId) return reply.code(404).send({ error: result.error });
        if (!result.ok) return reply.code(503).send({ error: result.error });
        return { ok: true, action: result.action ?? null };
      },
    );
  };
}

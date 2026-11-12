import type { FastifyInstance } from "fastify";
import { haSettings } from "../ha.ts";
import { LOOPBACK } from "../net-guard.ts";

/**
 * Réglages de Home Assistant donnés à la tablette appairée, qui parle ensuite à Home Assistant en direct.
 * Plugin à part de routes/ha.ts : il ne dépend pas de la liste blanche (HA_ENTITIES peut être vide), donc pas de 501.
 * Le jeton ne part jamais en clair sur le réseau, et aucun outil MCP ne l'expose.
 */
/**
 * Le jeton Home Assistant ne part qu'en HTTPS, ou en HTTP vers la machine elle-même (tablette en USB). `req.protocol` et
 * `req.ip` ne sont fiables que sans `trustProxy` (server.ts) : l'activer ouvrirait cette route aux en-têtes X-Forwarded-*.
 */
export const mayHandOverHaToken = (protocol: string, ip: string) => protocol === "https" || LOOPBACK.has(ip);

export default async function haSettingsRoutes(app: FastifyInstance) {
  app.get("/ha/settings", async (req, reply) => {
    if (!haSettings) return reply.code(404).send({ error: "Home Assistant n'est pas configuré sur l'ordinateur." });
    if (!mayHandOverHaToken(req.protocol, req.ip))
      return reply
        .code(403)
        .send({ error: "Le jeton de Home Assistant ne passe pas en clair sur le réseau : utilise la connexion HTTPS." });
    return reply.header("cache-control", "no-store").send(haSettings);
  });
}

import type { FastifyReply, FastifyRequest } from "fastify";

const LOOPBACK = new Set(["127.0.0.1", "::1", "::ffff:127.0.0.1"]);
const LOCAL_HOST = /^(127\.0\.0\.1|localhost|\[::1\])(:\d+)?$/i;

/**
 * `onRequest` d'une route réservée à la page locale de l'ordinateur : refusée (403) hors bouclage ou sous un autre nom
 * d'hôte, même avec le jeton d'appairage (que la tablette détient, et qui passe par le WiFi).
 */
export async function localPageOnly(req: FastifyRequest, reply: FastifyReply, error: string) {
  if (!LOOPBACK.has(req.ip) || !LOCAL_HOST.test(req.headers.host ?? "")) return reply.code(403).send({ error });
}

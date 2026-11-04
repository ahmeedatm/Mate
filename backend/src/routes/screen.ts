import type { FastifyInstance } from "fastify";
import { screenPreview } from "../os.ts";
import { singleFlight } from "../single-flight.ts";

/** Largeur maximale de l'aperçu : assez pour suivre un export, léger pour la tablette (2 Go de mémoire, tas de 128 Mo). */
const PREVIEW_WIDTH = 640;

/**
 * Aperçu de l'écran de l'ordinateur pour le widget de la tablette : un JPEG réduit, sous jeton comme toute route, jamais
 * gardé (ni sur le disque, ni en cache HTTP). Une seule capture à la fois : des requêtes qui se chevauchent la partagent.
 */
export default async function screenRoutes(app: FastifyInstance) {
  const capture = singleFlight<Buffer>(0);

  app.get("/screen/preview", { logLevel: "warn" }, async (_req, reply) => {
    const jpeg = await capture.get(() => screenPreview(PREVIEW_WIDTH));
    return reply.type("image/jpeg").header("Cache-Control", "no-store").send(jpeg);
  });
}

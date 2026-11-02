import type { FastifyInstance } from "fastify";
import { loadMetrics } from "../metrics.ts";

export default async function metricsRoutes(app: FastifyInstance) {
  /** Valeurs calculées par les commandes du fichier metrics.json de l'ordinateur ; sans fichier : liste vide. */
  app.get("/metrics", () => loadMetrics());
}

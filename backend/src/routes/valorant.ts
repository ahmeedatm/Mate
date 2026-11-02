import type { FastifyInstance } from "fastify";
import { valorantState } from "../valorant.ts";

/** Widget Valorant de la tablette : partie en cours, rangs et agents (client Riot de l'ordinateur, Windows). */
export default async function valorantRoutes(app: FastifyInstance) {
  app.get("/valorant", () => valorantState());
}

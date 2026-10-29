import type { FastifyInstance } from "fastify";
import { mailSummary } from "../mail.ts";

/** Courrier du compte Microsoft relié, pour la page Outlook du Deck (src/mail.ts). */
export default async function mailRoutes(app: FastifyInstance) {
  app.get("/mail/summary", async () => mailSummary());
}

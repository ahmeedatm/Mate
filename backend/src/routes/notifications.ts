import type { FastifyInstance } from "fastify";
import { notificationHistory } from "../notifications.ts";

// Historique des notifications de l'ordinateur (src/notifications.ts), en mémoire seulement. Le contenu n'est jamais
// écrit dans le journal : routes en `warn`, ni corps ni réponse journalisés.

/** Notifications de l'ordinateur vues par la tablette. */
export default async function notificationRoutes(app: FastifyInstance) {
  app.get("/notifications", { logLevel: "warn" }, async () => ({ items: notificationHistory.list() }));

  app.delete<{ Params: { id: string } }>("/notifications/:id", { logLevel: "warn" }, async (req, reply) => {
    if (!notificationHistory.remove(req.params.id))
      return reply.code(404).send({ error: "Cette notification n'est plus dans l'historique" });
    return { ok: true };
  });

  app.delete("/notifications", { logLevel: "warn" }, async () => {
    notificationHistory.clear();
    return { ok: true };
  });
}

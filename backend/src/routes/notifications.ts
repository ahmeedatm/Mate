import type { FastifyInstance } from "fastify";
import { notificationHistory, notificationsRead } from "../notifications.ts";

// Historique des notifications de l'ordinateur (src/notifications.ts), en mémoire seulement. Le contenu n'est jamais
// écrit dans le journal : routes en `warn`, ni corps ni réponse journalisés.

/** Notifications de l'ordinateur vues par la tablette. */
export default async function notificationRoutes(app: FastifyInstance) {
  app.get("/notifications", { logLevel: "warn" }, async (_req, reply) => {
    const items = notificationHistory.list();
    // Rien à montrer ET la lecture échoue : on le dit (503 = accès complet au disque à donner sur le Mac, 501 = système
    // non pris en charge) plutôt qu'un « aucune notification » trompeur. Avec un historique, on le renvoie quand même.
    if (items.length === 0) {
      const read = notificationsRead();
      if (!read.ok) return reply.code(read.code === 501 ? 501 : 503).send({ error: read.reason });
    }
    return { items };
  });

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

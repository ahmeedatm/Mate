import type { FastifyInstance } from "fastify";
import { accountUnread, inboxUrl, isProvider } from "../accounts.ts";
import { runAction } from "../deck.ts";

export default async function mailRoutes(app: FastifyInstance) {
  /** Non lus de la boîte de réception de chaque compte aux droits mails (liste vide sans compte), gardés 30 s. */
  app.get("/mail/unread", async (req) => ({ accounts: await accountUnread((line) => req.log.warn(line)) }));

  /** Ouvre sur l'ordinateur la boîte du fournisseur (adresse fixe : la tablette n'envoie que le fournisseur). */
  app.post<{ Body: { provider: string } }>(
    "/mail/open",
    { schema: { body: { type: "object", required: ["provider"], properties: { provider: { type: "string", maxLength: 20 } } } } },
    async (req, reply) => {
      if (!isProvider(req.body.provider)) return reply.code(400).send({ error: "Fournisseur inconnu" });
      const url = await inboxUrl(req.body.provider);
      if (!url) return reply.code(404).send({ error: "Les mails de ce compte ne sont pas connectés sur l'ordinateur" });
      await runAction({ type: "url", url });
      return { ok: true };
    },
  );
}

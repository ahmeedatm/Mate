import type { FastifyInstance } from "fastify";
import { answerCall, declineCall, hangUp } from "../calls.ts";

export default async function callRoutes(app: FastifyInstance) {
  // --- Appels entrants (src/calls.ts) : la tablette répond, ignore ou raccroche en appuyant sur les boutons de l'appli

  const callBody = { type: "object", properties: { app: { type: "string", minLength: 1, maxLength: 100 }, video: { type: "boolean" } } };
  app.post<{ Body: { app: string; video?: boolean } }>(
    "/calls/answer",
    { schema: { body: { ...callBody, required: ["app"] } } },
    async (req) => {
      await answerCall(req.body.app, req.body.video);
      return { ok: true };
    },
  );
  app.post<{ Body: { app: string } }>("/calls/decline", { schema: { body: { ...callBody, required: ["app"] } } }, async (req) => {
    await declineCall(req.body.app);
    return { ok: true };
  });
  app.post<{ Body: { app?: string } }>("/calls/hangup", { schema: { body: callBody } }, async (req) => ({
    ok: true,
    app: await hangUp(req.body.app),
  }));
}

import type { FastifyInstance } from "fastify";
import { present, PRESENT_ACTIONS, type PresentAction } from "../present.ts";

/** Widget Présentation de la tablette : diapo suivante ou précédente, début, fin, dans l'appli de diapos ouverte. */
export default async function presentRoutes(app: FastifyInstance) {
  app.post<{ Params: { action: PresentAction } }>(
    "/present/:action",
    { schema: { params: { type: "object", properties: { action: { enum: PRESENT_ACTIONS } } } } },
    async (req) => ({ ok: true, app: await present(req.params.action) }),
  );
}

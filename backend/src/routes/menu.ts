import type { FastifyInstance } from "fastify";
import { loadMenu, MAX_DISH, saveMenu, WEEK_DAYS, type MenuDay } from "../menu.ts";

const daysBody = {
  type: "object",
  required: ["days"],
  properties: {
    days: {
      type: "array",
      minItems: WEEK_DAYS,
      maxItems: WEEK_DAYS,
      items: {
        type: "object",
        required: ["lunch", "dinner"],
        properties: { lunch: { type: "string", maxLength: MAX_DISH * 3 }, dinner: { type: "string", maxLength: MAX_DISH * 3 } },
      },
    },
  },
};

export default async function menuRoutes(app: FastifyInstance) {
  /** Les sept jours du menu, du lundi au dimanche ; la tablette choisit aujourd'hui et demain avec sa propre horloge. */
  app.get("/menu", async () => ({ days: await loadMenu() }));

  // Réglage depuis la page locale de l'ordinateur (sans jeton, en local : isLocalPage dans server.ts).
  app.get("/local/config/menu", async () => ({ days: await loadMenu() }));

  app.put<{ Body: { days: MenuDay[] } }>("/local/config/menu", { schema: { body: daysBody } }, async (req) => ({
    days: await saveMenu(req.body.days),
  }));
}

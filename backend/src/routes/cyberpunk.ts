import type { FastifyInstance } from "fastify";
import { block, installMod, overview, presence, uninstallMod } from "../cyberpunk.ts";
import { pluginOn } from "../plugins.ts";

/**
 * Plugin Cyberpunk 2077 (Windows) : état de la partie lu dans le mod CET, blocs lents, installation du mod.
 * Plugin coupé : 403 partout, sauf `presence` (la tablette la sonde) qui répond `available: false`.
 */
export default async function cyberpunkRoutes(app: FastifyInstance) {
  app.addHook("preHandler", async (req, reply) => {
    if (req.url.split("?")[0] === "/cyberpunk/presence" || (await pluginOn("cyberpunk"))) return;
    return reply.code(403).send({ error: "Le plugin Cyberpunk 2077 est coupé" });
  });

  app.get("/cyberpunk/presence", async () => presence(await pluginOn("cyberpunk")));
  app.get("/cyberpunk", () => overview());
  app.get("/cyberpunk/inventory", () => block("inventory"));
  app.get("/cyberpunk/contacts", () => block("contacts"));
  app.get("/cyberpunk/quests", () => block("quests"));
  app.post("/cyberpunk/install", () => installMod());
  app.post("/cyberpunk/uninstall", () => uninstallMod());
}

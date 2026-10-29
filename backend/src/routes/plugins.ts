import type { FastifyInstance } from "fastify";
import { addDeckApp } from "../deck.ts";
import { isInstalled } from "../os.ts";
import { listPlugins, PLUGIN_IDS, savePlugins } from "../plugins.ts";

/** Plugins de la tablette (src/plugins.ts) : proposés à la première utilisation, puis depuis les Réglages. */
export default async function pluginRoutes(app: FastifyInstance) {
  app.get("/plugins", async () => ({ plugins: await listPlugins() }));

  app.post<{ Body: { choices: Record<string, boolean> } }>(
    "/plugins",
    {
      schema: {
        body: {
          type: "object",
          required: ["choices"],
          properties: {
            choices: {
              type: "object",
              propertyNames: { enum: PLUGIN_IDS },
              additionalProperties: { type: "boolean" },
              maxProperties: PLUGIN_IDS.length,
            },
          },
        },
      },
    },
    async (req) => {
      // Appli activée : ajoutée à la page Apps, sa page du deck devient un onglet tant qu'elle tourne.
      for (const name of await savePlugins(req.body.choices)) if (await isInstalled(name).catch(() => false)) await addDeckApp(name);
      return { plugins: await listPlugins() };
    },
  );
}

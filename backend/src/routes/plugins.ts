import type { FastifyInstance } from "fastify";
import { addDeckApp } from "../deck.ts";
import { isInstalled } from "../os.ts";
import { listPlugins, PLUGIN_IDS, savePlugins } from "../plugins.ts";

export const pluginChoicesBody = {
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
};

/** Enregistre les choix. Appli activée : ajoutée à la page Apps, sa page du deck devient un onglet tant qu'elle tourne. */
export async function applyPluginChoices(choices: Record<string, boolean>, withAbsent = false) {
  for (const name of await savePlugins(choices)) if (await isInstalled(name).catch(() => false)) await addDeckApp(name);
  return { plugins: await listPlugins(withAbsent) };
}

/** Plugins de la tablette (src/plugins.ts) : proposés à la première utilisation, puis depuis les Réglages. */
export default async function pluginRoutes(app: FastifyInstance) {
  app.get("/plugins", async () => ({ plugins: await listPlugins() }));

  app.post<{ Body: { choices: Record<string, boolean> } }>("/plugins", { schema: { body: pluginChoicesBody } }, async (req) =>
    applyPluginChoices(req.body.choices),
  );
}

import { createReadStream } from "node:fs";
import type { FastifyInstance } from "fastify";
import { appIconFile } from "../deck.ts";
import { controlOpenWindow, listOpenWindows } from "../os.ts";
import type { Layout, OpenAction, WindowTarget } from "../screens.ts";

/** Fenêtre visée (vue « Ouvertes » et fenêtres d'une tuile du deck). */
export const windowTarget = {
  type: "object",
  required: ["index", "title"],
  // Mac : index 0 = fenêtre d'un autre bureau (menu Fenêtre), retrouvée par son titre. Windows : handle.
  properties: { index: { type: "integer", minimum: 0 }, title: { type: "string", maxLength: 500 } },
};

/** Vue « Ouvertes » : toutes les applis ouvertes sur l'ordinateur et l'écran de chacune de leurs fenêtres. */
export default async function windowRoutes(app: FastifyInstance) {
  app.get("/windows/open", async () => listOpenWindows());

  app.get<{ Querystring: { name: string } }>(
    "/windows/icon",
    {
      schema: {
        querystring: { type: "object", required: ["name"], properties: { name: { type: "string", minLength: 1, maxLength: 200 } } },
      },
    },
    async (req, reply) => {
      const icon = await appIconFile(req.query.name);
      if (!icon) return reply.code(404).send({ error: "Pas d'icône" });
      reply.header("Cache-Control", "max-age=86400");
      return reply.type("image/png").send(createReadStream(icon));
    },
  );

  app.post<{ Body: { key: string; action: OpenAction; window: WindowTarget; display?: number; layout?: Layout } }>(
    "/windows/open",
    {
      schema: {
        body: {
          type: "object",
          required: ["key", "action", "window"],
          additionalProperties: false,
          properties: {
            key: { type: "string", maxLength: 1000 },
            action: { enum: ["focus", "place", "minimize", "zoom", "fullscreen", "close"] },
            window: windowTarget,
            display: { type: "integer" },
            layout: { enum: ["keep", "left", "right", "max"] },
          },
        },
      },
    },
    async (req) => {
      const { key, action, window, display, layout } = req.body;
      await controlOpenWindow(key, window, action, action === "place" ? { display, layout: layout ?? "keep" } : undefined);
      return listOpenWindows();
    },
  );
}

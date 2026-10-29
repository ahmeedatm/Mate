import { createReadStream } from "node:fs";
import type { FastifyInstance } from "fastify";
import { appIconFile } from "../deck.ts";
import { controlOpenWindow, listOpenWindows, movePage, showPage } from "../os.ts";
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
  // Une action à la fois : deux gestes rapprochés visaient la même fenêtre en même temps (plein écran animé…).
  let queue: Promise<unknown> = Promise.resolve();
  function oneAtATime<T>(task: () => Promise<T>): Promise<T> {
    const run = queue.then(task, task);
    queue = run.catch(() => {});
    return run;
  }

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

  // `list: false` : réponse dès l'action faite, sans relire la liste (près de 4 s sur Mac) ; la tablette la relit
  // elle-même une fois l'animation finie.
  app.post<{
    Body: { key: string; action: OpenAction; window: WindowTarget; display?: number; layout?: Layout; list?: boolean };
  }>(
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
            list: { type: "boolean" },
          },
        },
      },
    },
    async (req) => {
      const { key, action, window, display, layout, list } = req.body;
      try {
        await oneAtATime(() =>
          controlOpenWindow(key, window, action, action === "place" ? { display, layout: layout ?? "keep" } : undefined),
        );
      } catch (e) {
        // Quelle fenêtre a échoué (sans son titre, qui peut être privé) : pour retrouver le cas dans le journal.
        req.log.warn({ key, action, index: window.index }, "action de fenêtre échouée");
        throw e;
      }
      return list === false ? { ok: true } : listOpenWindows();
    },
  );

  // Mac : change l’ordre des pages (glisser dans Mission Control, ~1 s).
  app.post<{ Body: { page: number; to: number } }>(
    "/windows/page/move",
    {
      schema: {
        body: {
          type: "object",
          required: ["page", "to"],
          additionalProperties: false,
          properties: { page: { type: "integer", minimum: 0 }, to: { type: "integer", minimum: 0, maximum: 50 } },
        },
      },
    },
    async (req) => {
      await oneAtATime(() => movePage(req.body.page, req.body.to));
      return { ok: true };
    },
  );

  // Mac : page du balayage à trois doigts ; réponse dès les touches envoyées, l'animation suit.
  app.post<{ Body: { page: number } }>(
    "/windows/page",
    {
      schema: {
        body: {
          type: "object",
          required: ["page"],
          additionalProperties: false,
          properties: { page: { type: "integer", minimum: 0 } },
        },
      },
    },
    async (req) => {
      await oneAtATime(() => showPage(req.body.page));
      return { ok: true };
    },
  );
}

import { existsSync } from "node:fs";
import fs from "node:fs/promises";
import path from "node:path";
import { createHash } from "node:crypto";
import type { FastifyInstance } from "fastify";
import { config } from "../config.ts";
import { focusDnd } from "../dnd.ts";
import { applyScene, deleteScene, listScenes, saveScene } from "../mixer.ts";
import {
  appAudio,
  getAudio,
  getDnd,
  getPerf,
  getPerfDetail,
  mediaControl,
  mediaSessions,
  mediaThumb,
  mixer,
  openAccessibility,
  setAudio,
  typeText,
} from "../os.ts";

/** Ordinateur : Accessibilité, dictée, son, Ne pas déranger, lecture en cours. */
export default async function systemRoutes(app: FastifyInstance) {
  /** Ouvre sur le Mac le réglage Accessibilité (depuis la tablette, ou la page locale). */
  app.post("/system/accessibility", async () => {
    await openAccessibility();
    return { ok: true };
  });

  /** Dictée de la tablette (« écris : … ») : texte collé dans l'appli au premier plan, comme l'étape `text` du deck. */
  app.post<{ Body: { text: string } }>(
    "/system/type",
    { schema: { body: { type: "object", required: ["text"], properties: { text: { type: "string", minLength: 1, maxLength: 2000 } } } } },
    async (req) => {
      await typeText(req.body.text);
      return { ok: true };
    },
  );

  app.get("/system/audio", async () => getAudio());

  app.post<{ Body: { output?: number; outputDelta?: number; muted?: boolean; micMuted?: boolean } }>(
    "/system/audio",
    {
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          properties: {
            output: { type: "integer", minimum: 0, maximum: 100 },
            outputDelta: { type: "integer", minimum: -100, maximum: 100 },
            muted: { type: "boolean" },
            micMuted: { type: "boolean" },
          },
        },
      },
    },
    async (req) => setAudio(req.body),
  );

  // Processeur, mémoire et carte graphique : relus toutes les 2 s par la tablette tant que le widget est affiché.
  app.get("/system/perf", { logLevel: "warn" }, async () => getPerf());

  // Lecture brute du lecteur de carte graphique, pour diagnostiquer un PC à carte dédiée.
  app.get("/system/perf/detail", async () => getPerfDetail());

  // Ne pas déranger de l'ordinateur pendant le Focus de la tablette (voir src/dnd.ts).
  app.get("/system/dnd", async () => ({ on: await getDnd() }));

  app.post<{ Body: { on: boolean; minutes?: number } }>(
    "/system/dnd",
    {
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          required: ["on"],
          properties: { on: { type: "boolean" }, minutes: { type: "integer", minimum: 1, maximum: 240 } },
        },
      },
    },
    async (req) => focusDnd(req.body.on, req.body.minutes),
  );

  // Son d'une appli (page du deck liée à une appli : Discord…). Lu en boucle par la tablette tant que la page est affichée.
  const appName = { type: "string", minLength: 1, maxLength: 100 } as const;

  app.get<{ Querystring: { name: string } }>(
    "/system/audio/app",
    { logLevel: "warn", schema: { querystring: { type: "object", required: ["name"], properties: { name: appName } } } },
    async (req) => appAudio(req.query.name),
  );

  app.post<{ Body: { name: string; volume?: number; muted?: boolean } }>(
    "/system/audio/app",
    {
      schema: {
        body: {
          type: "object",
          required: ["name"],
          additionalProperties: false,
          properties: { name: appName, volume: { type: "integer", minimum: 0, maximum: 100 }, muted: { type: "boolean" } },
        },
      },
    },
    async (req) => appAudio(req.body.name, { volume: req.body.volume, muted: req.body.muted }),
  );

  // Onglet Son du deck : relu 4 fois par seconde tant qu'il est affiché (niveaux sonores).
  app.get("/system/mixer", { logLevel: "warn" }, async () => mixer());

  app.post<{ Body: { id: string; volume?: number; muted?: boolean } }>(
    "/system/mixer",
    {
      logLevel: "warn",
      schema: {
        body: {
          type: "object",
          required: ["id"],
          additionalProperties: false,
          properties: { id: appName, volume: { type: "integer", minimum: 0, maximum: 100 }, muted: { type: "boolean" } },
        },
      },
    },
    async (req) => mixer(req.body),
  );

  app.get("/system/mixer/scenes", async () => ({ scenes: await listScenes() }));

  app.post<{ Body: { name: string } }>(
    "/system/mixer/scenes",
    {
      schema: {
        body: {
          type: "object",
          required: ["name"],
          additionalProperties: false,
          properties: { name: { type: "string", minLength: 1, maxLength: 30, pattern: "\\S" } },
        },
      },
    },
    async (req) => saveScene(req.body.name),
  );

  app.delete<{ Params: { id: string } }>("/system/mixer/scenes/:id", async (req) => {
    await deleteScene(req.params.id);
    return { ok: true };
  });

  app.post<{ Params: { id: string } }>("/system/mixer/scenes/:id/apply", async (req) => applyScene(req.params.id));

  // Lecture en cours (YouTube, Twitch, Spotify… : contrôles multimédias de Windows). Relue chaque seconde par la
  // tablette tant que la page Média est affichée.
  app.get("/system/media", { logLevel: "warn" }, async () => {
    const sessions = (await mediaSessions()) ?? [];
    // `thumb` : clé de la vignette, change avec le morceau ou la vidéo (la tablette ne la recharge qu'alors).
    return {
      sessions: sessions.map((s) => ({
        ...s,
        thumb: s.hasThumb ? createHash("sha1").update(`${s.app}|${s.title}|${s.artist}`).digest("hex").slice(0, 16) : null,
      })),
    };
  });

  app.post<{ Body: { action: "play-pause" | "next" | "previous" | "seek"; app?: string; positionMs?: number } }>(
    "/system/media",
    {
      schema: {
        body: {
          type: "object",
          required: ["action"],
          additionalProperties: false,
          properties: {
            action: { enum: ["play-pause", "next", "previous", "seek"] },
            app: { type: "string", maxLength: 200 },
            positionMs: { type: "integer", minimum: 0 },
          },
        },
      },
    },
    async (req) => ({ ok: await mediaControl(req.body.action, req.body.app, req.body.positionMs) }),
  );

  app.get<{ Querystring: { app: string; key: string } }>(
    "/system/media/thumb",
    {
      logLevel: "warn",
      schema: {
        querystring: {
          type: "object",
          required: ["app", "key"],
          properties: { app: { type: "string", maxLength: 200 }, key: { type: "string", pattern: "^[0-9a-f]{16}$" } },
        },
      },
    },
    async (req, reply) => {
      const file = path.join(config.cacheDir, "media", req.query.key);
      if (!existsSync(file)) {
        await fs.mkdir(path.dirname(file), { recursive: true });
        if (!(await mediaThumb(req.query.app, file))) return reply.code(404).send({ error: "Pas de vignette" });
      }
      const data = await fs.readFile(file);
      const type = data[0] === 0x89 ? "image/png" : data[0] === 0xff ? "image/jpeg" : "application/octet-stream";
      return reply.type(type).send(data);
    },
  );
}

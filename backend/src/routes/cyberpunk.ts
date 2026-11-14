import type { FastifyInstance, FastifyReply } from "fastify";
import { cyberpunkMap } from "../cyberpunk-map.ts";
import { phoneRoutes } from "../cyberpunk-phone.ts";
import { PhoneRefusal, readThread, replyThread, sendCommand, type Command } from "../cyberpunk-commands.ts";
import { mappins } from "../cyberpunk-poi.ts";
import { addPin, listPins, removePin, replayPins, trackPin } from "../cyberpunk-pins.ts";
import { findGame } from "../cyberpunk-steam.ts";
import { block, installMod, overview, presence, readPos, uninstallMod } from "../cyberpunk.ts";
import { pluginOn } from "../plugins.ts";

/**
 * Plugin Cyberpunk 2077 (Windows) : état de la partie lu dans le mod CET, blocs lents, installation du mod, actions
 * et repères. Plugin coupé : 403 partout, sauf `presence` (la tablette la sonde) qui répond `available: false`.
 */

type Action = {
  type: "call" | "answer" | "hangup" | "summon" | "use" | "track";
  contact?: string;
  tdb?: string;
  kind?: string;
  id?: string;
};

const actionSchema = {
  type: "object",
  required: ["type"],
  additionalProperties: false,
  properties: {
    type: { enum: ["call", "answer", "hangup", "summon", "use", "track"] },
    contact: { type: "string", pattern: "^[a-z0-9_]{1,64}$" },
    tdb: { type: "string", maxLength: 200, pattern: "^Items\\.[A-Za-z0-9_.]+$" },
    kind: { enum: ["quest", "pin"] },
    id: { type: "string", maxLength: 100, pattern: "^[A-Za-z0-9_.:-]+$" },
  },
};

const pinSchema = {
  type: "object",
  required: ["x", "y"],
  additionalProperties: false,
  properties: {
    x: { type: "number", minimum: -8000, maximum: 8000 },
    y: { type: "number", minimum: -8000, maximum: 8000 },
    z: { type: "number", minimum: -2000, maximum: 2000 },
    name: { type: "string", maxLength: 40 },
  },
};

const idParams = { type: "object", properties: { id: { type: "integer", minimum: 1 } } };

/** L'ordre du mod pour une action, ou 400 s'il manque un champ de son type. */
function toCommand(a: Action): Command {
  const need = <T>(v: T | undefined, name: string): T => {
    if (v === undefined) throw Object.assign(new Error(`« ${name} » manque`), { statusCode: 400 });
    return v;
  };
  switch (a.type) {
    case "call":
      return { t: "call", contact: need(a.contact, "contact") };
    case "use":
      return { t: "use", tdb: need(a.tdb, "tdb") };
    case "track":
      return { t: "track", kind: need(a.kind, "kind"), ref: need(a.id, "id") };
    default:
      return { t: a.type };
  }
}

const threadParams = {
  type: "object",
  required: ["hash"],
  properties: { hash: { type: "string", pattern: "^-?[0-9]{1,10}$" } },
};
const replyBody = {
  type: "object",
  required: ["choice", "version"],
  additionalProperties: false,
  properties: {
    choice: { type: "string", pattern: "^-?[0-9]{1,10}$" },
    version: { type: "string", pattern: "^[0-9]{1,4}:-?[0-9]{1,10}$" },
  },
};

/** Un refus du mod devient 409 `{ error, res }` ; les autres erreurs suivent le chemin commun. */
async function withRes<T>(reply: FastifyReply, run: () => Promise<T>) {
  try {
    return await run();
  } catch (e) {
    if (e instanceof PhoneRefusal) return reply.code(409).send({ error: e.message, res: e.res });
    throw e;
  }
}

export default async function cyberpunkRoutes(app: FastifyInstance) {
  app.addHook("preHandler", async (req, reply) => {
    if (req.url.split("?")[0] === "/cyberpunk/presence" || (await pluginOn("cyberpunk"))) return;
    return reply.code(403).send({ error: "Le plugin Cyberpunk 2077 est coupé" });
  });

  // Lire l'état de la partie est ce qui déclenche le rejeu des repères (la tablette sonde la présence toutes les 4 s).
  app.get("/cyberpunk/presence", async () => {
    const res = await presence(await pluginOn("cyberpunk"));
    if (res.inGame) void replayPins();
    return res;
  });
  app.get("/cyberpunk", async () => {
    const res = await overview();
    if (res.status === "ingame") void replayPins();
    return res;
  });
  // Sondée ~4 fois par seconde par la tablette, en vue conduite seulement.
  app.get("/cyberpunk/pos", () => readPos());
  app.get("/cyberpunk/inventory", () => block("inventory"));
  app.get("/cyberpunk/contacts", () => block("contacts"));
  app.get("/cyberpunk/quests", () => block("quests"));
  app.get("/cyberpunk/cyberware", () => block("cyberware"));
  app.get("/cyberpunk/mappins", () => mappins());
  app.post("/cyberpunk/install", () => installMod());
  app.post("/cyberpunk/uninstall", () => uninstallMod());

  app.post<{ Body: Action }>("/cyberpunk/action", { schema: { body: actionSchema } }, (req) => sendCommand(toCommand(req.body)));

  app.get<{ Params: { hash: string } }>("/cyberpunk/threads/:hash", { schema: { params: threadParams } }, (req, reply) =>
    withRes(reply, () => readThread(req.params.hash)),
  );
  app.post<{ Params: { hash: string }; Body: { choice: string; version: string } }>(
    "/cyberpunk/threads/:hash/reply",
    { schema: { params: threadParams, body: replyBody } },
    (req, reply) => withRes(reply, () => replyThread(req.params.hash, req.body.choice, req.body.version)),
  );

  // Appel direct (pas app.register) : le hook « plugin coupé » ci-dessus s'applique aussi au look du téléphone.
  await phoneRoutes(app);

  app.get("/cyberpunk/map", async () => (await cyberpunkMap()).status());
  app.post("/cyberpunk/map/prepare", async () => {
    // Pas de jeu, pas de téléchargement de 700 Mo.
    if (!findGame()) throw Object.assign(new Error("Cyberpunk 2077 (Steam) est introuvable sur cet ordinateur"), { statusCode: 409 });
    const map = await cyberpunkMap();
    await map.prepare();
    return map.status();
  });
  const tileParams = {
    type: "object",
    properties: {
      z: { type: "string", pattern: "^(0|[1-9][0-9]?)$" },
      x: { type: "string", pattern: "^(0|[1-9][0-9]{0,2})$" },
      y: { type: "string", pattern: "^(0|[1-9][0-9]{0,2})$" },
    },
  };
  app.get<{ Params: { z: string; x: string; y: string } }>(
    "/cyberpunk/tiles/:z/:x/:y.webp",
    { schema: { params: tileParams } },
    async (req, reply) => {
      const tile = await (await cyberpunkMap()).tile(Number(req.params.z), Number(req.params.x), Number(req.params.y));
      if (!tile) return reply.code(404).send({ error: "Tuile absente" });
      return reply.header("cache-control", "public, max-age=31536000, immutable").type("image/webp").send(tile);
    },
  );

  app.get("/cyberpunk/pins", () => listPins());
  app.post<{ Body: { x: number; y: number; z?: number; name?: string } }>("/cyberpunk/pins", { schema: { body: pinSchema } }, (req) =>
    addPin(req.body),
  );
  app.delete<{ Params: { id: number } }>("/cyberpunk/pins/:id", { schema: { params: idParams } }, (req) => removePin(req.params.id));
  app.post<{ Params: { id: number } }>("/cyberpunk/pins/:id/track", { schema: { params: idParams } }, (req) => trackPin(req.params.id));
}

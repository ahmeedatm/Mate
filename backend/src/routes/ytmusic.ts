import type { FastifyInstance } from "fastify";
import { lyricsFor } from "../lyrics.ts";
import { isInstalled, launchApp, openTarget } from "../os.ts";
import { connectYtmd, forgetYtmd, sendYtmd, ytmdState } from "../ytmdesktop.ts";
import { MAX_ITEMS, receiveYtMusic, sendYtMusic, YT_MUSIC_OPS, ytMusic, type YtMusicAction, type YtMusicReport } from "../ytmusic.ts";

/** Noms de YTMDesktop dans le menu Démarrer (Windows) ou les Applications (Mac). */
const YTMD_APPS = ["YouTube Music Desktop App", "YouTube Music"];

/** Une pochette en 544 px pèse environ 60 Ko ; au-delà de 2 Mo, ce n'est pas une pochette. */
const COVER_MAX = 2 * 1024 * 1024;

const itemSchema = {
  type: "object",
  required: ["title"],
  properties: {
    title: { type: "string", maxLength: 500 },
    subtitle: { type: "string", maxLength: 500 },
    cover: { type: ["string", "null"], maxLength: 2000 },
  },
};

export default async function ytMusicRoutes(app: FastifyInstance) {
  /** État de l'onglet YouTube Music, envoyé par l'extension « Mate » du navigateur (en local, sans jeton). */
  app.post<{ Body: YtMusicReport }>(
    "/browser/extension/ytmusic",
    {
      logLevel: "warn",
      schema: {
        body: {
          type: "object",
          required: ["browser", "state"],
          properties: {
            browser: { type: "string", maxLength: 40 },
            state: {
              type: "object",
              required: ["playing", "track", "repeat", "queue", "query", "results"],
              properties: {
                playing: { type: "boolean" },
                track: {
                  type: ["object", "null"],
                  required: ["title", "like"],
                  properties: {
                    title: { type: "string", maxLength: 500 },
                    artist: { type: "string", maxLength: 500 },
                    album: { type: "string", maxLength: 500 },
                    cover: { type: ["string", "null"], maxLength: 2000 },
                    position: { type: "number" },
                    duration: { type: "number" },
                    like: { enum: ["like", "dislike", "none"] },
                  },
                },
                repeat: { enum: ["none", "all", "one", null] },
                queue: {
                  type: "array",
                  maxItems: 500,
                  items: { ...itemSchema, properties: { ...itemSchema.properties, current: { type: "boolean" } } },
                },
                query: { type: ["string", "null"], maxLength: 500 },
                results: { type: "array", maxItems: 500, items: itemSchema },
              },
            },
          },
        },
      },
    },
    async (req, reply) => {
      // Seule une extension (pas une page web ouverte sur l'ordinateur) peut annoncer un onglet.
      if (!/^(chrome|moz)-extension:\/\//.test(req.headers.origin ?? ""))
        return reply.code(403).send({ error: "Réservé à l'extension du navigateur" });
      receiveYtMusic(req.body);
      return { ok: true };
    },
  );

  /**
   * Ce que joue YouTube Music sur l'ordinateur : l'appli YTMDesktop si elle est connectée et répond, sinon l'onglet du
   * navigateur. `open: false` = ni l'un ni l'autre ; `desktop` = où en est la connexion à YTMDesktop.
   */
  app.get("/ytmusic", async () => {
    const desktop = await ytmdState();
    if (desktop.state) return { open: true, source: "desktop", desktop: desktop.status, ...desktop.state };
    const tab = ytMusic();
    return tab
      ? { open: true, source: "browser", browser: tab.browser, desktop: desktop.status, ...tab.state }
      : { open: false, desktop: desktop.status };
  });

  /**
   * Pochette relayée pour la tablette (elle ne parle qu'à son ordinateur, sur sa connexion épinglée) : seulement une
   * adresse de l'état en cours (titre joué, file, résultats), jamais une adresse quelconque.
   */
  app.get<{ Querystring: { url: string } }>(
    "/ytmusic/cover",
    { schema: { querystring: { type: "object", required: ["url"], properties: { url: { type: "string", maxLength: 2000 } } } } },
    async (req, reply) => {
      const state = (await ytmdState()).state ?? ytMusic()?.state;
      const known = state && [state.track?.cover, ...state.queue.map((q) => q.cover), ...state.results.map((r) => r.cover)];
      if (!known?.includes(req.query.url)) return reply.code(404).send({ error: "Pochette inconnue" });
      const res = await fetch(req.query.url, { signal: AbortSignal.timeout(8_000) }).catch(() => undefined);
      const type = res?.headers.get("content-type") ?? "";
      const size = Number(res?.headers.get("content-length") ?? 0);
      if (!res?.ok || !type.startsWith("image/") || size > COVER_MAX) return reply.code(502).send({ error: "Pochette indisponible" });
      const bytes = Buffer.from(await res.arrayBuffer());
      if (bytes.length > COVER_MAX) return reply.code(502).send({ error: "Pochette indisponible" });
      return reply.type(type).header("cache-control", "max-age=3600").send(bytes);
    },
  );

  /** Paroles du titre en cours (LRCLIB), synchronisées si possible ; `found: false` si LRCLIB ne les a pas. */
  app.get("/ytmusic/lyrics", async (req, reply) => {
    const track = ((await ytmdState()).state ?? ytMusic()?.state)?.track;
    if (!track) return { found: false };
    try {
      const lyrics = await lyricsFor(track);
      return lyrics ? { found: true, title: track.title, source: "LRCLIB", ...lyrics } : { found: false, title: track.title };
    } catch (e) {
      req.log.warn({ err: String(e) }, "paroles : LRCLIB injoignable");
      return reply.code(502).send({ error: "Paroles indisponibles pour l'instant : réessaie dans un moment" });
    }
  });

  /**
   * Ouvre YouTube Music sur l'ordinateur : l'appli YTMDesktop si elle est reliée ou installée (mise devant si elle
   * tourne), sinon le site dans le navigateur.
   */
  app.post("/ytmusic/open", async () => {
    const desktop = await ytmdState();
    if (desktop.status.status === "connected" || !ytMusic()) {
      for (const name of YTMD_APPS) {
        if (!(await isInstalled(name).catch(() => false))) continue;
        await launchApp(name);
        return { opened: "app", app: name };
      }
    }
    await openTarget("https://music.youtube.com/");
    return { opened: "site" };
  });

  /** Connexion à YTMDesktop : renvoie le code à comparer, l'accord se donne dans YTMDesktop (suivi par GET /ytmusic). */
  app.post("/ytmusic/desktop/connect", async () => ({ desktop: await connectYtmd() }));

  app.delete("/ytmusic/desktop", async () => {
    await forgetYtmd();
    return { ok: true };
  });

  /** Une action de la liste fermée, exécutée par l'extension dans l'onglet. */
  app.post<{ Body: YtMusicAction }>(
    "/ytmusic/action",
    {
      schema: {
        body: {
          type: "object",
          required: ["op"],
          additionalProperties: false,
          properties: {
            op: { enum: YT_MUSIC_OPS },
            index: { type: "integer", minimum: 0, maximum: MAX_ITEMS - 1 },
            query: { type: "string", minLength: 1, maxLength: 200 },
            seconds: { type: "number", minimum: 0 },
          },
        },
      },
    },
    async (req, reply) => {
      const action = req.body;
      const desktop = await ytmdState();
      if (desktop.state) {
        if (action.op === "play-queue" && (action.index === undefined || action.index >= desktop.state.queue.length))
          return reply.code(400).send({ error: "Ce titre n'est plus dans la liste : actualise" });
        if (action.op === "seek" && action.seconds === undefined) return reply.code(400).send({ error: "Position manquante" });
        const error = await sendYtmd(action);
        return error ? reply.code(409).send({ error }) : { ok: true };
      }
      const tab = ytMusic();
      const list = action.op === "play-queue" ? tab?.state.queue : action.op === "play-result" ? tab?.state.results : undefined;
      if (list && (action.index === undefined || action.index >= list.length))
        return reply.code(400).send({ error: "Ce titre n'est plus dans la liste : actualise" });
      if (action.op === "search" && !action.query?.trim()) return reply.code(400).send({ error: "Recherche vide" });
      if (action.op === "seek" && action.seconds === undefined) return reply.code(400).send({ error: "Position manquante" });
      if (!sendYtMusic(action))
        return reply.code(409).send({
          error: tab
            ? "L'extension du navigateur ne répond pas : recharge-la"
            : "Ouvre YouTube Music dans le navigateur de l'ordinateur (avec l'extension Mate)",
        });
      return { ok: true };
    },
  );
}

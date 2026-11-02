import path from "node:path";
import { config } from "./config.ts";
import { readJson, removeJson, writeJson } from "./store.ts";
import type { YtMusicAction, YtMusicItem, YtMusicState } from "./ytmusic.ts";

/**
 * YouTube Music par l'appli de bureau YTMDesktop 2 (ytmdesktop/ytmdesktop) : son « Companion Server »
 * (http://127.0.0.1:9863/api/v1, réglages > Integrations) donne l'état en temps réel et accepte des commandes.
 * Autorisation une fois : code à 4 chiffres affiché des deux côtés, l'utilisateur accepte dans YTMDesktop ; le jeton
 * reste sur l'ordinateur (`.data/ytmdesktop.json`, 600), jamais envoyé à la tablette.
 */

const BASE = (process.env.YTMD_URL ?? "http://127.0.0.1:9863").replace(/\/$/, "") + "/api/v1";
const APP = { appId: "mate", appName: "Mate", appVersion: "1.0.0" };
const storeFile = () => path.join(config.dataDir, "ytmdesktop.json");

export type DesktopStatus =
  { status: "off" } | { status: "connecting"; code: string } | { status: "connected" } | { status: "error"; error: string };

let status: DesktopStatus = { status: "off" };
let token: string | undefined;
let state: YtMusicState | undefined;
let stateAt = 0;

// --- Traduction de l'état de YTMDesktop (transformPlayerState de son api/v1) vers celui de la tablette.

type Thumb = { url: string; width: number };
type YtmdQueueItem = { title: string; author: string; thumbnails: Thumb[]; selected: boolean } | null;
export type YtmdState = {
  player: {
    trackState: number;
    videoProgress: number;
    adPlaying: boolean;
    queue: { items: YtmdQueueItem[]; repeatMode: number; selectedItemIndex: number } | null;
  };
  video: { title: string; author: string; album: string | null; thumbnails: Thumb[]; durationSeconds: number; likeStatus: number } | null;
};

const biggest = (thumbs: Thumb[] | undefined) => [...(thumbs ?? [])].sort((a, b) => b.width - a.width)[0]?.url ?? null;
const REPEAT = ["none", "all", "one"] as const;
const LIKE: Record<number, "like" | "dislike" | "none"> = { 0: "dislike", 2: "like" };

export function fromYtmd(s: YtmdState): YtMusicState {
  const v = s.video;
  const q = s.player.queue;
  return {
    // 1 = lecture, 2 = chargement (le son reprend aussitôt) ; 0 = pause, -1 = inconnu.
    playing: s.player.trackState === 1 || s.player.trackState === 2,
    track: v && {
      title: v.title,
      artist: v.author,
      album: v.album ?? "",
      cover: biggest(v.thumbnails),
      position: s.player.videoProgress,
      duration: v.durationSeconds,
      like: LIKE[v.likeStatus] ?? "none",
    },
    repeat: REPEAT[q?.repeatMode ?? -1] ?? null,
    queue: (q?.items ?? []).flatMap((item, i): (YtMusicItem & { current: boolean })[] =>
      item
        ? [
            {
              title: item.title,
              subtitle: item.author,
              cover: biggest(item.thumbnails),
              current: item.selected || i === q?.selectedItemIndex,
            },
          ]
        : [],
    ),
    query: null,
    results: [],
  };
}

// --- Commandes : la liste fermée de la tablette traduite en commandes de YTMDesktop (sa route /command).

export function ytmdCommand(action: YtMusicAction, current?: YtMusicState): { command: string; data?: unknown } | undefined {
  switch (action.op) {
    case "play-pause":
      return { command: "playPause" };
    case "next":
      return { command: "next" };
    case "previous":
      return { command: "previous" };
    case "seek":
      return { command: "seekTo", data: action.seconds ?? 0 };
    case "like":
      return { command: "toggleLike" };
    case "dislike":
      return { command: "toggleDislike" };
    case "shuffle":
      return { command: "shuffle" };
    case "repeat": {
      // Aucune → toute la file → ce titre → aucune, comme le bouton de YouTube Music.
      const now = Math.max(0, REPEAT.indexOf(current?.repeat ?? "none"));
      return { command: "repeatMode", data: (now + 1) % 3 };
    }
    case "play-queue":
      return { command: "playQueueIndex", data: action.index ?? 0 };
    default:
      // Recherche : pas dans l'API de YTMDesktop.
      return undefined;
  }
}

/** Erreur de YTMDesktop traduite pour la tablette (ses codes : AUTHORIZATION_DISABLED, AUTHORIZATION_DENIED…). */
function ytmdError(code: string | undefined, fallback: string) {
  if (code === "AUTHORIZATION_DISABLED")
    return "Dans YTMDesktop, Réglages > Integrations : active « Companion server » et « Enable companion authorization »";
  if (code === "AUTHORIZATION_DENIED") return "Accès refusé dans YTMDesktop";
  if (code === "AUTHORIZATION_TIME_OUT") return "Pas de réponse dans YTMDesktop : recommence";
  return fallback;
}

async function call(route: string, init: { method?: string; body?: unknown; timeoutMs?: number } = {}) {
  const res = await fetch(BASE + route, {
    method: init.method ?? (init.body === undefined ? "GET" : "POST"),
    headers: { ...(init.body !== undefined && { "content-type": "application/json" }), ...(token && { authorization: token }) },
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
    signal: AbortSignal.timeout(init.timeoutMs ?? 5_000),
  });
  const text = await res.text();
  const json = text ? (JSON.parse(text) as Record<string, unknown>) : {};
  return { status: res.status, json };
}

// --- Temps réel : Socket.IO (Engine.IO 4) sur le WebSocket de Node, sans dépendance. Trames utilisées :
// « 0{…} » ouverture, « 2 » ping (réponse « 3 »), « 40/ns,{auth} » connexion au namespace, « 44/ns,{…} » refus,
// « 42/ns,["state-update",{…}] » évènement.

const NAMESPACE = "/api/v1/realtime";

export function parseSocketFrame(frame: string): {
  type: "open" | "ping" | "connected" | "refused" | "event" | "other";
  event?: string;
  data?: unknown;
} {
  if (frame.startsWith("0")) return { type: "open" };
  if (frame === "2") return { type: "ping" };
  const m = /^4([0-4])(?:(\/[^,]*),)?(.*)$/s.exec(frame);
  if (!m || (m[2] ?? "/") !== NAMESPACE) return { type: "other" };
  if (m[1] === "0") return { type: "connected" };
  if (m[1] === "4") return { type: "refused", data: m[3] ? JSON.parse(m[3]) : undefined };
  if (m[1] === "2") {
    const [event, data] = JSON.parse(m[3]) as [string, unknown];
    return { type: "event", event, data };
  }
  return { type: "other" };
}

let socket: WebSocket | undefined;
let retry: NodeJS.Timeout | undefined;

function connectRealtime() {
  if (!token || socket || typeof WebSocket === "undefined") return;
  clearTimeout(retry);
  const ws = new WebSocket(BASE.replace(/^http/, "ws").replace(/\/api\/v1$/, "") + "/socket.io/?EIO=4&transport=websocket");
  socket = ws;
  ws.onmessage = (e) => {
    const frame = parseSocketFrame(String(e.data));
    if (frame.type === "open") ws.send(`40${NAMESPACE},${JSON.stringify({ token })}`);
    else if (frame.type === "ping") ws.send("3");
    else if (frame.type === "refused") {
      // Jeton retiré dans YTMDesktop : on l'oublie, la tablette repropose de connecter.
      void forgetYtmd();
      status = { status: "error", error: "YTMDesktop a retiré l'accès de Mate : reconnecte-le" };
    } else if (frame.type === "event" && frame.event === "state-update") {
      state = fromYtmd(frame.data as YtmdState);
      stateAt = Date.now();
    }
  };
  ws.onclose = () => {
    if (socket === ws) socket = undefined;
    // YTMDesktop fermé ou relancé : nouvel essai dans 5 s tant que le jeton est là.
    if (token) retry = setTimeout(connectRealtime, 5_000);
  };
  ws.onerror = () => ws.close();
}

let loaded = false;
async function load() {
  if (loaded) return;
  loaded = true;
  token = (await readJson<{ token?: string }>(storeFile(), () => ({}))).token;
  if (token) {
    status = { status: "connected" };
    connectRealtime();
  }
}

/** État en temps réel de YTMDesktop, s'il est connecté et a parlé dans les 30 dernières secondes. */
export async function ytmdState(): Promise<{ status: DesktopStatus; state?: YtMusicState }> {
  await load();
  const fresh = state && Date.now() - stateAt < 30_000 ? state : undefined;
  // Pas d'évènement depuis un moment (rien ne joue) : l'état complet, au plus une fois par 5 s (limite de YTMDesktop).
  if (token && !fresh && Date.now() - stateAt > 5_000) {
    stateAt = Date.now();
    const res = await call("/state").catch(() => undefined);
    if (res?.status === 200) state = fromYtmd(res.json as unknown as YtmdState);
    return { status, state: res?.status === 200 ? state : undefined };
  }
  return { status, state: fresh };
}

/** Commande vers YTMDesktop ; message d'erreur pour la tablette si elle n'est pas passée. */
export async function sendYtmd(action: YtMusicAction): Promise<string | undefined> {
  await load();
  if (!token) return "YTMDesktop n'est pas connecté";
  const command = ytmdCommand(action, state);
  if (!command) return "La recherche n'est pas possible avec YTMDesktop";
  const res = await call("/command", { body: command }).catch(() => undefined);
  if (!res) return "YTMDesktop ne répond pas : est-il ouvert ?";
  if (res.status === 401 || res.status === 403) {
    await forgetYtmd();
    return "YTMDesktop a retiré l'accès de Mate : reconnecte-le";
  }
  return res.status < 300 ? undefined : "YTMDesktop a refusé la commande";
}

/**
 * Autorisation : code demandé (affiché sur la tablette et dans YTMDesktop), puis attente de l'accord de
 * l'utilisateur dans YTMDesktop, en arrière-plan. Le statut dit où on en est.
 */
export async function connectYtmd(): Promise<DesktopStatus> {
  await load();
  token = undefined;
  const code = await call("/auth/requestcode", { body: APP }).catch(() => undefined);
  if (!code)
    return (status = { status: "error", error: "YTMDesktop ne répond pas : ouvre-le et active « Companion server » dans ses réglages" });
  if (code.status !== 200 || typeof code.json.code !== "string")
    return (status = { status: "error", error: ytmdError(code.json.code as string | undefined, "YTMDesktop a refusé la demande") });
  status = { status: "connecting", code: code.json.code };
  void (async () => {
    // YTMDesktop laisse 30 s à l'utilisateur pour accepter.
    const res = await call("/auth/request", { body: { appId: APP.appId, code: code.json.code }, timeoutMs: 40_000 }).catch(() => undefined);
    if (res?.status === 200 && typeof res.json.token === "string") {
      token = res.json.token;
      await writeJson(storeFile(), { token }, { mode: 0o600 });
      status = { status: "connected" };
      connectRealtime();
    } else {
      status = { status: "error", error: ytmdError(res?.json.code as string | undefined, "Pas de réponse dans YTMDesktop : recommence") };
    }
  })();
  return status;
}

export async function forgetYtmd() {
  token = undefined;
  state = undefined;
  status = { status: "off" };
  socket?.close();
  await removeJson(storeFile());
}

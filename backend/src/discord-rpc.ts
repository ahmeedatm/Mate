import {
  STREAMKIT_CLIENT_ID,
  STREAMKIT_ORIGIN,
  exchangeDiscordCode,
  forgetDiscordToken,
  loadDiscordToken,
  saveDiscordToken,
} from "./discord-auth.ts";
import { logFailure } from "./log.ts";

/**
 * Connexion au RPC local de l'appli Discord de l'ordinateur (WebSocket sur 127.0.0.1, ports 6463 à 6472) : participants
 * de l'appel en cours, leur photo et qui parle. Lecture seule : rien n'est piloté par ici (micro, sourdine et « quitter »
 * restent les boutons lus dans la fenêtre), aucun message n'est lu. La connexion reste ouverte tant qu'un jeton existe et
 * se rétablit seule (délai croissant) quand Discord redémarre ; sans jeton, rien ne se connecte : la fenêtre « Autoriser »
 * de Discord ne s'ouvre que sur le geste de la tablette (authorizeDiscord). Repli : sans RPC, l'état lu dans la fenêtre.
 */

export type DiscordMember = { id: string; name: string; avatar: boolean; speaking: boolean };
/** `off` : pas de photos (pas autorisé, Discord fermé) ; `waiting` : fenêtre « Autoriser » ouverte dans Discord. */
export type DiscordPhotos = "off" | "waiting" | "on";

/** Message pour la tablette (affiché tel quel). */
export class DiscordError extends Error {
  statusCode = 409;
}

const UNREACHABLE = "Discord ne répond pas : ouvre l'appli Discord de l'ordinateur";
const REFUSED = "Accès refusé dans Discord : les photos restent masquées";
const TIMEOUT = "Pas de réponse dans Discord : recommence et clique sur Autoriser";
const NO_TOKEN = "Discord n'a pas donné l'accès : recommence";
const CLOSED = "Discord s'est fermé pendant la demande : recommence";

const OPEN_TIMEOUT_MS = 2_000;
const MAX_RETRY_MS = 60_000;
/** Jeton de 7 jours : renouvelé en silence quand il lui reste moins d'un jour. */
const RENEW_BEFORE_MS = 86_400_000;
const CHANNEL_EVENTS = ["VOICE_STATE_CREATE", "VOICE_STATE_UPDATE", "VOICE_STATE_DELETE", "SPEAKING_START", "SPEAKING_STOP"];
const SNOWFLAKE = /^\d{1,25}$/;
/** Plafond de participants gardés (Discord permet 99 par salon ; la tablette en montre une dizaine au plus). */
const MAX_MEMBERS = 50;
/** Caractères invisibles ou de contrôle (sens d'écriture inversé…) retirés des noms. */
const CONTROL_CHARS = /\p{C}/gu;
const AVATAR_HASH = /^(a_)?[0-9a-f]{32}$/;

/** DISCORD_RPC_PORTS : « 6463-6472 » ou liste « 6463,6464 » (faux Discord des tests). */
function ports(): number[] {
  const spec = process.env.DISCORD_RPC_PORTS?.trim() || "6463-6472";
  return spec.split(",").flatMap((part) => {
    const [from, to = from] = part.split("-").map(Number);
    return Number.isInteger(from) && Number.isInteger(to) ? Array.from({ length: Math.max(0, to - from + 1) }, (_, i) => from + i) : [];
  });
}
const requestTimeoutMs = () => Number(process.env.DISCORD_RPC_REQUEST_TIMEOUT_MS) || 10_000;
const retryBaseMs = () => Number(process.env.DISCORD_RPC_RETRY_MS) || 2_000;
const authorizeTimeoutMs = () => Number(process.env.DISCORD_AUTHORIZE_TIMEOUT_MS) || 60_000;
const cdnUrl = () => (process.env.DISCORD_CDN_URL?.trim() || "https://cdn.discordapp.com").replace(/\/+$/, "");

/** `local` : pas une réponse de Discord, mais un délai dépassé ou une connexion fermée (le jeton n'est pas en cause). */
type Frame = { cmd?: string; evt?: string | null; nonce?: string | null; data?: any; local?: boolean };
type Member = { name: string; avatar?: string; speaking: boolean };
type Deferred = { promise: Promise<void>; resolve: () => void; reject: (e: Error) => void; timer: NodeJS.Timeout };

let running = false;
let hasToken = false;
let connecting = false;
let socket: WebSocket | undefined;
/** READY reçu sur `socket` : Discord accepte les demandes. */
let ready = false;
let authed = false;
let selfId: string | undefined;
let channelId: string | undefined;
let members = new Map<string, Member>();
let retry: NodeJS.Timeout | undefined;
let retryMs = retryBaseMs();
/** Demande de la tablette en attente ; `authorizeSent` : la fenêtre « Autoriser » est déjà ouverte dans Discord. */
let authorizing: Deferred | undefined;
let authorizeSent = false;
let nonce = 0;
const pending = new Map<string, (frame: Frame) => void>();

// --- Lecture (tablette)

export const discordPhotos = (): DiscordPhotos => (authed ? "on" : authorizing ? "waiting" : "off");

/** Participants de l'appel en cours, sans soi, dans l'ordre d'arrivée ; undefined hors RPC ou hors appel. */
export function discordMembers(): DiscordMember[] | undefined {
  if (!authed || !channelId) return undefined;
  return [...members].map(([id, m]) => ({ id, name: m.name, avatar: !!m.avatar, speaking: m.speaking }));
}

export const discordSpeaking = (): string[] => [...members].filter(([, m]) => m.speaking).map(([id]) => id);

/** Photo 64 px d'un participant de l'appel en cours ; le hash vient de Discord, jamais de la tablette. */
export function discordAvatarUrl(id: string): string | undefined {
  const hash = members.get(id)?.avatar;
  return hash ? `${cdnUrl()}/avatars/${id}/${hash}.png?size=64` : undefined;
}

/** État vocal lu dans la fenêtre, complété par le RPC : `photos` toujours, `members` pendant un appel. */
export function withDiscordMembers<T extends { connected: boolean }>(voice: T | null) {
  if (!voice) return null;
  const list = voice.connected ? discordMembers() : undefined;
  return { ...voice, photos: discordPhotos(), ...(list ? { members: list } : {}) };
}

// --- Cycle de vie

/** Au démarrage du compagnon : se connecte seulement si Discord a déjà été autorisé. */
export async function startDiscordRpc() {
  if (running) return;
  hasToken = !!(await loadDiscordToken());
  if (!hasToken || running) return;
  running = true;
  void connect();
}

export function stopDiscordRpc() {
  running = false;
  // Relu par startDiscordRpc.
  hasToken = false;
  clearTimeout(retry);
  retry = undefined;
  finishAuthorize(new DiscordError(CLOSED));
  const ws = socket;
  reset();
  ws?.close();
  retryMs = retryBaseMs();
}

/**
 * Geste « Montrer les photos » : ouvre la fenêtre « Autoriser » dans Discord (une seule à la fois) et attend le clic,
 * 60 s au plus. Après ce délai, la demande est refermée (expireAuthorize) : un clic tardif n'autorise rien.
 */
export function authorizeDiscord(): Promise<void> {
  if (authed) return Promise.resolve();
  if (authorizing) return authorizing.promise;
  let resolve!: () => void;
  let reject!: (e: Error) => void;
  const promise = new Promise<void>((ok, ko) => ((resolve = ok), (reject = ko)));
  // Rejet traité ici aussi : personne n'attend plus la demande si la tablette est partie.
  promise.catch(() => {});
  const timer = setTimeout(expireAuthorize, authorizeTimeoutMs());
  authorizing = { promise, resolve, reject, timer };
  running = true;
  if (!socket) void connect();
  else if (ready && !authorizeSent) void runAuthorize(socket).catch((e) => failed(socket, e));
  return promise;
}

/**
 * Pas de clic dans le délai : la demande est refermée (Discord l'annonce annulée), pour qu'un clic tardif n'autorise
 * pas sans nouveau geste. Avec un jeton déjà là, la connexion sert encore : rien à fermer.
 */
function expireAuthorize() {
  finishAuthorize(new DiscordError(TIMEOUT));
  if (authed) return;
  // Sans jeton, plus de connexion ; avec un jeton (reconnexion en cours), la reconnexion reprend sans la demande.
  running = hasToken;
  socket?.close();
}

function finishAuthorize(error?: Error) {
  const done = authorizing;
  if (!done) return;
  authorizing = undefined;
  clearTimeout(done.timer);
  if (error) done.reject(error);
  else done.resolve();
}

function reset() {
  socket = undefined;
  ready = false;
  authed = false;
  selfId = undefined;
  channelId = undefined;
  members = new Map();
  authorizeSent = false;
  for (const answer of [...pending.values()]) answer({ evt: "ERROR", data: { message: "closed" }, local: true });
}

function scheduleRetry() {
  if (!running || !hasToken) {
    running = !!authorizing;
    return;
  }
  clearTimeout(retry);
  retry = setTimeout(() => void connect(), retryMs);
  retry.unref();
  retryMs = Math.min(retryMs * 2, MAX_RETRY_MS);
}

// --- Connexion

async function connect() {
  if (socket || connecting || !running) return;
  connecting = true;
  const ws = await openSocket().finally(() => (connecting = false));
  if (!ws) {
    finishAuthorize(new DiscordError(UNREACHABLE));
    return scheduleRetry();
  }
  if (!running || socket) return ws.close();
  socket = ws;
  ws.onmessage = (e) => onMessage(ws, String(e.data));
  ws.onclose = () => onClosed(ws);
  // Un port qui accepte puis se tait (pas Discord) ne doit pas bloquer la reconnexion au vrai.
  setTimeout(() => {
    if (socket === ws && !ready) ws.close();
  }, requestTimeoutMs()).unref();
}

/** Premier port où Discord répond. Origine de StreamKit exigée par Discord (sinon fermeture 4001 « Invalid Origin »). */
async function openSocket(): Promise<WebSocket | undefined> {
  for (const port of ports()) {
    const ws = await new Promise<WebSocket | undefined>((resolve) => {
      let ws: WebSocket;
      try {
        // `headers` : option du WebSocket de Node (undici), absente du type du navigateur.
        ws = new WebSocket(`ws://127.0.0.1:${port}/?v=1&client_id=${STREAMKIT_CLIENT_ID}&encoding=json`, {
          headers: { Origin: STREAMKIT_ORIGIN },
        } as unknown as string[]);
      } catch {
        return resolve(undefined);
      }
      const timer = setTimeout(() => {
        resolve(undefined);
        ws.close();
      }, OPEN_TIMEOUT_MS);
      ws.onopen = () => (clearTimeout(timer), resolve(ws));
      // Pas de ws.close() dans onerror : sous Node 22 il relance onerror sans fin (voir ytmdesktop.ts).
      ws.onerror = () => (clearTimeout(timer), resolve(undefined));
    });
    if (ws) return ws;
  }
  return undefined;
}

/** Échec inattendu (disque plein en gardant le jeton…) : journal, puis connexion refermée pour repartir proprement. */
function failed(ws: WebSocket | undefined, e: unknown) {
  logFailure("Discord RPC", e);
  ws?.close();
}

function onClosed(ws: WebSocket) {
  if (socket !== ws) return;
  reset();
  if (authorizing) finishAuthorize(new DiscordError(CLOSED));
  scheduleRetry();
}

/** Trame reçue ; tout ce qui n'a pas la forme attendue est ignoré : rien ne doit lever dans le gestionnaire du WebSocket. */
function onMessage(ws: WebSocket, text: string) {
  try {
    const frame = JSON.parse(text) as Frame;
    if (!frame || typeof frame !== "object" || Array.isArray(frame)) return;
    const answer = typeof frame.nonce === "string" ? pending.get(frame.nonce) : undefined;
    if (answer) return answer(frame);
    if (frame.cmd !== "DISPATCH") return;
    if (frame.evt === "READY") void onReady(ws, frame.data).catch((e) => failed(ws, e));
    else if (authed && typeof frame.evt === "string" && frame.data && typeof frame.data === "object") onEvent(ws, frame.evt, frame.data);
  } catch (e) {
    logFailure("Discord RPC", e);
  }
}

/** Demande RPC ; la réponse porte le même `nonce`. `timeoutMs` 0 : attendre (clic de l'utilisateur). */
function request(ws: WebSocket, cmd: string, args: object, evt?: string, timeoutMs = requestTimeoutMs()): Promise<Frame> {
  const id = String(++nonce);
  return new Promise((resolve) => {
    const timer = timeoutMs ? setTimeout(() => done({ evt: "ERROR", data: { message: "timeout" }, local: true }), timeoutMs) : undefined;
    const done = (frame: Frame) => {
      clearTimeout(timer);
      pending.delete(id);
      resolve(frame);
    };
    pending.set(id, done);
    try {
      ws.send(JSON.stringify({ cmd, args, evt, nonce: id }));
    } catch {
      done({ evt: "ERROR", data: { message: "closed" }, local: true });
    }
  });
}

// --- Autorisation

/**
 * READY de Discord. Limite connue : un autre programme de l'ordinateur peut écouter sur 6463 avant Discord et recevoir
 * le jeton (scope `rpc`, 7 jours). Un tel programme lit déjà les fichiers de Discord, dont son jeton complet : on se
 * contente d'écarter ce qui ne se présente pas comme Discord.
 */
async function onReady(ws: WebSocket, data: any) {
  if (socket !== ws) return;
  if (
    data?.v !== 1 ||
    typeof data?.config?.api_endpoint !== "string" ||
    !/^\/\/(\w+\.)?discord\.com\/api$/.test(data.config.api_endpoint)
  ) {
    logFailure("Discord RPC", new Error("le port local ne répond pas comme Discord"));
    return ws.close();
  }
  ready = true;
  if (authorizing) return runAuthorize(ws).catch((e) => failed(ws, e));
  const token = await loadDiscordToken();
  if (!token) {
    hasToken = false;
    running = false;
    return ws.close();
  }
  await authenticate(ws, token, true);
}

async function runAuthorize(ws: WebSocket) {
  authorizeSent = true;
  const res = await request(ws, "AUTHORIZE", { client_id: STREAMKIT_CLIENT_ID, scopes: ["rpc"] }, undefined, 0);
  authorizeSent = false;
  if (socket !== ws) return;
  const code = res.evt === "ERROR" ? undefined : res.data?.code;
  const token = typeof code === "string" ? await exchangeDiscordCode(code) : undefined;
  if (!token) {
    finishAuthorize(new DiscordError(typeof code === "string" ? NO_TOKEN : REFUSED));
    if (!hasToken) {
      running = false;
      ws.close();
    }
    return;
  }
  await saveDiscordToken(token);
  hasToken = true;
  await authenticate(ws, token, false);
  finishAuthorize(authed ? undefined : new DiscordError(NO_TOKEN));
  // Jeton rendu mais refusé : aucune connexion sans jeton.
  if (!authed && !hasToken) {
    running = false;
    ws.close();
  }
}

/**
 * Renouvellement sans fenêtre (Discord ne l'ouvre pas pour une appli déjà autorisée) ; nouveau jeton gardé. `local` :
 * Discord n'a pas répondu (délai, fermeture), le refus n'est pas établi.
 */
async function renew(ws: WebSocket): Promise<{ token?: string; local?: boolean }> {
  const res = await request(ws, "AUTHORIZE", { client_id: STREAMKIT_CLIENT_ID, scopes: ["rpc"], prompt: "none" });
  if (res.local) return { local: true };
  const code = res.evt === "ERROR" ? undefined : res.data?.code;
  const token = typeof code === "string" ? await exchangeDiscordCode(code) : undefined;
  if (token) await saveDiscordToken(token);
  return { token };
}

async function authenticate(ws: WebSocket, token: string, mayRenew: boolean) {
  const res = await request(ws, "AUTHENTICATE", { access_token: token });
  if (socket !== ws) return;
  // Discord lent ou parti : on garde le jeton, la reconnexion réessaiera.
  if (res.local) return ws.close();
  if (res.evt === "ERROR") {
    const renewed = mayRenew ? await renew(ws) : {};
    if (renewed.local) return ws.close();
    if (renewed.token) return authenticate(ws, renewed.token, false);
    // Jeton expiré ou accès retiré dans Discord : on l'oublie, la tablette repropose « Montrer les photos ».
    await forgetDiscordToken();
    hasToken = false;
    running = !!authorizing;
    if (!authorizing) ws.close();
    return;
  }
  authed = true;
  retryMs = retryBaseMs();
  selfId = typeof res.data?.user?.id === "string" ? res.data.user.id : undefined;
  const expires = Date.parse(res.data?.expires ?? "");
  if (Number.isFinite(expires) && expires - Date.now() < RENEW_BEFORE_MS) void renew(ws).catch((e) => logFailure("Discord RPC", e));
  await request(ws, "SUBSCRIBE", {}, "VOICE_CHANNEL_SELECT");
  await refreshChannel(ws);
}

// --- Appel en cours

async function refreshChannel(ws: WebSocket) {
  const res = await request(ws, "GET_SELECTED_VOICE_CHANNEL", {});
  if (socket === ws && res.evt !== "ERROR") selectChannel(ws, res.data);
}

function selectChannel(ws: WebSocket, data: any) {
  const id = typeof data?.id === "string" ? data.id : undefined;
  const previous = channelId;
  if (previous && previous !== id) for (const evt of CHANNEL_EVENTS) void request(ws, "UNSUBSCRIBE", { channel_id: previous }, evt);
  channelId = id;
  const next = new Map<string, Member>();
  for (const state of Array.isArray(data?.voice_states) ? data.voice_states : []) {
    const parsed = memberOf(state);
    if (parsed && next.size < MAX_MEMBERS)
      next.set(parsed[0], { ...parsed[1], speaking: (id === previous && members.get(parsed[0])?.speaking) || false });
  }
  members = next;
  if (id && id !== previous) for (const evt of CHANNEL_EVENTS) void request(ws, "SUBSCRIBE", { channel_id: id }, evt);
}

/** Participant lu dans un état vocal de Discord ; soi-même et les données inattendues sont écartés. */
function memberOf(state: any): [string, Member] | undefined {
  const user = state?.user;
  const id = user?.id;
  if (typeof id !== "string" || !SNOWFLAKE.test(id) || id === selfId) return undefined;
  const name = [state.nick, user.global_name, user.username].find((n) => typeof n === "string" && n.trim()) ?? "?";
  const avatar = typeof user.avatar === "string" && AVATAR_HASH.test(user.avatar) ? user.avatar : undefined;
  return [id, { name: String(name).replace(CONTROL_CHARS, "").trim().slice(0, 100) || "?", avatar, speaking: false }];
}

function onEvent(ws: WebSocket, evt: string, data: any) {
  if (evt === "VOICE_CHANNEL_SELECT") return void refreshChannel(ws).catch((e) => failed(ws, e));
  if (data.channel_id !== undefined && data.channel_id !== channelId) return;
  if (evt === "VOICE_STATE_CREATE" || evt === "VOICE_STATE_UPDATE") {
    const parsed = memberOf(data);
    if (!parsed) return;
    const next = new Map(members);
    if (!members.has(parsed[0]) && members.size >= MAX_MEMBERS) return;
    next.set(parsed[0], { ...parsed[1], speaking: members.get(parsed[0])?.speaking ?? false });
    members = next;
  } else if (evt === "VOICE_STATE_DELETE") {
    const next = new Map(members);
    next.delete(String(data.user?.id));
    members = next;
  } else if (evt === "SPEAKING_START" || evt === "SPEAKING_STOP") {
    const member = members.get(String(data.user_id));
    if (!member) return;
    members = new Map(members).set(String(data.user_id), { ...member, speaking: evt === "SPEAKING_START" });
  }
}

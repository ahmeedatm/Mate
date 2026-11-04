import path from "node:path";
import { config } from "./config.ts";
import { readJson, removeJson, writeJson } from "./store.ts";

/**
 * Accès au RPC local de Discord (photos des participants, qui parle) : décision d'Ahmed du 4/11/2026, qui remplace « pas
 * de RPC » (docs/discord-pilotage.md). On se présente comme StreamKit, l'outil de Discord pour les streamers, comme le
 * projet libre Discover : Discord n'ouvre sa porte locale qu'aux applis qu'il connaît. Relevé sur le vrai Discord :
 * l'échange du code ne rend qu'un `access_token` (pas de `refresh_token`), valable 7 jours ; on le renouvelle par un
 * AUTHORIZE `prompt: none`, sans fenêtre. Le jeton reste dans .data/discord.json (0600) : ni réponse, ni journal, ni tablette.
 */

/** Identifiant public de StreamKit, dont la porte locale accepte l'origine http://localhost:3000. */
export const STREAMKIT_CLIENT_ID = "207646673902501888";
export const STREAMKIT_ORIGIN = "http://localhost:3000";
const TIMEOUT_MS = 15_000;

/** Échange du code (DISCORD_STREAMKIT_URL : faux Discord des tests). */
const streamkitUrl = () => (process.env.DISCORD_STREAMKIT_URL?.trim() || "https://streamkit.discord.com").replace(/\/+$/, "");

const storeFile = () => path.join(config.dataDir, "discord.json");

export async function loadDiscordToken(): Promise<string | undefined> {
  const token = (await readJson<{ accessToken?: unknown }>(storeFile(), () => ({}))).accessToken;
  return typeof token === "string" && token ? token : undefined;
}

export const saveDiscordToken = (accessToken: string) => writeJson(storeFile(), { accessToken }, { mode: 0o600 });
export const forgetDiscordToken = () => removeJson(storeFile());

/** Code rendu par AUTHORIZE → jeton, par StreamKit. undefined si l'échange échoue (le message ne dit jamais le code). */
export async function exchangeDiscordCode(code: string): Promise<string | undefined> {
  const res = await fetch(`${streamkitUrl()}/overlay/token`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code }),
    redirect: "error",
    signal: AbortSignal.timeout(TIMEOUT_MS),
  }).catch(() => undefined);
  if (!res?.ok) return undefined;
  const json = (await res.json().catch(() => ({}))) as { access_token?: unknown };
  return typeof json.access_token === "string" && json.access_token ? json.access_token : undefined;
}

import os from "node:os";
import path from "node:path";
import { loadIdentity } from "./data-files.ts";
import { backendRoot, dataDir, sharedDir } from "./paths.ts";

export { backendRoot };

function expandHome(p: string) {
  return p.startsWith("~/") || p.startsWith("~\\") ? path.join(os.homedir(), p.slice(2)) : p;
}

const identity = loadIdentity(dataDir);
// WiFi par défaut ; HOST=127.0.0.1 pour n'accepter que la tablette branchée en USB (adb reverse).
const host = process.env.HOST?.trim() || "0.0.0.0";
const loopbackOnly = host === "127.0.0.1" || host === "localhost" || host === "::1";
// COMPANION_TOKEN vide en loopback : pas de jeton (tests sur l'émulateur). Sinon jeton de .env, ou celui créé ici.
const envToken = process.env.COMPANION_TOKEN;
const token = envToken !== undefined && envToken.trim() === "" && loopbackOnly ? undefined : envToken?.trim() || identity.token;
const platform = process.platform === "win32" ? "windows" : "mac";

/**
 * Identifiants publics des applis OAuth (agenda, GitLab), livrés avec le logiciel ; ACCOUNTS_FILE pour un autre fichier
 * (tests : identifiants vides). Lu à chaque appel, pas au chargement.
 */
export const accountsFile = () =>
  process.env.ACCOUNTS_FILE ? path.resolve(process.env.ACCOUNTS_FILE) : path.join(backendRoot, "config", "accounts.json");

export const config = {
  host,
  port: Number(process.env.PORT ?? 3000),
  /** HTTPS pour le WiFi (le port HTTP n'écoute plus qu'en local). */
  tlsPort: Number(process.env.TLS_PORT ?? Number(process.env.PORT ?? 3000) + 1),
  token,
  /** Identifiant stable de cet ordinateur (la tablette le retrouve même si son IP change). */
  computerId: identity.id,
  /** En écoute uniquement sur la machine (cas `adb reverse` en USB) ? */
  loopbackOnly,
  docsDir: path.resolve(expandHome(process.env.DOCS_DIR || sharedDir())),
  icsUrls: (process.env.CALENDAR_ICS_URLS ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean),
  /** Deck de cet ordinateur ; DECK_FILE pour un autre fichier (deck de test). */
  deckFile: process.env.DECK_FILE ? path.resolve(process.env.DECK_FILE) : path.join(backendRoot, "config", "deck.json"),
  /** Deck de départ adapté à l'ordinateur (applis et raccourcis de macOS ou de Windows). */
  deckExampleFile: path.join(backendRoot, "config", platform === "windows" ? "deck.example.windows.json" : "deck.example.json"),
  cacheDir: path.join(backendRoot, ".cache"),
  /** Données du compagnon (tâches, identité) : propres à cet ordinateur, non versionnées. */
  dataDir,
};

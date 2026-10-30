/**
 * Backend de démonstration pour les captures des widgets du Deck sur émulateur : données réalistes sur le faux pilotage
 * (test/fake-os.ts), jeton connu (`jeton-de-test`), données dans un dossier temporaire supprimé à l'arrêt.
 * DEMO_PORT (3920) : le backend. L'émulateur le joint par 10.0.2.2:<port>.
 * DEMO_ID : identité à reprendre (émulateur déjà appairé à un backend de démo).
 * DEMO_OS (mac) : les agents Claude n'existent que sur Mac (/agents répond 501 ailleurs) ; `windows` pour le reste du Deck seul.
 * DEMO_DECK (deck.example.windows.json) : le deck de config/ ; `deck.example.json` (celui du Mac) a les touches « Rejoindre maintenant »
 * (`lobby`) et Caméra de Teams, que celui de Windows n'a pas.
 * Faire changer l'appel (Discord hors appel, Teams en `lobby`) : écrire `voice` et `inCall` dans le state.json du faux pilotage
 * (dossier `companion-os-*` de TMPDIR), relu à chaque requête.
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import type { AppWindow, Display, MixerChannel, OpenApp } from "../src/screens.ts";
import { startServer, TOKEN } from "./server.ts";

const PORT = Number(process.env.DEMO_PORT || 3920);
const demoOs = process.env.DEMO_OS === "windows" ? "windows" : "mac";
const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-widgets-demo-"));
const deckFile = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
  "config",
  process.env.DEMO_DECK || "deck.example.windows.json",
);

const channel = (id: string, name: string, volume: number, level: number, muted = false): MixerChannel => ({
  id,
  name,
  volume,
  muted,
  level,
});
const displays: Display[] = [
  { id: 1, label: "Écran 1", main: true, x: 0, y: 0, w: 1920, h: 1080 },
  { id: 2, label: "Écran 2", main: false, x: 1920, y: 0, w: 1920, h: 1080 },
];
const win = (index: number, title: string, display: number, front = false): AppWindow => ({
  index,
  title,
  minimized: false,
  fullscreen: false,
  display,
  elsewhere: false,
  front,
  maximized: false,
});
const openApps: OpenApp[] = [
  { key: "Discord.exe", name: "Discord", windows: [win(101, "Général | Les Copains - Discord", 2, true)] },
  { key: "chrome.exe", name: "Google Chrome", windows: [win(201, "Unowhy", 1), win(202, "Documentation Fastify", 1)] },
  {
    key: "Code.exe",
    name: "Visual Studio Code",
    windows: [win(301, "deck.ts - unowhy-companion", 1), win(302, "README.md - unowhy-companion", 2)],
  },
  { key: "Spotify.exe", name: "Spotify", windows: [win(401, "Spotify", 2)] },
];

// /deck/features n'annonce les agents que si `claude` existe : un faux qui échoue (ni `claude agents` ni ses sessions ne sont lus).
const claudeStub = path.join(dataDir, "claude-factice.sh");
fs.writeFileSync(claudeStub, "#!/bin/sh\nexit 1\n", { mode: 0o755 });

// DEMO_ID : identité d'un backend de démo déjà appairé à l'émulateur, pour qu'il le retrouve sans réappairage.
if (process.env.DEMO_ID) {
  fs.writeFileSync(path.join(dataDir, "identity.json"), JSON.stringify({ id: process.env.DEMO_ID, token: TOKEN }));
}

const server = await startServer({
  os: demoOs,
  port: PORT,
  dataDir,
  env: { DECK_FILE: deckFile, COMPUTER_NAME: "PC de démonstration", CLAUDE_BIN: claudeStub },
});

server.setOs({
  installed: ["Discord", "Microsoft Teams", "Spotify", "Google Chrome", "Visual Studio Code", "Notepad"],
  running: ["Discord", "Microsoft Teams", "Spotify", "Google Chrome", "Visual Studio Code", "Microsoft PowerPoint"],
  inCall: ["Discord"],
  mixer: {
    master: channel("master", "Sortie", 65, 40),
    mic: channel("mic", "Micro", 80, 15, true),
    apps: [
      channel("discord", "Discord", 100, 55),
      channel("spotify", "Spotify", 40, 70),
      channel("chrome", "Google Chrome", 75, 10),
      channel("teams", "Microsoft Teams", 90, 0, true),
    ],
  },
  voice: {
    Discord: {
      found: true,
      connected: true,
      streaming: false,
      channel: "Salon vocal",
      people: ["Léa", "Malo", "Moi"],
      muted: true,
      deafened: false,
      mic: "Micro coupé",
      deaf: null,
    },
    "Microsoft Teams": {
      found: true,
      connected: false,
      lobby: true,
      streaming: false,
      channel: null,
      people: [],
      muted: null,
      deafened: null,
      mic: null,
      deaf: null,
    },
  },
  windows: {
    Discord: [{ title: "Salon vocal | Les Copains - Discord" }],
    "Google Chrome": [{ title: "Unowhy" }, { title: "Documentation Fastify" }],
  },
  openWindows: { apps: openApps, displays },
  media: [
    {
      app: "Spotify.exe",
      name: "Spotify",
      current: true,
      title: "Midnight City",
      artist: "M83",
      album: "Hurry Up, We're Dreaming",
      hasThumb: false,
      status: "playing",
      canPlayPause: true,
      canNext: true,
      canPrevious: true,
      canSeek: true,
      positionMs: 62_000,
      durationMs: 244_000,
    },
  ],
});

const must = async (what: string, res: { status: number; json: unknown }) => {
  if (res.status !== 200) throw new Error(`${what} : HTTP ${res.status} ${JSON.stringify(res.json)}`);
};

async function fill() {
  for (const [url, label] of [
    ["youtube.com", "YouTube"],
    ["twitch.tv", "Twitch"],
    ["netflix.com", "Netflix"],
  ])
    await must(`site ${url}`, await server.call("POST", "/deck/links", { url, label }));

  const keys = [
    { label: "Discord", glyph: "💬", steps: [{ type: "app", name: "Discord" }] },
    { label: "Chrome", glyph: "🌐", steps: [{ type: "app", name: "Google Chrome" }] },
    { label: "Code", glyph: "⌨", color: "#1565C0", steps: [{ type: "app", name: "Visual Studio Code" }] },
    { label: "Capture", glyph: "📷", steps: [{ type: "system", command: "screenshot-clipboard" }] },
    { label: "Musique", glyph: "🎵", steps: [{ type: "media", command: "play-pause" }] },
    {
      label: "Mode focus",
      glyph: "🎯",
      color: "#2E7D32",
      steps: [
        { type: "app", name: "Visual Studio Code" },
        { type: "wait", ms: 500 },
        { type: "window", app: "Visual Studio Code", layout: "max" },
        { type: "media", command: "play-pause" },
      ],
    },
  ];
  for (const [slot, key] of keys.entries()) await must(`touche ${slot}`, await server.call("PUT", `/deck/keys/${slot}`, key));

  // Agents Claude : hooks avec le secret du dossier de données (comme qa/sanity.ts), session par session.
  if (demoOs === "mac") {
    const secret = JSON.parse(fs.readFileSync(path.join(dataDir, "agents-hook.json"), "utf8")).secret as string;
    const hook = async (body: object) => {
      const res = await fetch(`${server.url}/agents/hook`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-companion-hook": secret },
        body: JSON.stringify(body),
      });
      if (!res.ok) throw new Error(`hook ${JSON.stringify(body)} : HTTP ${res.status}`);
    };
    const travaille = { session_id: "demo-travaille", cwd: "/Users/demo/unowhy-companion", session_title: "Widgets du Deck" };
    await hook({ ...travaille, hook_event_name: "UserPromptSubmit" });
    await hook({
      ...travaille,
      hook_event_name: "PreToolUse",
      tool_name: "Edit",
      tool_input: { file_path: "app/src/main/kotlin/HomeFragment.kt" },
    });
    const fini = { session_id: "demo-fini", cwd: "/Users/demo/sqool-api", session_title: "Correctif export CSV" };
    await hook({ ...fini, hook_event_name: "UserPromptSubmit" });
    await hook({ ...fini, hook_event_name: "Stop", last_assistant_message: "Export corrigé, tests verts." });
  }
}

try {
  await fill();
} catch (e) {
  console.error(e);
  await server.stop();
  fs.rmSync(dataDir, { recursive: true, force: true });
  process.exit(1);
}

console.log(`Backend de démonstration (${demoOs}) sur ${server.url}, depuis l'émulateur : 10.0.2.2:${PORT}`);
console.log(`Jeton : ${TOKEN}`);
console.log(`Code d'appairage : curl -s -H 'authorization: Bearer ${TOKEN}' ${server.url}/local/status`);

const quit = async () => {
  await server.stop();
  fs.rmSync(dataDir, { recursive: true, force: true });
  process.exit(0);
};
process.on("SIGTERM", quit);
process.on("SIGINT", quit);
setInterval(() => {}, 1 << 30);

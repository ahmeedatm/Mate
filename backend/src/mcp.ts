import fs from "node:fs";
import { fileURLToPath } from "node:url";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";

/**
 * Extension MCP : Claude (Claude Code, Claude Desktop ou tout client MCP) pilote la tablette.
 * Lancée par le client en stdio, elle relaie vers le backend déjà démarré (service automatique ou `npm run dev`),
 * qui transmet à la tablette par le canal /tablet. stdout est réservé au protocole MCP.
 */

// Lancée par Claude depuis n'importe quel dossier : on charge nous-mêmes backend/.env.
const envFile = fileURLToPath(new URL("../.env", import.meta.url));
if (fs.existsSync(envFile)) process.loadEnvFile(envFile);
const { config } = await import("./config.ts");

const baseUrl = `http://127.0.0.1:${config.port}`;

async function api(method: "GET" | "POST" | "PUT" | "DELETE", path: string, body?: object): Promise<Record<string, any>> {
  const headers: Record<string, string> = {};
  if (config.token) headers.authorization = `Bearer ${config.token}`;
  if (body) headers["content-type"] = "application/json";
  const res = await fetch(baseUrl + path, { method, headers, body: body && JSON.stringify(body) }).catch(() => {
    throw new Error(`Backend du compagnon injoignable sur ${baseUrl} : lancer \`npm run service:install\` (ou \`npm run dev\`) dans backend/`);
  });
  const json = (await res.json().catch(() => ({}))) as Record<string, any>;
  // Erreurs Fastify : { error: "Bad Request", message: <la vraie raison> } ; les nôtres : { error }.
  if (!res.ok) throw new Error(json.message ?? json.error ?? `HTTP ${res.status}`);
  return json;
}

const command = (body: object) => api("POST", "/tablet/command", body);

/** Transforme les erreurs en résultat d'outil lisible par Claude plutôt qu'en erreur de protocole. */
async function attempt(fn: () => Promise<CallToolResult>): Promise<CallToolResult> {
  try {
    return await fn();
  } catch (e) {
    return { isError: true, content: [{ type: "text", text: e instanceof Error ? e.message : String(e) }] };
  }
}

const text = (value: unknown): CallToolResult => ({
  content: [{ type: "text", text: typeof value === "string" ? value : JSON.stringify(value, null, 2) }],
});

const server = new McpServer(
  { name: "unowhy-companion", version: "0.1.0" },
  {
    instructions:
      "Pilote la tablette Unowhy posée sur le bureau (compagnon de l'ordinateur, Mac ou PC Windows). Écran paysage 1280×800, " +
      "modes : accueil (tableau de bord : heure, prochain rendez-vous, agenda, tâches dont le worklog Jira, Focus, son de l'ordinateur), " +
      "deck (raccourcis de l'ordinateur, page Perso de touches à affecter : deck_touche_*), assistant (chat), docs (dossier partagé de l'ordinateur), focus (Pomodoro, sons d'ambiance), camera (visualiseur : la caméra filme une feuille, image projetée depuis l'ordinateur sur /camera), réglages. Commencer par tablette_etat ; tablette_capture montre l'écran. " +
      "Textes affichés en français, courts : la tablette est lue de loin.",
  },
);

const MODES = { accueil: "home", deck: "deck", assistant: "chat", docs: "docs", focus: "focus", camera: "camera", reglages: "settings" } as const;
const FOCUS = { demarrer: "start", pause: "pause", reinitialiser: "reset", passer: "skip" } as const;
const NOISES = { brun: "brown", rose: "pink", blanc: "white", silence: "off" } as const;
const keys = <T extends object>(o: T) => Object.keys(o) as [keyof T & string, ...(keyof T & string)[]];

server.registerTool(
  "tablette_etat",
  {
    title: "État de la tablette",
    description: "Connectée ou non, mode affiché, document ouvert, minuteur Focus, son d'ambiance, synthèse vocale disponible.",
    annotations: { readOnlyHint: true },
  },
  () => attempt(async () => text(await api("GET", "/tablet"))),
);

server.registerTool(
  "tablette_capture",
  {
    title: "Capture d'écran de la tablette",
    description: "Renvoie l'image de ce qui est affiché sur la tablette (l'interface du compagnon).",
    annotations: { readOnlyHint: true },
  },
  () =>
    attempt(async () => {
      const { data } = await command({ type: "screenshot" });
      return { content: [{ type: "image", data: data.jpeg, mimeType: "image/jpeg" }] };
    }),
);

server.registerTool(
  "tablette_afficher_mode",
  {
    title: "Changer d'écran",
    description: "Affiche un mode de la tablette.",
    inputSchema: { mode: z.enum(keys(MODES)) },
  },
  ({ mode }) => attempt(async () => (await command({ type: "mode", mode: MODES[mode] }), text(`Mode ${mode} affiché.`))),
);

server.registerTool(
  "tablette_message",
  {
    title: "Afficher un message",
    description: "Affiche un message en bandeau sur la tablette, et le lit à voix haute si demandé. Toucher le bandeau le ferme.",
    inputSchema: {
      texte: z.string().min(1).max(1000),
      lire_a_voix_haute: z.boolean().default(false),
      duree_secondes: z.number().int().min(2).max(600).default(10),
    },
  },
  ({ texte, lire_a_voix_haute, duree_secondes }) =>
    attempt(async () => {
      await command({ type: "message", text: texte, speak: lire_a_voix_haute, seconds: duree_secondes });
      return text("Message affiché" + (lire_a_voix_haute ? " et lu." : "."));
    }),
);

server.registerTool(
  "docs_lister",
  {
    title: "Lister le dossier partagé",
    description: "Liste un dossier du dossier partagé de l'ordinateur (celui que la tablette affiche dans Docs). Chemins relatifs.",
    inputSchema: { dossier: z.string().default("").describe("Chemin relatif, vide pour la racine") },
    annotations: { readOnlyHint: true },
  },
  ({ dossier }) => attempt(async () => text(await api("GET", "/docs?dir=" + encodeURIComponent(dossier)))),
);

server.registerTool(
  "tablette_document",
  {
    title: "Ouvrir ou fermer un document",
    description: "Ouvre un fichier du dossier partagé (PDF, image, texte) dans le panneau de droite de la tablette. Sans chemin : ferme le panneau.",
    inputSchema: { chemin: z.string().optional().describe("Chemin relatif renvoyé par docs_lister") },
  },
  ({ chemin }) =>
    attempt(async () => {
      await command({ type: "document", ...(chemin ? { path: chemin } : {}) });
      return text(chemin ? `${chemin} ouvert sur la tablette.` : "Panneau document fermé.");
    }),
);

server.registerTool(
  "tablette_focus",
  {
    title: "Minuteur Pomodoro",
    description: "Pilote le minuteur Focus. Avec des durées, le minuteur est d'abord réinitialisé sur ces durées.",
    inputSchema: {
      action: z.enum(keys(FOCUS)),
      minutes_focus: z.number().int().min(1).max(180).optional(),
      minutes_pause: z.number().int().min(1).max(60).optional(),
    },
  },
  ({ action, minutes_focus, minutes_pause }) =>
    attempt(async () => {
      const { data } = await command({ type: "focus", action: FOCUS[action], focusMinutes: minutes_focus, breakMinutes: minutes_pause });
      return text(data ?? "OK");
    }),
);

server.registerTool(
  "tablette_ambiance",
  {
    title: "Son d'ambiance",
    description: "Lance un bruit d'ambiance généré sur le haut-parleur de la tablette (brun : grave, rose : pluie), ou le coupe.",
    inputSchema: { son: z.enum(keys(NOISES)), volume: z.number().int().min(0).max(100).optional().describe("En %") },
  },
  ({ son, volume }) =>
    attempt(async () => {
      await command({ type: "noise", kind: NOISES[son], ...(volume === undefined ? {} : { volume: volume / 100 }) });
      return text(son === "silence" ? "Son coupé." : `Bruit ${son} lancé.`);
    }),
);

server.registerTool(
  "taches_lister",
  {
    title: "Tâches du jour",
    description: "Liste les tâches affichées sur la tablette : quotidiennes (ex. worklog Jira, avec le temps saisi) et ponctuelles.",
    annotations: { readOnlyHint: true },
  },
  () => attempt(async () => text((await api("GET", "/tasks?refresh=1")).tasks)),
);

server.registerTool(
  "tache_ajouter",
  {
    title: "Ajouter une tâche",
    description:
      "Ajoute une tâche à la liste de la tablette. Sans `jours` : ponctuelle (disparaît le lendemain une fois cochée). " +
      "`jours` (1 = lundi … 7 = dimanche) : récurrente ces jours-là. `rappel` (\"HH:MM\") : la tablette le dit à voix haute " +
      "à cette heure (récurrente : puis toutes les 30 min pendant 2 h tant que ce n'est pas coché).",
    inputSchema: {
      texte: z.string().min(1).max(200),
      jours: z.array(z.number().int().min(1).max(7)).max(7).optional(),
      rappel: z.string().regex(/^([01][0-9]|2[0-3]):[0-5][0-9]$/).optional(),
    },
  },
  ({ texte, jours, rappel }) => attempt(async () => text(await api("POST", "/tasks", { label: texte, days: jours, remindAt: rappel }))),
);

server.registerTool(
  "tache_cocher",
  {
    title: "Cocher une tâche",
    description: "Coche (ou décoche) une tâche par son id (voir taches_lister).",
    inputSchema: { id: z.string(), fait: z.boolean().default(true) },
  },
  ({ id, fait }) => attempt(async () => (await api("POST", `/tasks/${encodeURIComponent(id)}/done`, { done: fait }), text(fait ? "Tâche cochée." : "Tâche décochée."))),
);

server.registerTool(
  "agenda_ajouter",
  {
    title: "Noter un rendez-vous",
    description:
      "Ajoute un rendez-vous à l'agenda personnel de la tablette (affiché sur l'Accueil avec les agendas reliés). " +
      "`date` : AAAA-MM-JJ ; `heure` : HH:MM (absente = toute la journée) ; `rappel` : minutes avant pour que la tablette le dise à voix haute (0 = à l'heure).",
    inputSchema: {
      titre: z.string().min(1).max(200),
      date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
      heure: z.string().regex(/^([01][0-9]|2[0-3]):[0-5][0-9]$/).optional(),
      rappel: z.number().int().min(0).max(1440).optional(),
    },
  },
  ({ titre, date, heure, rappel }) =>
    attempt(async () => {
      // Heure locale de l'ordinateur (celle que montre la tablette).
      const [y, m, d] = date.split("-").map(Number);
      const [h, min] = (heure ?? "00:00").split(":").map(Number);
      const startMs = new Date(y, m - 1, d, h, min).getTime();
      return text(await api("POST", "/calendar/events", { title: titre, startMs, allDay: !heure, remindBefore: rappel ?? null }));
    }),
);

// Étapes d'une touche perso : mêmes types que backend/src/perso.ts (le backend revalide).
const STEP = z.discriminatedUnion("type", [
  z.object({ type: z.literal("app"), name: z.string().describe("Nom de l'appli : .app sur Mac, menu Démarrer sur Windows (ex. Visual Studio Code)") }),
  z.object({ type: z.literal("keys"), keys: z.string().describe("Raccourci, ex. cmd+shift+m, ctrl+up, f5, return (sur Windows cmd = Ctrl, win = touche Windows)"), app: z.string().optional().describe("Appli à mettre devant avant") }),
  z.object({ type: z.literal("text"), text: z.string().max(4000).describe("Texte collé dans l'appli au premier plan") }),
  z.object({ type: z.literal("url"), url: z.string().describe("http(s), mailto, msteams, slack, vscode, x-apple…") }),
  z.object({ type: z.literal("shortcut"), name: z.string().describe("Raccourci de l'appli Raccourcis (Mac seulement)") }),
  z.object({ type: z.literal("media"), command: z.enum(["play-pause", "next", "previous"]) }),
  z.object({ type: z.literal("system"), command: z.enum(["sleep-display", "toggle-dark-mode", "screenshot-clipboard", "lock"]) }),
  z.object({ type: z.literal("wait"), ms: z.number().int().min(0).max(10_000) }),
  z.object({
    type: z.literal("window"),
    app: z.string(),
    display: z.string().optional().describe("Libellé d'écran (voir deck_touches_lister), sinon l'écran actuel"),
    layout: z.enum(["keep", "left", "right", "max"]).optional().describe("keep : même place relative ; left/right : moitié ; max : agrandie"),
  }),
]);

server.registerTool(
  "deck_touches_lister",
  {
    title: "Touches perso du deck",
    description: "Liste les touches de la page Perso du deck (numéros 1 à 14, vides comprises), les écrans de l'ordinateur, son système et les raccourcis Apple disponibles (Mac).",
    annotations: { readOnlyHint: true },
  },
  () =>
    attempt(async () => {
      const [{ pages }, catalog] = await Promise.all([api("GET", "/deck"), api("GET", "/deck/catalog")]);
      const perso = pages.find((p: { editable: boolean }) => p.editable);
      const keys = await Promise.all(
        perso.tiles.map(async (_: unknown, i: number) => ({ numero: i + 1, ...((await api("GET", `/deck/keys/${i}`)).key ?? { vide: true }) })),
      );
      return text({ systeme: catalog.os, touches: keys, ecrans: catalog.displays, raccourcis_apple: catalog.shortcuts });
    }),
);

server.registerTool(
  "deck_touche_affecter",
  {
    title: "Affecter une touche perso",
    description:
      "Programme une touche de la page Perso du deck : une étape (raccourci simple) ou plusieurs (macro exécutée dans l'ordre). " +
      "Ex. « Mode code » : window VS Code sur l'écran externe en max, puis window Teams sur l'écran intégré à gauche. Remplace la touche si elle est prise.",
    inputSchema: {
      numero: z.number().int().min(1).max(14),
      libelle: z.string().min(1).max(40).describe("Court : lu de loin sur la tablette"),
      symbole: z.string().max(8).optional().describe("Emoji affiché à la place de l'icône d'appli"),
      couleur: z.string().regex(/^#[0-9A-Fa-f]{6}$/).optional(),
      etapes: z.array(STEP).min(1).max(20),
    },
  },
  ({ numero, libelle, symbole, couleur, etapes }) =>
    attempt(async () => {
      await api("PUT", `/deck/keys/${numero - 1}`, { label: libelle, glyph: symbole, color: couleur, steps: etapes });
      return text(`Touche ${numero} « ${libelle} » enregistrée (actualiser le deck sur la tablette).`);
    }),
);

server.registerTool(
  "deck_touche_effacer",
  {
    title: "Vider une touche perso",
    description: "Remet à vide une touche de la page Perso du deck.",
    inputSchema: { numero: z.number().int().min(1).max(14) },
  },
  ({ numero }) => attempt(async () => (await api("DELETE", `/deck/keys/${numero - 1}`), text(`Touche ${numero} vidée.`))),
);

await server.connect(new StdioServerTransport());

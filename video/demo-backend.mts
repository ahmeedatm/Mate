/**
 * Backend de démo pour les captures des vidéos (video/captures/) : faux pilotage (backend/test/fake-os.ts), données de personas,
 * jamais de donnée réelle, aucun nom de marque (applis génériques : Visio, Messagerie, Tableur…).
 * Lancer depuis backend/ : `DEMO_PERSONA=sylvie npx tsx ../video/demo-backend.mts`.
 * DEMO_PERSONA : sylvie (défaut), gwen, camille, aimee, sam, sacha ; choisit agenda, tâches, touches perso et applis.
 * DEMO_PORT (3940) : le backend, jeton `jeton-de-test` ; DEMO_DIR (/tmp/vdp) : données, dossier partagé, état du faux pilotage.
 * DEMO_OS : `windows` (défaut) ; `mac` (défaut de sacha) ajoute les agents Claude, un faux GitLab et un faux Jira.
 * Pilotage : `POST http://127.0.0.1:<DEMO_CONTROL_PORT, 3943>/os` avec un morceau de FakeState (fusionné, relu à chaque requête du backend).
 * DEMO_NOW (heure locale, `2026-10-29T14:29:00`) : instant simulé, pour la date de l'agenda et l'âge des fichiers du dossier partagé (défaut : maintenant).
 * Lecture en cours : `POST http://127.0.0.1:<DEMO_CONTROL_PORT>/media` avec `{"on": false}` la retire, `{"on": true}` la remet.
 * Un autre persona = relancer le script (le backend garde l'agenda 5 min en cache) ; tâches et touches sont refaites au démarrage.
 */
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import type { AppWindow, Display, MixerChannel, OpenApp } from "../backend/src/screens.ts";
import type { FakeState } from "../backend/test/fake-os.ts";
import { FAKE_CLIENT_ID, fakeMr, startFakeGitlab } from "../backend/test/fake-gitlab.ts";
import { startServer, TOKEN } from "../backend/test/server.ts";

const here = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.DEMO_PORT || 3940);
// Ports annexes (agenda, pilotage du faux ordinateur, faux Jira, faux GitLab) : changeables pour ne pas heurter un autre backend.
const ICS_PORT = Number(process.env.DEMO_ICS_PORT || 3941);
const CONTROL_PORT = Number(process.env.DEMO_CONTROL_PORT || 3943);
const JIRA_PORT = Number(process.env.DEMO_JIRA_PORT || 3944);
const GITLAB_PORT = Number(process.env.DEMO_GITLAB_PORT || 3931);
const dir = process.env.DEMO_DIR || "/tmp/vdp";
const persona = process.env.DEMO_PERSONA || "sylvie";
const now = process.env.DEMO_NOW ? new Date(process.env.DEMO_NOW) : new Date();
const demoOs = (process.env.DEMO_OS || (persona === "sacha" ? "mac" : "windows")) === "mac" ? "mac" : "windows";
const dataDir = path.join(dir, "data");
const docsDir = path.join(dir, "docs");
fs.mkdirSync(dataDir, { recursive: true });

type Ev = [h: number, m: number, endH: number, endM: number, title: string, place: string, visio?: boolean];
type Key = { label: string; glyph?: string; symbol?: string; color?: string; steps: object[] };
type Persona = { events: Ev[]; tasks: { label: string; days?: number[] }[]; keys: Key[]; apps: string[]; windows: Record<string, string[]> };

// --- Applis génériques : même nom côté faux pilotage, deck et fenêtres (aucune marque à l'écran)
const GENERIC = ["Visio", "Messagerie", "Navigateur", "Tableur", "Traitement de texte", "Présentation", "Notes", "Musique", "Chat équipe"];
const text = (label: string, t: string, glyph: string, color?: string): Key => ({ label, glyph, color, steps: [{ type: "text", text: t }] });
const combo = (label: string, keys: string, glyph: string, app?: string, color?: string): Key => ({ label, glyph, color, steps: [{ type: "keys", keys, ...(app ? { app } : {}) }] });

const PERSONAS: Record<string, Persona> = {
  // Sylvie, comptable (8:30)
  sylvie: {
    events: [
      [9, 0, 9, 30, "Point d'équipe du cabinet", "Visio", true],
      [10, 0, 11, 30, "Clôture du dossier Lemoine", "Salle 2"],
      [12, 30, 13, 30, "Déjeuner", ""],
      [14, 0, 15, 0, "Rapprochements bancaires", ""],
      [15, 30, 16, 0, "Appel client Durand", "Visio", true],
      [17, 0, 17, 30, "Revue des déclarations de TVA", "Salle 2"],
    ],
    tasks: [
      { label: "Rapprocher le compte 512" },
      { label: "TVA du dossier Lemoine" },
      { label: "Relancer M. Durand pour les pièces" },
      { label: "Export FEC du dossier Martin" },
      { label: "Envoyer le bilan à l'associé" },
      { label: "Point hebdo avec l'associé", days: [1, 3, 5] },
    ],
    keys: [
      {
        label: "Export FEC",
        glyph: "📤",
        color: "#1565C0",
        steps: [
          { type: "app", name: "Tableur" },
          { type: "wait", ms: 400 },
          { type: "keys", keys: "alt+f" },
          { type: "keys", keys: "e" },
          { type: "text", text: "FEC_clôture_2026" },
        ],
      },
      combo("Somme auto", "alt+=", "∑", "Tableur"),
      combo("Filtre", "ctrl+shift+l", "🔎", "Tableur"),
      combo("Rapprochement", "ctrl+f", "🏦", "Tableur", "#2E7D32"),
      text("Libellé TVA", "TVA collectée 20 %", "%"),
      {
        label: "Relance client",
        glyph: "✉",
        steps: [
          { type: "app", name: "Messagerie" },
          { type: "keys", keys: "ctrl+n" },
          { type: "text", text: "Bonjour, il nous manque les pièces du mois pour boucler votre dossier. Merci de nous les transmettre avant vendredi." },
        ],
      },
      text("Libellé acompte", "Acompte sur facture", "€"),
      combo("Dossier suivant", "ctrl+tab", "🗂", "Tableur"),
      { label: "Capture", glyph: "📷", steps: [{ type: "system", command: "screenshot-clipboard" }] },
    ],
    apps: ["Logiciel compta", "Tableur", "Messagerie", "Traitement de texte", "Navigateur", "Visio", "Notes", "Télédéclaration"],
    windows: { "Logiciel compta": ["Dossier Lemoine, journal des banques"], Tableur: ["Balance Lemoine", "Rapprochement 512"], Messagerie: ["Boîte de réception"] },
  },
  // Gwen, responsable marketing (10:30)
  gwen: {
    events: [
      [9, 30, 10, 0, "Point campagne", "Visio", true],
      [10, 30, 11, 30, "Webinaire : générer des leads B2B", "Visio", true],
      [12, 30, 13, 30, "Déjeuner équipe", ""],
      [14, 0, 14, 30, "Revue de la newsletter", "Salle Atelier"],
      [16, 0, 17, 0, "Brief agence", "Visio", true],
    ],
    tasks: [
      { label: "Programmer la newsletter de novembre" },
      { label: "Valider les visuels de la campagne" },
      { label: "Relancer l'agence pour le devis" },
      { label: "Préparer le replay du webinaire" },
      { label: "Mettre à jour le tableau de bord" },
    ],
    keys: [text("Signature", "Gwen, responsable marketing", "✍"), text("Modèle relance", "Bonjour, je reviens vers vous au sujet de notre échange.", "✉"), text("Hashtags", "#b2b #marketing #webinaire", "#")],
    apps: ["Visio", "Messagerie", "Navigateur", "Visuels", "CRM", "Réseaux", "Mailing", "Notes", "Tableur", "Présentation", "Statistiques", "Chat équipe"],
    windows: { Visio: ["Webinaire : générer des leads B2B"], Visuels: ["Bannière campagne"], CRM: ["Pipeline du trimestre"] },
  },
  // Camille, cheffe de projet (12:30)
  camille: {
    events: [
      [11, 0, 12, 0, "Point équipe projet", "Visio", true],
      [13, 0, 14, 0, "Comité de pilotage", "Visio", true],
      [15, 0, 15, 45, "Revue de planning", "Salle Turing"],
      [16, 30, 17, 0, "Sortie d'école", ""],
    ],
    tasks: [
      { label: "Envoyer le compte rendu du comité" },
      { label: "Mettre à jour le planning du projet" },
      { label: "Valider le budget de la phase 2" },
      { label: "Réserver la salle de mardi" },
    ],
    keys: [],
    apps: ["Visio", "Messagerie", "Navigateur", "Tableur", "Notes", "Musique", "Chat équipe", "Planning"],
    windows: {
      Tableur: ["Planning projet", "Budget phase 2"],
      Musique: ["Musique"],
      "Traitement de texte": ["Compte rendu du comité"],
      Navigateur: ["Suivi des risques", "Tableau de bord projet"],
      Messagerie: ["Boîte de réception"],
      Visio: ["Comité de pilotage"],
    },
  },
  // Aimée, chargée de projets en agence (14:29)
  aimee: {
    events: [
      [10, 0, 11, 0, "Atelier cadrage Projet Nova", "Salle Turing"],
      [14, 31, 15, 1, "Réunion découverte, Agence Orion", "Visio", true], // à 14:29:xx : « dans 1 min » (l'appli arrondit les minutes à l'inférieur)
      [16, 0, 16, 45, "Point Projet Alpes", "Salle Turing"],
      [17, 30, 18, 0, "Revue du budget", "Visio", true],
    ],
    tasks: [
      { label: "Appeler l'agence Orion" },
      { label: "Envoyer le devis Nova" },
      { label: "Retrouver le brief Alpes" },
      { label: "Valider les maquettes v3" },
      { label: "Préparer la réunion découverte" },
    ],
    keys: [],
    apps: ["Navigateur", "Messagerie", "Visio", "Tableur", "Traitement de texte", "Présentation", "Notes", "Maquettes"],
    windows: {
      Navigateur: ["Tableau de bord projets", "Brief client Nova", "Fiche agence Orion", "Planning équipe", "Idées d'ateliers", "Veille agence", "Tarifs 2026"],
      Tableur: ["Planning Projet Nova", "Budget Projet Alpes"],
      Messagerie: ["Boîte de réception"],
      "Traitement de texte": ["Compte rendu atelier"],
      Visio: ["Visio"],
      Notes: ["Questions découverte"],
      Maquettes: ["Maquettes v3"],
    },
  },
  // Sam, gestionnaire administratif (17:00)
  sam: {
    events: [
      [17, 30, 18, 0, "Point ergonomie du poste", "Visio", true],
      [16, 0, 16, 30, "Réunion de service", "Salle 3"],
    ],
    tasks: [
      { label: "Saisir les congés de l'équipe" },
      { label: "Classer les dossiers d'arrivée" },
      { label: "Envoyer les courriers types" },
      { label: "Archiver les devis signés" },
    ],
    keys: [
      text("Courrier type", "Madame, Monsieur, suite à votre demande, veuillez trouver ci-joint les documents demandés.", "✉", "#1565C0"),
      text("Accusé réception", "Nous accusons réception de votre dossier.", "✔", "#2E7D32"),
      text("Signature", "Cordialement, le service administratif", "✍"),
      combo("Enregistrer", "ctrl+s", "💾"),
      combo("Copier", "ctrl+c", "⎘"),
      combo("Coller", "ctrl+v", "⎗"),
    ],
    apps: ["Messagerie", "Traitement de texte", "Tableur", "Navigateur", "Visio", "Notes"],
    windows: { "Traitement de texte": ["Courrier type"], Messagerie: ["Boîte de réception"] },
  },
  // Sacha, développeur (19:10)
  sacha: {
    events: [
      [17, 0, 17, 30, "Revue de code", "Visio", true],
      [19, 30, 20, 0, "Point déploiement", "Visio", true],
    ],
    tasks: [{ label: "Relire la MR 145" }, { label: "Mettre à jour la CI" }, { label: "Écrire les tests d'export" }],
    keys: [],
    apps: ["Éditeur de code", "Terminal", "Navigateur", "Messagerie", "Visio", "Chat équipe", "Notes"],
    windows: { "Éditeur de code": ["export-csv.ts"], Terminal: ["npm test"] },
  },
};
const P = PERSONAS[persona];
if (!P) throw new Error(`persona inconnu : ${persona}`);
const allApps = [...new Set([...GENERIC, ...P.apps, ...Object.keys(P.windows)])];

// --- Dossier partagé (Fichiers de l'ordi), extensions neutres
const day = 86_400_000;
const put = (rel: string, daysAgo: number, size = 2048) => {
  const full = path.join(docsDir, rel);
  fs.mkdirSync(path.dirname(full), { recursive: true });
  fs.writeFileSync(full, Buffer.alloc(size, "x"));
  const t = new Date(now.getTime() - daysAgo * day);
  fs.utimesSync(full, t, t);
};
for (const [rel, ago, size] of [
  ["Projet Nova/Brief client.pdf", 0.2, 412_000],
  ["Projet Nova/Planning.csv", 0.5, 88_000],
  ["Projet Nova/Maquettes v3.pdf", 1, 2_300_000],
  ["Projet Nova/Compte rendu atelier.txt", 2, 36_000],
  ["Projet Alpes/Devis signé.pdf", 3, 210_000],
  ["Projet Alpes/Budget.csv", 4, 64_000],
  ["Contrat agence Orion.pdf", 1.2, 54_000],
  ["Suivi des heures octobre.csv", 0.1, 41_000],
  ["Présentation découverte.pdf", 0.8, 3_900_000],
  ["Logo Nova.png", 6, 120_000],
] as [string, number, number][])
  put(rel, ago, size);
for (const d of ["Projet Nova", "Projet Alpes", "Archives 2025", "Modèles"]) fs.mkdirSync(path.join(docsDir, d), { recursive: true });

// --- Agenda (source ICS servie en local, journée du jour : l'horloge de l'émulateur se règle sur l'heure du persona)
const ics = http.createServer((_req, res) => {
  const at = (h: number, m = 0) => {
    const d = new Date(now);
    d.setHours(h, m, 0, 0);
    return d;
  };
  const stamp = (d: Date) => d.toISOString().replace(/[-:]/g, "").replace(/\.\d+/, "");
  const link = "https://teams.microsoft.com/l/meetup-join/19%3ameeting_demo%40thread.v2/0";
  const lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//demo//FR"];
  P.events.forEach(([h, m, eh, em, title, place, visio], i) => {
    lines.push("BEGIN:VEVENT", `UID:demo-${i}@demo`, `DTSTAMP:${stamp(new Date())}`, `DTSTART:${stamp(at(h, m))}`, `DTEND:${stamp(at(eh, em))}`);
    lines.push(`SUMMARY:${title}`, `LOCATION:${place}`);
    if (visio) lines.push(`DESCRIPTION:Rejoindre la réunion : ${link}`);
    lines.push("END:VEVENT");
  });
  lines.push("END:VCALENDAR");
  res.writeHead(200, { "content-type": "text/calendar; charset=utf-8" }).end(lines.join("\r\n"));
});
await new Promise<void>((r) => ics.listen(ICS_PORT, "127.0.0.1", r));

// --- Deck : celui du système, noms de marque remplacés par des noms génériques, page Apps remplacée par les outils du persona
const BRANDS: [string, string][] = [
  ["Microsoft Teams", "Visio"],
  ["Micro Teams", "Micro"],
  ["Caméra Teams", "Caméra"],
  ["Teams", "Visio"],
  ["Microsoft Outlook", "Messagerie"],
  ["Outlook", "Messagerie"],
  ["Microsoft Word", "Traitement de texte"],
  ["Word", "Traitement de texte"],
  ["Microsoft Excel", "Tableur"],
  ["Excel", "Tableur"],
  ["Microsoft PowerPoint", "Présentation"],
  ["PowerPoint", "Présentation"],
  ["Google Chrome", "Navigateur"],
  ["Chrome", "Navigateur"],
  ["Spotlight", "Recherche"],
  ["Mission Control", "Vue des bureaux"],
];
const raw = fs.readFileSync(path.join(here, "..", "backend", "config", demoOs === "mac" ? "deck.example.json" : "deck.example.windows.json"), "utf8");
const deck = JSON.parse(BRANDS.reduce((s, [from, to]) => s.split(from).join(to), raw));
deck.pages = deck.pages.filter((p: { title: string }) => !["Discord", "Mission Control"].includes(p.title));
deck.pages.find((p: { apps?: boolean }) => p.apps).tiles = P.apps.map((name) => ({ label: name, action: { type: "app", name } }));
const deckFile = path.join(dir, "deck.json");
fs.writeFileSync(deckFile, JSON.stringify(deck));

// --- Fausse fenêtre vers l'extérieur : Jira (Mac seulement, tickets de Sacha)
let jiraUrl = "";
let jira: http.Server | undefined;
if (demoOs === "mac") {
  const STATUS_IDS: Record<string, string> = { "À faire": "10", "En cours": "11", "En revue": "12", Terminé: "13" };
  const issue = (key: string, summary: string, status: string, category: string, type: string, priority: string) => ({
    key,
    fields: {
      summary,
      status: { id: STATUS_IDS[status], name: status, statusCategory: { key: category } },
      issuetype: { name: type },
      priority: { name: priority },
    },
  });
  const col = (name: string, id: number) => ({ name, statuses: [{ id: String(id), self: "x" }] });
  jira = http.createServer((req, res) => {
    let body = "";
    req.on("data", (d) => (body += d));
    req.on("end", () => {
      res.setHeader("content-type", "application/json");
      const url = req.url ?? "";
      if (req.method === "POST" && url === "/rest/api/3/search/jql") {
        if (JSON.parse(body).jql.includes("statusCategory = Done")) {
          return res.end(JSON.stringify({ issues: [issue("API-401", "Ajouter l'authentification par jeton", "Terminé", "done", "Story", "High")] }));
        }
        return res.end(
          JSON.stringify({
            issues: [
              issue("API-412", "Export CSV des rapports mensuels", "En cours", "indeterminate", "Story", "High"),
              issue("API-418", "Corriger le délai d'expiration du jeton", "En cours", "indeterminate", "Bug", "Highest"),
              issue("API-421", "Migrer la CI vers les runners partagés", "En revue", "indeterminate", "Tâche", "Medium"),
              issue("API-407", "Documenter les webhooks", "À faire", "new", "Tâche", "Low"),
              issue("API-430", "Limiter le débit sur /search", "À faire", "new", "Story", "Medium"),
              issue("API-433", "Mettre à jour le serveur web", "À faire", "new", "Tâche", "Low"),
            ],
          }),
        );
      }
      if (url.startsWith("/rest/agile/1.0/board?")) return res.end(JSON.stringify({ values: [{ id: 1, name: "API Kanban" }], startAt: 0, isLast: true }));
      if (url === "/rest/agile/1.0/board/1/configuration") {
        return res.end(JSON.stringify({ columnConfig: { columns: [col("À faire", 10), col("En cours", 11), col("En revue", 12), col("Terminé", 13)] } }));
      }
      res.end("{}");
    });
  });
  await new Promise<void>((r) => jira!.listen(JIRA_PORT, "127.0.0.1", r));
  jiraUrl = `http://127.0.0.1:${JIRA_PORT}`;
}

// --- Faux GitLab (Mac seulement)
const gitlab = demoOs === "mac" ? await startFakeGitlab(GITLAB_PORT) : undefined;
if (gitlab) {
  gitlab.mrs.assigned = [
    fakeMr({ iid: "142", title: "feat: export CSV des rapports mensuels", project: { fullPath: "agence/api" }, headPipeline: { status: "SUCCESS" } }),
    fakeMr({
      iid: "145",
      title: "fix: expiration du jeton de session",
      project: { fullPath: "agence/api" },
      updatedAt: "2026-10-29T09:00:00Z",
      detailedMergeStatus: "NOT_APPROVED",
      approvedBy: { nodes: [] },
      approvalsLeft: 1,
      headPipeline: { status: "RUNNING" },
    }),
  ];
  gitlab.mrs.review = [
    fakeMr({
      iid: "139",
      title: "refactor: découper le module de facturation",
      project: { fullPath: "agence/facturation" },
      author: { name: "Inès" },
      headPipeline: { status: "SUCCESS" },
      approvedBy: { nodes: [] },
      detailedMergeStatus: "NOT_APPROVED",
      approvalsLeft: 1,
    }),
  ];
}

// Les agents ne sont annoncés que si `claude` existe : un faux qui échoue (ni `claude agents` ni ses sessions ne sont lus).
const claudeStub = path.join(dir, "claude-factice.sh");
fs.writeFileSync(claudeStub, "#!/bin/sh\nexit 1\n", { mode: 0o755 });

const server = await startServer({
  os: demoOs,
  port: PORT,
  dataDir,
  env: {
    DECK_FILE: deckFile,
    DOCS_DIR: docsDir,
    COMPUTER_NAME: process.env.DEMO_NAME || (demoOs === "mac" ? "Mac du bureau" : "PC du bureau"),
    CALENDAR_ICS_URLS: `http://127.0.0.1:${ICS_PORT}/cal.ics`,
    CLAUDE_BIN: claudeStub,
    ...(gitlab ? { GITLAB_URL: gitlab.url, GITLAB_CLIENT_ID: FAKE_CLIENT_ID } : {}),
    ...(jira ? { JIRA_SITE: jiraUrl, JIRA_EMAIL: "demo@exemple.test", JIRA_API_TOKEN: "jeton-jira" } : {}),
  },
});

const channel = (id: string, name: string, volume: number, level: number, muted = false): MixerChannel => ({ id, name, volume, muted, level });
const displays: Display[] = [
  { id: 1, label: "Écran 1", main: true, x: 0, y: 0, w: 1920, h: 1080 },
  { id: 2, label: "Écran 2", main: false, x: 1920, y: 0, w: 1920, h: 1080 },
];
let winIndex = 100;
const win = (title: string, display: number, front = false): AppWindow => ({
  index: winIndex++,
  title,
  minimized: false,
  fullscreen: false,
  display,
  elsewhere: false,
  front,
  maximized: false,
});
// Fenêtres de l'onglet Ouvertes : réparties sur les deux écrans, la première de la première appli devant
const openApps: OpenApp[] = Object.entries(P.windows).map(([name, titles], i) => ({
  key: name.toLowerCase().replace(/\s+/g, "-") + ".exe",
  name,
  windows: titles.map((t, j) => win(t, (i + j) % 2 === 0 ? 1 : 2, i === 0 && j === 0)),
}));
const base: FakeState = {
  installed: allApps,
  running: Object.keys(P.windows),
  inCall: [],
  mixer: {
    master: channel("master", "Sortie", 65, 40),
    mic: channel("mic", "Micro", 80, 15),
    apps: [channel("visio", "Visio", 90, 35), channel("musique", "Musique", 35, 70), channel("navigateur", "Navigateur", 75, 10)],
  },
  voice: {},
  windows: Object.fromEntries(Object.entries(P.windows).map(([name, titles]) => [name, titles.map((title) => ({ title }))])),
  openWindows: { apps: openApps, displays },
  media: [
    {
      app: "musique.exe",
      name: "Musique",
      current: true,
      title: "Morceau calme",
      artist: "Playlist concentration",
      album: "",
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
};
let current: FakeState = base;
server.setOs(current);
const control = http.createServer((req, res) => {
  let body = "";
  req.on("data", (d) => (body += d));
  req.on("end", () => {
    if (req.method === "POST" && req.url === "/media") {
      current = { ...current, media: JSON.parse(body || "{}").on ? base.media : [] };
      server.setOs(current);
    } else if (req.method === "POST" && req.url === "/os") {
      current = { ...current, ...JSON.parse(body || "{}") };
      server.setOs(current);
    }
    res.writeHead(200).end("{}");
  });
});
await new Promise<void>((r) => control.listen(CONTROL_PORT, "127.0.0.1", r));

const must = async (what: string, res: { status: number; json: unknown }) => {
  if (res.status !== 200) throw new Error(`${what} : HTTP ${res.status} ${JSON.stringify(res.json)}`);
};

/** Tâches et touches perso du persona, refaites à chaque démarrage (le DEMO_DIR garde l'identité de l'ordinateur appairé). */
async function fill() {
  for (const t of (await server.call("GET", "/tasks")).json.tasks ?? []) await server.call("DELETE", `/tasks/${encodeURIComponent(t.id)}`);
  for (const t of (await server.call("GET", "/tasks/recurring")).json.tasks ?? []) await server.call("DELETE", `/tasks/${encodeURIComponent(t.id)}`);
  for (let slot = 0; slot < 9; slot++) await server.call("DELETE", `/deck/keys/${slot}`);
  for (const [slot, key] of P.keys.entries()) await must(`touche ${slot}`, await server.call("PUT", `/deck/keys/${slot}`, key));
  for (const t of P.tasks) await must(`tâche ${t.label}`, await server.call("POST", "/tasks", t));
}

async function agents() {
  const secret = JSON.parse(fs.readFileSync(path.join(dataDir, "agents-hook.json"), "utf8")).secret as string;
  const hook = async (body: object) => {
    const res = await fetch(`${server.url}/agents/hook`, {
      method: "POST",
      headers: { "content-type": "application/json", "x-companion-hook": secret },
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new Error(`hook ${JSON.stringify(body)} : HTTP ${res.status}`);
  };
  const travaille = { session_id: "d-1", cwd: "/Users/demo/agence-api", session_title: "Export CSV des rapports" };
  await hook({ ...travaille, hook_event_name: "UserPromptSubmit" });
  await hook({ ...travaille, hook_event_name: "PreToolUse", tool_name: "Edit", tool_input: { file_path: "src/export/csv.ts" } });
  const tests = { session_id: "d-2", cwd: "/Users/demo/agence-api", session_title: "Corriger l'expiration du jeton" };
  await hook({ ...tests, hook_event_name: "UserPromptSubmit" });
  await hook({ ...tests, hook_event_name: "PreToolUse", tool_name: "Bash", tool_input: { command: "npm test" } });
  const fini = { session_id: "d-3", cwd: "/Users/demo/facturation", session_title: "Découper le module de facturation" };
  await hook({ ...fini, hook_event_name: "UserPromptSubmit" });
  await hook({ ...fini, hook_event_name: "Stop", last_assistant_message: "Module découpé en 3 fichiers, tests verts." });
  const attend = { session_id: "d-4", cwd: "/Users/demo/agence-api", session_title: "Migrer la CI vers les runners partagés" };
  await hook({ ...attend, hook_event_name: "UserPromptSubmit" });
  await hook({ ...attend, hook_event_name: "Notification", notification_type: "agent_needs_input", message: "Quelle version de Node cibler ?" });
}

try {
  await fill();
  if (demoOs === "mac") {
    await agents();
    await must("tableau Jira", await server.call("POST", "/jira/boards", { ids: [1] }));
    const start = await fetch(`${server.url}/oauth/gitlab/start`, { redirect: "manual" });
    const back = await gitlab!.authorize(start.headers.get("location")!);
    await fetch(back, { redirect: "manual" });
  }
} catch (e) {
  console.error(e);
  await server.stop();
  process.exit(1);
}

console.log(`Backend de démo (${persona}, ${demoOs}) sur ${server.url}, depuis l'émulateur : 10.0.2.2:${PORT} ; jeton : ${TOKEN}`);
console.log(`Code d'appairage : curl -s -H 'authorization: Bearer ${TOKEN}' ${server.url}/local/status`);

const quit = async () => {
  control.close();
  ics.close();
  jira?.close();
  await gitlab?.stop();
  await server.stop();
  process.exit(0);
};
process.on("SIGTERM", quit);
process.on("SIGINT", quit);
setInterval(() => {}, 1 << 30);

import path from "node:path";
import { config } from "./config.ts";
import { dayRange } from "./calendar.ts";
import { localEvents } from "./events.ts";
import { sortPipelines, statusLabel, type Mr, type PipelineRow } from "./gitlab.ts";
import { readJson } from "./store.ts";
import { resumeAway, startAway, type AwayDeps, type Saved } from "./away.ts";
import type { PersonaId } from "./demo.ts";
import type { AppWindow, AudioState, Display, MixerChannel, MixerState, OpenApp } from "./screens.ts";

/**
 * Données fictives de la démo : tant qu'un persona est actif (`.data/demo.json`) et que la tablette envoie le même (en-tête
 * `X-Demo-Persona`, voir `demoPersonaOf`), les routes qui LISENT l'ordinateur
 * (GitLab, Jira, agents Claude, fenêtres ouvertes, lecture en cours, son, performances, notifications,
 * presse-papiers, onglets du navigateur, appels, agenda) répondent avec les données du persona, jamais avec celles de
 * l'ordinateur. Les actions sur ces données répondent ok sans toucher à l'ordinateur et changent l'état fictif
 * (volume, muet, lecture, fenêtre devant, ticket déplacé, MR fusionnée). Les touches et macros du deck, elles, restent
 * réelles (`/deck/run`). Un état par activation, en mémoire : quitter la démo ou changer de persona le repart de zéro.
 * Aucun vrai nom de client, aucun lien vers un vrai service, aucun chiffre de marché.
 */

type Win = { title: string; display?: number; minimized?: boolean; maximized?: boolean };
type Track = { title: string; artist: string; album: string; durationMs: number };
type Issue = { key: string; summary: string; type: string; priority: string };
type Column = { title: string; issues: Issue[] };
type MockMr = {
  iid: number;
  title: string;
  draft?: boolean;
  author: string;
  pipeline: Mr["pipeline"];
  given: number;
  status: string;
  by?: string[];
};
type MockPipeline = {
  project?: string;
  ref: string;
  id: number;
  pipeline: PipelineRow["pipeline"];
  /** Durée du pipeline terminé. */
  durationSec?: number;
  /** Il y a combien de minutes il a fini (ou démarré, s'il tourne). */
  minutes: number;
  failedJobs?: string[];
};
type MockAgent = {
  title: string;
  dir: string;
  branch: string;
  state: "working" | "waiting" | "done" | "limit" | "idle";
  minutes: number;
  tool?: string;
  lines?: string[];
  ticket?: string;
  mr?: number;
  monitoring?: boolean;
  worked?: number;
};
type MockNotification = { app: string; title: string; body: string; minutes: number };

type PersonaMock = {
  screens: 1 | 2;
  windows: Record<string, Win[]>;
  media: { app: string; name: string; tracks: Track[] };
  /** Applis du mélangeur, en plus de celle du média. */
  sound: string[];
  perf: { cpu: number; ram: number };
  notifications: MockNotification[];
  clipboard: string[];
  tabs: { title: string; url: string }[];
  jira?: Column[];
  gitlab?: { project: string; mine: MockMr[]; review: MockMr[]; pipelines?: MockPipeline[] };
  agents?: MockAgent[];
};

const OUTLOOK = "Microsoft Outlook";
const TEAMS = "Microsoft Teams";
const CHROME = "Google Chrome";
const EXCEL = "Microsoft Excel";
const WORD = "Microsoft Word";
const POWERPOINT = "Microsoft PowerPoint";
const track = (title: string, artist: string, album: string, minutes: number): Track => ({
  title,
  artist,
  album,
  durationMs: Math.round(minutes * 60_000),
});
const tab = (title: string, host: string) => ({ title, url: `https://${host}.example.org/` });
const issue = (key: string, type: string, priority: string, summary: string): Issue => ({ key, summary, type, priority });

const MOCKS: Record<PersonaId, PersonaMock> = {
  nour: {
    screens: 1,
    windows: {
      [EXCEL]: [{ title: "Balance des tiers.xlsx" }, { title: "Clôture mensuelle.xlsx" }],
      "Logiciel comptable": [{ title: "Journal des achats" }],
      [OUTLOOK]: [{ title: "Boîte de réception" }],
      [CHROME]: [{ title: "Rapprochement bancaire" }],
    },
    media: {
      app: "Spotify",
      name: "Spotify",
      tracks: [
        track("Matin calme", "Atelier du silence", "Travail de fond", 3.4),
        track("Pages blanches", "Atelier du silence", "Travail de fond", 4.1),
      ],
    },
    sound: [TEAMS],
    perf: { cpu: 18, ram: 46 },
    notifications: [
      { app: "Outlook", title: "Cabinet partenaire", body: "Pièces de la clôture reçues", minutes: 6 },
      { app: "Outlook", title: "Cliente (dossier 14)", body: "Relevé de banque en pièce jointe", minutes: 41 },
      { app: "Calendrier", title: "Point clôture mensuelle", body: "Dans 10 minutes", minutes: 2 },
    ],
    clipboard: ["Règlement reçu par virement, lettrage effectué sur le compte client.", "FR76 0000 0000 0000 0000 0000 000"],
    tabs: [tab("Rapprochement bancaire", "banque"), tab("Plan comptable", "compta")],
  },
  gwen: {
    screens: 2,
    windows: {
      [CHROME]: [
        { title: "Tableau de bord des campagnes", display: 2 },
        { title: "Calendrier éditorial", display: 2 },
      ],
      [POWERPOINT]: [{ title: "Plan trimestriel.pptx" }],
      Canva: [{ title: "Visuel de la newsletter" }],
      [TEAMS]: [{ title: "Point hebdo marketing" }],
      [OUTLOOK]: [{ title: "Boîte de réception" }],
    },
    media: {
      app: "Chrome",
      name: "Google Chrome",
      tracks: [
        track("Mesurer une campagne", "Le podcast du marketing", "Saison 3", 28),
        track("Écrire un bon objet de mail", "Le podcast du marketing", "Saison 3", 24),
      ],
    },
    sound: [TEAMS],
    perf: { cpu: 27, ram: 58 },
    notifications: [
      { app: "Teams", title: "Atelier réseaux sociaux", body: "La salle est ouverte", minutes: 4 },
      { app: "Outlook", title: "Agence créative", body: "Visuels de la newsletter prêts", minutes: 18 },
      { app: "Slack", title: "Équipe contenu", body: "Le calendrier éditorial est à jour", minutes: 52 },
    ],
    clipboard: ["Merci d'avoir participé au webinaire, le replay arrive dans la journée.", "https://newsletter.example.org/printemps"],
    tabs: [
      tab("Tableau de bord des campagnes", "campagnes"),
      tab("Calendrier éditorial", "editorial"),
      tab("Planification des posts", "posts"),
    ],
  },
  camille: {
    screens: 1,
    windows: {
      [OUTLOOK]: [{ title: "Boîte de réception" }],
      [TEAMS]: [{ title: "Comité de pilotage" }],
      [CHROME]: [{ title: "Planning du projet" }],
      [WORD]: [{ title: "Compte rendu du comité.docx" }],
    },
    media: {
      app: "Spotify",
      name: "Spotify",
      tracks: [
        track("Retour à la maison", "Les bords de Seine", "Fin de journée", 3.7),
        track("Dîner en famille", "Les bords de Seine", "Fin de journée", 4.2),
      ],
    },
    sound: [TEAMS],
    perf: { cpu: 22, ram: 51 },
    notifications: [
      { app: "Teams", title: "Équipe projet", body: "Réunion dans 5 minutes", minutes: 3 },
      { app: "Messages", title: "École", body: "Rendez-vous jeudi à 17 h 30", minutes: 33 },
    ],
    clipboard: ["Compte rendu : décisions, actions (qui, quoi, pour quand), prochaine réunion."],
    tabs: [tab("Planning du projet", "planning"), tab("Recette de la semaine", "recettes")],
  },
  aimee: {
    screens: 2,
    windows: {
      [CHROME]: [
        { title: "Atlas : suivi" },
        { title: "Boréal : tickets", display: 2 },
        { title: "Planning des fournisseurs" },
        { title: "Comité de suivi : notes", display: 2 },
        { title: "Budget du lot 2", display: 2 },
      ],
      Notion: [{ title: "Projet Atlas" }, { title: "Projet Boréal" }],
      Slack: [{ title: "Équipe Atlas" }],
      [TEAMS]: [{ title: "Point Atlas" }],
      [OUTLOOK]: [{ title: "Boîte de réception" }],
      [WORD]: [{ title: "Compte rendu Atlas.docx" }, { title: "Cahier des charges Boréal.docx", minimized: true }],
      [EXCEL]: [{ title: "Budget Boréal.xlsx", display: 2 }],
    },
    media: {
      app: "Spotify",
      name: "Spotify",
      tracks: [
        track("Concentration profonde", "Atelier du silence", "Travail de fond", 52),
        track("Pluie sur le toit", "Atelier du silence", "Travail de fond", 47),
      ],
    },
    sound: [TEAMS, "Slack"],
    perf: { cpu: 41, ram: 78 },
    notifications: [
      { app: "Teams", title: "Point Atlas", body: "Réunion dans 10 minutes", minutes: 1 },
      { app: "Slack", title: "Équipe Atlas", body: "Le planning a bougé", minutes: 8 },
      { app: "Outlook", title: "Fournisseur du lot 2", body: "Devis mis à jour", minutes: 25 },
      { app: "Notion", title: "Projet Boréal", body: "3 tâches ont changé d'état", minutes: 47 },
    ],
    clipboard: ["À faire : ", "Envoyer le compte rendu de la réunion Atlas"],
    tabs: Array.from({ length: 20 }, (_, i) =>
      tab(
        ["Atlas : suivi", "Boréal : tickets", "Planning des fournisseurs", "Comité de suivi", "Budget du lot 2"][i % 5] +
          (i >= 5 ? ` (${Math.floor(i / 5) + 1})` : ""),
        `projet${i + 1}`,
      ),
    ),
  },
  sam: {
    screens: 1,
    windows: {
      [WORD]: [{ title: "Courrier type.docx" }],
      [EXCEL]: [{ title: "Tableau de suivi.xlsx" }],
      [OUTLOOK]: [{ title: "Boîte de réception" }],
      [CHROME]: [{ title: "Demandes à traiter" }],
    },
    media: {
      app: "Spotify",
      name: "Spotify",
      tracks: [
        track("Ciel dégagé", "Atelier du silence", "Travail de fond", 3.9),
        track("Fin d'après-midi", "Atelier du silence", "Travail de fond", 4.4),
      ],
    },
    sound: [TEAMS],
    perf: { cpu: 14, ram: 39 },
    notifications: [
      { app: "Outlook", title: "Service achats", body: "Demande de convocation", minutes: 9 },
      { app: "Calendrier", title: "Réunion de service", body: "Dans 15 minutes", minutes: 5 },
    ],
    clipboard: ["Madame, Monsieur, nous accusons réception de votre demande."],
    tabs: [tab("Demandes à traiter", "demandes"), tab("Tableau de suivi", "suivi")],
  },
  sacha: {
    screens: 2,
    windows: {
      Terminal: [
        { title: "zsh : appli-facturation", display: 2 },
        { title: "npm test : appli-facturation", display: 2 },
      ],
      "Visual Studio Code": [{ title: "factures.ts : appli-facturation" }],
      [CHROME]: [{ title: "Revue de code : relances" }, { title: "Tableau de bord des tests", display: 2 }],
      Slack: [{ title: "Équipe technique" }],
      Docker: [{ title: "Conteneurs" }],
    },
    media: {
      app: "Spotify",
      name: "Spotify",
      tracks: [
        track("Boucle de nuit", "Fréquence douce", "Code et café", 2.8),
        track("Compilation", "Fréquence douce", "Code et café", 3.2),
      ],
    },
    sound: ["Slack"],
    perf: { cpu: 52, ram: 71 },
    notifications: [
      { app: "GitLab", title: "Pipeline", body: "Le pipeline de la MR « Relances » est passé", minutes: 3 },
      { app: "Slack", title: "Équipe technique", body: "Mise en production à 16 h", minutes: 14 },
      { app: "Jira", title: "FACT-236", body: "Priorité relevée à Haute", minutes: 38 },
    ],
    clipboard: ["npm test -- --coverage", "git log --oneline -10"],
    tabs: [tab("Revue de code : relances", "revues"), tab("Tableau de bord des tests", "tests"), tab("Documentation de l'API", "docs")],
    jira: [
      {
        title: "À faire",
        issues: [
          issue("FACT-231", "Story", "Medium", "Export CSV des factures par client"),
          issue("FACT-236", "Bug", "High", "Arrondi de la TVA sur les avoirs"),
          issue("FACT-240", "Task", "Low", "Mettre à jour la dépendance du client PDF"),
        ],
      },
      {
        title: "En cours",
        issues: [
          issue("FACT-228", "Story", "High", "Relances automatiques des impayés"),
          issue("FACT-233", "Bug", "High", "Le filtre par date ignore le fuseau horaire"),
        ],
      },
      { title: "En revue", issues: [issue("FACT-225", "Bug", "Medium", "Doublon de facture à la double validation")] },
      {
        title: "Terminé",
        issues: [
          issue("FACT-219", "Bug", "High", "Page blanche sur une facture sans lignes"),
          issue("FACT-221", "Story", "Medium", "Pagination de la liste des clients"),
        ],
      },
    ],
    gitlab: {
      project: "equipe-demo/appli-facturation",
      mine: [
        {
          iid: 412,
          title: "Relances automatiques des impayés",
          author: "Sacha",
          pipeline: "success",
          given: 1,
          status: "mergeable",
          by: ["Inès"],
        },
        {
          iid: 409,
          title: "Corriger l'arrondi de la TVA sur les avoirs",
          author: "Sacha",
          pipeline: "running",
          given: 0,
          status: "ci_still_running",
        },
        {
          iid: 405,
          title: "Brouillon : export CSV des factures",
          draft: true,
          author: "Sacha",
          pipeline: "none",
          given: 0,
          status: "draft_status",
        },
      ],
      review: [
        { iid: 411, title: "Pagination de la liste des clients", author: "Inès", pipeline: "success", given: 0, status: "not_approved" },
        { iid: 407, title: "Mettre à jour le client PDF", author: "Malo", pipeline: "failed", given: 0, status: "ci_must_pass" },
      ],
      pipelines: [
        { ref: "main", id: 8841, pipeline: "running", minutes: 4 },
        { ref: "v2.8.0", id: 8836, pipeline: "success", durationSec: 372, minutes: 95 },
        {
          ref: "fix/FACT-236-arrondi-tva",
          id: 8839,
          pipeline: "failed",
          durationSec: 148,
          minutes: 22,
          failedJobs: ["tests-unitaires", "tests-e2e"],
        },
        { project: "equipe-demo/portail-clients", ref: "main", id: 3107, pipeline: "success", durationSec: 251, minutes: 180 },
      ],
    },
    agents: [
      {
        title: "Corriger l'arrondi de la TVA sur les avoirs",
        dir: "appli-facturation",
        branch: "fix/FACT-236-arrondi-tva",
        state: "waiting",
        minutes: 2,
        tool: "Bash",
        ticket: "FACT-236",
        mr: 409,
      },
      {
        title: "Relances automatiques des impayés",
        dir: "appli-facturation",
        branch: "feat/FACT-228-relances",
        state: "working",
        minutes: 9,
        tool: "Edit",
        ticket: "FACT-228",
        mr: 412,
      },
      {
        title: "Surveiller le pipeline de la MR 409",
        dir: "appli-facturation",
        branch: "fix/FACT-236-arrondi-tva",
        state: "working",
        minutes: 14,
        monitoring: true,
        ticket: "FACT-236",
        mr: 409,
      },
      {
        title: "Export CSV des factures par client",
        dir: "appli-facturation",
        branch: "feat/FACT-231-export-csv",
        state: "done",
        minutes: 26,
        worked: 18 * 60_000,
        ticket: "FACT-231",
        lines: ["Export ajouté et testé.", "3 tests ajoutés, la suite passe.", "Reste à relire les en-têtes."],
      },
      {
        title: "Mettre à jour le client PDF",
        dir: "appli-facturation",
        branch: "chore/FACT-240-client-pdf",
        state: "idle",
        minutes: 70,
        ticket: "FACT-240",
        lines: ["Prêt pour la revue."],
      },
    ],
  },
};
// Aimée : tâches de projets clients dans Jira.
MOCKS.aimee.jira = [
  {
    title: "À faire",
    issues: [
      issue("ATLAS-44", "Task", "High", "Valider le planning avec la cliente"),
      issue("BOREAL-22", "Task", "Medium", "Réserver la salle du comité"),
      issue("BOREAL-15", "Task", "Medium", "Préparer la présentation du lot 2"),
    ],
  },
  {
    title: "En cours",
    issues: [
      issue("ATLAS-41", "Task", "High", "Envoyer le compte rendu du comité"),
      issue("BOREAL-18", "Task", "Medium", "Relancer le fournisseur"),
    ],
  },
  { title: "Bloqué", issues: [issue("ATLAS-39", "Task", "Low", "Mettre à jour le suivi des projets")] },
  { title: "Terminé", issues: [issue("ATLAS-36", "Task", "Medium", "Organiser le point Atlas")] },
];

// --- Activation (persona lu dans .data/demo.json, gardé en mémoire)

const stateFile = () => path.join(config.dataDir, "demo.json");
let active: PersonaId | null | undefined;
let state: State | undefined;

/** Persona de la démo en cours sur l'ordinateur, null hors démo (pas celui d'une requête : voir `demoPersonaOf`). */
export async function activePersona(): Promise<PersonaId | null> {
  if (active === undefined) {
    const saved = await readJson<{ persona?: PersonaId }>(stateFile(), () => ({}));
    active = saved.persona && saved.persona in MOCKS ? saved.persona : null;
  }
  return active;
}

/** En-tête que la tablette pose sur chaque requête quand elle a choisi un persona. */
export const PERSONA_HEADER = "x-demo-persona";
export type PersonaHeaders = Record<string, string | string[] | undefined>;

/**
 * Persona dont les données fictives servent CETTE requête : celui de la démo en cours, seulement si la tablette
 * demande le même. Sans en-tête (tablette sans persona : essai, réinstallation) ou avec un autre : null, vraies données.
 */
export async function demoPersonaOf(headers?: PersonaHeaders): Promise<PersonaId | null> {
  const id = await activePersona();
  return id && headers?.[PERSONA_HEADER] === id ? id : null;
}

export function activateMock(id: PersonaId) {
  active = id;
  state = undefined;
}

export function deactivateMock() {
  active = null;
  state = undefined;
}

// --- État fictif

type State = {
  id: PersonaId;
  mock: PersonaMock;
  displays: Display[];
  windows: OpenApp[];
  audio: AudioState;
  master: MixerChannel;
  mic: MixerChannel;
  sound: MixerChannel[];
  scenes: {
    id: string;
    name: string;
    master: { volume: number; muted: boolean };
    apps: Record<string, { volume: number; muted: boolean }>;
  }[];
  track: number;
  playing: boolean;
  positionAt: { ms: number; at: number };
  dnd: boolean;
  clipboard: string;
  notifications: (MockNotification & { id: string; at: number })[];
  columns: Column[];
  mrs: { mine: MockMr[]; review: MockMr[] };
  agents: MockAgent[];
  /** Fenêtres gardées par « Je m'absente » : en mémoire, comme tout l'état fictif. */
  away: Saved | null;
};

const channel = (id: string, name: string, volume: number): MixerChannel => ({ id, name, volume, muted: false, level: 0 });

function build(id: PersonaId): State {
  const mock = MOCKS[id];
  const displays: Display[] = [{ id: 1, label: "Écran 1", main: true, x: 0, y: 0, w: 1920, h: 1080 }];
  if (mock.screens === 2) displays.push({ id: 2, label: "Écran 2", main: false, x: 1920, y: 0, w: 1920, h: 1080 });
  let index = 100;
  const windows = Object.entries(mock.windows).map(([name, wins], a): OpenApp => {
    return {
      key: name,
      name,
      windows: wins.map((w, i): AppWindow => ({
        index: ++index,
        title: w.title,
        minimized: w.minimized ?? false,
        fullscreen: false,
        display: w.display ?? 1,
        elsewhere: false,
        front: a === 0 && i === 0,
        maximized: w.maximized ?? false,
      })),
    };
  });
  const now = Date.now();
  return {
    id,
    mock,
    displays,
    windows,
    audio: { output: 55, muted: false, input: 70, micMuted: false },
    master: channel("master", "Sortie", 55),
    mic: channel("mic", "Micro", 70),
    sound: [mock.media.name, ...mock.sound].map((n) => channel(n, n.replace(/^Microsoft /, ""), 60)),
    scenes: [],
    track: 0,
    playing: true,
    positionAt: { ms: 42_000, at: now },
    dnd: false,
    clipboard: mock.clipboard[0],
    notifications: mock.notifications.map((n, i) => ({ ...n, id: `demo-n${i}`, at: now - n.minutes * 60_000 })),
    columns: structuredClone(mock.jira ?? []),
    mrs: { mine: [...(mock.gitlab?.mine ?? [])], review: [...(mock.gitlab?.review ?? [])] },
    agents: structuredClone(mock.agents ?? []),
    away: null,
  };
}

const current = (id: PersonaId) => (state?.id === id ? state : (state = build(id)));

// --- Lectures

const sha = (project: string, iid: number) =>
  (project + iid)
    .split("")
    .map((c) => c.charCodeAt(0).toString(16).padStart(2, "0"))
    .join("")
    .padEnd(40, "0")
    .slice(0, 40);

function mrsOf(s: State) {
  const project = s.mock.gitlab?.project ?? "equipe-demo/projet";
  const view = (m: MockMr): Mr => ({
    project,
    iid: m.iid,
    title: m.title,
    draft: m.draft ?? false,
    author: m.author,
    pipeline: m.pipeline,
    approvals: { given: m.given, required: 1, by: m.by ?? [] },
    status: m.status,
    statusLabel: statusLabel(m.status),
    mergeable: m.status === "mergeable",
    sha: sha(project, m.iid),
    updatedAt: new Date(Date.now() - m.iid * 90_000).toISOString(),
  });
  return { connected: true, canMerge: true, mine: s.mrs.mine.map(view), review: s.mrs.review.map(view) };
}

function pipelinesOf(s: State) {
  const main = s.mock.gitlab?.project ?? "equipe-demo/projet";
  const rows = (s.mock.gitlab?.pipelines ?? []).map((p): PipelineRow => ({
    project: p.project ?? main,
    ref: p.ref,
    id: p.id,
    pipeline: p.pipeline,
    durationSec: p.durationSec ?? null,
    ageSec: p.minutes * 60,
    failedJobs: p.failedJobs ?? [],
  }));
  return { connected: true, pipelines: sortPipelines(rows) };
}

function issuesOf(s: State) {
  const last = s.columns.length - 1;
  return {
    boardsChosen: true,
    columns: s.columns.map((c, i) => ({ title: c.title, issues: c.issues.map((x) => ({ ...x, done: i === last })) })),
  };
}

function agentsOf(s: State) {
  const now = Date.now();
  const sessions = s.agents.map((a, i) => {
    const done = a.state === "done" || a.state === "limit";
    return {
      id: `demo-a${i}`,
      name: a.dir,
      title: a.title,
      agent: "claude",
      dir: a.dir,
      branch: a.branch,
      ticket: a.ticket ?? null,
      mr: a.mr ?? null,
      monitoring: a.state === "working" && (a.monitoring ?? false),
      state: a.state,
      since: now - a.minutes * 60_000,
      waitingFor: a.state === "waiting" ? "permission prompt" : null,
      tool: a.tool ?? null,
      lines: done || a.state === "idle" ? (a.lines ?? []) : [],
      workedMs: done ? (a.worked ?? 0) : null,
      limit: null,
      resumeAt: null,
      resumeArmed: false,
      askId: null,
      askKind: null,
      canSend: a.state === "done" || a.state === "idle",
      canFocus: true,
      canInterrupt: a.state === "working",
    };
  });
  const counts = { working: 0, monitoring: 0, waiting: 0, done: 0, limit: 0, idle: 0 };
  for (const a of s.agents) {
    if (a.state === "working" && a.monitoring) counts.monitoring++;
    else counts[a.state]++;
  }
  const quota = s.agents.length
    ? {
        fiveHour: { used: 42, resetsAt: now + 2 * 3_600_000 },
        sevenDay: { used: 17, resetsAt: now + 4 * 86_400_000 },
        at: now - 60_000,
        ageMs: 60_000,
      }
    : null;
  return { sessions, counts, total: sessions.length, hooks: true, quota, available: s.agents.length > 0, now };
}

function mediaOf(s: State) {
  const t = s.mock.media.tracks[s.track];
  const position = Math.min(t.durationMs, s.positionAt.ms + (s.playing ? Date.now() - s.positionAt.at : 0));
  return {
    sessions: [
      {
        app: s.mock.media.app,
        name: s.mock.media.name,
        current: true,
        title: t.title,
        artist: t.artist,
        album: t.album,
        hasThumb: false,
        thumb: null,
        status: s.playing ? "playing" : "paused",
        canPlayPause: true,
        canNext: true,
        canPrevious: true,
        canSeek: true,
        positionMs: position,
        durationMs: t.durationMs,
      },
    ],
  };
}

/** Niveau sonore qui bouge : seule l'appli qui joue en a un. */
function mixerOf(s: State): MixerState {
  const t = Date.now() / 600;
  const level = (c: MixerChannel, i: number) => ({
    ...c,
    level: c.muted || !s.playing || i !== 0 ? 0 : Math.round(35 + 25 * Math.sin(t + i)),
  });
  return { master: level(s.master, 0), mic: { ...s.mic, level: 0 }, apps: s.sound.map(level) };
}

const wave = (base: number, spread: number, step: number) => Math.round(base + spread * Math.sin(Date.now() / step));

function perfOf(s: State) {
  const total = 16;
  const percent = Math.max(1, Math.min(99, s.mock.perf.ram + wave(0, 2, 5000)));
  return {
    cpu: Math.max(1, Math.min(99, s.mock.perf.cpu + wave(0, 6, 3000))),
    ram: { used: Math.round(((total * percent) / 100) * 10) / 10, total, percent },
  };
}

const openWindows = (s: State) => ({ apps: s.windows, displays: s.displays });

type Ctx = { s: State; body: any; query: any; params: any };
type Handler = (c: Ctx) => unknown | Promise<unknown>;
const ok = { ok: true };

/** `METHOD /route` (forme de la route Fastify, paramètres compris) → réponse fictive. */
const HANDLERS: Record<string, Handler> = {
  "GET /gitlab/mrs": ({ s }) => mrsOf(s),
  "POST /gitlab/mrs/merge": ({ s, body }) => {
    s.mrs.mine = s.mrs.mine.filter((m) => m.iid !== body.iid);
    s.mrs.review = s.mrs.review.filter((m) => m.iid !== body.iid);
    return ok;
  },
  "POST /gitlab/mrs/open": () => ok,
  "GET /gitlab/pipelines": ({ s }) => pipelinesOf(s),
  "POST /gitlab/pipelines/open": () => ok,

  "GET /jira/boards": ({ s }) => ({
    boards: [{ id: 1, name: s.mock.jira ? "Tableau de l'équipe" : "Aucun", project: s.id === "aimee" ? "ATLAS" : "FACT" }],
    selected: [1],
  }),
  "POST /jira/boards": () => ok,
  "GET /jira/issues": ({ s }) => issuesOf(s),
  "GET /jira/issues/:key/transitions": ({ s, params }) => ({
    transitions: s.columns
      .map((c, i) => ({ id: String(i + 1), name: c.title, to: c.title, needsJira: false }))
      .filter((t) => !s.columns[Number(t.id) - 1].issues.some((x) => x.key === params.key)),
  }),
  "POST /jira/issues/:key/transitions": ({ s, params, body }) => {
    const issue = s.columns.flatMap((c) => c.issues).find((x) => x.key === params.key);
    const target = s.columns[Number(body.id) - 1];
    if (issue && target) {
      for (const c of s.columns) c.issues = c.issues.filter((x) => x !== issue);
      target.issues.unshift(issue);
    }
    return ok;
  },
  "POST /jira/issues/:key/open": () => ok,

  "GET /agents": ({ s }) => agentsOf(s),
  "POST /agents/send": ({ s, body }) => agentAction(s, body.session, "working"),
  "POST /agents/interrupt": ({ s, body }) => agentAction(s, body.session, "idle"),
  "POST /agents/focus": () => ok,
  "POST /agents/resume": () => ok,

  "GET /windows/open": ({ s }) => openWindows(s),
  "POST /windows/open": ({ s, body }) => {
    controlWindow(s, body);
    return body.list === false ? ok : openWindows(s);
  },
  "POST /windows/page": () => ok,
  "POST /windows/page/move": () => ok,

  "GET /away": ({ s }) => ({ saved: s.away !== null, count: s.away?.windows.length ?? 0 }),
  "POST /away/start": ({ s }) => startAway(awayDeps(s)),
  "POST /away/resume": ({ s }) => resumeAway(awayDeps(s)),

  "GET /system/media": ({ s }) => mediaOf(s),
  "POST /system/media": ({ s, body }) => {
    const tracks = s.mock.media.tracks.length;
    const position = (mediaOf(s).sessions[0].positionMs as number) ?? 0;
    if (body.action === "play-pause") {
      s.positionAt = { ms: position, at: Date.now() };
      s.playing = !s.playing;
    } else if (body.action === "seek") s.positionAt = { ms: body.positionMs ?? 0, at: Date.now() };
    else {
      s.track = (s.track + (body.action === "next" ? 1 : tracks - 1)) % tracks;
      s.positionAt = { ms: 0, at: Date.now() };
      s.playing = true;
    }
    return { ok: true };
  },

  "GET /system/audio": ({ s }) => s.audio,
  "POST /system/audio": ({ s, body }) => {
    if (body.output !== undefined) s.audio.output = body.output;
    if (body.outputDelta !== undefined) s.audio.output = Math.max(0, Math.min(100, (s.audio.output ?? 0) + body.outputDelta));
    if (body.muted !== undefined) s.audio.muted = body.muted;
    if (body.micMuted !== undefined) s.audio.micMuted = body.micMuted;
    s.master = { ...s.master, volume: s.audio.output, muted: s.audio.muted };
    s.mic = { ...s.mic, muted: s.audio.micMuted };
    return s.audio;
  },
  "GET /system/audio/app": ({ s, query }) => appAudio(s, query.name),
  "POST /system/audio/app": ({ s, body }) => {
    changeChannel(s, { id: body.name, volume: body.volume, muted: body.muted });
    return appAudio(s, body.name);
  },
  "GET /system/mixer": ({ s }) => mixerOf(s),
  "POST /system/mixer": ({ s, body }) => {
    changeChannel(s, body);
    return mixerOf(s);
  },
  "GET /system/mixer/scenes": ({ s }) => ({ scenes: s.scenes }),
  "POST /system/mixer/scenes": ({ s, body }) => {
    const level = (c: MixerChannel) => ({ volume: c.volume ?? 0, muted: c.muted });
    const scene = {
      id: `demo-s${s.scenes.length + 1}`,
      name: body.name,
      master: level(s.master),
      apps: Object.fromEntries(s.sound.map((c) => [c.id, level(c)])),
    };
    s.scenes = [...s.scenes.filter((x) => x.name !== scene.name), scene].slice(-8);
    return scene;
  },
  "DELETE /system/mixer/scenes/:id": ({ s, params }) => {
    s.scenes = s.scenes.filter((x) => x.id !== params.id);
    return ok;
  },
  "POST /system/mixer/scenes/:id/apply": ({ s, params }) => {
    const scene = s.scenes.find((x) => x.id === params.id);
    if (scene) {
      changeChannel(s, { id: "master", ...scene.master });
      for (const [id, l] of Object.entries(scene.apps)) changeChannel(s, { id, ...l });
    }
    return { mixer: mixerOf(s), missing: [] };
  },

  "GET /system/perf": ({ s }) => perfOf(s),
  "GET /system/perf/detail": ({ s }) => perfOf(s),
  "GET /system/dnd": ({ s }) => ({ on: s.dnd }),
  "POST /system/dnd": ({ s, body }) => {
    s.dnd = body.on;
    return { on: s.dnd };
  },
  "GET /system/clipboard": ({ s }) => ({ kind: "text", text: s.clipboard }),
  "POST /system/clipboard": ({ s, body }) => {
    s.clipboard = body.text;
    return ok;
  },
  "GET /system/clipboard/history": ({ s }) => ({
    items: s.mock.clipboard.map((text, i) => ({ id: String(i + 1), text, at: Date.now() - (i + 1) * 240_000, secret: false })),
  }),
  "POST /system/clipboard/history/:id/copy": ({ s, params }) => {
    s.clipboard = s.mock.clipboard[Number(params.id) - 1] ?? s.clipboard;
    return ok;
  },
  "DELETE /system/clipboard/history/:id": () => ok,
  "DELETE /system/clipboard/history": () => ok,

  "GET /notifications": ({ s }) => ({
    items: s.notifications.map(({ id, app, title, body, at }) => ({ id, app, title, body, at })),
  }),
  "DELETE /notifications/:id": ({ s, params }) => {
    s.notifications = s.notifications.filter((n) => n.id !== params.id);
    return ok;
  },
  "DELETE /notifications": ({ s }) => {
    s.notifications = [];
    return ok;
  },

  "GET /browser/tabs": ({ s }) => ({
    extension: true,
    tabs: s.mock.tabs.map((t, i) => ({ id: `demo-t${i}`, title: t.title, url: t.url, browser: "Chrome", active: i === 0 })),
  }),
  "GET /browser/page": ({ s }) => ({ url: s.mock.tabs[0].url, title: s.mock.tabs[0].title, browser: "Chrome" }),
  "POST /browser/open": () => ok,

  // Jamais d'appel en démo : la tablette passerait seule sur son écran Réunion à chaque connexion (Deck V2) et quitterait
  // l'écran d'arrivée du persona. La veille des réunions suit (server.ts).
  "GET /deck/live": () => ({ apps: [], open: [] }),
  "GET /deck/voice": () => ({ voice: null }),

  // Agenda : l'agenda personnel posé par la démo (`.data/events.json`), jamais les agendas reliés de l'ordinateur.
  "GET /calendar": async ({ query }) => {
    const days = Math.min(Math.max(Number.parseInt(query.days ?? "1", 10) || 1, 1), 14);
    const events = await localEvents(...dayRange(days));
    return { source: "local", events: events.map(({ joinUrl, ...e }) => ({ ...e, joinable: !!joinUrl })) };
  },
  "GET /calendar/linked": () => ({ calendars: [] }),
};

function agentAction(s: State, session: string, to: MockAgent["state"]) {
  const a = s.agents[Number(String(session).replace("demo-a", ""))];
  if (a) {
    a.state = to;
    a.minutes = 0;
  }
  return ok;
}

function appAudio(s: State, name: string) {
  const c = s.sound.find((x) => x.id.toLowerCase() === String(name).toLowerCase());
  return {
    running: !!c,
    volume: c?.volume ?? null,
    muted: c?.muted ?? false,
    level: c && s.playing && !c.muted ? 40 : 0,
    capturing: false,
  };
}

function changeChannel(s: State, change: { id: string; volume?: number; muted?: boolean }) {
  const apply = (c: MixerChannel): MixerChannel => ({
    ...c,
    ...(change.volume !== undefined ? { volume: change.volume } : {}),
    ...(change.muted !== undefined ? { muted: change.muted } : {}),
  });
  if (change.id === "master") {
    s.master = apply(s.master);
    s.audio = { ...s.audio, output: s.master.volume, muted: s.master.muted };
  } else if (change.id === "mic") {
    s.mic = apply(s.mic);
    s.audio = { ...s.audio, input: s.mic.volume ?? 0, micMuted: s.mic.muted };
  } else s.sound = s.sound.map((c) => (c.id === change.id ? apply(c) : c));
}

/** « Je m'absente » sur les fenêtres fictives et une sauvegarde en mémoire : l'ordinateur n'est pas touché. */
const awayDeps = (s: State): AwayDeps => ({
  list: async () => openWindows(s),
  control: async (key, window, action, opts) => controlWindow(s, { key, action, window, display: opts?.display, layout: opts?.layout }),
  load: async () => s.away,
  save: async (saved) => void (s.away = saved),
  clear: async () => void (s.away = null),
});

function controlWindow(s: State, body: { key: string; action: string; window: { index: number }; display?: number; layout?: string }) {
  const app = s.windows.find((a) => a.key === body.key);
  const win = app?.windows.find((w) => w.index === body.window.index);
  if (!app || !win) return;
  const all = s.windows.flatMap((a) => a.windows);
  switch (body.action) {
    case "focus":
      for (const w of all) w.front = false;
      win.front = true;
      win.minimized = false;
      break;
    case "minimize":
      win.minimized = true;
      win.front = false;
      break;
    case "zoom":
      win.maximized = !win.maximized;
      break;
    case "fullscreen":
      win.fullscreen = !win.fullscreen;
      break;
    case "place":
      if (body.display !== undefined) win.display = body.display;
      win.maximized = body.layout === "max";
      break;
    case "close":
      app.windows = app.windows.filter((w) => w !== win);
      s.windows = s.windows.filter((a) => a.windows.length > 0);
      break;
  }
}

/**
 * Réponse fictive à la requête, ou `undefined` : hors démo, ou route sans données de l'ordinateur à remplacer.
 * `route` : la forme de la route Fastify (`/jira/issues/:key/transitions`).
 */
export async function demoAnswer(
  method: string,
  route: string,
  req: { body?: unknown; query?: unknown; params?: unknown; headers?: PersonaHeaders },
) {
  const handler = HANDLERS[`${method} ${route}`];
  if (!handler) return undefined;
  const id = await demoPersonaOf(req.headers);
  if (!id) return undefined;
  return handler({ s: current(id), body: req.body ?? {}, query: req.query ?? {}, params: req.params ?? {} });
}

/**
 * Ce que le persona offre en plus de ce que l'ordinateur offre : GitLab, Jira et agents ne dépendent que de ses
 * données fictives (même sans jeton ni Claude Code sur l'ordinateur, même avec : un persona qui ne les liste pas les cache).
 */
export async function demoFeatures(headers?: PersonaHeaders) {
  const id = await demoPersonaOf(headers);
  if (!id) return null;
  const m = MOCKS[id];
  return { agents: !!m.agents, gitlab: !!m.gitlab, jira: !!m.jira };
}

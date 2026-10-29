import path from "node:path";
import { config } from "./config.ts";
import type { Step } from "./deck.ts";
import { isInstalled } from "./os.ts";
import { readJson, removeJson, writeJson } from "./store.ts";
import { saveApps, type DeckApp, type PersoKey } from "./perso.ts";
import type { DailyTask, OneOffTask } from "./tasks.ts";
import type { LocalEvent } from "./events.ts";

/**
 * Démo produit : six personas dont on pose les données fictives (touches perso, page Apps, tâches, agenda) à la place
 * de celles de l'ordinateur. Les fichiers d'origine sont gardés dans .data/demo.json au premier choix et rendus par
 * `quitDemo`. Passer d'un persona à l'autre garde la sauvegarde d'avant la démo. Rien ici n'est un vrai compte ni un
 * vrai document.
 */

export const PERSONA_IDS = ["sylvie", "julie", "camille", "thomas", "karim", "leo"] as const;
export type PersonaId = (typeof PERSONA_IDS)[number];

/** `day` : jours après aujourd'hui ; `at` : "HH:MM" ; `remind` : minutes avant, pour le rappel parlé. */
type DemoEvent = { title: string; day: number; at: string; minutes: number; remind?: number };
type DemoTask = { label: string; days?: number[]; remindAt?: string };
type Persona = { keys: PersoKey[]; apps: DeckApp[]; daily: DemoTask[]; once: DemoTask[]; events: DemoEvent[] };

const app = (name: string): Step => ({ type: "app", name });
const keys = (combo: string, inApp?: string): Step => ({ type: "keys", keys: combo, ...(inApp ? { app: inApp } : {}) });
const text = (value: string): Step => ({ type: "text", text: value });
const place = (name: string, layout: "left" | "right" | "max"): Step => ({ type: "window", app: name, layout });
const key = (label: string, glyph: string, color: string, ...steps: Step[]): PersoKey => ({ label, glyph, color, steps });
const label = (name: string) => ({ label: name.replace(/^(Microsoft|Google) /, ""), name });

const EXCEL = "Microsoft Excel";
const OUTLOOK = "Microsoft Outlook";
const TEAMS = "Microsoft Teams";
const WORD = "Microsoft Word";
const CHROME = "Google Chrome";

const PERSONAS: Record<PersonaId, Persona> = {
  sylvie: {
    keys: [
      key("Excel", "📊", "#1D6F42", app(EXCEL)),
      key("Enregistrer", "💾", "#4A5568", keys("cmd+s", EXCEL)),
      key("Rechercher", "🔍", "#4A5568", keys("cmd+f", EXCEL)),
      key("Format cellule", "🔢", "#4A5568", keys("cmd+1", EXCEL)),
      key("Annuler", "↩️", "#4A5568", keys("cmd+z", EXCEL)),
      key("Clôture", "🗂", "#B7791F", app(EXCEL), app(OUTLOOK), place(EXCEL, "left"), place(OUTLOOK, "right")),
      key("Règlement reçu", "✅", "#2F855A", text("Règlement reçu par virement, lettrage effectué sur le compte client.")),
      key(
        "Pièce manquante",
        "📎",
        "#C05621",
        text("Bonjour, il manque une pièce justificative pour clore ce dossier. Pouvez-vous nous la transmettre ?"),
      ),
      key("Écriture de régul.", "🧾", "#6B46C1", text("Écriture de régularisation de fin de période : charge à payer.")),
    ],
    apps: [label(EXCEL), label(WORD), label(OUTLOOK), label(CHROME), label(TEAMS)],
    daily: [
      { label: "Rapprochement bancaire", days: [1, 2, 3, 4, 5] },
      { label: "Relances clients", days: [2, 4] },
    ],
    once: [{ label: "Envoyer le dossier de clôture au cabinet" }, { label: "Vérifier la balance des tiers" }],
    events: [
      { title: "Point clôture mensuelle", day: 0, at: "14:00", minutes: 45, remind: 10 },
      { title: "Rendez-vous client : bilan", day: 1, at: "10:00", minutes: 60 },
      { title: "Date limite de la déclaration de TVA", day: 3, at: "09:00", minutes: 30 },
    ],
  },
  julie: {
    keys: [
      key("Canva", "🎨", "#00A3A3", app("Canva")),
      key("CRM", "👥", "#2B6CB0", { type: "url", url: "https://app.hubspot.com" }),
      key("Réseaux", "📣", "#C53030", { type: "url", url: "https://www.linkedin.com" }),
      key("Statistiques", "📈", "#2F855A", { type: "url", url: "https://analytics.google.com" }),
      key("Diapos", "🖥", "#C05621", app("Microsoft PowerPoint")),
      key(
        "Projet Printemps",
        "🌸",
        "#B83280",
        app(CHROME),
        app("Microsoft PowerPoint"),
        place(CHROME, "left"),
        place("Microsoft PowerPoint", "right"),
      ),
      key("Merci du retour", "🙏", "#4A5568", text("Merci pour votre retour, je reviens vers vous avant la fin de la semaine.")),
      key(
        "Relance e-mailing",
        "✉️",
        "#6B46C1",
        text("Bonjour, nous reprenons contact au sujet de notre dernière campagne. Avez-vous un moment pour en parler ?"),
      ),
      key("Visio", "🎥", "#2B6CB0", app(TEAMS)),
    ],
    apps: [label(CHROME), label("Canva"), label("Microsoft PowerPoint"), label(TEAMS), label(OUTLOOK), label("Slack")],
    daily: [{ label: "Tableau de bord des campagnes", days: [1, 2, 3, 4, 5] }],
    once: [{ label: "Valider le calendrier éditorial" }, { label: "Préparer le brief de la prochaine newsletter" }],
    events: [
      { title: "Point hebdo marketing", day: 0, at: "09:30", minutes: 30, remind: 5 },
      { title: "Revue des campagnes en visio", day: 1, at: "14:00", minutes: 45 },
      { title: "Atelier réseaux sociaux", day: 2, at: "11:00", minutes: 60 },
      { title: "Présentation du plan trimestriel", day: 4, at: "15:00", minutes: 60 },
    ],
  },
  camille: {
    keys: [
      key("Mon espace", "🏠", "#2B6CB0", app(OUTLOOK), app(TEAMS), place(OUTLOOK, "left"), place(TEAMS, "right")),
      key("Réunion", "🎥", "#2F855A", app(TEAMS)),
      key("Couper le micro", "🎙", "#C53030", keys("cmd+shift+m", TEAMS)),
      key("Mode travail", "💼", "#4A5568", app(OUTLOOK), app(TEAMS), app(CHROME)),
      key("Mode perso", "🌿", "#B83280", app(CHROME), { type: "media", command: "play-pause" }),
      key("Écran verrouillé", "🔒", "#6B46C1", { type: "system", command: "lock" }),
    ],
    apps: [label(OUTLOOK), label(TEAMS), label(CHROME), label(WORD)],
    daily: [
      { label: "Point rapide avec l'équipe", days: [1, 2, 3, 4, 5], remindAt: "09:15" },
      { label: "Chercher les enfants", days: [1, 2, 4, 5], remindAt: "16:30" },
    ],
    once: [{ label: "Répondre au compte rendu du comité de pilotage" }],
    events: [
      { title: "Réunion d'équipe projet", day: 0, at: "10:00", minutes: 30, remind: 5 },
      { title: "Comité de pilotage", day: 1, at: "14:00", minutes: 60, remind: 10 },
      { title: "Rendez-vous école", day: 2, at: "17:30", minutes: 30 },
    ],
  },
  thomas: {
    keys: [
      key("Projet Atlas", "🧭", "#2B6CB0", app(CHROME), app("Notion"), place(CHROME, "left"), place("Notion", "right")),
      key("Projet Boréal", "❄️", "#00A3A3", app(CHROME), app("Slack"), place(CHROME, "left"), place("Slack", "right")),
      key("Fenêtres à gauche", "◀️", "#4A5568", place(CHROME, "left")),
      key("Plein écran", "⛶", "#4A5568", place(CHROME, "max")),
      key("Capture", "📸", "#B7791F", { type: "system", command: "screenshot-clipboard" }),
      key("Note rapide", "📝", "#6B46C1", text("À faire : ")),
    ],
    apps: [label(OUTLOOK), label(TEAMS), label(CHROME), label("Notion"), label("Slack")],
    daily: [
      { label: "Relire les tâches du jour", days: [1, 2, 3, 4, 5], remindAt: "09:00" },
      { label: "Mettre à jour le suivi des projets", days: [5], remindAt: "16:00" },
    ],
    once: [
      { label: "Envoyer le compte rendu de la réunion Atlas", remindAt: "11:30" },
      { label: "Réserver la salle du comité" },
      { label: "Relancer le fournisseur" },
    ],
    events: [
      { title: "Point Atlas", day: 0, at: "09:30", minutes: 30, remind: 10 },
      { title: "Revue Boréal", day: 0, at: "15:00", minutes: 45, remind: 10 },
      { title: "Comité de suivi", day: 1, at: "11:00", minutes: 60 },
      { title: "Point fournisseur", day: 3, at: "14:30", minutes: 30 },
    ],
  },
  karim: {
    keys: [
      key("Copier", "📄", "#2B6CB0", keys("cmd+c")),
      key("Coller", "📋", "#2F855A", keys("cmd+v")),
      key("Enregistrer", "💾", "#6B46C1", keys("cmd+s")),
      key("Annuler", "↩️", "#C05621", keys("cmd+z")),
      key("Imprimer", "🖨", "#4A5568", keys("cmd+p")),
      key("Courrier type", "✉️", "#B7791F", app(WORD)),
      key(
        "Accusé réception",
        "📨",
        "#2B6CB0",
        text("Madame, Monsieur,\n\nNous accusons réception de votre demande et la traitons dans les meilleurs délais.\n\nCordialement,"),
      ),
      key(
        "Pièces à fournir",
        "📎",
        "#C53030",
        text(
          "Madame, Monsieur,\n\nPour poursuivre le traitement de votre dossier, merci de nous transmettre les pièces demandées.\n\nCordialement,",
        ),
      ),
    ],
    apps: [label(WORD), label(EXCEL), label(OUTLOOK), label(CHROME)],
    daily: [
      { label: "Traiter les demandes reçues", days: [1, 2, 3, 4, 5] },
      { label: "Classer les courriers envoyés", days: [5] },
    ],
    once: [{ label: "Envoyer les convocations" }, { label: "Mettre à jour le tableau de suivi" }],
    events: [
      { title: "Réunion de service", day: 0, at: "10:00", minutes: 45, remind: 5 },
      { title: "Rendez-vous avec un fournisseur", day: 2, at: "14:00", minutes: 30 },
    ],
  },
  leo: {
    keys: [
      key("Tests", "🧪", "#2F855A", app("Terminal"), text("npm test"), keys("enter")),
      key("Build", "🔨", "#B7791F", app("Terminal"), text("npm run build"), keys("enter")),
      key("Git status", "🌿", "#4A5568", app("Terminal"), text("git status"), keys("enter")),
      key("Git pull", "⬇️", "#2B6CB0", app("Terminal"), text("git pull --rebase"), keys("enter")),
      key("Déployer", "🚀", "#C53030", app("Terminal"), text("./scripts/deploy.sh"), keys("enter")),
      key("Dev / prod", "🔀", "#6B46C1", app(CHROME), place(CHROME, "right")),
      key("Éditeur", "💻", "#2B6CB0", app("Visual Studio Code")),
      key("Revues", "👀", "#00A3A3", { type: "url", url: "https://gitlab.com" }),
    ],
    apps: [label("Terminal"), label("Visual Studio Code"), label(CHROME), label("Slack"), label("Docker")],
    daily: [{ label: "Lire les revues de code en attente", days: [1, 2, 3, 4, 5] }],
    once: [
      { label: "Corriger le ticket de la file d'attente" },
      { label: "Préparer la mise en production" },
      { label: "Relire la revue de code ouverte" },
    ],
    events: [
      { title: "Daily de l'équipe technique", day: 0, at: "09:45", minutes: 15, remind: 5 },
      { title: "Revue de code en binôme", day: 1, at: "15:00", minutes: 45 },
      { title: "Mise en production", day: 2, at: "16:00", minutes: 60, remind: 15 },
    ],
  },
};

/** Fichiers de .data/ remplacés par un persona. */
const FILES = ["keys.json", "apps.json", "tasks.json", "events.json"] as const;
const file = (name: string) => path.join(config.dataDir, name);
const stateFile = file("demo.json");

type DemoState = { persona: PersonaId; backup: Record<string, object | null> };

/** Touches posées, telles que la tablette les affiche (`k-<emplacement>`). */
export type DemoKey = { id: string; label: string; glyph: string | undefined; color: string | undefined };

const at = (day: number, hhmm: string) => {
  const d = new Date();
  d.setDate(d.getDate() + day);
  const [h, m] = hhmm.split(":").map(Number);
  d.setHours(h, m, 0, 0);
  return d.getTime();
};

function tasksOf(id: PersonaId, data: Persona) {
  const recurring: DailyTask[] = data.daily.map((t, i) => {
    const task: DailyTask = { id: `demo-${id}-r${i}`, label: t.label, days: t.days };
    if (t.remindAt) Object.assign(task, { remindAt: t.remindAt, repeatMinutes: 30, remindUntil: "19:00" });
    return task;
  });
  const items: OneOffTask[] = data.once.map((t, i) => {
    const [h, m] = (t.remindAt ?? "").split(":").map(Number);
    const remind = t.remindAt ? new Date().setHours(h, m, 0, 0) : undefined;
    return { id: `demo-${id}-o${i}`, label: t.label, createdMs: Date.now(), ...(remind ? { remindAtMs: remind } : {}) };
  });
  return { done: {}, items, recurring };
}

function eventsOf(id: PersonaId, data: Persona): { items: LocalEvent[] } {
  return {
    items: data.events.map((e, i) => ({
      id: `demo-${id}-${i}`,
      title: e.title,
      startMs: at(e.day, e.at),
      endMs: at(e.day, e.at) + e.minutes * 60_000,
      allDay: false,
      ...(e.remind ? { remindBefore: e.remind } : {}),
    })),
  };
}

/**
 * Pose le persona. Au premier appel de la démo, garde les fichiers d'origine ; ensuite ils restent ceux d'avant la
 * démo. La page Apps ne garde que les applis installées sur l'ordinateur (elle retombe sur le profil de base si aucune).
 */
export async function setPersona(id: PersonaId): Promise<DemoKey[]> {
  const data = PERSONAS[id];
  const previous = await readJson<Partial<DemoState>>(stateFile, () => ({}));
  const backup =
    previous.backup ??
    Object.fromEntries(await Promise.all(FILES.map(async (f) => [f, await readJson<object | null>(file(f), () => null)])));
  await writeJson(stateFile, { persona: id, backup });

  const installed = await Promise.all(data.apps.map((a) => isInstalled(a.name).catch(() => true)));
  const apps = data.apps.filter((_, i) => installed[i]);
  await writeJson(file("keys.json"), { slots: data.keys });
  await saveApps(apps.length ? apps : null);
  await writeJson(file("tasks.json"), tasksOf(id, data));
  await writeJson(file("events.json"), eventsOf(id, data));
  return data.keys.map((k, slot) => ({ id: `k-${slot}`, label: k.label, glyph: k.glyph, color: k.color }));
}

/** Rend les fichiers d'avant la démo. Faux s'il n'y avait pas de démo en cours. */
export async function quitDemo(): Promise<boolean> {
  const state = await readJson<Partial<DemoState>>(stateFile, () => ({}));
  if (!state.backup) return false;
  for (const f of FILES) {
    const saved = state.backup[f];
    if (saved) await writeJson(file(f), saved);
    else await removeJson(file(f));
  }
  await removeJson(stateFile);
  return true;
}

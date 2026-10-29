import path from "node:path";
import { config } from "./config.ts";
import type { Step } from "./deck.ts";
import { isInstalled } from "./os.ts";
import { readJson, removeJson, writeJson } from "./store.ts";
import { saveApps, type DeckApp, type PersoKey } from "./perso.ts";
import { activateMock, deactivateMock } from "./demo-mock.ts";
import { forgetPluginChoices, PLUGIN_IDS } from "./plugins.ts";
import type { DailyTask, OneOffTask } from "./tasks.ts";
import type { LocalEvent } from "./events.ts";

/**
 * Démo produit : six personas dont on pose les données fictives (touches perso, pages de touches nommées, page Apps,
 * plugins, tâches, agenda) à la place de celles de l'ordinateur. Les fichiers d'origine sont gardés dans .data/demo.json
 * au premier choix et rendus par `quitDemo`. Passer d'un persona à l'autre garde la sauvegarde d'avant la démo. Rien
 * ici n'est un vrai compte ni un vrai document, et aucune touche n'exécute de commande destructrice. Tant que la démo
 * dure, les routes qui lisent l'ordinateur (GitLab, Jira, agents, fenêtres, média, son, courrier…) rendent aussi les
 * données fictives du persona : voir demo-mock.ts.
 *
 * `keys` : page Perso (et widget Touches de l'Accueil) ; `pages` : pages de touches nommées après Perso, leurs titres
 * sont ceux que la tablette met dans l'ordre des onglets (ui/home/Personas.kt) ; `off` : plugins coupés (plugins.ts).
 */

export const PERSONA_IDS = ["nour", "gwen", "camille", "aimee", "sam", "sacha"] as const;
export type PersonaId = (typeof PERSONA_IDS)[number];

/** `day` : jours après aujourd'hui ; `at` : "HH:MM" ; `remind` : minutes avant, pour le rappel parlé. */
type DemoEvent = { title: string; day: number; at: string; minutes: number; remind?: number };
type DemoTask = { label: string; days?: number[]; remindAt?: string };
type DemoPage = { title: string; keys: PersoKey[] };
type Persona = {
  keys: PersoKey[];
  pages: DemoPage[];
  apps: DeckApp[];
  daily: DemoTask[];
  once: DemoTask[];
  events: DemoEvent[];
  off: string[];
};

const app = (name: string): Step => ({ type: "app", name });
const keys = (combo: string, inApp?: string): Step => ({ type: "keys", keys: combo, ...(inApp ? { app: inApp } : {}) });
const text = (value: string): Step => ({ type: "text", text: value });
const place = (name: string, layout: "left" | "right" | "max"): Step => ({ type: "window", app: name, layout });
const key = (label: string, glyph: string, color: string, ...steps: Step[]): PersoKey => ({ label, glyph, color, steps });
const page = (title: string, ...keys: PersoKey[]): DemoPage => ({ title, keys });
const site = (url: string): Step => ({ type: "url", url });
const term = (name: string, glyph: string, color: string, command: string) =>
  key(name, glyph, color, app("Terminal"), text(command), keys("enter"));
const folder = (name: string, dir: string) => key(name, "📁", "#B7791F", app("Finder"), keys("cmd+shift+g"), text(dir), keys("enter"));
const label = (name: string) => ({ label: name.replace(/^(Microsoft|Google) /, ""), name });

const EXCEL = "Microsoft Excel";
const OUTLOOK = "Microsoft Outlook";
const TEAMS = "Microsoft Teams";
const WORD = "Microsoft Word";
const CHROME = "Google Chrome";
const POWERPOINT = "Microsoft PowerPoint";
const KEYNOTE = "Keynote";

/** Touches de Sam : page Perso, widget Touches de l'Accueil et page « Mes touches » (grosses touches avec le thème Contraste élevé). */
const SAM_KEYS: PersoKey[] = [
  key("Copier", "📄", "#2B6CB0", keys("cmd+c")),
  key("Coller", "📋", "#2F855A", keys("cmd+v")),
  key("Enregistrer", "💾", "#6B46C1", keys("cmd+s")),
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
];

const PERSONAS: Record<PersonaId, Persona> = {
  nour: {
    keys: [
      key("Excel", "📊", "#1D6F42", app(EXCEL)),
      key("Clôture", "🗂", "#B7791F", app(EXCEL), app(OUTLOOK), place(EXCEL, "left"), place(OUTLOOK, "right")),
      key("Enregistrer", "💾", "#4A5568", keys("cmd+s", EXCEL)),
      key("Règlement reçu", "✅", "#2F855A", text("Règlement reçu par virement, lettrage effectué sur le compte client.")),
      key(
        "Pièce manquante",
        "📎",
        "#C05621",
        text("Bonjour, il manque une pièce justificative pour clore ce dossier. Pouvez-vous nous la transmettre ?"),
      ),
      key("Écriture de régul.", "🧾", "#6B46C1", text("Écriture de régularisation de fin de période : charge à payer.")),
    ],
    pages: [
      page(
        "Excel",
        key("Enregistrer", "💾", "#1D6F42", keys("cmd+s", EXCEL)),
        key("Rechercher", "🔍", "#4A5568", keys("cmd+f", EXCEL)),
        key("Format cellule", "🔢", "#4A5568", keys("cmd+1", EXCEL)),
        key("Annuler", "↩️", "#C05621", keys("cmd+z", EXCEL)),
        key("Rétablir", "↪️", "#C05621", keys("cmd+y", EXCEL)),
        key("Somme auto", "Σ", "#2B6CB0", keys("cmd+shift+t", EXCEL)),
        key("Filtrer", "🔽", "#6B46C1", keys("cmd+shift+f", EXCEL)),
        key("Tout sélectionner", "▦", "#4A5568", keys("cmd+a", EXCEL)),
      ),
      page(
        "Libellés",
        key("Règlement reçu", "✅", "#2F855A", text("Règlement reçu par virement, lettrage effectué sur le compte client.")),
        key(
          "Pièce manquante",
          "📎",
          "#C05621",
          text("Bonjour, il manque une pièce justificative pour clore ce dossier. Pouvez-vous nous la transmettre ?"),
        ),
        key("Écriture de régul.", "🧾", "#6B46C1", text("Écriture de régularisation de fin de période : charge à payer.")),
        key(
          "Relance facture",
          "⏰",
          "#C53030",
          text("Bonjour, sauf erreur de notre part, la facture reste impayée à ce jour. Pouvons-nous convenir d'une date de règlement ?"),
        ),
        key("Rapprochement validé", "🏦", "#2B6CB0", text("Rapprochement bancaire validé, aucun écart constaté.")),
        key("Avoir émis", "↩️", "#B7791F", text("Avoir émis pour annuler la facture, à imputer sur le prochain règlement.")),
      ),
      page(
        "Dossiers clients",
        folder("Durand SARL", "~/Documents/Clients/Durand SARL"),
        folder("Martin et Fils", "~/Documents/Clients/Martin et Fils"),
        folder("Boulangerie Lefèvre", "~/Documents/Clients/Boulangerie Lefèvre"),
        folder("Cabinet Moreau", "~/Documents/Clients/Cabinet Moreau"),
        folder("Garage du Centre", "~/Documents/Clients/Garage du Centre"),
        folder("Clôture 2026", "~/Documents/Clients/Clôture 2026"),
      ),
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
    off: ["discord", "mixer", "pad", "media", "claude", "jira"],
  },
  gwen: {
    keys: [
      key("Canva", "🎨", "#00A3A3", app("Canva")),
      key("CRM", "👥", "#2B6CB0", site("https://app.hubspot.com")),
      key("Statistiques", "📈", "#2F855A", site("https://analytics.google.com")),
      key("Diapos", "🖥", "#C05621", app(POWERPOINT)),
      key("Projet Printemps", "🌸", "#B83280", app(CHROME), app(POWERPOINT), place(CHROME, "left"), place(POWERPOINT, "right")),
      key("Visio", "🎥", "#2B6CB0", app(TEAMS)),
    ],
    pages: [
      page(
        "Diapos",
        key("PPT suivante", "▶️", "#C05621", keys("right", POWERPOINT)),
        key("PPT précédente", "◀️", "#C05621", keys("left", POWERPOINT)),
        key("PPT début", "⏮", "#C05621", keys("1", POWERPOINT), keys("enter")),
        key("PPT fin", "⏹", "#C05621", keys("escape", POWERPOINT)),
        key("Keynote suivante", "▶️", "#2B6CB0", keys("right", KEYNOTE)),
        key("Keynote précédente", "◀️", "#2B6CB0", keys("left", KEYNOTE)),
        key("Keynote début", "⏮", "#2B6CB0", keys("1", KEYNOTE), keys("enter")),
        key("Keynote fin", "⏹", "#2B6CB0", keys("escape", KEYNOTE)),
      ),
      page(
        "Réseaux",
        key("LinkedIn", "💼", "#2B6CB0", site("https://www.linkedin.com")),
        key("Instagram", "📷", "#B83280", site("https://www.instagram.com")),
        key("Facebook", "👍", "#2B6CB0", site("https://www.facebook.com")),
        key("YouTube", "▶️", "#C53030", site("https://studio.youtube.com")),
        key("Planification", "🗓", "#6B46C1", site("https://buffer.com")),
        key("Nouveau post", "✍️", "#4A5568", text("Nouveau post : ")),
      ),
      page(
        "Modèles",
        key("Merci du retour", "🙏", "#4A5568", text("Merci pour votre retour, je reviens vers vous avant la fin de la semaine.")),
        key(
          "Relance e-mailing",
          "✉️",
          "#6B46C1",
          text("Bonjour, nous reprenons contact au sujet de notre dernière campagne. Avez-vous un moment pour en parler ?"),
        ),
        key(
          "Demande de visuel",
          "🖼",
          "#00A3A3",
          text("Bonjour, pourriez-vous nous envoyer le visuel en haute définition, au format PNG ?"),
        ),
        key(
          "Invitation webinaire",
          "🎤",
          "#C05621",
          text("Bonjour, nous vous invitons à notre prochain webinaire. L'inscription est gratuite, le lien est dans ce message."),
        ),
        key(
          "Merci aux participants",
          "🎉",
          "#2F855A",
          text("Merci d'avoir participé au webinaire, le replay et les diapositives arrivent dans la journée."),
        ),
        key("Brief agence", "📝", "#B7791F", text("Brief : objectif, cible, canaux, budget, calendrier, indicateurs de réussite.")),
      ),
    ],
    apps: [label(CHROME), label("Canva"), label(POWERPOINT), label(TEAMS), label(OUTLOOK), label("Slack")],
    daily: [{ label: "Tableau de bord des campagnes", days: [1, 2, 3, 4, 5] }],
    once: [{ label: "Valider le calendrier éditorial" }, { label: "Préparer le brief de la prochaine newsletter" }],
    events: [
      { title: "Point hebdo marketing", day: 0, at: "09:30", minutes: 30, remind: 5 },
      { title: "Revue des campagnes en visio", day: 1, at: "14:00", minutes: 45 },
      { title: "Atelier réseaux sociaux", day: 2, at: "11:00", minutes: 60 },
      { title: "Présentation du plan trimestriel", day: 4, at: "15:00", minutes: 60 },
    ],
    off: ["discord", "claude", "pad", "jira", "open", "mixer"],
  },
  camille: {
    keys: [
      key("Mon espace", "🏠", "#2B6CB0", app(OUTLOOK), app(TEAMS), place(OUTLOOK, "left"), place(TEAMS, "right")),
      key("Réunion", "🎥", "#2F855A", app(TEAMS)),
      key("Couper le micro", "🎙", "#C53030", keys("cmd+shift+m", TEAMS)),
      key("Écran verrouillé", "🔒", "#6B46C1", { type: "system", command: "lock" }),
    ],
    pages: [
      page(
        "Travail",
        key("Mode travail", "💼", "#4A5568", app(OUTLOOK), app(TEAMS), app(CHROME)),
        key("Mon espace", "🏠", "#2B6CB0", app(OUTLOOK), app(TEAMS), place(OUTLOOK, "left"), place(TEAMS, "right")),
        key("Réunion", "🎥", "#2F855A", app(TEAMS)),
        key("Couper le micro", "🎙", "#C53030", keys("cmd+shift+m", TEAMS)),
        key("Lever la main", "✋", "#B7791F", keys("cmd+shift+k", TEAMS)),
        key("Compte rendu", "🗒", "#6B46C1", text("Compte rendu : décisions, actions (qui, quoi, pour quand), prochaine réunion.")),
      ),
      page(
        "Maison",
        key("Mode perso", "🌿", "#B83280", app(CHROME), { type: "media", command: "play-pause" }),
        key("Musique", "🎵", "#2B6CB0", { type: "media", command: "play-pause" }),
        key("Suivant", "⏭", "#2B6CB0", { type: "media", command: "next" }),
        key("Recettes", "🍲", "#C05621", site("https://www.marmiton.org")),
        key("Courses", "🛒", "#2F855A", site("https://www.carrefour.fr")),
        key("École", "🎒", "#6B46C1", site("https://www.ecoledirecte.com")),
      ),
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
    off: ["discord", "claude", "pad", "mixer", "jira", "open"],
  },
  aimee: {
    keys: [
      key("Projet Atlas", "🧭", "#2B6CB0", app(CHROME), app("Notion"), place(CHROME, "left"), place("Notion", "right")),
      key("Projet Boréal", "❄️", "#00A3A3", app(CHROME), app("Slack"), place(CHROME, "left"), place("Slack", "right")),
      key("Plein écran", "⛶", "#4A5568", place(CHROME, "max")),
      key("Capture", "📸", "#B7791F", { type: "system", command: "screenshot-clipboard" }),
      key("Note rapide", "📝", "#6B46C1", text("À faire : ")),
    ],
    pages: [
      page(
        "Projets",
        key("Atlas", "🧭", "#2B6CB0", app(CHROME), app("Notion"), place(CHROME, "left"), place("Notion", "right")),
        key("Boréal", "❄️", "#00A3A3", app(CHROME), app("Slack"), place(CHROME, "left"), place("Slack", "right")),
        folder("Dossier Atlas", "~/Projets/Atlas"),
        folder("Dossier Boréal", "~/Projets/Boréal"),
        key("Atlas : suivi", "📊", "#2B6CB0", site("https://www.notion.so")),
        key("Boréal : tickets", "🎫", "#00A3A3", site("https://www.atlassian.com")),
        key("Comité de suivi", "👥", "#6B46C1", app(TEAMS), app("Notion"), place(TEAMS, "left"), place("Notion", "right")),
        key("Fenêtre à gauche", "◀️", "#4A5568", place(CHROME, "left")),
      ),
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
    off: ["discord", "claude", "pad", "mixer", "media"],
  },
  sam: {
    keys: SAM_KEYS,
    pages: [{ title: "Mes touches", keys: SAM_KEYS }],
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
    off: ["discord", "claude", "jira", "open", "mixer", "pad", "media"],
  },
  sacha: {
    keys: [
      term("Tests", "🧪", "#2F855A", "npm test"),
      term("Build", "🔨", "#B7791F", "npm run build"),
      term("Git status", "🌿", "#4A5568", "git status"),
      key("Éditeur", "💻", "#2B6CB0", app("Visual Studio Code")),
      key("Dev / prod", "🔀", "#6B46C1", app(CHROME), place(CHROME, "right")),
      key("Revues", "👀", "#00A3A3", site("https://gitlab.com")),
    ],
    pages: [
      page(
        "Git",
        term("Status", "🌿", "#4A5568", "git status"),
        term("Diff", "🔍", "#2B6CB0", "git diff --stat"),
        term("Pull", "⬇️", "#2B6CB0", "git pull --rebase"),
        term("Log", "📜", "#4A5568", "git log --oneline -10"),
        term("Branches", "🌳", "#2F855A", "git branch -vv"),
        term("Stash", "📦", "#B7791F", "git stash list"),
      ),
      page(
        "Tests",
        term("Tout", "🧪", "#2F855A", "npm test"),
        term("Lint", "🧹", "#B7791F", "npm run lint"),
        term("Types", "🔠", "#2B6CB0", "npm run typecheck"),
        term("Couverture", "📐", "#6B46C1", "npm test -- --coverage"),
        key("Un fichier", "🎯", "#4A5568", app("Terminal"), text("npm test -- ")),
      ),
      page(
        "Déploiement",
        term("Build", "🔨", "#B7791F", "npm run build"),
        term("Simulation", "🚀", "#C53030", "./scripts/deploy.sh --dry-run"),
        term("Conteneurs", "🐳", "#2B6CB0", "docker compose ps"),
        term("Pods", "☸️", "#6B46C1", "kubectl get pods"),
        term("Versions", "🏷", "#4A5568", "git tag --list --sort=-v:refname"),
        key("Dev / prod", "🔀", "#6B46C1", app(CHROME), place(CHROME, "right")),
      ),
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
    off: ["discord", "teams", "mixer", "pad", "media"],
  },
};

/** Fichiers de .data/ remplacés par un persona. */
const FILES = ["keys.json", "pages.json", "apps.json", "plugins.json", "tasks.json", "events.json"] as const;
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
 * Les plugins sont tous choisis (coupés ou gardés) : la tablette ne propose pas l'écran de première utilisation.
 */
export async function setPersona(id: PersonaId): Promise<DemoKey[]> {
  const data = PERSONAS[id];
  const previous = await readJson<Partial<DemoState>>(stateFile, () => ({}));
  const backup = previous.backup ?? {};
  // Fichier absent de la sauvegarde (démo commencée avant son ajout) : son contenu actuel est encore celui d'origine.
  for (const f of FILES) if (!(f in backup)) backup[f] = await readJson<object | null>(file(f), () => null);
  await writeJson(stateFile, { persona: id, backup });

  const installed = await Promise.all(data.apps.map((a) => isInstalled(a.name).catch(() => true)));
  const apps = data.apps.filter((_, i) => installed[i]);
  await writeJson(file("keys.json"), { slots: data.keys });
  await writeJson(file("pages.json"), { pages: data.pages.map((p) => ({ title: p.title, slots: p.keys })) });
  await saveApps(apps.length ? apps : null);
  await writeJson(file("plugins.json"), { choices: Object.fromEntries(PLUGIN_IDS.map((p) => [p, !data.off.includes(p)])) });
  forgetPluginChoices();
  activateMock(id);
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
  forgetPluginChoices();
  deactivateMock();
  await removeJson(stateFile);
  return true;
}

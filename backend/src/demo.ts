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
 * dure, les routes qui lisent l'ordinateur (GitLab, Jira, agents, fenêtres, média, son…) rendent aussi les
 * données fictives du persona : voir demo-mock.ts.
 *
 * `keys` : page Perso ; `pages` : pages de touches nommées après Perso, dont les touches vont aux widgets Touches des
 * écrans du persona (leurs titres sont ceux que lit ui/home/Personas.kt) ; `off` : plugins coupés (plugins.ts).
 */

export const PERSONA_IDS = ["nour", "gwen", "camille", "aimee", "sam", "sacha"] as const;
export type PersonaId = (typeof PERSONA_IDS)[number];

/**
 * `day` : jours après aujourd'hui ; `at` : "HH:MM" ; `remind` : minutes avant, pour le rappel parlé. `inMinutes` à la place
 * de `day` et `at` : début dans N minutes à partir du choix du persona, arrondi à la minute pleine (alerte de réunion).
 */
type DemoEvent = { title: string; minutes: number; remind?: number } & ({ day: number; at: string } | { inMinutes: number });
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
/** `symbol` : icône Material de la touche (liste TILE_SYMBOLS) ; l'emoji `glyph` reste en secours, comme dans le deck de départ. */
const key = (symbol: string, label: string, glyph: string, color: string, ...steps: Step[]): PersoKey => ({
  label,
  symbol,
  glyph,
  color,
  steps,
});
const page = (title: string, ...keys: PersoKey[]): DemoPage => ({ title, keys });
const site = (url: string): Step => ({ type: "url", url });
const term = (symbol: string, name: string, glyph: string, color: string, command: string) =>
  key(symbol, name, glyph, color, app("Terminal"), text(command), keys("enter"));
const folder = (name: string, dir: string) =>
  key("folder", name, "📁", "#B7791F", app("Finder"), keys("cmd+shift+g"), text(dir), keys("enter"));
const label = (name: string) => ({ label: name.replace(/^(Microsoft|Google) /, ""), name });

const EXCEL = "Microsoft Excel";
const OUTLOOK = "Microsoft Outlook";
const TEAMS = "Microsoft Teams";
const WORD = "Microsoft Word";
const CHROME = "Google Chrome";
const POWERPOINT = "Microsoft PowerPoint";

/** Touches de Sam : page Perso, widget Touches de l'Accueil et page « Mes touches » (grosses touches avec le thème Contraste élevé). */
const SAM_KEYS: PersoKey[] = [
  key("content_copy", "Copier", "📄", "#2B6CB0", keys("cmd+c")),
  key("content_paste", "Coller", "📋", "#2F855A", keys("cmd+v")),
  key("save", "Enregistrer", "💾", "#6B46C1", keys("cmd+s")),
  key("mail", "Courrier type", "✉️", "#B7791F", app(WORD)),
  key(
    "mail",
    "Accusé réception",
    "📨",
    "#2B6CB0",
    text("Madame, Monsieur,\n\nNous accusons réception de votre demande et la traitons dans les meilleurs délais.\n\nCordialement,"),
  ),
  key(
    "attach_file",
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
      key("table_chart", "Excel", "📊", "#1D6F42", app(EXCEL)),
      key("vertical_split", "Clôture", "🗂", "#B7791F", app(EXCEL), app(OUTLOOK), place(EXCEL, "left"), place(OUTLOOK, "right")),
      key("save", "Enregistrer", "💾", "#4A5568", keys("cmd+s", EXCEL)),
      key("check_circle", "Règlement reçu", "✅", "#2F855A", text("Règlement reçu par virement, lettrage effectué sur le compte client.")),
      key(
        "attach_file",
        "Pièce manquante",
        "📎",
        "#C05621",
        text("Bonjour, il manque une pièce justificative pour clore ce dossier. Pouvez-vous nous la transmettre ?"),
      ),
      key("receipt_long", "Écriture de régul.", "🧾", "#6B46C1", text("Écriture de régularisation de fin de période : charge à payer.")),
    ],
    pages: [
      page(
        "Excel",
        key("save", "Enregistrer", "💾", "#1D6F42", keys("cmd+s", EXCEL)),
        key("search", "Rechercher", "🔍", "#4A5568", keys("cmd+f", EXCEL)),
        key("table_chart", "Format cellule", "🔢", "#4A5568", keys("cmd+1", EXCEL)),
        key("undo", "Annuler", "↩️", "#C05621", keys("cmd+z", EXCEL)),
        key("redo", "Rétablir", "↪️", "#C05621", keys("cmd+y", EXCEL)),
        key("add", "Somme auto", "Σ", "#2B6CB0", keys("cmd+shift+t", EXCEL)),
        key("filter_alt", "Filtrer", "🔽", "#6B46C1", keys("cmd+shift+f", EXCEL)),
        key("table_chart", "Tout sélectionner", "▦", "#4A5568", keys("cmd+a", EXCEL)),
      ),
      page(
        "Libellés",
        key(
          "check_circle",
          "Règlement reçu",
          "✅",
          "#2F855A",
          text("Règlement reçu par virement, lettrage effectué sur le compte client."),
        ),
        key(
          "attach_file",
          "Pièce manquante",
          "📎",
          "#C05621",
          text("Bonjour, il manque une pièce justificative pour clore ce dossier. Pouvez-vous nous la transmettre ?"),
        ),
        key("receipt_long", "Écriture de régul.", "🧾", "#6B46C1", text("Écriture de régularisation de fin de période : charge à payer.")),
        key(
          "alarm",
          "Relance facture",
          "⏰",
          "#C53030",
          text("Bonjour, sauf erreur de notre part, la facture reste impayée à ce jour. Pouvons-nous convenir d'une date de règlement ?"),
        ),
        key("account_balance", "Rapprochement validé", "🏦", "#2B6CB0", text("Rapprochement bancaire validé, aucun écart constaté.")),
        key("undo", "Avoir émis", "↩️", "#B7791F", text("Avoir émis pour annuler la facture, à imputer sur le prochain règlement.")),
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
      key("palette", "Canva", "🎨", "#00A3A3", app("Canva")),
      key("groups", "CRM", "👥", "#2B6CB0", site("https://app.hubspot.com")),
      key("bar_chart", "Statistiques", "📈", "#2F855A", site("https://analytics.google.com")),
      key("slideshow", "Diapos", "🖥", "#C05621", app(POWERPOINT)),
      key(
        "vertical_split",
        "Projet Printemps",
        "🌸",
        "#B83280",
        app(CHROME),
        app(POWERPOINT),
        place(CHROME, "left"),
        place(POWERPOINT, "right"),
      ),
      key("videocam", "Visio", "🎥", "#2B6CB0", app(TEAMS)),
    ],
    pages: [
      page(
        "Réseaux",
        key("work", "LinkedIn", "💼", "#2B6CB0", site("https://www.linkedin.com")),
        key("image", "Instagram", "📷", "#B83280", site("https://www.instagram.com")),
        key("thumb_up", "Facebook", "👍", "#2B6CB0", site("https://www.facebook.com")),
        key("play", "YouTube", "▶️", "#C53030", site("https://studio.youtube.com")),
        key("event", "Planification", "🗓", "#6B46C1", site("https://buffer.com")),
        key("edit_note", "Nouveau post", "✍️", "#4A5568", text("Nouveau post : ")),
      ),
      page(
        "Modèles",
        key(
          "thumb_up",
          "Merci du retour",
          "🙏",
          "#4A5568",
          text("Merci pour votre retour, je reviens vers vous avant la fin de la semaine."),
        ),
        key(
          "mail",
          "Relance e-mailing",
          "✉️",
          "#6B46C1",
          text("Bonjour, nous reprenons contact au sujet de notre dernière campagne. Avez-vous un moment pour en parler ?"),
        ),
        key(
          "image",
          "Demande de visuel",
          "🖼",
          "#00A3A3",
          text("Bonjour, pourriez-vous nous envoyer le visuel en haute définition, au format PNG ?"),
        ),
        key(
          "campaign",
          "Invitation webinaire",
          "🎤",
          "#C05621",
          text("Bonjour, nous vous invitons à notre prochain webinaire. L'inscription est gratuite, le lien est dans ce message."),
        ),
        key(
          "thumb_up",
          "Merci aux participants",
          "🎉",
          "#2F855A",
          text("Merci d'avoir participé au webinaire, le replay et les diapositives arrivent dans la journée."),
        ),
        key(
          "description",
          "Brief agence",
          "📝",
          "#B7791F",
          text("Brief : objectif, cible, canaux, budget, calendrier, indicateurs de réussite."),
        ),
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
      key("vertical_split", "Mon espace", "🏠", "#2B6CB0", app(OUTLOOK), app(TEAMS), place(OUTLOOK, "left"), place(TEAMS, "right")),
      key("videocam", "Réunion", "🎥", "#2F855A", app(TEAMS)),
      key("mic_off", "Couper le micro", "🎙", "#C53030", keys("cmd+shift+m", TEAMS)),
      key("lock", "Écran verrouillé", "🔒", "#6B46C1", { type: "system", command: "lock" }),
    ],
    pages: [
      page(
        "Travail",
        key("work", "Mode travail", "💼", "#4A5568", app(OUTLOOK), app(TEAMS), app(CHROME)),
        key("vertical_split", "Mon espace", "🏠", "#2B6CB0", app(OUTLOOK), app(TEAMS), place(OUTLOOK, "left"), place(TEAMS, "right")),
        key("videocam", "Réunion", "🎥", "#2F855A", app(TEAMS)),
        key("mic_off", "Couper le micro", "🎙", "#C53030", keys("cmd+shift+m", TEAMS)),
        key("hand", "Lever la main", "✋", "#B7791F", keys("cmd+shift+k", TEAMS)),
        key(
          "edit_note",
          "Compte rendu",
          "🗒",
          "#6B46C1",
          text("Compte rendu : décisions, actions (qui, quoi, pour quand), prochaine réunion."),
        ),
      ),
      page(
        "Maison",
        key("home", "Mode perso", "🌿", "#B83280", app(CHROME), { type: "media", command: "play-pause" }),
        key("play_pause", "Musique", "🎵", "#2B6CB0", { type: "media", command: "play-pause" }),
        key("skip_next", "Suivant", "⏭", "#2B6CB0", { type: "media", command: "next" }),
        key("restaurant", "Recettes", "🍲", "#C05621", site("https://www.marmiton.org")),
        key("shopping_cart", "Courses", "🛒", "#2F855A", site("https://www.carrefour.fr")),
        key("school", "École", "🎒", "#6B46C1", site("https://www.ecoledirecte.com")),
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
      key("vertical_split", "Projet Atlas", "🧭", "#2B6CB0", app(CHROME), app("Notion"), place(CHROME, "left"), place("Notion", "right")),
      key("vertical_split", "Projet Boréal", "❄️", "#00A3A3", app(CHROME), app("Slack"), place(CHROME, "left"), place("Slack", "right")),
      key("fullscreen", "Plein écran", "⛶", "#4A5568", place(CHROME, "max")),
      key("screenshot", "Capture", "📸", "#B7791F", { type: "system", command: "screenshot-clipboard" }),
      key("edit_note", "Note rapide", "📝", "#6B46C1", text("À faire : ")),
    ],
    pages: [
      page(
        "Projets",
        key("vertical_split", "Atlas", "🧭", "#2B6CB0", app(CHROME), app("Notion"), place(CHROME, "left"), place("Notion", "right")),
        key("vertical_split", "Boréal", "❄️", "#00A3A3", app(CHROME), app("Slack"), place(CHROME, "left"), place("Slack", "right")),
        folder("Dossier Atlas", "~/Projets/Atlas"),
        folder("Dossier Boréal", "~/Projets/Boréal"),
        key("bar_chart", "Atlas : suivi", "📊", "#2B6CB0", site("https://www.notion.so")),
        key("check_circle", "Boréal : tickets", "🎫", "#00A3A3", site("https://www.atlassian.com")),
        key("groups", "Comité de suivi", "👥", "#6B46C1", app(TEAMS), app("Notion"), place(TEAMS, "left"), place("Notion", "right")),
        key("vertical_split", "Fenêtre à gauche", "◀️", "#4A5568", place(CHROME, "left")),
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
      { title: "Point client Boréal", inMinutes: 6, minutes: 30 },
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
      term("science", "Tests", "🧪", "#2F855A", "npm test"),
      term("build", "Build", "🔨", "#B7791F", "npm run build"),
      term("account_tree", "Git status", "🌿", "#4A5568", "git status"),
      key("code", "Éditeur", "💻", "#2B6CB0", app("Visual Studio Code")),
      key("swap_horiz", "Dev / prod", "🔀", "#6B46C1", app(CHROME), place(CHROME, "right")),
      key("visibility", "Revues", "👀", "#00A3A3", site("https://gitlab.com")),
    ],
    pages: [
      page(
        "Git",
        term("account_tree", "Status", "🌿", "#4A5568", "git status"),
        term("search", "Diff", "🔍", "#2B6CB0", "git diff --stat"),
        term("download", "Pull", "⬇️", "#2B6CB0", "git pull --rebase"),
        term("history", "Log", "📜", "#4A5568", "git log --oneline -10"),
        term("account_tree", "Branches", "🌳", "#2F855A", "git branch -vv"),
        term("folder", "Stash", "📦", "#B7791F", "git stash list"),
      ),
      page(
        "Tests",
        term("science", "Tout", "🧪", "#2F855A", "npm test"),
        term("check_circle", "Lint", "🧹", "#B7791F", "npm run lint"),
        term("code", "Types", "🔠", "#2B6CB0", "npm run typecheck"),
        term("bar_chart", "Couverture", "📐", "#6B46C1", "npm test -- --coverage"),
        key("description", "Un fichier", "🎯", "#4A5568", app("Terminal"), text("npm test -- ")),
      ),
      page(
        "Déploiement",
        term("build", "Build", "🔨", "#B7791F", "npm run build"),
        term("rocket_launch", "Simulation", "🚀", "#C53030", "./scripts/deploy.sh --dry-run"),
        term("deployed_code", "Conteneurs", "🐳", "#2B6CB0", "docker compose ps"),
        term("deployed_code", "Pods", "☸️", "#6B46C1", "kubectl get pods"),
        term("sell", "Versions", "🏷", "#4A5568", "git tag --list --sort=-v:refname"),
        key("swap_horiz", "Dev / prod", "🔀", "#6B46C1", app(CHROME), place(CHROME, "right")),
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
export type DemoKey = { id: string; label: string; symbol: string | undefined; glyph: string | undefined; color: string | undefined };
/** Page de touches nommée posée, avec ses touches (`k<rang + 1>-<emplacement>`) : celles du widget Touches de la tablette. */
export type DemoPageKeys = { title: string; keys: DemoKey[] };

const at = (day: number, hhmm: string) => {
  const d = new Date();
  d.setDate(d.getDate() + day);
  const [h, m] = hhmm.split(":").map(Number);
  d.setHours(h, m, 0, 0);
  return d.getTime();
};

/** Début d'un événement fictif : à son jour et son heure, ou dans `inMinutes` minutes (minute pleine). */
const startOf = (e: DemoEvent) => {
  if (!("inMinutes" in e)) return at(e.day, e.at);
  const d = new Date();
  d.setSeconds(0, 0);
  return d.getTime() + e.inMinutes * 60_000;
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
    items: data.events
      .map((e, i) => {
        const startMs = startOf(e);
        return {
          id: `demo-${id}-${i}`,
          title: e.title,
          startMs,
          endMs: startMs + e.minutes * 60_000,
          allDay: false,
          ...(e.remind ? { remindBefore: e.remind } : {}),
        };
      })
      // Trié par début, comme events.ts le garde : un événement « dans N minutes » ne doit pas passer après ceux de demain.
      .sort((a, b) => a.startMs - b.startMs),
  };
}

/**
 * Pose le persona. Au premier appel de la démo, garde les fichiers d'origine ; ensuite ils restent ceux d'avant la
 * démo. La page Apps ne garde que les applis installées sur l'ordinateur (elle retombe sur le profil de base si aucune).
 * Les plugins sont tous choisis (coupés ou gardés) : la tablette ne propose pas l'écran de première utilisation.
 */
export async function setPersona(id: PersonaId): Promise<{ keys: DemoKey[]; pages: DemoPageKeys[] }> {
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
  const tiles = (list: PersoKey[], prefix: string): DemoKey[] =>
    list.map((k, slot) => ({ id: `${prefix}${slot}`, label: k.label, symbol: k.symbol, glyph: k.glyph, color: k.color }));
  return { keys: tiles(data.keys, "k-"), pages: data.pages.map((p, n) => ({ title: p.title, keys: tiles(p.keys, `k${n + 1}-`) })) };
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

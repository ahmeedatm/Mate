import path from "node:path";
import { agentsAvailable } from "./agents-sources.ts";
import { config } from "./config.ts";
import { jiraConfigured } from "./jira.ts";
import { canMedia, isInstalled, osName } from "./os.ts";
import { readJson, updateJson } from "./store.ts";

/**
 * Plugins de la tablette, choisis par ordinateur à la première utilisation. `app` : lié à une appli trouvée sur
 * l'ordinateur (proposé seulement si elle est installée) ; `native` : fonction du compagnon (onglets du Deck).
 * Un plugin jamais choisi reste actif (comportement d'avant) et la tablette le propose (`fresh`).
 */
export type PluginKind = "app" | "native";

type PluginDef = {
  id: string;
  kind: PluginKind;
  name: string;
  description: string;
  /** Appli de l'ordinateur liée au plugin (icône, page du deck, appels). */
  app?: string;
  present: () => boolean | Promise<boolean>;
};

const desktop = () => osName !== "other";
const installed = (name: string) => () => desktop() && isInstalled(name).catch(() => false);

const CATALOG: PluginDef[] = [
  {
    id: "discord",
    kind: "app",
    name: "Discord",
    description: "Micro, sourdine, partage d'écran et appels entrants",
    app: "Discord",
    present: installed("Discord"),
  },
  {
    id: "teams",
    kind: "app",
    name: "Teams",
    description: "Rejoindre une réunion, micro, caméra et raccrocher",
    app: "Microsoft Teams",
    present: installed("Microsoft Teams"),
  },
  {
    id: "claude",
    kind: "app",
    name: "Claude Code",
    description: "Suivre les agents et leur répondre depuis la tablette",
    present: agentsAvailable,
  },
  {
    id: "jira",
    kind: "app",
    name: "Jira",
    description: "Voir ses tickets en colonnes et changer leur statut",
    present: () => jiraConfigured,
  },
  {
    id: "open",
    kind: "native",
    name: "Fenêtres ouvertes",
    description: "Mettre une fenêtre devant ou la changer d'écran",
    present: desktop,
  },
  { id: "mixer", kind: "native", name: "Mixage du son", description: "Volume de chaque appli, micro et scènes", present: desktop },
  { id: "pad", kind: "native", name: "Pavé tactile", description: "La tablette sert de souris", present: desktop },
  { id: "media", kind: "native", name: "Média", description: "Lecture en cours et sites de vidéo ou de musique", present: () => canMedia },
];

export const PLUGIN_IDS = CATALOG.map((p) => p.id);

/** `.data/plugins.json` : choix faits sur la tablette, par plugin. Absent = première utilisation. */
type Choices = { choices: Record<string, boolean> };
const file = () => path.join(config.dataDir, "plugins.json");
let cache: Record<string, boolean> | undefined;

export async function choices(): Promise<Record<string, boolean>> {
  cache ??= (await readJson<Choices>(file(), () => ({ choices: {} }))).choices ?? {};
  return cache;
}

/** Un plugin est actif tant qu'il n'a pas été coupé depuis la tablette. */
export async function pluginOn(id: string) {
  return (await choices())[id] !== false;
}

/** Plugins coupés (la tablette masque leurs onglets). */
export async function pluginsOff() {
  const chosen = await choices();
  return PLUGIN_IDS.filter((id) => chosen[id] === false);
}

/** Plugin lié à cette appli et coupé (page du deck, appels). */
export async function appPluginOff(app: string) {
  const def = CATALOG.find((p) => p.app?.toLowerCase() === app.toLowerCase());
  return def !== undefined && !(await pluginOn(def.id));
}

/**
 * Plugins proposés sur cet ordinateur : applis installées et fonctions qu'il sait faire. `withAbsent` (page locale) :
 * tous ceux du catalogue, avec `present` ; la tablette ne reçoit que les présents, sans ce champ.
 */
export async function listPlugins(withAbsent = false) {
  const chosen = await choices();
  const present = await Promise.all(CATALOG.map((p) => p.present()));
  return CATALOG.flatMap((p, i) =>
    present[i] || withAbsent
      ? [
          {
            id: p.id,
            kind: p.kind,
            name: p.name,
            description: p.description,
            app: p.app ?? null,
            on: chosen[p.id] !== false,
            fresh: !(p.id in chosen),
            ...(withAbsent ? { present: present[i] } : {}),
          },
        ]
      : [],
  );
}

/** Enregistre les choix envoyés (les autres sont gardés) ; renvoie les plugins applis qui viennent d'être activés. */
export async function savePlugins(change: Record<string, boolean>) {
  const before = await choices();
  const turnedOn = CATALOG.filter((p) => p.app && change[p.id] === true && before[p.id] !== true).map((p) => p.app as string);
  cache = await updateJson<Choices, Record<string, boolean>>(
    file(),
    () => ({ choices: {} }),
    (data) => {
      data.choices = { ...data.choices, ...change };
      return data.choices;
    },
  );
  return turnedOn;
}

import fs from "node:fs/promises";
import path from "node:path";
import { backendRoot } from "./config.ts";
import { isWindows, listButtons, listWindows, pressButton } from "./os.ts";

/**
 * Appels entrants (Discord…) signalés sur la tablette. Pendant la sonnerie, l'appli renomme sa fenêtre (« Appel
 * entrant - Discord ») : on ne lit que les titres toutes les 2 s (peu coûteux), et l'interface seulement au début
 * d'un appel, pour le nom de l'appelant. Répondre / ignorer / raccrocher = appuyer sur le vrai bouton de l'appli
 * (UI Automation). Règles dans config/calls.json ; Windows seulement (Mac pas encore observé).
 */

type CallRule = {
  app: string; title: string; caller?: string;
  answer: string[]; video?: string[]; decline: string[]; hangup?: string[];
};

export type CallEvent = { state: "ringing" | "ended"; app: string; caller: string | null; video: boolean };

const rulesFile = path.join(backendRoot, "config", "calls.json");
let rules: CallRule[] = [];

async function loadRules(): Promise<CallRule[]> {
  try {
    return (JSON.parse(await fs.readFile(rulesFile, "utf8")) as { rules?: CallRule[] }).rules ?? [];
  } catch {
    return [];
  }
}

const ruleOf = (app: string) => rules.find((r) => r.app.toLowerCase() === app.toLowerCase());

/** Appli qui sonne en ce moment, et son appelant. */
let ringing: CallEvent | null = null;
/** Après Répondre / Ignorer, le titre peut rester une seconde : pas de nouvelle sonnerie pour cette appli. */
const quietUntil = new Map<string, number>();

export function currentCall() {
  return ringing;
}

async function callerOf(rule: CallRule): Promise<string | null> {
  if (!rule.caller) return null;
  const pattern = new RegExp(rule.caller);
  const { buttons } = await listButtons(rule.app, true).catch(() => ({ buttons: [] as string[] }));
  for (const item of buttons) {
    const match = pattern.exec(item);
    if (match?.[1]) return match[1].trim();
  }
  return null;
}

export async function startCallWatch(notify: (event: CallEvent) => void) {
  if (!isWindows) return;
  rules = await loadRules();
  if (rules.length === 0) return;
  let busy = false;
  const tick = async () => {
    if (busy) return;
    busy = true;
    try {
      let found: CallRule | undefined;
      for (const rule of rules) {
        const info = await listWindows(rule.app).catch(() => null);
        const title = new RegExp(rule.title);
        if ((quietUntil.get(rule.app) ?? 0) > Date.now()) continue;
        if (info?.windows.some((w) => title.test(w.title))) {
          found = rule;
          break;
        }
      }
      if (found && ringing?.app !== found.app) {
        ringing = { state: "ringing", app: found.app, caller: null, video: Boolean(found.video?.length) };
        // Prévenir tout de suite, l'appelant suit (lire l'interface prend une ou deux secondes).
        notify(ringing);
        const caller = await callerOf(found);
        if (caller && ringing?.app === found.app) {
          ringing = { ...ringing, caller };
          notify(ringing);
        }
      } else if (!found && ringing) {
        const ended = ringing;
        ringing = null;
        notify({ ...ended, state: "ended" });
      }
    } finally {
      busy = false;
    }
  };
  setInterval(() => void tick().catch(() => undefined), 2_000);
}

async function press(app: string, kind: "answer" | "video" | "decline" | "hangup") {
  const rule = ruleOf(app);
  const names = rule?.[kind];
  if (!rule || !names?.length) throw new Error(`Rien à faire pour ${app}`);
  if (!(await pressButton(rule.app, names))) throw new Error(`Bouton introuvable dans ${rule.app} : l'appel est peut-être fini`);
  if (kind !== "hangup") quietUntil.set(rule.app, Date.now() + 6_000);
}

export async function answerCall(app: string, video = false) {
  await press(app, video ? "video" : "answer");
  if (ringing?.app === app) ringing = null;
}

export async function declineCall(app: string) {
  await press(app, "decline");
  if (ringing?.app === app) ringing = null;
}

/** Raccroche l'appli donnée, sinon la première dont le bouton « raccrocher » est là ; renvoie son nom. */
export async function hangUp(app?: string): Promise<string> {
  for (const rule of app ? rules.filter((r) => r.app.toLowerCase() === app.toLowerCase()) : rules) {
    if (rule.hangup?.length && (await pressButton(rule.app, rule.hangup).catch(() => null))) return rule.app;
  }
  throw new Error("Aucun appel en cours à raccrocher");
}

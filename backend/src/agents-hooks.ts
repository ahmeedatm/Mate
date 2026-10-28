/**
 * Bloc de hooks HTTP de Claude Code pour les agents (src/agents.ts), et sa fusion dans un `settings.json`.
 * Les entrées du compagnon se reconnaissent à l'adresse `/agents/hook` : relancer la fusion les remplace, les hooks
 * des autres outils (Orca, scripts perso) ne sont jamais touchés.
 */

type HttpHook = {
  type: "http";
  url: string;
  timeout: number;
  headers: Record<string, string>;
  allowedEnvVars: string[];
};
type Group = { matcher?: string; hooks: Record<string, unknown>[] };
export type HooksBlock = { hooks: Record<string, Group[]> };
export type Settings = { hooks?: Record<string, Group[]> } & Record<string, unknown>;

/** Événements d'observation : réponse immédiate, délai court. */
const OBSERVE = ["UserPromptSubmit", "Notification", "Stop", "StopFailure", "SessionEnd"] as const;

/** `holdS` : attente maximale d'une réponse de la tablette (AGENTS_HOLD_S) ; le délai du hook lui laisse 30 s de marge. */
export function hooksBlock(opts: { port: number; secret: string; holdS?: number }): HooksBlock {
  const url = `http://127.0.0.1:${opts.port}/agents/hook`;
  const hook = (timeout: number): HttpHook => ({
    type: "http",
    url,
    timeout,
    headers: { "X-Companion-Hook": opts.secret, "X-Orca-Pane": "$ORCA_PANE_KEY" },
    allowedEnvVars: ["ORCA_PANE_KEY"],
  });
  const wait = Math.ceil(opts.holdS ?? 90) + 30;
  const hooks: Record<string, Group[]> = {
    PermissionRequest: [{ hooks: [hook(wait)] }],
    PreToolUse: [{ matcher: "AskUserQuestion|ExitPlanMode", hooks: [hook(wait)] }],
  };
  for (const event of OBSERVE) hooks[event] = [{ hooks: [hook(5)] }];
  return { hooks };
}

const isOurs = (h: Record<string, unknown>) => typeof h.url === "string" && h.url.includes("/agents/hook");

/** Retire les entrées du compagnon, garde tout le reste (groupes vides supprimés). */
export function withoutAgentHooks(settings: Settings): Settings {
  if (!settings.hooks) return settings;
  const hooks: Record<string, Group[]> = {};
  for (const [event, groups] of Object.entries(settings.hooks)) {
    const kept = groups.map((g) => ({ ...g, hooks: g.hooks.filter((h) => !isOurs(h)) })).filter((g) => g.hooks.length > 0);
    if (kept.length) hooks[event] = kept;
  }
  const { hooks: _old, ...rest } = settings;
  return Object.keys(hooks).length ? { ...rest, hooks } : rest;
}

/** Ajoute le bloc au `settings.json` (remplace une ancienne version du bloc). */
export function mergeAgentHooks(settings: Settings, block: HooksBlock): Settings {
  const base = withoutAgentHooks(settings);
  const hooks = { ...(base.hooks ?? {}) };
  for (const [event, groups] of Object.entries(block.hooks)) hooks[event] = [...(hooks[event] ?? []), ...groups];
  return { ...base, hooks };
}

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
export type Settings = { hooks?: Record<string, Group[]>; statusLine?: unknown } & Record<string, unknown>;

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

// --- Statusline : le quota 5 h / 7 j n'est donné qu'à elle. On enveloppe celle de l'utilisateur (sortie inchangée) et on
// envoie en plus son entrée au compagnon, en arrière-plan, sans retarder l'affichage.

type StatusLine = { type?: string; command?: string } & Record<string, unknown>;
const WRAPPED =
  /^input=\$\(cat\); \(printf '%s' "\$input" \| curl .*\/agents\/statusline >\/dev\/null 2>&1 &\); printf '%s' "\$input" \| \{\n([\s\S]*)\n\}$/;

/**
 * Commande qui envoie l'entrée de la statusline au compagnon puis la passe à `original` (rien : statusline vide). La commande
 * d'origine est sur sa propre ligne : un `#` ou un `&` final ne casse pas l'enveloppe. Sous Windows, Claude Code lance les
 * commandes par Git Bash (vu le 30/10/2026 avec un hook `command`, Claude Code 2.1.260) : même enveloppe, `curl` de Git.
 */
export function statusLineCommand(opts: { port: number; secret: string }, original: string | undefined): string {
  const post = `curl -s -m 2 -o /dev/null -X POST -H 'Content-Type: application/json' -H 'X-Companion-Hook: ${opts.secret}' --data-binary @- http://127.0.0.1:${opts.port}/agents/statusline`;
  return `input=$(cat); (printf '%s' "$input" | ${post} >/dev/null 2>&1 &); printf '%s' "$input" | {\n${original?.trim() || ":"}\n}`;
}

/** Commande d'origine d'une statusline enveloppée, ou null si elle ne l'est pas. */
export function unwrapStatusLine(command: string | undefined): string | null {
  const m = command ? WRAPPED.exec(command) : null;
  return m ? m[1] : null;
}

/** Enveloppe la statusline (une seule fois ; une ancienne enveloppe est remplacée, sa commande d'origine gardée). */
export function withStatusLine(settings: Settings, opts: { port: number; secret: string }): Settings {
  const current = (settings.statusLine ?? {}) as StatusLine;
  const original = unwrapStatusLine(current.command) ?? current.command;
  return {
    ...settings,
    statusLine: { ...current, type: "command", command: statusLineCommand(opts, original === ":" ? undefined : original) },
  };
}

/** Rend la statusline d'origine (ou la retire si l'enveloppe n'en avait pas). */
export function withoutStatusLine(settings: Settings): Settings {
  const current = settings.statusLine as StatusLine | undefined;
  const original = unwrapStatusLine(current?.command);
  if (original === null) return settings;
  const { statusLine: _old, ...rest } = settings;
  return original === ":" ? rest : { ...rest, statusLine: { ...current, command: original } };
}

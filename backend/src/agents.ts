import { randomUUID } from "node:crypto";
import path from "node:path";

/**
 * Agents Claude Code : l'état des sessions de l'ordinateur et les réponses depuis la tablette.
 *
 * Trois sources, aucune installation obligatoire pour lire :
 * - `claude agents --json` (filet, toutes les 4 s, seulement tablette connectée) : toutes les sessions, terminal compris ;
 * - les hooks HTTP de Claude Code (`POST /agents/hook`) : l'instant exact, la commande ou la question en attente ;
 * - `orca worktree ps --json` si Orca est là : branche, dernier outil, terminal à qui écrire.
 *
 * L'état vit en mémoire, rien n'est écrit sur disque. Le texte venu de l'agent est tronqué, jamais interprété.
 * Une requête de hook qui attend une décision (permission, question) reste ouverte jusqu'à la réponse de la
 * tablette ou `holdMs`, puis reçoit `{}` : le terminal décide. Jamais de décision sans action sur la tablette.
 */

export const AGENT_STATES = ["working", "waiting", "done", "limit", "idle"] as const;
export type AgentState = (typeof AGENT_STATES)[number];

/** Ce que `claude agents --json` donne d'une session (champs lus seulement). */
export type ClaudeSession = {
  sessionId?: string;
  cwd?: string;
  name?: string;
  pid?: number;
  startedAt?: number;
  kind?: string;
  status?: "busy" | "waiting" | "idle";
  state?: string;
  waitingFor?: string;
};

/** Ce qu'Orca sait des sessions lancées dans ses worktrees. */
export type OrcaInfo = {
  worktrees: {
    path: string;
    branch: string | null;
    agents: { paneKey: string; toolName: string | null; lastMessage: string | null; structured: boolean }[];
  }[];
  /** Terminal vivant par clé de volet (`tabId:leafId`). */
  handles: Map<string, string>;
};

export type AgentAlert = {
  kind: "waiting" | "done" | "limit" | "clear";
  session?: string;
  name?: string;
  /** Demande à laquelle la tablette peut répondre (`GET /agents/ask`). */
  askId?: string;
  /** Sessions qui attendent encore (la lumière reste allumée tant qu'il y en a). */
  waiting: number;
};

export type AgentsDeps = {
  now?: () => number;
  listClaude: () => Promise<ClaudeSession[] | null>;
  listOrca: () => Promise<OrcaInfo | null>;
  /** L'appli au premier plan est un terminal ou Orca : l'utilisateur est devant Claude. */
  frontIsClaude: () => Promise<boolean>;
  tabletConnected: () => boolean;
  notify: (alert: AgentAlert) => void;
  /** Durée maximale d'attente d'une réponse, en ms (AGENTS_HOLD_S). */
  holdMs: number;
  /** Écrit une ligne dans le terminal d'une session (`orca terminal send`). */
  sendText: (handle: string, text: string) => Promise<void>;
  /** Amène une session devant sur l'ordinateur (terminal Orca, ou Orca seulement). */
  focusSession: (handle: string | null) => Promise<void>;
};

type Question = { question: string; header: string; multi: boolean; options: { label: string; description: string }[] };

type Pending = {
  id: string;
  sessionId: string;
  kind: "permission" | "question";
  tool: string;
  /** Commande, fichier ou adresse, tronqué. */
  text: string;
  risky: boolean;
  /** Questions d'origine : renvoyées telles quelles avec `answers`. */
  original?: unknown[];
  questions?: Question[];
  createdAt: number;
  finish: (output: unknown) => void;
};

type Session = {
  id: string;
  name: string;
  cwd: string;
  pid: number | null;
  startedAt: number | null;
  state: AgentState;
  since: number;
  waitingFor: string | null;
  tool: string | null;
  message: string | null;
  turnStartedAt: number | null;
  workedMs: number | null;
  limitText: string | null;
  /** Heure de reprise lue dans le message de limite, et « continue » armé depuis la tablette pour cette heure. */
  resumeAt: number | null;
  resumeArmed: boolean;
  resumeTimer: NodeJS.Timeout | null;
  pane: string | null;
  branch: string | null;
  handle: string | null;
  inOrca: boolean;
  /** Dernier hook reçu : le filet `claude agents` ne contredit pas un hook de moins de 6 s. */
  hookAt: number;
  missing: number;
  /** La tablette a été prévenue que cette session attend (lumière allumée). */
  alerted: boolean;
  ask: Pending | null;
};

/** Hooks reçus depuis le démarrage : la tablette sait si « répondre » est possible. */
const HOOK_STALE_MS = 6_000;
/** Un « fini » devient « inactif » au bout de ce délai. */
const DONE_TTL_MS = 10 * 60_000;
/** Une session connue par hook seulement, sans `SessionEnd`, est oubliée après ce délai sans nouvelle. */
const SILENT_TTL_MS = 12 * 3600_000;

export const LIMITS = { name: 60, dir: 60, tool: 40, ask: 400, question: 200, option: 80, description: 120, line: 120, free: 500 };

/** Texte venu de l'agent : sans caractères de contrôle, tronqué. */
export function cut(value: unknown, max: number): string {
  const text = typeof value === "string" ? value : "";
  const clean = text.replace(/[\u0000-\u0008\u000b-\u001f\u007f]/g, " ").trim();
  return clean.length > max ? clean.slice(0, max - 1) + "…" : clean;
}

/** Trois lignes du dernier message, pour le résumé de fin. */
export function summaryLines(message: string | null): string[] {
  if (!message) return [];
  return message
    .split("\n")
    .map((l) => l.trim())
    .filter(Boolean)
    .slice(0, 3)
    .map((l) => cut(l, LIMITS.line));
}

/** Commandes qui demandent un appui maintenu pour autoriser (la tablette ne s'y fie pas à un simple toucher). */
const RISKY_BASH = [
  /\brm\s/,
  /\bgit\s+(push|reset\s+--hard|clean|checkout\s+--|branch\s+-D)\b/,
  /\bsudo\b/,
  /\b(curl|wget)\b[^|]*\|\s*(sudo\s+)?(ba|z|da)?sh\b/,
  /\b(chmod|chown|dd|mkfs|kill|killall|pkill|launchctl)\b/,
  /\bnpm\s+publish\b/,
];

/** Commandes qui écrivent : risquées quand une de leurs cibles est hors du dossier du projet. */
const WRITE_CMD = /\b(cp|mv|tee|mkdir|touch|ln|rsync|install|dd|chmod|chown|sed\s+-i)\b/;
/** Dossiers où écrire reste sans conséquence. */
const SCRATCH = ["/tmp/", "/private/tmp/", "/var/folders/", "/dev/null"];

/** Chemin absolu ou `~` cité dans la commande, hors du dossier du projet et des dossiers temporaires. */
function writesOutside(command: string, cwd: string): boolean {
  const targets = command.match(/(?:^|[\s=])(~[^\s'";|&<>]*|\/[^\s'";|&<>]*)/g) ?? [];
  return targets.some((raw) => {
    const target = raw.trim().replace(/^=/, "");
    if (target.startsWith("~")) return true;
    if (SCRATCH.some((dir) => target === dir.replace(/\/$/, "") || target.startsWith(dir))) return false;
    const rel = path.relative(cwd, target);
    return rel.startsWith("..") || path.isAbsolute(rel);
  });
}

export function isRisky(tool: string, input: Record<string, unknown>, cwd: string): boolean {
  if (tool === "Bash") {
    const command = String(input.command ?? "");
    return (
      RISKY_BASH.some((re) => re.test(command)) || ((WRITE_CMD.test(command) || />\s*[~/]/.test(command)) && writesOutside(command, cwd))
    );
  }
  const file = typeof input.file_path === "string" ? input.file_path : typeof input.notebook_path === "string" ? input.notebook_path : null;
  if (file) {
    const rel = path.relative(cwd, path.resolve(cwd, file));
    return rel.startsWith("..") || path.isAbsolute(rel);
  }
  return false;
}

/** Ce que la tablette affiche d'une demande de permission : la commande, le fichier ou l'adresse. */
function permissionText(tool: string, input: Record<string, unknown>): string {
  const pick = (k: string) => (typeof input[k] === "string" ? (input[k] as string) : "");
  return cut(pick("command") || pick("file_path") || pick("notebook_path") || pick("url") || pick("pattern") || tool, LIMITS.ask);
}

function parseQuestions(input: unknown): { questions: Question[]; original: unknown[] } | null {
  const raw = (input as { questions?: unknown })?.questions;
  if (!Array.isArray(raw) || raw.length === 0 || raw.length > 4) return null;
  const questions: Question[] = [];
  for (const q of raw as Record<string, unknown>[]) {
    const options = Array.isArray(q?.options) ? (q.options as Record<string, unknown>[]) : [];
    if (typeof q?.question !== "string" || options.length === 0 || options.length > 6 || options.some((o) => typeof o?.label !== "string"))
      return null;
    questions.push({
      question: cut(q.question, LIMITS.question),
      header: cut(q.header, 30),
      multi: q.multiSelect === true,
      options: options.map((o) => ({ label: cut(o.label, LIMITS.option), description: cut(o.description, LIMITS.description) })),
    });
  }
  return { questions, original: raw };
}

/** Réponse d'un hook `PermissionRequest` : autoriser ou refuser CET appel, rien d'autre (ni règle durable, ni changement de mode). */
export function permissionOutput(behavior: "allow" | "deny") {
  const decision = behavior === "allow" ? { behavior } : { behavior, message: "Refusé depuis la tablette" };
  return { hookSpecificOutput: { hookEventName: "PermissionRequest", decision } };
}

/** Réponse d'un hook `PreToolUse` sur `AskUserQuestion` : les questions d'origine + `answers` (« question » → libellés joints par « , »). */
export function questionOutput(original: unknown[], answers: Record<string, string>) {
  return {
    hookSpecificOutput: { hookEventName: "PreToolUse", permissionDecision: "allow", updatedInput: { questions: original, answers } },
  };
}

export type QuestionAnswer = { choices?: number[]; other?: string };

export class AnswerError extends Error {
  statusCode: number;
  constructor(message: string, statusCode = 400) {
    super(message);
    this.statusCode = statusCode;
  }
}

/** Heure de reprise lue dans le texte d'une limite (« resets 3pm », « resets at 14:30 ») ; null si illisible. */
export function parseResume(text: string | null, now: number): number | null {
  const m = /reset[s]?(?:\s+at)?\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?/i.exec(text ?? "");
  if (!m) return null;
  let hour = Number(m[1]);
  const minute = Number(m[2] ?? 0);
  const meridiem = m[3]?.toLowerCase();
  if (meridiem === "pm" && hour < 12) hour += 12;
  if (meridiem === "am" && hour === 12) hour = 0;
  if (hour > 23 || minute > 59) return null;
  const at = new Date(now);
  at.setHours(hour, minute, 0, 0);
  if (at.getTime() <= now) at.setDate(at.getDate() + 1);
  return at.getTime();
}

export function createAgents(deps: AgentsDeps) {
  const now = deps.now ?? Date.now;
  const sessions = new Map<string, Session>();
  const asks = new Map<string, Pending>();
  let hooksSeen = false;
  let timer: NodeJS.Timeout | undefined;
  let refreshing = false;
  let lastOrca = 0;

  const blank = (id: string, cwd: string): Session => ({
    id,
    name: path.basename(cwd) || "session",
    cwd,
    pid: null,
    startedAt: null,
    state: "idle",
    since: now(),
    waitingFor: null,
    tool: null,
    message: null,
    turnStartedAt: null,
    workedMs: null,
    limitText: null,
    resumeAt: null,
    resumeArmed: false,
    resumeTimer: null,
    pane: null,
    branch: null,
    handle: null,
    inOrca: false,
    hookAt: 0,
    missing: 0,
    alerted: false,
    ask: null,
  });

  const waitingCount = () => [...sessions.values()].filter((s) => s.alerted).length;

  /** Alerte la tablette, sauf si l'utilisateur est devant Claude ou si elle n'est pas là. */
  async function alert(kind: AgentAlert["kind"], s: Session, askId?: string): Promise<boolean> {
    if (!deps.tabletConnected()) return false;
    if (kind !== "clear" && (await deps.frontIsClaude().catch(() => false))) return false;
    if (kind === "waiting") s.alerted = true;
    deps.notify({ kind, session: s.id, name: s.name, askId, waiting: waitingCount() });
    return true;
  }

  /** La session n'attend plus : éteint sa part de lumière ; `clear` quand plus personne n'attend. */
  function settle(s: Session) {
    if (!s.alerted) return;
    s.alerted = false;
    if (waitingCount() === 0 && deps.tabletConnected()) deps.notify({ kind: "clear", session: s.id, name: s.name, waiting: 0 });
  }

  function dropAsk(s: Session, output: unknown = {}) {
    const ask = s.ask;
    if (ask) ask.finish(output);
  }

  function setState(s: Session, next: AgentState, quiet = false) {
    if (s.state === next) return;
    if (s.state === "waiting") {
      s.waitingFor = null;
      dropAsk(s);
      settle(s);
    }
    if (s.state === "limit") disarm(s);
    s.state = next;
    s.since = now();
    if (next === "working" && s.turnStartedAt === null) s.turnStartedAt = s.since;
    if (quiet) return;
    if (next === "done") void alert("done", s);
    if (next === "limit") void alert("limit", s);
  }

  function setWaiting(s: Session, why: string | null, withAlert: boolean) {
    const entering = s.state !== "waiting";
    if (entering) {
      s.state = "waiting";
      s.since = now();
    }
    s.waitingFor = why ?? s.waitingFor;
    if (entering && withAlert) void alert("waiting", s);
  }

  /** Garde la requête de hook ouverte jusqu'à la réponse de la tablette ; `{}` tout de suite si elle ne peut pas répondre. */
  async function hold(
    s: Session,
    build: (finish: (out: unknown) => void) => Pending,
    onClosed: (cb: () => void) => void,
  ): Promise<unknown> {
    if (s.ask) return {};
    if (!deps.tabletConnected() || (await deps.frontIsClaude().catch(() => false))) {
      setWaiting(s, s.waitingFor, false);
      return {};
    }
    return new Promise((resolve) => {
      let ended = false;
      let expiry: NodeJS.Timeout | undefined;
      const finish = (output: unknown) => {
        if (ended) return;
        ended = true;
        clearTimeout(expiry);
        asks.delete(pending.id);
        if (s.ask === pending) s.ask = null;
        // Réponse donnée (autre chose que `{}`) : Claude reprend. Sinon le terminal décide : plus rien à signaler à la tablette.
        if (Object.keys(output as object).length > 0) setState(s, "working", true);
        settle(s);
        resolve(output);
      };
      const pending = build(finish);
      expiry = setTimeout(() => finish({}), deps.holdMs);
      asks.set(pending.id, pending);
      s.ask = pending;
      setWaiting(s, pending.kind === "question" ? "input needed" : "permission prompt", false);
      onClosed(() => finish({}));
      void alert("waiting", s, pending.id).then((sent) => {
        // Prévenir la tablette a échoué (elle vient de partir, ou on est passé devant Claude) : le terminal décide.
        if (!sent) finish({});
      });
    });
  }

  function mapName(s: Session, title: unknown) {
    const t = cut(title, LIMITS.name);
    if (t) s.name = t;
  }

  /** Un événement de hook de Claude Code. `onClosed` : appelé si Claude Code coupe la connexion (délai du hook, session fermée). */
  async function hook(body: Record<string, unknown>, meta: { pane?: string; onClosed?: (cb: () => void) => void }): Promise<unknown> {
    const event = typeof body.hook_event_name === "string" ? body.hook_event_name : "";
    const id = typeof body.session_id === "string" ? body.session_id : "";
    const cwd = typeof body.cwd === "string" ? body.cwd : "";
    if (!event || !id) return {};
    hooksSeen = true;
    let s = sessions.get(id);
    if (!s) {
      if (event === "SessionEnd") return {};
      s = blank(id, cwd);
      sessions.set(id, s);
    }
    s.hookAt = now();
    s.missing = 0;
    if (cwd) s.cwd = cwd;
    if (meta.pane) s.pane = cut(meta.pane, 120);
    mapName(s, body.session_title);
    const input = (body.tool_input ?? {}) as Record<string, unknown>;
    const tool = cut(body.tool_name, LIMITS.tool);

    switch (event) {
      case "UserPromptSubmit":
        s.turnStartedAt = now();
        s.workedMs = null;
        s.limitText = null;
        s.message = null;
        s.tool = null;
        disarm(s);
        setState(s, "working");
        break;
      case "PermissionRequest": {
        const text = permissionText(tool, input);
        const risky = isRisky(tool, input, s.cwd);
        return hold(
          s,
          (finish) => ({ id: randomUUID(), sessionId: s.id, kind: "permission", tool, text, risky, createdAt: now(), finish }),
          meta.onClosed ?? (() => {}),
        );
      }
      case "PreToolUse": {
        if (tool !== "AskUserQuestion") {
          s.tool = tool || s.tool;
          break;
        }
        const parsed = parseQuestions(input);
        if (!parsed) {
          setWaiting(s, "input needed", true);
          break;
        }
        return hold(
          s,
          (finish) => ({
            id: randomUUID(),
            sessionId: s.id,
            kind: "question",
            tool,
            text: "",
            risky: false,
            original: parsed.original,
            questions: parsed.questions,
            createdAt: now(),
            finish,
          }),
          meta.onClosed ?? (() => {}),
        );
      }
      case "Notification": {
        const type = body.notification_type;
        if (type === "permission_prompt" || type === "elicitation_dialog" || type === "agent_needs_input") {
          if (s.state !== "waiting") setWaiting(s, type === "permission_prompt" ? "permission prompt" : "input needed", true);
        } else if (type === "idle_prompt" && s.state === "done") {
          setState(s, "idle", true);
        }
        break;
      }
      case "PostToolUse":
      case "PostToolUseFailure":
        setState(s, "working");
        break;
      case "Stop":
        s.message = cut(body.last_assistant_message, 1000) || s.message;
        s.workedMs = s.turnStartedAt ? Math.max(0, now() - s.turnStartedAt) : null;
        s.turnStartedAt = null;
        setState(s, "done");
        break;
      case "StopFailure": {
        const text = cut(body.last_assistant_message, 300) || cut(body.error_details, 300);
        s.workedMs = s.turnStartedAt ? Math.max(0, now() - s.turnStartedAt) : null;
        s.turnStartedAt = null;
        if (body.error === "rate_limit") {
          s.limitText = text || "Limite atteinte";
          s.resumeAt = parseResume(text, now());
          setState(s, "limit");
        } else {
          s.message = text || cut(body.error, 60);
          setState(s, "done");
        }
        break;
      }
      case "SessionEnd":
        remove(s);
        break;
    }
    return {};
  }

  function disarm(s: Session) {
    clearTimeout(s.resumeTimer ?? undefined);
    s.resumeTimer = null;
    s.resumeArmed = false;
    s.resumeAt = null;
  }

  function remove(s: Session) {
    disarm(s);
    dropAsk(s);
    settle(s);
    sessions.delete(s.id);
  }

  /** Réponse de la tablette à une demande en attente. */
  function answer(askId: string, body: { decision?: string; held?: boolean; answers?: QuestionAnswer[] }) {
    const ask = asks.get(askId);
    if (!ask) throw new AnswerError("Cette demande n'existe plus (déjà traitée au clavier, ou expirée)", 404);
    if (ask.kind === "permission") {
      if (body.decision === "deny") return void ask.finish(permissionOutput("deny"));
      if (body.decision !== "allow") throw new AnswerError("Réponse attendue : allow ou deny");
      if (ask.risky && body.held !== true) throw new AnswerError("Commande à risque : appui maintenu requis pour autoriser", 403);
      return void ask.finish(permissionOutput("allow"));
    }
    const questions = ask.questions ?? [];
    const given = body.answers;
    if (!Array.isArray(given) || given.length !== questions.length) throw new AnswerError("Une réponse par question est attendue");
    const answers: Record<string, string> = {};
    const original = ask.original as { question: string; options: { label: string }[] }[];
    questions.forEach((q, i) => {
      const a = given[i] ?? {};
      const choices = [...new Set(a.choices ?? [])];
      if (choices.some((c) => !Number.isInteger(c) || c < 0 || c >= q.options.length)) throw new AnswerError("Choix inconnu");
      const other = typeof a.other === "string" ? cut(a.other, LIMITS.free) : "";
      if (!q.multi && choices.length + (other ? 1 : 0) > 1) throw new AnswerError("Une seule réponse possible à cette question");
      if (choices.length === 0 && !other) throw new AnswerError("Une réponse par question est attendue");
      // Libellés d'origine (pas ceux tronqués pour l'écran).
      answers[original[i].question] = [
        ...choices.sort((x, y) => x - y).map((c) => original[i].options[c].label),
        ...(other ? [other] : []),
      ].join(", ");
    });
    ask.finish(questionOutput(ask.original ?? [], answers));
  }

  function effectiveState(s: Session): AgentState {
    return s.state === "done" && now() - s.since > DONE_TTL_MS ? "idle" : s.state;
  }

  const ORDER: Record<AgentState, number> = { waiting: 0, limit: 1, done: 2, working: 3, idle: 4 };

  function view(s: Session) {
    const state = effectiveState(s);
    const done = state === "done" || state === "limit";
    return {
      id: s.id,
      name: cut(s.name, LIMITS.name),
      dir: cut(path.basename(s.cwd), LIMITS.dir),
      branch: s.branch ? cut(s.branch, LIMITS.name) : null,
      state,
      since: s.since,
      waitingFor: s.waitingFor,
      tool: s.tool ? cut(s.tool, LIMITS.tool) : null,
      lines: done || state === "idle" ? summaryLines(s.message ?? s.limitText) : [],
      workedMs: done ? s.workedMs : null,
      limit: state === "limit" ? cut(s.limitText, 160) : null,
      resumeAt: state === "limit" ? s.resumeAt : null,
      resumeArmed: state === "limit" && s.resumeArmed,
      askId: s.ask?.id ?? null,
      askKind: s.ask?.kind ?? null,
      canSend: Boolean(s.handle) && state !== "working" && state !== "waiting",
      canFocus: Boolean(s.handle) || s.inOrca,
    };
  }

  function list() {
    const all = [...sessions.values()].sort((a, b) => ORDER[effectiveState(a)] - ORDER[effectiveState(b)] || b.since - a.since);
    const counts = { working: 0, waiting: 0, done: 0, limit: 0, idle: 0 };
    for (const s of all) counts[effectiveState(s)]++;
    return { sessions: all.slice(0, 10).map(view), counts, total: all.length, hooks: hooksSeen };
  }

  /** Détail d'une demande en attente, pour la tablette. */
  function ask(askId: string) {
    const a = asks.get(askId);
    const s = a && sessions.get(a.sessionId);
    if (!a || !s) return null;
    return {
      id: a.id,
      kind: a.kind,
      session: cut(s.name, LIMITS.name),
      dir: cut(path.basename(s.cwd), LIMITS.dir),
      tool: a.tool,
      text: a.text,
      risky: a.risky,
      questions: a.questions ?? null,
      /** Reste avant que le terminal reprenne la main : relatif, la tablette n'a pas la même horloge. */
      expiresInMs: Math.max(0, a.createdAt + deps.holdMs - now()),
    };
  }

  /** Relit `claude agents --json` (et Orca) et corrige l'état : le filet quand un hook manque (Esc, session fermée…). */
  async function refresh() {
    if (refreshing) return;
    refreshing = true;
    try {
      const [live, orca] = await Promise.all([deps.listClaude(), now() - lastOrca > 8_000 ? deps.listOrca() : Promise.resolve(undefined)]);
      if (orca !== undefined) lastOrca = now();
      const t = now();
      if (live) {
        const seen = new Set<string>();
        for (const e of live) {
          if (!e.sessionId) continue;
          seen.add(e.sessionId);
          let s = sessions.get(e.sessionId);
          const fresh = !s;
          if (!s) {
            s = blank(e.sessionId, e.cwd ?? "");
            sessions.set(s.id, s);
          }
          s.missing = 0;
          if (e.cwd) s.cwd = e.cwd;
          if (e.name && (fresh || !s.hookAt || s.name === path.basename(s.cwd))) s.name = cut(e.name, LIMITS.name);
          s.pid = e.pid ?? s.pid;
          s.startedAt = e.startedAt ?? s.startedAt;
          const status = e.status ?? (e.state === "working" ? "busy" : e.state === "blocked" ? "waiting" : "idle");
          if (!fresh && t - s.hookAt < HOOK_STALE_MS) continue;
          // Question tenue par un PreToolUse : Claude attend le hook (« busy »), aucun dialogue n'est au terminal, la
          // réponse ne peut venir que de la tablette, du délai ou d'une coupure.
          if (s.ask && s.ask.kind !== "permission") continue;
          if (status === "busy") setState(s, "working", fresh);
          else if (status === "waiting") {
            if (s.state !== "waiting") setWaiting(s, e.waitingFor ?? null, !fresh);
            else s.waitingFor = e.waitingFor ?? s.waitingFor;
          } else if (s.state === "working" || s.state === "waiting") setState(s, "idle", true);
        }
        for (const s of [...sessions.values()]) {
          if (seen.has(s.id)) continue;
          if (++s.missing >= 2 && !s.ask) remove(s);
        }
      }
      for (const s of [...sessions.values()]) {
        if (t - Math.max(s.hookAt, s.since) > SILENT_TTL_MS) remove(s);
      }
      if (orca) joinOrca(orca);
    } finally {
      refreshing = false;
    }
  }

  function joinOrca(orca: OrcaInfo) {
    const norm = (p: string) => p.replace(/\/+$/, "");
    for (const s of sessions.values()) {
      s.inOrca = false;
      s.handle = null;
      // Volet exact donné par le hook (le dossier de la session peut différer de celui du worktree) ; sinon le dossier,
      // et alors la seule session Claude du worktree.
      let tree = s.pane ? orca.worktrees.find((w) => w.agents.some((a) => a.paneKey === s.pane)) : undefined;
      const byPane = Boolean(tree);
      tree ??= orca.worktrees.find((w) => norm(w.path) === norm(s.cwd));
      if (!tree) continue;
      s.inOrca = true;
      s.branch = tree.branch;
      const agent = byPane ? tree.agents.find((a) => a.paneKey === s.pane) : tree.agents.length === 1 ? tree.agents[0] : undefined;
      if (!agent) continue;
      if (agent.toolName && s.state === "working") s.tool = agent.toolName;
      if (!s.message && agent.lastMessage) s.message = cut(agent.lastMessage, 1000);
      // Une session de chat Orca n'a pas de terminal : rien à qui écrire.
      if (!agent.structured) s.handle = orca.handles.get(agent.paneKey) ?? null;
    }
  }

  function start(intervalMs = 4_000) {
    stop();
    timer = setInterval(() => {
      if (deps.tabletConnected()) void refresh().catch(() => {});
    }, intervalMs);
    timer.unref?.();
  }

  function stop() {
    clearInterval(timer);
    timer = undefined;
  }

  /** Terminal d'une session, pour lui écrire ou l'amener devant. */
  function handleOf(sessionId: string): { handle: string | null; inOrca: boolean; state: AgentState } | null {
    const s = sessions.get(sessionId);
    return s ? { handle: s.handle, inOrca: s.inOrca, state: effectiveState(s) } : null;
  }

  /** Message rapide à une session au repos, seulement si on sait la joindre de façon fiable (terminal Orca). */
  async function send(sessionId: string, text: string) {
    const s = sessions.get(sessionId);
    if (!s) throw new AnswerError("Cette session n'existe plus", 404);
    const state = effectiveState(s);
    if (state === "working" || state === "waiting") throw new AnswerError("La session travaille : attends qu'elle ait fini", 409);
    if (!s.handle) throw new AnswerError("Cette session n'est pas dans un terminal Orca : impossible de lui écrire d'ici", 409);
    const line = cut(text.replace(/[\r\n]+/g, " "), LIMITS.free);
    if (!line) throw new AnswerError("Message vide");
    await deps.sendText(s.handle, line);
    setState(s, "working", true);
  }

  async function focus(sessionId: string) {
    const s = sessions.get(sessionId);
    if (!s) throw new AnswerError("Cette session n'existe plus", 404);
    if (!s.handle && !s.inOrca) throw new AnswerError("Je ne sais pas où est cette session sur l'ordinateur", 409);
    await deps.focusSession(s.handle);
  }

  /** Arme (ou désarme) l'envoi de « continue » à l'heure de reprise d'une limite : décision prise sur la tablette. */
  function resume(sessionId: string, armed: boolean) {
    const s = sessions.get(sessionId);
    if (!s || s.state !== "limit") throw new AnswerError("Cette session n'est pas en limite", 409);
    clearTimeout(s.resumeTimer ?? undefined);
    s.resumeTimer = null;
    s.resumeArmed = false;
    if (!armed) return;
    if (!s.handle) throw new AnswerError("Cette session n'est pas dans un terminal Orca : impossible de la relancer d'ici", 409);
    if (!s.resumeAt) throw new AnswerError("Heure de reprise inconnue", 409);
    s.resumeArmed = true;
    // La reprise se déclenche seule, pourvu que la session soit toujours en limite et son terminal toujours là.
    s.resumeTimer = setTimeout(
      () => {
        s.resumeTimer = null;
        if (s.state !== "limit" || !s.resumeArmed || !s.handle) return;
        s.resumeArmed = false;
        deps
          .sendText(s.handle, "continue")
          .then(() => setState(s, "working", true))
          .catch(() => {});
      },
      Math.max(0, s.resumeAt - now()),
    );
    s.resumeTimer.unref?.();
  }

  return { hook, answer, list, ask, refresh, start, stop, handleOf, send, focus, resume };
}

export type Agents = ReturnType<typeof createAgents>;

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
    /** Ticket Jira lié au worktree (`SQOOL-12256`) et MR GitLab liée (numéro). */
    ticket?: string | null;
    mr?: number | null;
    agents: {
      paneKey: string;
      state: string | null;
      /** Orca la voit surveiller une tâche de fond (pipeline, job) : affichée à part, comme dans Orca. */
      monitoring?: boolean;
      toolName: string | null;
      lastMessage: string | null;
      structured: boolean;
    }[];
  }[];
  /** Terminal vivant par clé de volet (`tabId:leafId`). */
  handles: Map<string, string>;
};

/** Où amener une session : son terminal Orca, sinon son worktree et son onglet de chat dans Orca. */
export type FocusTarget = { handle: string | null; worktree: string | null; tab: string | null };

export type AgentAlert = {
  kind: "waiting" | "done" | "limit" | "clear";
  session?: string;
  name?: string;
  /** Demande à laquelle la tablette peut répondre (`GET /agents/ask`). */
  askId?: string;
  /** Sessions qui attendent encore (la lumière reste allumée tant qu'il y en a). */
  waiting: number;
};

/** Part du quota utilisée (%) et fin de la fenêtre (ms), pour 5 h et 7 j. */
export type Quota = { fiveHour: QuotaWindow | null; sevenDay: QuotaWindow | null; at: number };
type QuotaWindow = { used: number; resetsAt: number | null };

/** Lit `rate_limits` de la statusline (`used_percentage`, `resets_at` en secondes) ; rien d'autre n'est gardé. */
export function parseQuota(rateLimits: unknown, at: number): Quota | null {
  const r = rateLimits as Record<string, { used_percentage?: unknown; resets_at?: unknown }> | null | undefined;
  if (!r || typeof r !== "object") return null;
  const window = (w: { used_percentage?: unknown; resets_at?: unknown } | undefined): QuotaWindow | null => {
    if (!w || typeof w.used_percentage !== "number" || !Number.isFinite(w.used_percentage)) return null;
    const resets = typeof w.resets_at === "number" && Number.isFinite(w.resets_at) ? w.resets_at * 1000 : null;
    return { used: Math.max(0, Math.min(100, Math.round(w.used_percentage))), resetsAt: resets };
  };
  const q = { fiveHour: window(r.five_hour), sevenDay: window(r.seven_day), at };
  return q.fiveHour || q.sevenDay ? q : null;
}

/**
 * Lit l'usage Claude qu'Orca tient à jour (`rateLimits.claude` de `accounts.list`, lu auprès d'Anthropic par Orca) :
 * `session` = 5 h, `weekly` = 7 j, en % et heure de fin en ms ; `at` = heure de la lecture d'Orca.
 */
export function parseOrcaQuota(claude: unknown): Quota | null {
  const c = claude as { updatedAt?: unknown; session?: unknown; weekly?: unknown } | null | undefined;
  // Pas de filtre sur `status` : quand Orca ne peut pas relire (jeton périmé), il garde les dernières valeurs et leur heure.
  if (!c || typeof c !== "object" || typeof c.updatedAt !== "number") return null;
  const window = (w: unknown): QuotaWindow | null => {
    const x = w as { usedPercent?: unknown; resetsAt?: unknown } | null | undefined;
    if (!x || typeof x.usedPercent !== "number" || !Number.isFinite(x.usedPercent)) return null;
    const resets = typeof x.resetsAt === "number" && Number.isFinite(x.resetsAt) ? x.resetsAt : null;
    return { used: Math.max(0, Math.min(100, Math.round(x.usedPercent))), resetsAt: resets };
  };
  const q = { fiveHour: window(c.session), sevenDay: window(c.weekly), at: c.updatedAt };
  return q.fiveHour || q.sevenDay ? q : null;
}

/** Ce que le journal d'une session donne en plus : le titre automatique de Claude, la dernière MR créée. */
export type TranscriptInfo = { title: string | null; mr: number | null };

export type AgentsDeps = {
  now?: () => number;
  /** Lit le journal de la session (titre automatique, MR) ; absent dans les tests qui n'en ont pas besoin. */
  transcriptInfo?: (sessionId: string, cwd: string, hinted: string | null) => Promise<TranscriptInfo | null>;
  listClaude: () => Promise<ClaudeSession[] | null>;
  listOrca: () => Promise<OrcaInfo | null>;
  /** Usage Claude lu dans Orca (absent hors Mac et dans les tests qui n'en ont pas besoin). */
  readQuota?: () => Promise<Quota | null>;
  /** L'appli au premier plan est un terminal ou Orca : l'utilisateur est devant Claude. */
  frontIsClaude: () => Promise<boolean>;
  tabletConnected: () => boolean;
  notify: (alert: AgentAlert) => void;
  /** Durée maximale d'attente d'une réponse, en ms (AGENTS_HOLD_S). */
  holdMs: number;
  /** Écrit une ligne dans le terminal d'une session (`orca terminal send`). */
  sendText: (handle: string, text: string) => Promise<void>;
  /** Envoie un message dans un chat Orca (`agentSession.send`), `session` = `claude_<…>` de la clé de son volet. */
  sendChat: (session: string, text: string) => Promise<void>;
  /** Interrompt le tour en cours d'une session (Échap dans son terminal Orca). */
  interruptSession: (handle: string) => Promise<void>;
  /** Amène une session devant sur l'ordinateur (terminal Orca, chat Orca, ou Orca seulement). */
  focusSession: (target: FocusTarget) => Promise<void>;
};

type Question = { question: string; header: string; multi: boolean; options: { label: string; description: string }[] };

type Pending = {
  id: string;
  sessionId: string;
  kind: "permission" | "question" | "plan";
  tool: string;
  /** Commande, fichier ou adresse, tronqué. */
  text: string;
  risky: boolean;
  /** Questions d'origine : renvoyées telles quelles avec `answers`. */
  original?: unknown[];
  questions?: Question[];
  createdAt: number;
  /** Durée de l'attente (le délai d'une question posée par l'outil MCP est plus long que celui d'un hook). */
  holdMs?: number;
  /** Question posée par l'outil MCP `tablette_demander` : pas de session derrière, qui la pose et d'où. */
  who?: string;
  where?: string;
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
  /** Titre automatique de Claude (journal de la session), ticket et MR liés (Orca, journal, branche). */
  title: string | null;
  ticket: string | null;
  mr: number | null;
  monitoring: boolean;
  transcript: string | null;
  handle: string | null;
  inOrca: boolean;
  /** Worktree Orca de la session et, pour une session de chat, son onglet (`agent-session:<id>`). */
  orcaTree: string | null;
  orcaTab: string | null;
  /** État vu par Orca (`working`, `done`) : il voit aussi les tâches de fond (surveillance de pipeline), que `claude agents` ne compte pas. */
  orcaState: string | null;
  /** Dernier hook reçu : le filet `claude agents` ne contredit pas un hook de moins de 6 s. */
  hookAt: number;
  missing: number;
  /** La tablette a été prévenue que cette session attend (lumière allumée). */
  alerted: boolean;
  ask: Pending | null;
};

/** Hooks reçus depuis le démarrage : la tablette sait si « répondre » est possible. */
const HOOK_STALE_MS = 6_000;
const QUOTA_TTL_MS = 3 * 3_600_000;
/** Lecture de l'usage dans Orca : une par minute au plus. */
const QUOTA_READ_MS = 60_000;
/** Une session connue par hook seulement, sans `SessionEnd`, est oubliée après ce délai sans nouvelle. */
const SILENT_TTL_MS = 12 * 3600_000;

export const LIMITS = {
  plan: 6000,
  feedback: 500,
  name: 60,
  dir: 60,
  tool: 40,
  ask: 400,
  question: 200,
  option: 80,
  description: 120,
  line: 120,
  free: 500,
};

/** Texte venu de l'agent : sans caractères de contrôle, tronqué. */
export function cut(value: unknown, max: number): string {
  const text = typeof value === "string" ? value : "";
  const clean = text.replace(/[\u0000-\u0008\u000b-\u001f\u007f]/g, " ").trim();
  return clean.length > max ? clean.slice(0, max - 1) + "…" : clean;
}

/** Ticket cité dans un nom de branche : `auto-test/SQOOL-12256-catalog` → `SQOOL-12256`. */
/** Onglet d'un chat Orca d'après la clé de son volet : `structured-agent-session-<id>:<…>` → `agent-session:<id>`. */
export function chatTab(paneKey: string): string | null {
  const m = /^structured-agent-session-([\w-]+):/.exec(paneKey);
  return m ? `agent-session:${m[1]}` : null;
}

/** Session d'un chat Orca, d'après son onglet : `agent-session:<id>` → `<id>`. */
const chatSession = (s: { orcaTab: string | null }) => s.orcaTab?.slice("agent-session:".length) || null;

export function ticketOf(branch: string | null): string | null {
  return /(?:^|[/_-])([A-Z][A-Z0-9]+-\d+)(?=$|[/_-])/.exec(branch ?? "")?.[1] ?? null;
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

const CLAUDE_PLAN = /\/(\.claude|claude-profiles\/[^/]+\/home)\/plans\/[^/]+\.md$/;

export function isRisky(tool: string, input: Record<string, unknown>, cwd: string): boolean {
  if (tool === "Bash") {
    const command = String(input.command ?? "");
    return (
      RISKY_BASH.some((re) => re.test(command)) || ((WRITE_CMD.test(command) || />\s*[~/]/.test(command)) && writesOutside(command, cwd))
    );
  }
  const file = typeof input.file_path === "string" ? input.file_path : typeof input.notebook_path === "string" ? input.notebook_path : null;
  if (file) {
    // Fichier de plan que Claude écrit lui-même en mode plan : pas une écriture du projet, pas un risque.
    if (CLAUDE_PLAN.test(file)) return false;
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

/**
 * Réponse à `ExitPlanMode` : approuver = `allow` avec l'entrée d'origine (exigé pour ce outil), Claude sort du mode plan
 * vers le mode par défaut (chaque modification reste demandée, vérifié) ; refuser = `deny` avec ce qu'il faut changer.
 */
export function planOutput(approve: boolean, input: unknown, feedback?: string) {
  if (approve) return { hookSpecificOutput: { hookEventName: "PreToolUse", permissionDecision: "allow", updatedInput: input } };
  const reason = "Plan refusé depuis la tablette." + (feedback ? ` À changer : ${feedback}` : " Attends la suite au clavier.");
  return { hookSpecificOutput: { hookEventName: "PreToolUse", permissionDecision: "deny", permissionDecisionReason: reason } };
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
  /** Quota 5 h / 7 j du compte : lu dans Orca, ou dans la statusline de Claude Code (`POST /agents/statusline`). */
  let quota: Quota | null = null;
  let lastQuotaRead = -Infinity;
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
    title: null,
    ticket: null,
    mr: null,
    monitoring: false,
    transcript: null,
    handle: null,
    inOrca: false,
    orcaTree: null,
    orcaTab: null,
    orcaState: null,
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
    if (typeof body.transcript_path === "string") s.transcript = body.transcript_path;
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
        // Un plan ne s'approuve pas ici (essayé : `allow` laisse le dialogue) ; le PreToolUse l'a déjà proposé à la tablette.
        if (tool === "ExitPlanMode") {
          setWaiting(s, "plan", false);
          return {};
        }
        const text = permissionText(tool, input);
        const risky = isRisky(tool, input, s.cwd);
        return hold(
          s,
          (finish) => ({ id: randomUUID(), sessionId: s.id, kind: "permission", tool, text, risky, createdAt: now(), finish }),
          meta.onClosed ?? (() => {}),
        );
      }
      case "PreToolUse": {
        if (tool === "ExitPlanMode") {
          const plan = cut(input.plan, LIMITS.plan);
          if (!plan) {
            setWaiting(s, "plan", true);
            break;
          }
          return hold(
            s,
            (finish) => ({
              id: randomUUID(),
              sessionId: s.id,
              kind: "plan",
              tool,
              text: plan,
              risky: false,
              original: [input],
              createdAt: now(),
              finish,
            }),
            meta.onClosed ?? (() => {}),
          );
        }
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
          // Heure lue dans le message, sinon la fin de la fenêtre de 5 h donnée par la statusline.
          const window = quota?.fiveHour?.resetsAt;
          s.resumeAt = parseResume(text, now()) ?? (window && window > now() ? window : null);
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
  function answer(askId: string, body: { decision?: string; held?: boolean; answers?: QuestionAnswer[]; feedback?: string }) {
    const ask = asks.get(askId);
    if (!ask) throw new AnswerError("Cette demande n'existe plus (déjà traitée au clavier, ou expirée)", 404);
    if (ask.kind === "plan") {
      if (body.decision !== "allow" && body.decision !== "deny") throw new AnswerError("Réponse attendue : allow ou deny");
      const feedback = typeof body.feedback === "string" ? cut(body.feedback.replace(/[\r\n]+/g, " "), LIMITS.feedback) : "";
      return void ask.finish(planOutput(body.decision === "allow", (ask.original ?? [])[0], feedback || undefined));
    }
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
    return s.state;
  }

  const ORDER: Record<AgentState, number> = { waiting: 0, limit: 1, done: 2, working: 3, idle: 4 };

  function view(s: Session) {
    const state = effectiveState(s);
    const done = state === "done" || state === "limit";
    return {
      id: s.id,
      name: cut(s.name, LIMITS.name),
      /** Titre à afficher : celui que Claude donne à la session, sinon son nom. */
      title: cut(s.title || s.name, 90),
      agent: "claude",
      dir: cut(path.basename(s.cwd), LIMITS.dir),
      branch: s.branch ? cut(s.branch, LIMITS.name) : null,
      ticket: s.ticket ?? ticketOf(s.branch),
      mr: s.mr,
      monitoring: state === "working" && s.monitoring,
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
      canSend: Boolean(s.handle || chatSession(s)) && state !== "working" && state !== "waiting",
      canFocus: Boolean(s.handle) || s.inOrca,
      canInterrupt: Boolean(s.handle) && state === "working",
    };
  }

  function list() {
    const all = [...sessions.values()].sort((a, b) => ORDER[effectiveState(a)] - ORDER[effectiveState(b)] || b.since - a.since);
    // `monitoring` : sessions qui surveillent une tâche de fond, comptées à part de celles qui travaillent (comme Orca).
    const counts = { working: 0, monitoring: 0, waiting: 0, done: 0, limit: 0, idle: 0 };
    for (const s of all) {
      const state = effectiveState(s);
      if (state === "working" && s.monitoring) counts.monitoring++;
      else counts[state]++;
    }
    return { sessions: all.slice(0, 10).map(view), counts, total: all.length, hooks: hooksSeen, quota: quotaView() };
  }

  /**
   * Dernier quota connu, avec son âge (`ageMs`, pour dire « il y a 20 min » : Orca et la statusline ne relisent pas toujours).
   * Plus de 3 h : oublié. Une fenêtre déjà terminée est écartée (son pourcentage ne vaut plus rien).
   */
  function quotaView() {
    if (!quota || now() - quota.at >= QUOTA_TTL_MS) return null;
    const open = (w: QuotaWindow | null) => (w && (w.resetsAt === null || w.resetsAt > now()) ? w : null);
    const fiveHour = open(quota.fiveHour);
    const sevenDay = open(quota.sevenDay);
    if (!fiveHour && !sevenDay) return null;
    return { fiveHour, sevenDay, at: quota.at, ageMs: now() - quota.at };
  }

  /** Détail d'une demande en attente, pour la tablette. */
  function ask(askId: string) {
    const a = asks.get(askId);
    const s = a && sessions.get(a.sessionId);
    if (!a || (!s && !a.who)) return null;
    return {
      id: a.id,
      kind: a.kind,
      session: cut(s?.name ?? a.who, LIMITS.name),
      dir: cut(s ? path.basename(s.cwd) : (a.where ?? ""), LIMITS.dir),
      tool: a.tool,
      text: a.text,
      risky: a.risky,
      questions: a.questions ?? null,
      /** Reste avant que le terminal reprenne la main : relatif, la tablette n'a pas la même horloge. */
      expiresInMs: Math.max(0, a.createdAt + (a.holdMs ?? deps.holdMs) - now()),
    };
  }

  /**
   * Question posée par Claude lui-même avec l'outil MCP `tablette_demander` : même carte qu'une question à choix,
   * tenue jusqu'à la réponse ou `holdMs`. Rend le texte de la réponse (libellés joints par « , »), ou null sans réponse.
   */
  function askTablet(
    q: { question: string; options: string[]; multi: boolean; who: string; where: string },
    holdMs: number,
  ): Promise<string | null> {
    if (!deps.tabletConnected()) throw new AnswerError("Tablette non connectée : impossible de lui poser la question", 409);
    const original = [{ question: q.question, header: "", multiSelect: q.multi, options: q.options.map((label) => ({ label })) }];
    const parsed = parseQuestions({ questions: original });
    if (!parsed) throw new AnswerError("Question invalide : de 1 à 6 options");
    return new Promise((resolve) => {
      let ended = false;
      const id = randomUUID();
      const finish = (output: unknown) => {
        if (ended) return;
        ended = true;
        clearTimeout(expiry);
        asks.delete(id);
        const answers = (output as { hookSpecificOutput?: { updatedInput?: { answers?: Record<string, string> } } }).hookSpecificOutput
          ?.updatedInput?.answers;
        if (waitingCount() === 0 && deps.tabletConnected()) deps.notify({ kind: "clear", waiting: 0 });
        resolve(answers ? (Object.values(answers)[0] ?? null) : null);
      };
      const expiry = setTimeout(() => finish({}), holdMs);
      asks.set(id, {
        id,
        sessionId: "",
        kind: "question",
        tool: "tablette_demander",
        text: "",
        risky: false,
        original: parsed.original,
        questions: parsed.questions,
        createdAt: now(),
        holdMs,
        who: cut(q.who, LIMITS.name),
        where: cut(q.where, LIMITS.dir),
        finish,
      });
      deps.notify({ kind: "waiting", name: cut(q.who, LIMITS.name), askId: id, waiting: waitingCount() + 1 });
    });
  }

  /** Relit `claude agents --json` (et Orca) et corrige l'état : le filet quand un hook manque (Esc, session fermée…). */
  async function refresh() {
    if (refreshing) return;
    refreshing = true;
    try {
      const [live, orca] = await Promise.all([deps.listClaude(), now() - lastOrca > 8_000 ? deps.listOrca() : Promise.resolve(undefined)]);
      if (orca !== undefined) lastOrca = now();
      if (deps.readQuota && now() - lastQuotaRead >= QUOTA_READ_MS) {
        lastQuotaRead = now();
        // Orca relit l'usage de lui-même : le plus récent des deux (Orca, statusline) l'emporte.
        const q = await deps.readQuota().catch(() => null);
        if (q && (!quota || q.at > quota.at)) quota = q;
      }
      const t = now();
      if (live) {
        const seen = new Set<string>();
        for (const e of live) {
          // Session dont le processus n'existe plus (session en arrière-plan abandonnée, gardée par Claude tant qu'elle
          // « attend ») : rien à suivre ni à quoi répondre.
          if (!e.sessionId || (!e.status && !e.pid)) continue;
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
          // Question ou plan tenus par un PreToolUse : Claude attend le hook (« busy »), aucun dialogue n'est au terminal,
          // la réponse ne peut venir que de la tablette, du délai ou d'une coupure.
          if (s.ask && s.ask.kind !== "permission") continue;
          if (status === "busy") setState(s, "working", fresh);
          else if (status === "waiting") {
            if (s.state !== "waiting") setWaiting(s, e.waitingFor ?? null, !fresh);
            else s.waitingFor = e.waitingFor ?? s.waitingFor;
          } else if ((s.state === "working" || s.state === "waiting") && s.orcaState !== "working") {
            // Tour fini sans hook Stop (pas de hooks, ou Échap) : fini, avec son alerte.
            s.workedMs = s.turnStartedAt ? Math.max(0, t - s.turnStartedAt) : null;
            s.turnStartedAt = null;
            setState(s, "done");
          }
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
      if (deps.transcriptInfo) {
        await Promise.all(
          [...sessions.values()].map(async (s) => {
            const info = await deps.transcriptInfo!(s.id, s.cwd, s.transcript).catch(() => null);
            if (!info) return;
            if (info.title) s.title = info.title;
            if (info.mr) s.mr = info.mr;
          }),
        );
      }
    } finally {
      refreshing = false;
    }
  }

  function joinOrca(orca: OrcaInfo) {
    const norm = (p: string) => p.replace(/\/+$/, "");
    for (const s of sessions.values()) {
      s.inOrca = false;
      s.handle = null;
      s.orcaTree = null;
      s.orcaTab = null;
      s.orcaState = null;
      s.monitoring = false;
      // Volet exact donné par le hook (le dossier de la session peut différer de celui du worktree) ; sinon le dossier,
      // et alors la seule session Claude du worktree.
      let tree = s.pane ? orca.worktrees.find((w) => w.agents.some((a) => a.paneKey === s.pane)) : undefined;
      const byPane = Boolean(tree);
      tree ??= orca.worktrees.find((w) => norm(w.path) === norm(s.cwd));
      if (!tree) continue;
      s.inOrca = true;
      s.orcaTree = tree.path;
      s.branch = tree.branch;
      s.ticket = tree.ticket ?? s.ticket;
      if (tree.mr) s.mr = tree.mr;
      const agent = byPane ? tree.agents.find((a) => a.paneKey === s.pane) : tree.agents.length === 1 ? tree.agents[0] : undefined;
      if (!agent) continue;
      s.orcaState = agent.state;
      s.monitoring = agent.monitoring === true;
      if (agent.toolName && s.state === "working") s.tool = agent.toolName;
      const hooksStale = now() - s.hookAt >= HOOK_STALE_MS;
      if (agent.lastMessage && (!s.message || hooksStale)) s.message = cut(agent.lastMessage, 1000);
      // Sans hook récent, l'état d'Orca corrige celui de `claude agents` (au prompt mais une surveillance tourne ; tour fini).
      if (hooksStale && !s.ask) {
        if (agent.state === "working" && (s.state === "idle" || s.state === "done")) setState(s, "working", true);
        else if (agent.state === "done" && s.state === "idle") setState(s, "done", true);
      }
      // Une session de chat Orca n'a pas de terminal : rien à qui écrire.
      if (!agent.structured) s.handle = orca.handles.get(agent.paneKey) ?? null;
      else s.orcaTab = chatTab(agent.paneKey);
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

  /** Message rapide à une session au repos : dans son terminal Orca, ou dans son chat Orca. */
  async function send(sessionId: string, text: string) {
    const s = sessions.get(sessionId);
    if (!s) throw new AnswerError("Cette session n'existe plus", 404);
    const state = effectiveState(s);
    if (state === "working" || state === "waiting") throw new AnswerError("La session travaille : attends qu'elle ait fini", 409);
    const chat = chatSession(s);
    if (!s.handle && !chat) throw new AnswerError("Cette session n'est pas dans Orca : impossible de lui écrire d'ici", 409);
    const line = cut(text.replace(/[\r\n]+/g, " "), LIMITS.free);
    if (!line) throw new AnswerError("Message vide");
    if (s.handle) await deps.sendText(s.handle, line);
    else if (chat) await deps.sendChat(chat, line);
    setState(s, "working", true);
  }

  /** Interrompt le tour en cours (Échap) ; seulement une session qui travaille, dans un terminal Orca. */
  async function interrupt(sessionId: string) {
    const s = sessions.get(sessionId);
    if (!s) throw new AnswerError("Cette session n'existe plus", 404);
    if (effectiveState(s) !== "working") throw new AnswerError("Cette session ne travaille pas", 409);
    if (!s.handle) throw new AnswerError("Cette session n'est pas dans un terminal Orca : impossible de l'interrompre d'ici", 409);
    await deps.interruptSession(s.handle);
    // Pas de hook Stop après une interruption : la session est au repos.
    s.turnStartedAt = null;
    setState(s, "idle", true);
  }

  /** Statusline de Claude Code : seul `rate_limits` est lu. */
  function statusline(body: Record<string, unknown>) {
    const q = parseQuota(body.rate_limits, now());
    if (q) quota = q;
  }

  async function focus(sessionId: string) {
    const s = sessions.get(sessionId);
    if (!s) throw new AnswerError("Cette session n'existe plus", 404);
    if (!s.handle && !s.inOrca) throw new AnswerError("Je ne sais pas où est cette session sur l'ordinateur", 409);
    await deps.focusSession({ handle: s.handle, worktree: s.orcaTree, tab: s.orcaTab });
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

  return { hook, answer, list, ask, askTablet, refresh, start, stop, handleOf, send, focus, resume, interrupt, statusline };
}

export type Agents = ReturnType<typeof createAgents>;

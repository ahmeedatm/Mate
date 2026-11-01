import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import {
  AnswerError,
  createAgents,
  cut,
  isRisky,
  parseOrcaQuota,
  parseQuota,
  ticketOf,
  parseResume,
  permissionOutput,
  summaryLines,
  type AgentAlert,
  type AgentsDeps,
  type ClaudeSession,
  type FocusTarget,
  type OrcaInfo,
  type Quota,
} from "../src/agents.ts";
import { execFile, spawnSync } from "node:child_process";
import http from "node:http";
import {
  hooksBlock,
  mergeAgentHooks,
  statusLineCommand,
  unwrapStatusLine,
  withoutAgentHooks,
  withoutStatusLine,
  withStatusLine,
} from "../src/agents-hooks.ts";

let clock = 1_000_000;
let tablet = true;
let front = false;
let live: ClaudeSession[] | null = [];
let orca: OrcaInfo | null = null;
const alerts: AgentAlert[] = [];
const sent: { handle: string; text: string }[] = [];
const focused: FocusTarget[] = [];

const deps = (holdMs = 5_000): AgentsDeps => ({
  now: () => clock,
  listClaude: async () => live,
  listOrca: async () => orca,
  frontIsClaude: async () => front,
  tabletConnected: () => tablet,
  notify: (a) => alerts.push(a),
  holdMs,
  sendText: async (handle, text) => void sent.push({ handle, text }),
  sendChat: async (session, text) => void sent.push({ handle: `chat:${session}`, text }),
  interruptSession: async (handle) => void sent.push({ handle, text: "<Échap>" }),
  focusSession: async (target) => void focused.push(target),
});

const flush = async () => {
  for (let i = 0; i < 10; i++) await new Promise((r) => setImmediate(r));
};

const event = (name: string, extra: Record<string, unknown> = {}) => ({
  hook_event_name: name,
  session_id: "s1",
  cwd: "/work/projet",
  ...extra,
});

const permission = (command: string) => event("PermissionRequest", { tool_name: "Bash", tool_input: { command } });

const QUESTIONS = [
  {
    question: "Quelle couleur ?",
    header: "Couleur",
    multiSelect: false,
    options: [
      { label: "Rouge", description: "Le rouge" },
      { label: "Bleu", description: "Le bleu" },
    ],
  },
  {
    question: "Quels fruits ?",
    header: "Fruits",
    multiSelect: true,
    options: [{ label: "Pomme" }, { label: "Poire" }, { label: "Kiwi" }],
  },
];
const askQuestions = () => event("PreToolUse", { tool_name: "AskUserQuestion", tool_input: { questions: QUESTIONS } });

beforeEach(() => {
  clock = 1_000_000;
  tablet = true;
  front = false;
  live = [];
  orca = null;
  alerts.length = 0;
  sent.length = 0;
  focused.length = 0;
});

describe("états par les hooks", () => {
  it("travaille puis fini, avec le résumé et le temps passé", async () => {
    const a = createAgents(deps());
    await a.hook(event("UserPromptSubmit", { session_title: "refacto" }), {});
    assert.equal(a.list().sessions[0].state, "working");
    assert.equal(a.list().sessions[0].name, "refacto");
    clock += 95_000;
    await a.hook(event("Stop", { last_assistant_message: "Premier\n\nSecond\nTroisième\nQuatrième" }), {});
    await flush();
    const s = a.list().sessions[0];
    assert.equal(s.state, "done");
    assert.equal(s.workedMs, 95_000);
    assert.deepEqual(s.lines, ["Premier", "Second", "Troisième"]);
    assert.deepEqual(alerts, [{ kind: "done", session: "s1", name: "refacto", askId: undefined, waiting: 0 }]);
  });

  it("fini reste fini tant qu'on ne relance pas la session (elle t'attend)", async () => {
    const a = createAgents(deps());
    await a.hook(event("Stop", { last_assistant_message: "ok" }), {});
    clock += 3 * 3600_000;
    assert.equal(a.list().sessions[0].state, "done");
    assert.equal(a.list().counts.done, 1);
  });

  it("limite atteinte : état limite et alerte", async () => {
    const a = createAgents(deps());
    await a.hook(event("StopFailure", { error: "rate_limit", last_assistant_message: "You've hit your limit · resets 3pm" }), {});
    await flush();
    const s = a.list().sessions[0];
    assert.equal(s.state, "limit");
    assert.match(s.limit ?? "", /resets 3pm/);
    assert.equal(alerts[0].kind, "limit");
  });

  it("une autre erreur d'API est un tour fini, sans alerte de limite", async () => {
    const a = createAgents(deps());
    await a.hook(event("StopFailure", { error: "overloaded", last_assistant_message: "API Error: overloaded" }), {});
    assert.equal(a.list().sessions[0].state, "done");
    assert.deepEqual(a.list().sessions[0].lines, ["API Error: overloaded"]);
  });

  it("SessionEnd retire la session", async () => {
    const a = createAgents(deps());
    await a.hook(event("UserPromptSubmit"), {});
    await a.hook(event("SessionEnd"), {});
    assert.equal(a.list().total, 0);
  });

  it("une notification de permission fait attendre la session (alerte sans demande)", async () => {
    const a = createAgents(deps());
    await a.hook(event("Notification", { notification_type: "permission_prompt" }), {});
    await flush();
    assert.equal(a.list().sessions[0].state, "waiting");
    assert.deepEqual(alerts, [{ kind: "waiting", session: "s1", name: "projet", askId: undefined, waiting: 1 }]);
    await a.hook(event("UserPromptSubmit"), {});
    assert.equal(alerts.at(-1)?.kind, "clear");
  });

  it("aucune alerte devant Claude ni sans tablette", async () => {
    const a = createAgents(deps());
    front = true;
    await a.hook(event("Stop", { last_assistant_message: "ok" }), {});
    tablet = false;
    front = false;
    await a.hook(event("StopFailure", { error: "rate_limit" }), {});
    await flush();
    assert.deepEqual(alerts, []);
    assert.equal(a.list().sessions[0].state, "limit");
  });

  it("jette un événement sans session ou sans nom, ignore un événement inconnu", async () => {
    const a = createAgents(deps());
    assert.deepEqual(await a.hook({ hook_event_name: "Stop" }, {}), {});
    assert.deepEqual(await a.hook({ session_id: "x" }, {}), {});
    assert.deepEqual(await a.hook(event("Truc"), {}), {});
    assert.equal(a.list().total, 1);
  });
});

describe("permission", () => {
  it("garde la requête ouverte, alerte avec la demande, autorise une seule fois", async () => {
    const a = createAgents(deps());
    const out = a.hook(permission("npm test"), {});
    await flush();
    const askId = alerts[0].askId!;
    assert.equal(alerts[0].kind, "waiting");
    assert.deepEqual(a.ask(askId), {
      id: askId,
      kind: "permission",
      session: "projet",
      dir: "projet",
      tool: "Bash",
      text: "npm test",
      risky: false,
      questions: null,
      expiresInMs: 5_000,
    });
    a.answer(askId, { decision: "allow" });
    assert.deepEqual(await out, { hookSpecificOutput: { hookEventName: "PermissionRequest", decision: { behavior: "allow" } } });
    assert.equal(a.list().sessions[0].state, "working");
    assert.equal(alerts.at(-1)?.kind, "clear");
  });

  it("refuse avec un message", async () => {
    const a = createAgents(deps());
    const out = a.hook(permission("ls"), {});
    await flush();
    a.answer(alerts[0].askId!, { decision: "deny" });
    const decision = ((await out) as ReturnType<typeof permissionOutput>).hookSpecificOutput.decision;
    assert.deepEqual(decision, { behavior: "deny", message: "Refusé depuis la tablette" });
  });

  it("une commande à risque n'est autorisée que par appui maintenu", async () => {
    const a = createAgents(deps());
    const out = a.hook(permission("rm -rf build"), {});
    await flush();
    const askId = alerts[0].askId!;
    assert.equal(a.ask(askId)?.risky, true);
    assert.throws(
      () => a.answer(askId, { decision: "allow" }),
      (e: AnswerError) => e.statusCode === 403,
    );
    a.answer(askId, { decision: "allow", held: true });
    assert.equal(((await out) as ReturnType<typeof permissionOutput>).hookSpecificOutput.decision.behavior, "allow");
  });

  it("refuser reste possible d'un simple appui, même à risque", async () => {
    const a = createAgents(deps());
    const out = a.hook(permission("sudo reboot"), {});
    await flush();
    a.answer(alerts[0].askId!, { decision: "deny" });
    assert.equal(((await out) as ReturnType<typeof permissionOutput>).hookSpecificOutput.decision.behavior, "deny");
  });

  it("sans réponse à temps : {} (le terminal décide) et la lumière s'éteint", async () => {
    const a = createAgents(deps(30));
    assert.deepEqual(await a.hook(permission("npm test"), {}), {});
    assert.equal(a.ask(alerts[0].askId!), null);
    assert.equal(alerts.at(-1)?.kind, "clear");
    assert.equal(a.list().sessions[0].state, "waiting", "la session attend toujours, dans le terminal");
  });

  it("tablette absente ou fenêtre Claude devant : {} tout de suite, sans alerte", async () => {
    const a = createAgents(deps());
    tablet = false;
    assert.deepEqual(await a.hook(permission("npm test"), {}), {});
    tablet = true;
    front = true;
    assert.deepEqual(await a.hook(permission("npm test"), {}), {});
    assert.deepEqual(alerts, []);
  });

  it("jamais de décision par défaut : la connexion coupée rend {}", async () => {
    const a = createAgents(deps());
    let close = () => {};
    const out = a.hook(permission("npm test"), { onClosed: (cb) => (close = cb) });
    await flush();
    close();
    assert.deepEqual(await out, {});
  });

  it("refuse une réponse à une demande inconnue ou déjà traitée", async () => {
    const a = createAgents(deps());
    assert.throws(
      () => a.answer("inconnu", { decision: "allow" }),
      (e: AnswerError) => e.statusCode === 404,
    );
    const out = a.hook(permission("ls"), {});
    await flush();
    const askId = alerts[0].askId!;
    a.answer(askId, { decision: "allow" });
    await out;
    assert.throws(
      () => a.answer(askId, { decision: "allow" }),
      (e: AnswerError) => e.statusCode === 404,
    );
  });

  it("réponse donnée au clavier : le filet voit la session travailler, la demande disparaît", async () => {
    const a = createAgents(deps());
    const out = a.hook(permission("npm test"), {});
    await flush();
    clock += 20_000;
    live = [{ sessionId: "s1", cwd: "/work/projet", status: "busy" }];
    await a.refresh();
    assert.deepEqual(await out, {});
    assert.equal(a.list().sessions[0].state, "working");
    assert.equal(a.list().sessions[0].askId, null);
    assert.equal(alerts.at(-1)?.kind, "clear");
  });

  it("n'accepte pas de décision autre que allow ou deny", async () => {
    const a = createAgents(deps());
    void a.hook(permission("ls"), {});
    await flush();
    assert.throws(() => a.answer(alerts[0].askId!, {}), AnswerError);
    assert.throws(() => a.answer(alerts[0].askId!, { decision: "always" }), AnswerError);
  });
});

describe("questions à choix", () => {
  it("renvoie les questions d'origine et les réponses avec les libellés d'origine", async () => {
    const a = createAgents(deps());
    const out = a.hook(askQuestions(), {});
    await flush();
    const detail = a.ask(alerts[0].askId!)!;
    assert.equal(detail.kind, "question");
    assert.equal(detail.questions?.length, 2);
    a.answer(detail.id, { answers: [{ choices: [1] }, { choices: [2, 0] }] });
    assert.deepEqual(await out, {
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: "allow",
        updatedInput: { questions: QUESTIONS, answers: { "Quelle couleur ?": "Bleu", "Quels fruits ?": "Pomme, Kiwi" } },
      },
    });
  });

  it("pendant qu'une question est tenue, le filet ne la rend pas (Claude est « busy » sur le hook)", async () => {
    const a = createAgents(deps());
    const out = a.hook(askQuestions(), {});
    await flush();
    clock += 30_000;
    live = [{ sessionId: "s1", cwd: "/work/projet", status: "busy" }];
    await a.refresh();
    const id = alerts[0].askId!;
    assert.equal(a.ask(id)?.kind, "question", "toujours en attente");
    a.answer(id, { answers: [{ choices: [0] }, { choices: [1] }] });
    assert.equal(((await out) as any).hookSpecificOutput.permissionDecision, "allow");
  });

  it("texte libre (Autre) : seul, ou ajouté aux choix d'une question multiple", async () => {
    const a = createAgents(deps());
    const out = a.hook(askQuestions(), {});
    await flush();
    a.answer(alerts[0].askId!, { answers: [{ other: "Violet foncé" }, { choices: [1], other: "Fraise" }] });
    const answers = ((await out) as any).hookSpecificOutput.updatedInput.answers;
    assert.deepEqual(answers, { "Quelle couleur ?": "Violet foncé", "Quels fruits ?": "Poire, Fraise" });
  });

  it("refuse un choix hors des options, une question sans réponse, deux choix sur une question simple", async () => {
    const a = createAgents(deps());
    void a.hook(askQuestions(), {});
    await flush();
    const id = alerts[0].askId!;
    assert.throws(() => a.answer(id, { answers: [{ choices: [5] }, { choices: [0] }] }), /Choix inconnu/);
    assert.throws(() => a.answer(id, { answers: [{ choices: [0] }, {}] }), /Une réponse par question/);
    assert.throws(() => a.answer(id, { answers: [{ choices: [0, 1] }, { choices: [0] }] }), /Une seule réponse/);
    assert.throws(() => a.answer(id, { answers: [{ choices: [0] }] }), /Une réponse par question/);
    assert.throws(() => a.answer(id, { decision: "allow" }), AnswerError);
  });

  it("tronque le texte libre", async () => {
    const a = createAgents(deps());
    const out = a.hook(askQuestions(), {});
    await flush();
    a.answer(alerts[0].askId!, { answers: [{ other: "x".repeat(900) }, { choices: [0] }] });
    const answer = ((await out) as any).hookSpecificOutput.updatedInput.answers["Quelle couleur ?"] as string;
    assert.equal(answer.length, 500);
  });

  it("des questions mal formées : la session attend, pas de demande", async () => {
    const a = createAgents(deps());
    const out = await a.hook(event("PreToolUse", { tool_name: "AskUserQuestion", tool_input: { questions: [{ question: 3 }] } }), {});
    assert.deepEqual(out, {});
    assert.equal(a.list().sessions[0].state, "waiting");
  });

  it("un autre outil en PreToolUse ne retient rien", async () => {
    const a = createAgents(deps());
    assert.deepEqual(await a.hook(event("PreToolUse", { tool_name: "Bash", tool_input: { command: "ls" } }), {}), {});
  });

  it("tronque ce qui vient de l'agent et retire les caractères de contrôle", async () => {
    const a = createAgents(deps());
    const long = "q".repeat(500);
    void a.hook(
      event("PreToolUse", {
        tool_name: "AskUserQuestion",
        tool_input: { questions: [{ question: long + "\u0007", options: [{ label: "a\u001bb" }] }] },
      }),
      {},
    );
    await flush();
    const q = a.ask(alerts[0].askId!)!.questions![0];
    assert.equal(q.question.length, 200);
    assert.equal(q.options[0].label, "a b");
  });
});

describe("approuver un plan", () => {
  const plan = { plan: "# Plan\n1. Écrire hello.txt\n2. Relire", planFilePath: "/Users/x/.claude/plans/p.md" };
  const exitPlan = () => event("PreToolUse", { tool_name: "ExitPlanMode", tool_input: plan, permission_mode: "plan" });

  it("tient la demande et renvoie le plan d'origine pour l'approuver (mode par défaut ensuite, pas d'auto)", async () => {
    const a = createAgents(deps());
    const out = a.hook(exitPlan(), {});
    await flush();
    const detail = a.ask(alerts[0].askId!)!;
    assert.equal(detail.kind, "plan");
    assert.match(detail.text, /Écrire hello.txt/);
    a.answer(detail.id, { decision: "allow" });
    assert.deepEqual(await out, { hookSpecificOutput: { hookEventName: "PreToolUse", permissionDecision: "allow", updatedInput: plan } });
  });

  it("refuse avec ce qu'il faut changer, sur une ligne", async () => {
    const a = createAgents(deps());
    const out = a.hook(exitPlan(), {});
    await flush();
    a.answer(alerts[0].askId!, { decision: "deny", feedback: "le fichier\ns'appelle salut.txt" });
    const o = (await out) as any;
    assert.equal(o.hookSpecificOutput.permissionDecision, "deny");
    assert.equal(
      o.hookSpecificOutput.permissionDecisionReason,
      "Plan refusé depuis la tablette. À changer : le fichier s'appelle salut.txt",
    );
  });

  it("refus sans retour : Claude attend la suite au clavier ; une autre réponse est refusée", async () => {
    const a = createAgents(deps());
    const out = a.hook(exitPlan(), {});
    await flush();
    assert.throws(() => a.answer(alerts[0].askId!, { answers: [] }), AnswerError);
    a.answer(alerts[0].askId!, { decision: "deny" });
    assert.match(((await out) as any).hookSpecificOutput.permissionDecisionReason, /au clavier/);
  });

  it("pendant qu'un plan ou une question est tenu, le filet ne le rend pas (Claude est « busy » sur le hook)", async () => {
    const a = createAgents(deps());
    const out = a.hook(exitPlan(), {});
    await flush();
    clock += 30_000;
    live = [{ sessionId: "s1", cwd: "/work/projet", status: "busy" }];
    await a.refresh();
    const id = alerts[0].askId!;
    assert.equal(a.ask(id)?.kind, "plan", "toujours en attente");
    a.answer(id, { decision: "allow" });
    assert.equal(((await out) as any).hookSpecificOutput.permissionDecision, "allow");
  });

  it("le PermissionRequest d'un plan rend la main au terminal tout de suite", async () => {
    const a = createAgents(deps());
    assert.deepEqual(await a.hook(event("PermissionRequest", { tool_name: "ExitPlanMode", tool_input: plan }), {}), {});
    assert.equal(a.list().sessions[0].state, "waiting");
    assert.equal(a.list().sessions[0].askId, null);
  });

  it("le fichier de plan que Claude écrit n'est pas une écriture à risque", () => {
    assert.equal(isRisky("Write", { file_path: "/Users/x/.claude/plans/p.md" }, "/work/projet"), false);
    assert.equal(
      isRisky("Write", { file_path: "/Users/x/Library/Application Support/orca/claude-profiles/abc/home/plans/p.md" }, "/work/projet"),
      false,
    );
    assert.equal(isRisky("Write", { file_path: "/Users/x/.claude/settings.json" }, "/work/projet"), true);
  });
});

describe("question posée par l'outil MCP tablette_demander", () => {
  const q = { question: "On déploie ?", options: ["Oui", "Non", "Demain"], multi: false, who: "Claude", where: "projet" };

  it("passe par la même carte et rend le libellé choisi", async () => {
    const a = createAgents(deps());
    const out = a.askTablet(q, 5_000);
    const id = alerts[0].askId!;
    assert.equal(alerts[0].kind, "waiting");
    const detail = a.ask(id)!;
    assert.equal(detail.session, "Claude");
    assert.equal(detail.dir, "projet");
    assert.equal(detail.expiresInMs, 5_000);
    assert.deepEqual(
      detail.questions?.[0].options.map((o) => o.label),
      ["Oui", "Non", "Demain"],
    );
    a.answer(id, { answers: [{ choices: [2] }] });
    assert.equal(await out, "Demain");
    assert.equal(alerts.at(-1)?.kind, "clear");
  });

  it("choix multiple et texte libre", async () => {
    const a = createAgents(deps());
    const out = a.askTablet({ ...q, multi: true }, 5_000);
    a.answer(alerts[0].askId!, { answers: [{ choices: [0, 2], other: "après 18 h" }] });
    assert.equal(await out, "Oui, Demain, après 18 h");
  });

  it("sans réponse : null ; tablette absente : refus", async () => {
    const a = createAgents(deps());
    assert.equal(await a.askTablet(q, 20), null);
    tablet = false;
    assert.throws(
      () => a.askTablet(q, 20),
      (e: AnswerError) => e.statusCode === 409,
    );
    tablet = true;
    assert.throws(() => a.askTablet({ ...q, options: [] }, 20), AnswerError);
  });
});

describe("filet claude agents --json", () => {
  it("crée les sessions sans hook, sans alerter à la découverte", async () => {
    const a = createAgents(deps());
    live = [
      { sessionId: "a", cwd: "/x/un", name: "un", status: "busy" },
      { sessionId: "b", cwd: "/x/deux", name: "deux", status: "waiting", waitingFor: "permission prompt" },
      { sessionId: "c", cwd: "/x/trois", name: "trois", status: "idle" },
    ];
    await a.refresh();
    await flush();
    assert.deepEqual(a.list().counts, { working: 1, monitoring: 0, waiting: 1, done: 0, limit: 0, idle: 1 });
    assert.deepEqual(alerts, []);
  });

  it("ignore une session en arrière-plan dont le processus n'existe plus", async () => {
    const a = createAgents(deps());
    live = [
      { sessionId: "vieille", cwd: "/x/v", name: "Review ADR", kind: "background", state: "blocked" },
      { sessionId: "vivante", cwd: "/x/w", name: "en cours", kind: "background", state: "working", status: "busy", pid: 12 },
    ];
    await a.refresh();
    assert.deepEqual(
      a.list().sessions.map((s) => s.id),
      ["vivante"],
    );
  });

  it("une session qui passe en attente alerte (sans demande : la tablette ne peut pas répondre)", async () => {
    const a = createAgents(deps());
    live = [{ sessionId: "a", cwd: "/x/un", name: "un", status: "busy" }];
    await a.refresh();
    live = [{ sessionId: "a", cwd: "/x/un", name: "un", status: "waiting", waitingFor: "input needed" }];
    await a.refresh();
    await flush();
    assert.deepEqual(alerts, [{ kind: "waiting", session: "a", name: "un", askId: undefined, waiting: 1 }]);
    assert.equal(a.list().sessions[0].waitingFor, "input needed");
  });

  it("ne contredit pas un hook de moins de 6 s", async () => {
    const a = createAgents(deps());
    await a.hook(event("UserPromptSubmit"), {});
    live = [{ sessionId: "s1", cwd: "/work/projet", status: "idle" }];
    clock += 2_000;
    await a.refresh();
    assert.equal(a.list().sessions[0].state, "working");
    clock += 5_000;
    await a.refresh();
    assert.equal(a.list().sessions[0].state, "done", "tour fini sans hook Stop : fini");
  });

  it("oublie une session absente deux relevés de suite, pas un relevé raté", async () => {
    const a = createAgents(deps());
    live = [{ sessionId: "a", cwd: "/x/un", status: "idle" }];
    await a.refresh();
    live = [];
    await a.refresh();
    assert.equal(a.list().total, 1);
    live = null;
    await a.refresh();
    assert.equal(a.list().total, 1, "claude agents en échec : on garde");
    live = [];
    await a.refresh();
    assert.equal(a.list().total, 0);
  });

  it("garde un fini ou une limite quand le processus est au repos", async () => {
    const a = createAgents(deps());
    await a.hook(event("Stop", { last_assistant_message: "ok" }), {});
    clock += 10_000;
    live = [{ sessionId: "s1", cwd: "/work/projet", status: "idle" }];
    await a.refresh();
    assert.equal(a.list().sessions[0].state, "done");
  });

  it("trie : attend, limite, fini, travaille, inactif ; au plus 40 sessions (la tablette filtre par projet)", async () => {
    const a = createAgents(deps());
    live = Array.from({ length: 42 }, (_, i) => ({
      sessionId: `s${i}`,
      cwd: `/x/${i}`,
      status: i === 41 ? ("waiting" as const) : ("idle" as const),
    }));
    await a.refresh();
    const list = a.list();
    assert.equal(list.sessions.length, 40);
    assert.equal(list.total, 42);
    assert.equal(list.sessions[0].id, "s41");
  });
});

describe("Orca", () => {
  it("sans hooks : l'état d'Orca prime (surveillance en tâche de fond = travaille ; tour fini = fini)", async () => {
    const a = createAgents(deps());
    live = [
      { sessionId: "veille", cwd: "/w/veille", status: "idle" },
      { sessionId: "finie", cwd: "/w/finie", status: "idle" },
    ];
    orca = {
      worktrees: [
        {
          path: "/w/veille",
          branch: "b1",
          agents: [{ paneKey: "p1", state: "working", toolName: null, lastMessage: "Surveillance du pipeline", structured: false }],
        },
        {
          path: "/w/finie",
          branch: "b2",
          agents: [{ paneKey: "p2", state: "done", toolName: null, lastMessage: "Les deux MR sont prêtes.", structured: false }],
        },
      ],
      handles: new Map(),
    };
    await a.refresh();
    const byId = Object.fromEntries(a.list().sessions.map((s) => [s.id, s]));
    assert.equal(byId.veille.state, "working");
    assert.equal(byId.finie.state, "done");
    assert.deepEqual(byId.finie.lines, ["Les deux MR sont prêtes."]);
    // Relevé suivant : claude toujours « idle », Orca toujours « working » : pas de bascule en « fini ».
    clock += 10_000;
    await a.refresh();
    assert.equal(a.list().sessions.find((s) => s.id === "veille")?.state, "working");
  });

  it("sans hooks : un tour qui se termine passe à fini, avec l'alerte", async () => {
    const a = createAgents(deps());
    live = [{ sessionId: "x", cwd: "/w/x", status: "busy" }];
    await a.refresh();
    clock += 60_000;
    live = [{ sessionId: "x", cwd: "/w/x", status: "idle" }];
    await a.refresh();
    await flush();
    assert.equal(a.list().sessions[0].state, "done");
    assert.equal(a.list().sessions[0].workedMs, 60_000);
    assert.equal(alerts.at(-1)?.kind, "done");
  });

  const info = (agents: OrcaInfo["worktrees"][number]["agents"]): OrcaInfo => ({
    worktrees: [{ path: "/work/projet", branch: "feat/x", agents }],
    handles: new Map([["tab:leaf", "term_1"]]),
  });

  it("branche et terminal par le volet donné par le hook ; « continue » seulement au repos", async () => {
    const a = createAgents(deps());
    orca = info([{ paneKey: "tab:leaf", state: null, toolName: "Edit", lastMessage: null, structured: false }]);
    await a.hook(event("UserPromptSubmit"), { pane: "tab:leaf" });
    live = [{ sessionId: "s1", cwd: "/work/projet", status: "busy" }];
    await a.refresh();
    let s = a.list().sessions[0];
    assert.equal(s.branch, "feat/x");
    assert.equal(s.tool, "Edit");
    assert.equal(s.canSend, false, "au travail : pas de message");
    await a.hook(event("Stop", { last_assistant_message: "ok" }), {});
    s = a.list().sessions[0];
    assert.equal(s.canSend, true);
    assert.equal(a.handleOf("s1")?.handle, "term_1");
  });

  it("le volet du hook prime sur le dossier : session lancée dans un autre dossier que le worktree", async () => {
    const a = createAgents(deps());
    orca = info([{ paneKey: "tab:leaf", state: null, toolName: null, lastMessage: null, structured: false }]);
    await a.hook({ ...event("Stop", { last_assistant_message: "ok" }), cwd: "/tmp/ailleurs" }, { pane: "tab:leaf" });
    live = [{ sessionId: "s1", cwd: "/tmp/ailleurs", status: "idle" }];
    await a.refresh();
    const s = a.list().sessions[0];
    assert.equal(s.branch, "feat/x");
    assert.equal(s.canSend, true);
  });

  it("une session de chat Orca reçoit le message dans son chat, pas dans un terminal", async () => {
    const a = createAgents(deps());
    orca = info([{ paneKey: "structured-agent-session-claude_x:y", state: null, toolName: null, lastMessage: null, structured: true }]);
    await a.hook(event("Stop", { last_assistant_message: "ok" }), {});
    live = [{ sessionId: "s1", cwd: "/work/projet", status: "idle" }];
    await a.refresh();
    const s = a.list().sessions[0];
    assert.equal(s.branch, "feat/x");
    assert.equal(s.canSend, true);
    assert.equal(s.canInterrupt, false, "Échap : terminal seulement");
    await a.send("s1", "lance les tests\ndu backend");
    assert.deepEqual(sent, [{ handle: "chat:claude_x", text: "lance les tests du backend" }]);
    assert.equal(a.list().sessions[0].state, "working");
    // Un prompt dicté d'une minute passe entier.
    await a.hook(event("Stop", { last_assistant_message: "ok" }), {});
    await a.send("s1", "x".repeat(1500));
    assert.equal(sent.at(-1)?.text.length, 1500);
  });

  it("un chat Orca stoppé (sans processus Claude) reste affiché, avec son projet, et le message le relance", async () => {
    const a = createAgents(deps());
    orca = {
      worktrees: [
        {
          path: "/work/vieux",
          branch: "fix/ancien",
          repo: "robot-framework",
          name: "Ancien chantier",
          activityAt: 900_000,
          agents: [
            {
              paneKey: "structured-agent-session-claude_old:z",
              state: "done",
              toolName: null,
              lastMessage: "Tout est vert.",
              structured: true,
            },
          ],
        },
      ],
      handles: new Map(),
    };
    live = [];
    await a.refresh();
    const s = a.list().sessions[0];
    assert.equal(s.id, "orca:claude_old");
    assert.equal(s.name, "Ancien chantier");
    assert.equal(s.project, "robot-framework");
    assert.equal(s.state, "done");
    assert.equal(s.since, 900_000);
    assert.deepEqual(s.lines, ["Tout est vert."]);
    assert.equal(s.canSend, true);
    assert.equal(a.list().counts.done, 1);
    await a.send("orca:claude_old", "relance les tests");
    assert.deepEqual(sent, [{ handle: "chat:claude_old", text: "relance les tests" }]);
  });

  it("un chat Orca dont la session tourne n'est pas compté deux fois ; projet = dossier hors Orca", async () => {
    const a = createAgents(deps());
    orca = info([{ paneKey: "structured-agent-session-claude_x:y", state: "done", toolName: null, lastMessage: null, structured: true }]);
    orca.worktrees[0].repo = "Mate";
    await a.hook(event("Stop", { last_assistant_message: "ok" }), {});
    await a.hook({ ...event("Stop", { last_assistant_message: "ok" }), session_id: "s2", cwd: "/Users/moi" }, {});
    live = [
      { sessionId: "s1", cwd: "/work/projet", status: "idle" },
      { sessionId: "s2", cwd: "/Users/moi", status: "idle" },
    ];
    await a.refresh();
    const list = a.list().sessions;
    assert.equal(list.length, 2);
    assert.equal(list.find((s) => s.id === "s1")?.project, "Mate");
    assert.equal(list.find((s) => s.id === "s2")?.project, "moi");
  });

  it("deux agents dans le worktree et pas de volet connu : pas de jonction hasardeuse", async () => {
    const a = createAgents(deps());
    orca = info([
      { paneKey: "tab:leaf", state: null, toolName: null, lastMessage: null, structured: false },
      { paneKey: "tab2:leaf2", state: null, toolName: null, lastMessage: null, structured: false },
    ]);
    await a.hook(event("Stop", { last_assistant_message: "ok" }), {});
    live = [{ sessionId: "s1", cwd: "/work/projet", status: "idle" }];
    await a.refresh();
    assert.equal(a.list().sessions[0].canSend, false);
    assert.equal(a.list().sessions[0].branch, "feat/x");
  });
});

/** Session au repos dans un terminal Orca (volet `tab:leaf`, terminal `term_1`). */
async function restingInOrca(
  a: ReturnType<typeof createAgents>,
  stopEvent: Record<string, unknown> = { hook_event_name: "Stop", last_assistant_message: "ok" },
) {
  orca = {
    worktrees: [
      {
        path: "/work/projet",
        branch: "feat/x",
        agents: [{ paneKey: "tab:leaf", state: null, toolName: null, lastMessage: null, structured: false }],
      },
    ],
    handles: new Map([["tab:leaf", "term_1"]]),
  };
  await a.hook(event(String(stopEvent.hook_event_name), stopEvent), { pane: "tab:leaf" });
  live = [{ sessionId: "s1", cwd: "/work/projet", status: "idle" }];
  clock += 10_000;
  await a.refresh();
}

describe("message rapide et session devant", () => {
  it("écrit une ligne dans le terminal d'une session au repos", async () => {
    const a = createAgents(deps());
    await restingInOrca(a);
    await a.send("s1", "continue\navec les tests");
    assert.deepEqual(sent, [{ handle: "term_1", text: "continue avec les tests" }]);
    assert.equal(a.list().sessions[0].state, "working");
  });

  it("refuse : session qui travaille, hors terminal Orca, inconnue, message vide", async () => {
    const a = createAgents(deps());
    await a.hook(event("Stop", { last_assistant_message: "ok" }), {});
    await assert.rejects(a.send("s1", "continue"), /ni dans Orca/);
    await assert.rejects(a.send("inconnue", "continue"), (e: AnswerError) => e.statusCode === 404);
    await restingInOrca(a);
    await assert.rejects(a.send("s1", "  \n "), /vide/);
    await a.hook(event("UserPromptSubmit"), {});
    await assert.rejects(a.send("s1", "continue"), (e: AnswerError) => e.statusCode === 409);
    assert.deepEqual(sent, []);
  });

  it("amène devant le terminal, ou le chat Orca de la session ; sinon refuse", async () => {
    const a = createAgents(deps());
    await restingInOrca(a);
    await a.focus("s1");
    assert.deepEqual(focused, [{ handle: "term_1", worktree: "/work/projet", tab: null }]);
    const b = createAgents(deps());
    await b.hook({ ...event("Stop"), session_id: "libre", cwd: "/ailleurs" }, {});
    await assert.rejects(b.focus("libre"), (e: AnswerError) => e.statusCode === 409);
    const chat = {
      paneKey: "structured-agent-session-claude_c9c1:67e9",
      state: "done",
      toolName: null,
      lastMessage: null,
      structured: true,
    };
    orca = { worktrees: [{ path: "/work/projet", branch: null, agents: [chat] }], handles: new Map() };
    await b.hook({ ...event("Stop"), session_id: "chat" }, {});
    live = [{ sessionId: "chat", cwd: "/work/projet", status: "idle" }];
    clock += 10_000;
    await b.refresh();
    await b.focus("chat");
    assert.deepEqual(focused[1], { handle: null, worktree: "/work/projet", tab: "agent-session:claude_c9c1" });
    // Plusieurs agents dans le worktree, aucun reconnu : le worktree seulement.
    orca = {
      worktrees: [{ path: "/work/projet", branch: null, agents: [chat, { ...chat, paneKey: "structured-agent-session-claude_d2:1" }] }],
      handles: new Map(),
    };
    clock += 10_000;
    await b.refresh();
    await b.focus("chat");
    assert.deepEqual(focused[2], { handle: null, worktree: "/work/projet", tab: null });
  });
});

describe("titre, ticket, MR, surveillance", () => {
  it("titre automatique et MR du journal, ticket et surveillance d'Orca", async () => {
    const a = createAgents({
      ...deps(),
      transcriptInfo: async (id) => (id === "s1" ? { title: "Agents Claude : première version", mr: 74 } : null),
    });
    await a.hook(event("UserPromptSubmit", { session_title: "agents-claude-v1-33" }), { pane: "tab:leaf" });
    orca = {
      worktrees: [
        {
          path: "/work/projet",
          branch: "auto-test/TECH-12256-catalog",
          ticket: "TECH-12256",
          mr: 394,
          agents: [{ paneKey: "tab:leaf", state: "working", monitoring: true, toolName: null, lastMessage: null, structured: false }],
        },
      ],
      handles: new Map(),
    };
    live = [{ sessionId: "s1", cwd: "/work/projet", status: "idle" }];
    clock += 10_000;
    await a.refresh();
    const s = a.list().sessions[0];
    assert.equal(s.title, "Agents Claude : première version");
    assert.equal(s.name, "agents-claude-v1-33");
    assert.equal(s.agent, "claude");
    assert.equal(s.ticket, "TECH-12256");
    assert.equal(s.mr, 74, "la MR du journal, plus récente, passe devant celle du worktree");
    assert.equal(s.state, "working");
    assert.equal(s.monitoring, true);
    assert.deepEqual(a.list().counts, { working: 0, monitoring: 1, waiting: 0, done: 0, limit: 0, idle: 0 });
  });

  it("sans titre : le nom ; sans Orca : le ticket lu dans la branche", async () => {
    const a = createAgents(deps());
    await a.hook(event("UserPromptSubmit", { session_title: "ma-session" }), {});
    const s = a.list().sessions[0];
    assert.equal(s.title, "ma-session");
    assert.equal(s.ticket, null);
    assert.equal(s.monitoring, false);
  });

  it("ticketOf : clé Jira dans la branche", () => {
    assert.equal(ticketOf("auto-test/TECH-12256-catalog-settings"), "TECH-12256");
    assert.equal(ticketOf("feat/QA-12_x"), "QA-12");
    assert.equal(ticketOf("feat/agents-claude"), null);
    assert.equal(ticketOf(null), null);
  });
});

describe("interrompre et quota", () => {
  it("interrompt une session qui travaille dans un terminal Orca (Échap), puis inactive", async () => {
    const a = createAgents(deps());
    orca = {
      worktrees: [
        {
          path: "/work/projet",
          branch: null,
          agents: [{ paneKey: "tab:leaf", state: null, toolName: null, lastMessage: null, structured: false }],
        },
      ],
      handles: new Map([["tab:leaf", "term_1"]]),
    };
    await a.hook(event("UserPromptSubmit"), { pane: "tab:leaf" });
    live = [{ sessionId: "s1", cwd: "/work/projet", status: "busy" }];
    await a.refresh();
    assert.equal(a.list().sessions[0].canInterrupt, true);
    await a.interrupt("s1");
    assert.deepEqual(sent, [{ handle: "term_1", text: "<Échap>" }]);
    assert.equal(a.list().sessions[0].state, "idle");
    await assert.rejects(a.interrupt("s1"), /ne travaille pas/);
  });

  it("refuse d'interrompre hors terminal Orca", async () => {
    const a = createAgents(deps());
    await a.hook(event("UserPromptSubmit"), {});
    assert.equal(a.list().sessions[0].canInterrupt, false);
    await assert.rejects(a.interrupt("s1"), /ni dans Orca/);
  });

  it("garde le quota de la statusline 3 h, avec son âge, rien d'autre", async () => {
    const a = createAgents(deps());
    a.statusline({
      cwd: "/secret",
      rate_limits: { five_hour: { used_percentage: 21.4, resets_at: 1_800_000 }, seven_day: { used_percentage: 44 } },
    });
    assert.deepEqual(a.list().quota, {
      fiveHour: { used: 21, resetsAt: 1_800_000_000 },
      sevenDay: { used: 44, resetsAt: null },
      at: clock,
      ageMs: 0,
    });
    a.statusline({ cwd: "/secret" });
    assert.equal(a.list().quota?.fiveHour?.used, 21, "une statusline sans quota ne l'efface pas");
    clock += 20 * 60_000;
    assert.equal(a.list().quota?.ageMs, 20 * 60_000);
    clock += 3 * 3_600_000;
    assert.equal(a.list().quota, null);
  });

  it("parseQuota : bornes et champs manquants", () => {
    assert.equal(parseQuota(undefined, 0), null);
    assert.equal(parseQuota({ five_hour: { used_percentage: "x" } }, 0), null);
    assert.deepEqual(parseQuota({ seven_day: { used_percentage: 140 } }, 5), {
      fiveHour: null,
      sevenDay: { used: 100, resetsAt: null },
      at: 5,
    });
  });

  it("parseOrcaQuota : usage Claude lu par Orca (`rateLimits.claude` de `accounts.list`)", () => {
    const claude = {
      provider: "claude",
      session: { usedPercent: 35.4, windowMinutes: 300, resetsAt: 1_793_229_600_357, resetDescription: "Thu 12:20 AM" },
      weekly: { usedPercent: 56, windowMinutes: 10080, resetsAt: 1_793_462_400_357 },
      updatedAt: 1_793_219_746_396,
      status: "ok",
    };
    assert.deepEqual(parseOrcaQuota(claude), {
      fiveHour: { used: 35, resetsAt: 1_793_229_600_357 },
      sevenDay: { used: 56, resetsAt: 1_793_462_400_357 },
      at: 1_793_219_746_396,
    });
    assert.equal(parseOrcaQuota({ ...claude, status: "error" })?.sevenDay?.used, 56, "Orca n'a pas pu relire : dernières valeurs");
    assert.equal(parseOrcaQuota({ ...claude, session: null, weekly: null }), null);
    assert.equal(parseOrcaQuota({ ...claude, updatedAt: "hier" }), null);
    assert.equal(parseOrcaQuota(null), null);
  });

  it("lit l'usage dans Orca à chaque minute ; la statusline plus récente l'emporte", async () => {
    let reads = 0;
    let fromOrca: Quota | null = { fiveHour: { used: 35, resetsAt: null }, sevenDay: { used: 56, resetsAt: null }, at: clock - 60_000 };
    const a = createAgents({ ...deps(), readQuota: async () => (reads++, fromOrca) });
    await a.refresh();
    assert.equal(a.list().quota?.sevenDay?.used, 56);
    await a.refresh();
    assert.equal(reads, 1, "pas plus d'une lecture par minute");
    a.statusline({ rate_limits: { five_hour: { used_percentage: 40 } } });
    assert.equal(a.list().quota?.fiveHour?.used, 40);
    clock += 61_000;
    fromOrca = { ...fromOrca, at: clock - 120_000 };
    await a.refresh();
    assert.equal(reads, 2);
    assert.equal(a.list().quota?.fiveHour?.used, 40, "un usage d'Orca plus ancien ne remplace pas la statusline");
  });

  it("écarte une fenêtre de quota déjà terminée", () => {
    const a = createAgents(deps());
    a.statusline({
      rate_limits: { five_hour: { used_percentage: 90, resets_at: (clock + 60_000) / 1000 }, seven_day: { used_percentage: 50 } },
    });
    clock += 61_000;
    assert.equal(a.list().quota?.fiveHour, null);
    assert.equal(a.list().quota?.sevenDay?.used, 50);
  });

  it("limite sans heure dans le message : la fin de la fenêtre de 5 h de la statusline", async () => {
    const a = createAgents(deps());
    a.statusline({ rate_limits: { five_hour: { used_percentage: 100, resets_at: (clock + 3_600_000) / 1000 } } });
    await a.hook(event("StopFailure", { error: "rate_limit", last_assistant_message: "API Error: Rate limit reached" }), {});
    assert.equal(a.list().sessions[0].resumeAt, clock + 3_600_000);
  });
});

describe("reprise après limite", () => {
  const limit = { hook_event_name: "StopFailure", error: "rate_limit", last_assistant_message: "limit reached · resets 3pm" };
  const almost3pm = () => {
    clock = new Date(2026, 9, 28, 14, 59, 59, 900).getTime();
  };

  it("lit l'heure de reprise et l'annonce", async () => {
    const a = createAgents(deps());
    almost3pm();
    await restingInOrca(a, limit);
    const s = a.list().sessions[0];
    assert.equal(s.state, "limit");
    assert.equal(new Date(s.resumeAt!).getHours(), 15);
    assert.equal(s.resumeArmed, false);
  });

  it("armé depuis la tablette : envoie « continue » à l'heure, une seule fois", async () => {
    const a = createAgents(deps());
    almost3pm();
    await a.hook(event("StopFailure", limit), { pane: "tab:leaf" });
    orca = {
      worktrees: [
        {
          path: "/work/projet",
          branch: null,
          agents: [{ paneKey: "tab:leaf", state: null, toolName: null, lastMessage: null, structured: false }],
        },
      ],
      handles: new Map([["tab:leaf", "term_1"]]),
    };
    live = [{ sessionId: "s1", cwd: "/work/projet", status: "idle" }];
    await a.refresh();
    a.resume("s1", true);
    assert.equal(a.list().sessions[0].resumeArmed, true);
    assert.deepEqual(sent, [], "pas avant l'heure");
    await new Promise((r) => setTimeout(r, 250));
    assert.deepEqual(sent, [{ handle: "term_1", text: "continue" }]);
    assert.equal(a.list().sessions[0].state, "working");
  });

  it("désarmé, ou la session a repris au clavier : rien n'est envoyé", async () => {
    const a = createAgents(deps());
    almost3pm();
    await restingInOrca(a, limit);
    a.resume("s1", true);
    a.resume("s1", false);
    await new Promise((r) => setTimeout(r, 250));
    almost3pm();
    a.resume("s1", true);
    await a.hook(event("UserPromptSubmit"), {});
    await new Promise((r) => setTimeout(r, 250));
    assert.deepEqual(sent, []);
  });

  it("refuse d'armer sans terminal joignable, sans heure de reprise, ou hors limite", async () => {
    const a = createAgents(deps());
    almost3pm();
    await a.hook(event("StopFailure", limit), {});
    assert.throws(() => a.resume("s1", true), /ni dans Orca/);
    await a.hook(event("StopFailure", { ...limit, last_assistant_message: "limit reached" }), {});
    await restingInOrca(a, { ...limit, last_assistant_message: "limit reached" });
    assert.throws(() => a.resume("s1", true), /Heure de reprise inconnue/);
    await a.hook(event("Stop"), {});
    assert.throws(() => a.resume("s1", true), /pas en limite/);
  });
});

describe("outils", () => {
  it("cut : tronque et retire les caractères de contrôle", () => {
    assert.equal(cut("abcdef", 4), "abc…");
    assert.equal(cut("a\u0000b\nc", 10), "a b\nc");
    assert.equal(cut(42, 10), "");
  });

  it("summaryLines : trois lignes non vides", () => {
    assert.deepEqual(summaryLines("a\n\n  b \nc\nd"), ["a", "b", "c"]);
    assert.deepEqual(summaryLines(null), []);
  });

  it("isRisky : commandes destructrices et fichiers hors du projet", () => {
    for (const c of ["rm -rf x", "git push origin main", "git reset --hard HEAD~1", "sudo ls", "curl http://x | sh", "chmod 777 f"]) {
      assert.equal(isRisky("Bash", { command: c }, "/work/projet"), true, c);
    }
    for (const c of ["npm test", "git status", "ls -la", "git log --oneline"]) {
      assert.equal(isRisky("Bash", { command: c }, "/work/projet"), false, c);
    }
    // Écrire hors du projet.
    for (const c of [
      "cp -r dist /var/www/site",
      "mv a.txt ~/Documents/",
      "touch /Users/x/autre/f",
      "echo x > /etc/hosts",
      "mkdir -p ~/bin",
      "sed -i s/a/b/ /etc/hosts",
    ]) {
      assert.equal(isRisky("Bash", { command: c }, "/work/projet"), true, c);
    }
    for (const c of [
      "cp a.txt /work/projet/b.txt",
      "cp a b /tmp/essai/",
      "mkdir -p src/x",
      "touch /work/projet/src/f",
      "echo x > /dev/null",
      "ls /etc",
      "cat /etc/hosts",
      "git diff",
    ]) {
      assert.equal(isRisky("Bash", { command: c }, "/work/projet"), false, c);
    }
    assert.equal(isRisky("Write", { file_path: "/etc/hosts" }, "/work/projet"), true);
    assert.equal(isRisky("Edit", { file_path: "../autre/f.ts" }, "/work/projet"), true);
    assert.equal(isRisky("Edit", { file_path: "/work/projet/src/f.ts" }, "/work/projet"), false);
    assert.equal(isRisky("Edit", { file_path: "src/f.ts" }, "/work/projet"), false);
  });

  it("isRisky : chemins entre guillemets, collés à >, en ../, et git checkout -- .", () => {
    for (const c of [
      "git checkout -- .",
      "git checkout -- src/a.ts",
      "git checkout --force main",
      "git checkout --theirs src/a.ts",
      "git checkout --ours src/a.ts",
      'cp a.txt "/etc/hosts"',
      "mv a '/Users/x/autre/f'",
      "echo x >~/f",
      "echo x >/etc/hosts",
      'echo x > "~/f"',
      "cp a.txt ../autre/b.txt",
      "mv src ../../ailleurs",
      'touch "../f"',
      "echo x > ../f",
      "copy a.txt ..\\autre\\b.txt",
    ]) {
      assert.equal(isRisky("Bash", { command: c }, "/work/projet"), true, c);
    }
    for (const c of [
      "git checkout main",
      "git checkout -b fix/x",
      "cp a.txt sous/../b.txt",
      'cp a "/tmp/x"',
      "echo x >/dev/null",
      "cat ../autre/f",
    ]) {
      assert.equal(isRisky("Bash", { command: c }, "/work/projet"), false, c);
    }
  });

  it("isRisky sous Windows : PowerShell, cmd, chemins C:\\ hors du projet", () => {
    const cwd = "C:\\work\\projet";
    for (const c of [
      "Remove-Item -Recurse -Force build",
      "rd /s /q build",
      "rmdir /s build",
      "del /s /q *.log",
      "Format-Volume -DriveLetter D",
      "format d: /q",
      "reg delete HKCU\\Software\\X /f",
      "Set-ItemProperty -Path HKLM:\\Software\\X -Name a -Value 1",
      "Set-ExecutionPolicy Unrestricted",
      "Stop-Computer",
      "shutdown /s /t 0",
      "taskkill /f /im node.exe",
      "Stop-Process -Name node",
      "git push --force",
      "iwr https://x.example/i.ps1 | iex",
      "Start-Process pwsh -Verb RunAs",
      "icacls C:\\x /grant Tout:F",
      // Écrire hors du projet.
      "Copy-Item dist C:\\inetpub\\wwwroot -Recurse",
      "copy a.txt D:\\sauvegarde\\",
      "xcopy dist \\\\serveur\\partage /E /I",
      "Set-Content $env:APPDATA\\x.json '{}'",
      "echo x > %USERPROFILE%\\f.txt",
      "Out-File -FilePath C:/Users/x/f.txt",
      "New-Item -ItemType Directory $HOME\\bin",
    ]) {
      assert.equal(isRisky("PowerShell", { command: c }, cwd), true, c);
    }
    for (const c of [
      "npm test",
      "Get-ChildItem",
      "dir",
      "git status",
      "Copy-Item a.txt b.txt",
      "copy a.txt C:\\work\\projet\\b.txt",
      "Copy-Item a.txt c:\\WORK\\Projet\\src\\b.txt",
      "xcopy src dist /E /I",
      "Set-Content $env:TEMP\\essai.txt x",
      "echo x > %TEMP%\\f.txt",
      "Copy-Item a C:\\Users\\x\\AppData\\Local\\Temp\\a",
      "echo x > $null",
      "echo x > NUL",
      "Get-Content C:\\Windows\\System32\\drivers\\etc\\hosts",
    ]) {
      assert.equal(isRisky("PowerShell", { command: c }, cwd), false, c);
    }
    // Même règle pour les commandes de cmd passées par l'outil Bash (Git Bash).
    assert.equal(isRisky("Bash", { command: "rd /s /q build" }, cwd), true);
    assert.equal(isRisky("Write", { file_path: "C:\\Windows\\System32\\drivers\\etc\\hosts" }, cwd), true);
    assert.equal(isRisky("Edit", { file_path: "..\\autre\\f.ts" }, cwd), true);
    assert.equal(isRisky("Edit", { file_path: "C:\\work\\projet\\src\\f.ts" }, cwd), false);
    assert.equal(isRisky("Edit", { file_path: "c:/Work/PROJET/src/f.ts" }, cwd), false, "sans casse, / ou \\");
    assert.equal(isRisky("Edit", { file_path: "src\\f.ts" }, cwd), false);
    assert.equal(isRisky("Write", { file_path: "C:\\Users\\x\\.claude\\plans\\p.md" }, cwd), false, "plan de Claude");
  });

  it("parseResume : heure de reprise lue dans le message, lendemain si passée", () => {
    const now = new Date(2026, 9, 28, 13, 0).getTime();
    assert.equal(new Date(parseResume("limit reached · resets 3pm", now)!).getHours(), 15);
    assert.equal(new Date(parseResume("resets at 14:30", now)!).getMinutes(), 30);
    const next = new Date(parseResume("resets 9am", now)!);
    assert.equal(next.getDate(), 29);
    assert.equal(next.getHours(), 9);
    assert.equal(parseResume("rien", now), null);
    assert.equal(parseResume(null, now), null);
  });
});

describe("statusline enveloppée", () => {
  const opts = { port: 3000, secret: "s3cret" };

  it("garde la statusline d'origine, s'enveloppe une seule fois, se retire proprement", () => {
    const settings = { statusLine: { type: "command", command: "bun ~/statusline.ts # perso", padding: 0 } };
    const once = withStatusLine(settings, opts);
    const command = (once.statusLine as { command: string }).command;
    assert.equal(unwrapStatusLine(command), "bun ~/statusline.ts # perso");
    assert.equal((once.statusLine as { padding: number }).padding, 0);
    const twice = withStatusLine(once, { port: 3000, secret: "nouveau" });
    assert.equal(unwrapStatusLine((twice.statusLine as { command: string }).command), "bun ~/statusline.ts # perso");
    assert.deepEqual(withoutStatusLine(twice), settings);
    assert.deepEqual(withoutStatusLine({}), {});
    assert.deepEqual(withoutStatusLine(withStatusLine({}, opts)), {}, "pas de statusline avant : aucune après");
  });

  it("en vrai : la sortie d'origine s'affiche et le compagnon reçoit l'entrée", async () => {
    let received = "";
    let secret = "";
    const srv = http.createServer((req, res) => {
      secret = String(req.headers["x-companion-hook"]);
      req.on("data", (c) => (received += c));
      req.on("end", () => res.end("{}"));
    });
    await new Promise<void>((r) => srv.listen(0, "127.0.0.1", r));
    const port = (srv.address() as { port: number }).port;
    const command = statusLineCommand({ port, secret: "s3cret" }, "cat > /dev/null; echo 'ligne perso' # fin");
    const out = await new Promise<string>((resolve, reject) => {
      const child = execFile("/bin/sh", ["-c", command], (err, stdout) => (err ? reject(err) : resolve(stdout)));
      child.stdin!.end(JSON.stringify({ rate_limits: { five_hour: { used_percentage: 3 } } }));
    });
    assert.equal(out, "ligne perso\n");
    // Sans curl (image Linux minimale de la CI), l'enveloppe affiche quand même la statusline, sans rien envoyer.
    if (spawnSync("sh", ["-c", "command -v curl"]).status !== 0) return void srv.close();
    for (let i = 0; i < 50 && !received; i++) await new Promise((r) => setTimeout(r, 50));
    srv.close();
    assert.equal(secret, "s3cret");
    assert.equal(JSON.parse(received).rate_limits.five_hour.used_percentage, 3);
  });
});

describe("bloc de hooks", () => {
  const block = hooksBlock({ port: 3000, secret: "s3cret", holdS: 90 });

  it("attend sur PermissionRequest et AskUserQuestion seulement, avec une marge sur le délai", () => {
    const hold = (e: string) => (block.hooks[e][0].hooks[0] as { timeout: number }).timeout;
    assert.equal(hold("PermissionRequest"), 120);
    assert.equal(hold("PreToolUse"), 120);
    assert.equal(block.hooks.PreToolUse[0].matcher, "AskUserQuestion|ExitPlanMode");
    for (const e of ["UserPromptSubmit", "Notification", "Stop", "StopFailure", "SessionEnd"]) assert.equal(hold(e), 5, e);
    assert.equal(block.hooks.SessionStart, undefined, "SessionStart n'accepte pas les hooks http");
  });

  it("envoie le secret et le volet Orca, rien d'autre", () => {
    const h = block.hooks.Stop[0].hooks[0] as { url: string; headers: Record<string, string>; allowedEnvVars: string[] };
    assert.equal(h.url, "http://127.0.0.1:3000/agents/hook");
    assert.deepEqual(h.headers, { "X-Companion-Hook": "s3cret", "X-Orca-Pane": "$ORCA_PANE_KEY" });
    assert.deepEqual(h.allowedEnvVars, ["ORCA_PANE_KEY"]);
  });

  it("fusion : garde les hooks des autres, remplace l'ancienne version, se retire proprement", () => {
    const orcaHook = { type: "command", command: "orca-hook.sh" };
    const settings = { model: "opus", hooks: { Stop: [{ hooks: [orcaHook] }], Notification: [{ hooks: [orcaHook] }] } };
    const once = mergeAgentHooks(settings, block);
    assert.equal(once.model, "opus");
    assert.deepEqual(once.hooks!.Stop[0].hooks, [orcaHook]);
    assert.equal(once.hooks!.Stop.length, 2);
    const twice = mergeAgentHooks(once, hooksBlock({ port: 3000, secret: "nouveau" }));
    assert.equal(twice.hooks!.Stop.length, 2, "pas de doublon");
    assert.equal((twice.hooks!.Stop[1].hooks[0] as { headers: Record<string, string> }).headers["X-Companion-Hook"], "nouveau");
    assert.deepEqual(withoutAgentHooks(twice), settings);
    assert.deepEqual(withoutAgentHooks({ hooks: mergeAgentHooks({}, block).hooks }), {});
  });
});

describe("Windows : fenêtre du terminal, sans Orca", () => {
  const calls: string[] = [];
  const desk = (spots: Record<number, { canSend: boolean }>): NonNullable<AgentsDeps["desktop"]> => ({
    locate: async (pid, names) => {
      calls.push(`locate ${pid} ${names.join("|")}`);
      return spots[pid] ?? null;
    },
    focus: async (pid) => void calls.push(`focus ${pid}`),
    send: async (pid, _names, line) => void calls.push(`send ${pid} ${line}`),
    interrupt: async (pid) => void calls.push(`interrupt ${pid}`),
  });
  beforeEach(() => {
    calls.length = 0;
  });

  it("fenêtre sûre : amener devant, écrire, interrompre ; noms = ceux de Claude, pas le dossier", async () => {
    const a = createAgents({ ...deps(), desktop: desk({ 42: { canSend: true } }) });
    live = [
      { sessionId: "s1", cwd: "C:\\work\\projet", pid: 42, name: "Corrige le parseur", status: "idle" },
      { sessionId: "s2", cwd: "C:\\work\\autre", pid: 43, name: "autre", status: "idle" },
    ];
    await a.refresh();
    const [one, two] = ["s1", "s2"].map((id) => a.list().sessions.find((s) => s.id === id)!);
    assert.deepEqual([one.canFocus, one.canSend, one.canInterrupt], [true, true, false]);
    assert.deepEqual([two.canFocus, two.canSend], [false, false], "aucune fenêtre trouvée");
    assert.deepEqual(calls.sort(), ["locate 42 Corrige le parseur", "locate 43 "]);
    calls.length = 0;
    await a.focus("s1");
    await a.send("s1", "continue\nmerci");
    assert.equal(a.list().sessions.find((s) => s.id === "s1")!.canInterrupt, true);
    await a.interrupt("s1");
    assert.deepEqual(calls, ["focus 42", "send 42 continue merci", "interrupt 42"]);
    assert.deepEqual(sent, [], "rien par Orca");
    await assert.rejects(a.focus("s2"), (e: AnswerError) => e.statusCode === 409 && /Je ne sais pas/.test(e.message));
  });

  it("fenêtre pas sûre (éditeur, onglet du Terminal pas affiché) : devant seulement, message refusé", async () => {
    const a = createAgents({ ...deps(), desktop: desk({ 42: { canSend: false } }) });
    live = [{ sessionId: "s1", cwd: "C:\\work\\projet", pid: 42, name: "Titre", status: "idle" }];
    await a.refresh();
    const v = a.list().sessions[0];
    assert.deepEqual([v.canFocus, v.canSend], [true, false]);
    await assert.rejects(a.send("s1", "continue"), (e: AnswerError) => e.statusCode === 409 && /fenêtre de terminal sûre/.test(e.message));
    await a.focus("s1");
    assert.deepEqual(calls.slice(1), ["focus 42"]);
  });

  it("une session d'Orca passe par Orca, sa fenêtre n'est pas cherchée", async () => {
    const a = createAgents({ ...deps(), desktop: desk({ 42: { canSend: true } }) });
    await restingInOrca(a);
    live = [{ sessionId: "s1", cwd: "/work/projet", pid: 42, status: "idle" }];
    clock += 10_000;
    await a.refresh();
    await a.send("s1", "continue");
    assert.deepEqual(sent, [{ handle: "term_1", text: "continue" }]);
    assert.ok(!calls.some((c) => c.startsWith("locate")), "pas cherchée");
  });
});

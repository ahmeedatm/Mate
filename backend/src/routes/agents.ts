import type { FastifyInstance } from "fastify";
import { AnswerError, createAgents, type QuestionAnswer } from "../agents.ts";
import { hookSecret, hookSecretOk } from "../agents-secret.ts";
import {
  agentsAvailable,
  focusSession,
  frontIsClaude,
  interruptSession,
  listClaude,
  listOrca,
  sendText,
  transcriptInfo,
} from "../agents-sources.ts";
import { isMac } from "../os.ts";
import { sendToTablet, tabletStatus } from "../tablet.ts";

/** Attente maximale d'une réponse de la tablette avant de rendre la main au terminal (AGENTS_HOLD_S, 90 s par défaut). */
/** AGENTS_ALWAYS_ALERT=1 : alerter même si le terminal est devant (essais depuis un terminal). */
const alwaysAlert = process.env.AGENTS_ALWAYS_ALERT === "1";
/** Attente d'une réponse à une question posée par l'outil MCP (AGENTS_ASK_S, 3 min par défaut). */
const askTabletMs = Math.max(5, Number(process.env.AGENTS_ASK_S) || 180) * 1000;
const holdMs = Math.max(5, Number(process.env.AGENTS_HOLD_S) || 90) * 1000;

/** Agents Claude Code (src/agents.ts) : `POST /agents/hook` pour Claude Code, le reste pour la tablette. */
export default async function agentRoutes(app: FastifyInstance) {
  const agents = createAgents({
    listClaude,
    listOrca,
    transcriptInfo,
    frontIsClaude: alwaysAlert ? async () => false : frontIsClaude,
    tabletConnected: () => tabletStatus().connected,
    // Prévenir la tablette sans attendre : l'alerte ne doit jamais retarder un hook.
    notify: (alert) => void sendToTablet({ type: "agent", ...alert }, 20_000),
    holdMs,
    sendText,
    interruptSession,
    focusSession,
  });
  if (isMac) hookSecret(); // créé au démarrage : `npm run agents:hooks` et les contrôles de sanity le lisent
  if (isMac) agents.start(Math.max(1, Number(process.env.AGENTS_POLL_MS) || 4000));
  app.addHook("onClose", async () => agents.stop());

  // Mac seulement pour l'instant : `agents` est absent de /deck/features ailleurs.
  app.addHook("preHandler", async (req, reply) => {
    if (req.url.startsWith("/agents") && !isMac) return reply.code(501).send({ error: "Agents Claude disponibles sur macOS seulement" });
  });

  app.post<{ Body: Record<string, unknown> }>(
    "/agents/hook",
    { logLevel: "warn", bodyLimit: 8 * 1024 * 1024, schema: { body: { type: "object" } } },
    async (req, reply) => {
      // Secret propre aux hooks (en-tête posé par `npm run agents:hooks`) : le jeton d'appairage n'est pas utilisé ici.
      if (!hookSecretOk(req.headers["x-companion-hook"] as string | undefined))
        return reply.code(401).send({ error: "Secret de hook invalide" });
      const pane = req.headers["x-orca-pane"];
      return agents.hook(req.body, {
        pane: typeof pane === "string" ? pane : undefined,
        // Connexion coupée par Claude Code (délai du hook, session fermée) : plus rien à attendre.
        onClosed: (cb) => reply.raw.on("close", () => !reply.raw.writableEnded && cb()),
      });
    },
  );

  // Statusline de Claude Code (enveloppe posée par `npm run agents:hooks -- --install --statusline`) : quota 5 h / 7 j.
  app.post<{ Body: Record<string, unknown> }>(
    "/agents/statusline",
    { logLevel: "warn", bodyLimit: 1024 * 1024, schema: { body: { type: "object" } } },
    async (req, reply) => {
      if (!hookSecretOk(req.headers["x-companion-hook"] as string | undefined))
        return reply.code(401).send({ error: "Secret de hook invalide" });
      agents.statusline(req.body);
      return {};
    },
  );

  app.get("/agents", { logLevel: "warn" }, async () => ({ ...agents.list(), available: agentsAvailable(), now: Date.now() }));

  app.get<{ Querystring: { id: string } }>(
    "/agents/ask",
    { schema: { querystring: { type: "object", required: ["id"], properties: { id: { type: "string", maxLength: 80 } } } } },
    async (req, reply) => agents.ask(req.query.id) ?? reply.code(404).send({ error: "Cette demande n'existe plus" }),
  );

  type AnswerBody = { id: string; decision?: "allow" | "deny"; held?: boolean; answers?: QuestionAnswer[]; feedback?: string };
  app.post<{ Body: AnswerBody }>(
    "/agents/answer",
    {
      schema: {
        body: {
          type: "object",
          required: ["id"],
          additionalProperties: false,
          properties: {
            id: { type: "string", maxLength: 80 },
            decision: { enum: ["allow", "deny"] },
            held: { type: "boolean" },
            feedback: { type: "string", maxLength: 2000 },
            answers: {
              type: "array",
              maxItems: 4,
              items: {
                type: "object",
                additionalProperties: false,
                properties: {
                  choices: { type: "array", maxItems: 6, items: { type: "integer" } },
                  other: { type: "string", maxLength: 2000 },
                },
              },
            },
          },
        },
      },
    },
    async (req, reply) => {
      try {
        agents.answer(req.body.id, req.body);
        return { ok: true };
      } catch (e) {
        if (e instanceof AnswerError) return reply.code(e.statusCode).send({ error: e.message });
        throw e;
      }
    },
  );

  /** Erreurs prévues des actions sur une session : leur phrase et leur code passent tels quels. */
  const guarded = async (reply: { code: (n: number) => { send: (b: unknown) => unknown } }, action: () => unknown) => {
    try {
      await action();
      return { ok: true };
    } catch (e) {
      if (e instanceof AnswerError) return reply.code(e.statusCode).send({ error: e.message });
      throw e;
    }
  };
  const sessionBody = { type: "object", required: ["session"], additionalProperties: false } as const;
  const sessionId = { type: "string", minLength: 1, maxLength: 80 } as const;

  // Message rapide à une session au repos (« continue » ou une phrase dictée).
  app.post<{ Body: { session: string; text: string } }>(
    "/agents/send",
    {
      schema: {
        body: {
          ...sessionBody,
          required: ["session", "text"],
          properties: { session: sessionId, text: { type: "string", minLength: 1, maxLength: 2000 } },
        },
      },
    },
    (req, reply) => guarded(reply, () => agents.send(req.body.session, req.body.text)),
  );

  // Interrompt le tour en cours (Échap), après confirmation sur la tablette.
  app.post<{ Body: { session: string } }>(
    "/agents/interrupt",
    { schema: { body: { ...sessionBody, properties: { session: sessionId } } } },
    (req, reply) => guarded(reply, () => agents.interrupt(req.body.session)),
  );

  // Amène la session devant sur l'ordinateur.
  app.post<{ Body: { session: string } }>(
    "/agents/focus",
    { schema: { body: { ...sessionBody, properties: { session: sessionId } } } },
    (req, reply) => guarded(reply, () => agents.focus(req.body.session)),
  );

  // Limite atteinte : envoyer « continue » à l'heure de reprise (armé depuis la tablette, désarmable).
  app.post<{ Body: { session: string; armed: boolean } }>(
    "/agents/resume",
    {
      schema: { body: { ...sessionBody, required: ["session", "armed"], properties: { session: sessionId, armed: { type: "boolean" } } } },
    },
    (req, reply) => guarded(reply, () => agents.resume(req.body.session, req.body.armed)),
  );

  // Outil MCP `tablette_demander` (src/mcp.ts) : Claude pose lui-même une question sur la tablette ; tenue jusqu'à la réponse.
  app.post<{ Body: { question: string; options: string[]; multi?: boolean; who?: string; where?: string } }>(
    "/agents/ask-tablet",
    {
      schema: {
        body: {
          type: "object",
          required: ["question", "options"],
          additionalProperties: false,
          properties: {
            question: { type: "string", minLength: 1, maxLength: 500 },
            options: { type: "array", minItems: 1, maxItems: 6, items: { type: "string", minLength: 1, maxLength: 200 } },
            multi: { type: "boolean" },
            who: { type: "string", maxLength: 100 },
            where: { type: "string", maxLength: 300 },
          },
        },
      },
    },
    async (req, reply) => {
      const b = req.body;
      try {
        const answer = await agents.askTablet(
          { question: b.question, options: b.options, multi: b.multi === true, who: b.who || "Claude", where: b.where ?? "" },
          askTabletMs,
        );
        return { answered: answer !== null, answer };
      } catch (e) {
        if (e instanceof AnswerError) return reply.code(e.statusCode).send({ error: e.message });
        throw e;
      }
    },
  );
}

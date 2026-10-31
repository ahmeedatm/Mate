import type { FastifyInstance } from "fastify";
import { docEntry } from "../docs.ts";
import {
  completeCommand,
  FOCUS_ACTIONS,
  MODES,
  NOISES,
  noteTabletAddress,
  pollCommands,
  reportState,
  sendToTablet,
  tabletStatus,
  type TabletCommand,
} from "../tablet.ts";

/** Canal ordinateur → tablette (utilisé par le serveur MCP : Claude pilote la tablette, voir src/mcp.ts). */
export default async function tabletRoutes(app: FastifyInstance) {
  app.post<{ Body: { state?: unknown } }>(
    "/tablet/poll",
    { logLevel: "warn", schema: { body: { type: "object", properties: { state: { type: "object" } } } } },
    async (req, reply) => {
      noteTabletAddress(req.ip);
      const closed = new AbortController();
      reply.raw.on("close", () => closed.abort());
      return { commands: await pollCommands(req.body.state ?? null, 25_000, closed.signal) };
    },
  );

  app.post<{ Body: { id: string; ok: boolean; error?: string; data?: Record<string, unknown>; state?: unknown } }>(
    "/tablet/result",
    {
      logLevel: "warn",
      bodyLimit: 10 * 1024 * 1024, // capture d'écran en base64
      schema: {
        body: {
          type: "object",
          required: ["id", "ok"],
          properties: {
            id: { type: "string" },
            ok: { type: "boolean" },
            error: { type: "string" },
            data: { type: "object" },
            state: { type: "object" },
          },
        },
      },
    },
    async (req) => {
      const { id, ok, error, data, state } = req.body;
      if (state) reportState(state);
      return { known: completeCommand(id, { ok, error, data }) };
    },
  );

  app.get("/tablet", async () => tabletStatus());

  type CommandBody = {
    type: TabletCommand["type"];
    mode?: (typeof MODES)[number];
    text?: string;
    speak?: boolean;
    seconds?: number;
    path?: string;
    action?: (typeof FOCUS_ACTIONS)[number];
    focusMinutes?: number;
    breakMinutes?: number;
    kind?: (typeof NOISES)[number];
    volume?: number;
  };

  app.post<{ Body: CommandBody }>(
    "/tablet/command",
    {
      schema: {
        body: {
          type: "object",
          required: ["type"],
          additionalProperties: false,
          properties: {
            type: { enum: ["mode", "message", "document", "focus", "noise", "screenshot"] },
            mode: { enum: MODES },
            text: { type: "string", minLength: 1, maxLength: 1000 },
            speak: { type: "boolean" },
            seconds: { type: "integer", minimum: 2, maximum: 600 },
            path: { type: "string" },
            action: { enum: FOCUS_ACTIONS },
            focusMinutes: { type: "integer", minimum: 1, maximum: 180 },
            breakMinutes: { type: "integer", minimum: 1, maximum: 60 },
            kind: { enum: NOISES },
            volume: { type: "number", minimum: 0, maximum: 1 },
          },
        },
      },
    },
    async (req, reply) => {
      const b = req.body;
      const missing = (field: string) => reply.code(400).send({ error: `Champ '${field}' requis pour '${b.type}'` });
      let command: TabletCommand;
      switch (b.type) {
        case "mode":
          if (!b.mode) return missing("mode");
          command = { type: "mode", mode: b.mode };
          break;
        case "message":
          if (!b.text) return missing("text");
          command = { type: "message", text: b.text, speak: b.speak, seconds: b.seconds };
          break;
        case "document": {
          // Sans chemin : ferme le document affiché.
          const doc = b.path ? await docEntry(b.path) : null;
          if (doc === undefined) return reply.code(404).send({ error: "Fichier introuvable dans le dossier partagé" });
          command = { type: "document", doc };
          break;
        }
        case "focus":
          if (!b.action) return missing("action");
          command = { type: "focus", action: b.action, focusMinutes: b.focusMinutes, breakMinutes: b.breakMinutes };
          break;
        case "noise":
          if (!b.kind) return missing("kind");
          command = { type: "noise", kind: b.kind, volume: b.volume };
          break;
        case "screenshot":
          command = { type: "screenshot" };
          break;
        case "call":
        case "agent":
        case "notification":
        case "meeting":
        case "foreground":
        case "lock":
        case "settings":
        case "layout":
        case "update":
          // Envoyés seulement par la surveillance des appels (src/calls.ts), les agents Claude (src/agents.ts), celle
          // des notifications (src/notifications.ts), celle des réunions (src/meeting-watch.ts), celle du premier plan (src/foreground-watch.ts), celle du verrouillage
          // (src/screen-lock.ts) et la page locale.
          return reply.code(400).send({ error: "Ordre réservé à l'ordinateur" });
      }
      const result = await sendToTablet(command, b.type === "screenshot" ? 30_000 : 15_000);
      return result.ok ? result : reply.code(502).send(result);
    },
  );
}

import type { FastifyInstance } from "fastify";
import { runAction } from "../deck.ts";
import {
  issueTransitions,
  issueUrl,
  JiraFieldsError,
  jiraConfigured,
  myBoards,
  myIssues,
  selectBoards,
  selectedBoards,
  transitionIssue,
} from "../jira.ts";

const keyParams = {
  type: "object",
  required: ["key"],
  properties: { key: { type: "string", pattern: "^[A-Z][A-Z0-9_]+-[0-9]+$" } },
};

export default async function jiraRoutes(app: FastifyInstance) {
  app.addHook("preHandler", async (_req, reply) => {
    if (!jiraConfigured) return reply.code(501).send({ error: "Jira n'est pas configuré sur l'ordinateur." });
  });

  app.get("/jira/boards", async () => ({ boards: await myBoards(), selected: await selectedBoards() }));

  app.post<{ Body: { ids: number[] } }>(
    "/jira/boards",
    {
      schema: {
        body: {
          type: "object",
          required: ["ids"],
          properties: { ids: { type: "array", maxItems: 10, items: { type: "integer", minimum: 1 } } },
        },
      },
    },
    async (req) => {
      await selectBoards(req.body.ids);
      return { ok: true };
    },
  );

  app.get("/jira/issues", async () => myIssues());

  app.get<{ Params: { key: string } }>("/jira/issues/:key/transitions", { schema: { params: keyParams } }, async (req) => ({
    transitions: await issueTransitions(req.params.key),
  }));

  app.post<{ Params: { key: string }; Body: { id: string } }>(
    "/jira/issues/:key/transitions",
    {
      schema: {
        params: keyParams,
        body: { type: "object", required: ["id"], properties: { id: { type: "string", pattern: "^[0-9]{1,20}$" } } },
      },
    },
    async (req, reply) => {
      try {
        await transitionIssue(req.params.key, req.body.id);
      } catch (e) {
        if (e instanceof JiraFieldsError) return reply.code(409).send({ error: e.message });
        throw e;
      }
      return { ok: true };
    },
  );

  app.post<{ Params: { key: string } }>("/jira/issues/:key/open", { schema: { params: keyParams } }, async (req) => {
    await runAction({ type: "url", url: issueUrl(req.params.key) });
    return { ok: true };
  });
}

import type { FastifyInstance } from "fastify";
import { runAction } from "../deck.ts";
import {
  columnList,
  issueTransitions,
  issueUrl,
  JiraFieldsError,
  jiraConfigured,
  myBoards,
  myIssues,
  saveColumnChoices,
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
  // Seule la lecture des tickets répond sans Jira relié (`connected: false` : la tablette montre sa carte « Connecter Jira »).
  app.addHook("preHandler", async (req, reply) => {
    if (jiraConfigured() || (req.method === "GET" && req.routeOptions.url === "/jira/issues")) return;
    return reply.code(501).send({ error: "Jira n'est pas relié sur l'ordinateur." });
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

  app.get("/jira/columns", async () => columnList());

  const keys = { type: "array", maxItems: 50, items: { type: "string", minLength: 1, maxLength: 100 } };
  app.post<{ Body: { hidden: string[]; everyone: string[] } }>(
    "/jira/columns",
    { schema: { body: { type: "object", required: ["hidden", "everyone"], properties: { hidden: keys, everyone: keys } } } },
    async (req) => {
      await saveColumnChoices(req.body);
      return { ok: true };
    },
  );

  app.get("/jira/issues", async () =>
    jiraConfigured() ? { connected: true, ...(await myIssues()) } : { connected: false, boardsChosen: false, columns: [] },
  );

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

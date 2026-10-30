import type { FastifyInstance } from "fastify";
import { listMrs, listPipelines, mergeMr, mrUrl, pipelineUrl } from "../gitlab.ts";
import { openTarget } from "../os.ts";

// Chemin complet d'un projet GitLab (`groupe/sous-groupe/projet`) : jamais de `..`, ni de segment vide ou commençant par `.` ou `-`.
const PROJECT = { type: "string", pattern: "^[A-Za-z0-9_][A-Za-z0-9_.-]*(/[A-Za-z0-9_][A-Za-z0-9_.-]*)+$", maxLength: 300 };
const IID = { type: "integer", minimum: 1 };

export default async function gitlabRoutes(app: FastifyInstance) {
  // --- MR GitLab de l'utilisateur du jeton (src/gitlab.ts). Les erreurs sont des GitLabError : server.ts les rend en { error }.

  app.get("/gitlab/mrs", async () => listMrs());

  // `sha` = tête de la MR telle qu'affichée : GitLab refuse si elle a bougé depuis.
  app.post<{ Body: { project: string; iid: number; sha: string } }>(
    "/gitlab/mrs/merge",
    {
      schema: {
        body: {
          type: "object",
          required: ["project", "iid", "sha"],
          additionalProperties: false,
          properties: { project: PROJECT, iid: IID, sha: { type: "string", pattern: "^[0-9a-f]{40}$" } },
        },
      },
    },
    async (req) => {
      await mergeMr(req.body.project, req.body.iid, req.body.sha);
      return { ok: true };
    },
  );

  // L'adresse est reconstruite ici : la tablette ne fournit jamais d'URL.
  app.post<{ Body: { project: string; iid: number } }>(
    "/gitlab/mrs/open",
    {
      schema: {
        body: { type: "object", required: ["project", "iid"], additionalProperties: false, properties: { project: PROJECT, iid: IID } },
      },
    },
    async (req) => {
      await openTarget(mrUrl(req.body.project, req.body.iid));
      return { ok: true };
    },
  );

  // Derniers pipelines des projets de mes MR (lecture seule).
  app.get("/gitlab/pipelines", async () => listPipelines());

  app.post<{ Body: { project: string; id: number } }>(
    "/gitlab/pipelines/open",
    {
      schema: {
        body: { type: "object", required: ["project", "id"], additionalProperties: false, properties: { project: PROJECT, id: IID } },
      },
    },
    async (req) => {
      await openTarget(pipelineUrl(req.body.project, req.body.id));
      return { ok: true };
    },
  );
}

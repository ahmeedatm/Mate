import type { FastifyInstance } from "fastify";
import { listPrs, listRuns, mergePr, prUrl, runUrl } from "../github.ts";
import { openTarget } from "../os.ts";

// `propriétaire/dépôt` comme GitHub les autorise : jamais `.` ni `..` comme nom de dépôt, rien d'autre qu'un seul `/`.
const REPO = { type: "string", pattern: "^[A-Za-z0-9][A-Za-z0-9-]{0,38}/(?!\\.\\.?$)[A-Za-z0-9_.-]{1,100}$" };
const NUMBER = { type: "integer", minimum: 1 };

export default async function githubRoutes(app: FastifyInstance) {
  // --- PR GitHub de l'utilisateur connecté (src/github.ts). Les erreurs sont des GitHubError : server.ts les rend en { error }.
  // Mêmes champs que les routes GitLab : `project` = dépôt, `iid` = numéro de la PR.

  app.get("/github/prs", async () => listPrs());

  // `sha` = tête de la PR telle qu'affichée : GitHub refuse si elle a bougé depuis.
  app.post<{ Body: { project: string; iid: number; sha: string } }>(
    "/github/prs/merge",
    {
      schema: {
        body: {
          type: "object",
          required: ["project", "iid", "sha"],
          additionalProperties: false,
          properties: { project: REPO, iid: NUMBER, sha: { type: "string", pattern: "^[0-9a-f]{40}$" } },
        },
      },
    },
    async (req) => {
      await mergePr(req.body.project, req.body.iid, req.body.sha);
      return { ok: true };
    },
  );

  // L'adresse est reconstruite ici : la tablette ne fournit jamais d'URL.
  app.post<{ Body: { project: string; iid: number } }>(
    "/github/prs/open",
    {
      schema: {
        body: { type: "object", required: ["project", "iid"], additionalProperties: false, properties: { project: REPO, iid: NUMBER } },
      },
    },
    async (req) => {
      await openTarget(prUrl(req.body.project, req.body.iid));
      return { ok: true };
    },
  );

  // Derniers runs GitHub Actions des dépôts de mes PR (lecture seule), au format des pipelines GitLab.
  app.get("/github/actions", async () => listRuns());

  app.post<{ Body: { project: string; id: number } }>(
    "/github/actions/open",
    {
      schema: {
        body: { type: "object", required: ["project", "id"], additionalProperties: false, properties: { project: REPO, id: NUMBER } },
      },
    },
    async (req) => {
      await openTarget(runUrl(req.body.project, req.body.id));
      return { ok: true };
    },
  );
}

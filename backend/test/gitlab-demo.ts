/**
 * Compagnon + faux GitLab pour essayer les widgets GitLab et la connexion par jeton collé sur l'émulateur (jamais de vrai GitLab).
 * GITLAB_DEMO_PORT (3930) : le compagnon, jeton `jeton-de-test`, joint par l'émulateur en 10.0.2.2:<port>.
 * GITLAB_DEMO_FAKE_PORT (3931) : le faux GitLab (API, fusion ; accepte le jeton `glpat-jeton-de-demo`).
 * GITLAB_DEMO_CONTROL_PORT (3932) : pilotage depuis le Mac, à la place du navigateur et de l'utilisateur de GitLab :
 *   POST /login      colle le jeton de démo dans la page locale : la tablette passe de « Connecter GitLab » aux MR
 *   POST /revoke     GitLab révoque le jeton : le compte passe à « jeton refusé »
 *   GET  /seen       requêtes reçues par le faux GitLab (jetons masqués)
 *   GET  /opened     adresses que le compagnon a ouvertes dans le navigateur de l'ordinateur (faux pilotage)
 * Faux pilotage (test/fake-os.ts, Mac) : rien ne s'ouvre ni ne se fusionne pour de vrai ; données dans un dossier temporaire.
 */
import http from "node:http";
import { fakeDiscussion, fakeMr, fakePipeline, fakeSchedule, fakeStages, startFakeGitlab } from "./fake-gitlab.ts";
import { startServer, TOKEN } from "./server.ts";

const PORT = Number(process.env.GITLAB_DEMO_PORT || 3930);
const FAKE_PORT = Number(process.env.GITLAB_DEMO_FAKE_PORT || 3931);
const CONTROL_PORT = Number(process.env.GITLAB_DEMO_CONTROL_PORT || 3932);

const DEMO_TOKEN = "glpat-jeton-de-demo";
const gitlab = await startFakeGitlab(FAKE_PORT);
gitlab.tokens[DEMO_TOKEN] = ["api"];

// Une MR par colonne du widget GitLab, un relecteur à relire, deux planifications dont une en échec : le faux compte est « camille ».
const minutesAgo = (m: number) => new Date(Date.now() - m * 60_000).toISOString();
const mine = (iid: number, title: string, over: Record<string, unknown> = {}) =>
  fakeMr({
    iid: String(iid),
    title,
    updatedAt: minutesAgo(iid),
    approved: false,
    approvedBy: { nodes: [] },
    headPipeline: fakePipeline({ id: `gid://gitlab/Ci::Pipeline/${iid}` }),
    commitsWithoutMergeCommits: { nodes: [{ committedDate: minutesAgo(60), author: { username: "camille" } }] },
    ...over,
  });
gitlab.mrs = {
  assigned: [
    mine(1, "Brouillon : export CSV", { draft: true, detailedMergeStatus: "DRAFT_STATUS" }),
    mine(2, "Corriger l'arrondi de la TVA", {
      detailedMergeStatus: "DISCUSSIONS_NOT_RESOLVED",
      discussions: { nodes: [fakeDiscussion(false, [{ at: minutesAgo(10), by: "eugenie" }])] },
      headPipeline: fakePipeline({
        id: "gid://gitlab/Ci::Pipeline/2",
        status: "FAILED",
        stages: fakeStages(["build", "success"], ["test", "failed"]),
      }),
    }),
    mine(3, "Relances automatiques", { approved: true, approvedBy: { nodes: [{ name: "Eugénie", username: "eugenie" }] } }),
    mine(4, "Pagination des clients", {
      detailedMergeStatus: "CI_STILL_RUNNING",
      headPipeline: fakePipeline({
        id: "gid://gitlab/Ci::Pipeline/4",
        status: "RUNNING",
        finishedAt: null,
        duration: null,
        stages: fakeStages(["build", "success"], ["test", "running"], ["deploy", "created"]),
      }),
    }),
    mine(5, "Mettre à jour le client PDF", { detailedMergeStatus: "NOT_APPROVED" }),
  ],
  review: [fakeMr({ iid: "40", title: "Page de connexion", approvedBy: { nodes: [] }, project: { fullPath: "grp/autre" } })],
};
gitlab.memberProjects = ["grp/proj", "grp/autre"];
gitlab.schedules = {
  "grp/proj": [
    fakeSchedule({ id: "gid://gitlab/Ci::PipelineSchedule/1", description: "Nightly" }),
    fakeSchedule({
      id: "gid://gitlab/Ci::PipelineSchedule/2",
      description: "Smoke tests",
      lastPipeline: fakePipeline({ id: "gid://gitlab/Ci::Pipeline/90", status: "FAILED", stages: fakeStages(["tests", "failed"]) }),
    }),
  ],
};
gitlab.failedJobs = { 2: ["tests-unitaires"], 90: ["smoke-login"] };
const server = await startServer({
  port: PORT,
  env: { GITLAB_URL: gitlab.url, COMPUTER_NAME: "Ordi de démo" },
});

const routes: Record<string, () => Promise<unknown>> = {
  "POST /login": async () => {
    const res = await fetch(`${server.url}/local/accounts/gitlab`, {
      method: "PUT",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ token: DEMO_TOKEN }),
    });
    return { status: res.status, ...(await res.json()) };
  },
  "POST /revoke": async () => void gitlab.revokeAll(),
  "GET /seen": async () => gitlab.seen.map((s) => ({ ...s, privateToken: s.privateToken && "…" })),
  "GET /opened": async () => server.osCalls(),
};
const control = http.createServer(async (req, res) => {
  const handler = routes[`${req.method} ${req.url}`];
  res.setHeader("content-type", "application/json");
  if (!handler) return res.writeHead(404).end("{}");
  try {
    res.end(JSON.stringify((await handler()) ?? { ok: true }));
  } catch (e) {
    res.writeHead(500).end(JSON.stringify({ error: String(e) }));
  }
});
await new Promise<void>((r) => control.listen(CONTROL_PORT, "127.0.0.1", r));

console.log(`Compagnon de démo sur ${server.url} (émulateur : 10.0.2.2:${PORT}), jeton : ${TOKEN}`);
console.log(`Faux GitLab : ${gitlab.url} ; pilotage : http://127.0.0.1:${CONTROL_PORT} (POST /login, /revoke ; GET /seen, /opened)`);
console.log(`Code d'appairage : curl -s -H 'authorization: Bearer ${TOKEN}' ${server.url}/local/status`);

const quit = async () => {
  control.close();
  await server.stop();
  await gitlab.stop();
  process.exit(0);
};
process.on("SIGTERM", quit);
process.on("SIGINT", quit);
setInterval(() => {}, 1 << 30);

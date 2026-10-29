/**
 * Compagnon + faux GitLab pour essayer l'onglet GitLab et sa connexion OAuth sur l'émulateur (jamais de vrai GitLab).
 * GITLAB_DEMO_PORT (3930) : le compagnon, jeton `jeton-de-test`, joint par l'émulateur en 10.0.2.2:<port>.
 * GITLAB_DEMO_FAKE_PORT (3931) : le faux GitLab (page d'autorisation, /oauth/token, API, fusion).
 * GITLAB_DEMO_CONTROL_PORT (3932) : pilotage depuis le Mac, à la place du navigateur et de l'utilisateur de GitLab :
 *   POST /login      parcours complet (start, Autoriser, retour) : la tablette passe de « Connecter GitLab » aux MR
 *   POST /revoke     GitLab révoque tout : le compte passe à « à reconnecter »
 *   POST /expire     jeton d'accès périmé au prochain appel (renouvellement observable dans /seen)
 *   GET  /seen       requêtes reçues par le faux GitLab (jetons masqués)
 *   GET  /opened     adresses que le compagnon a ouvertes dans le navigateur de l'ordinateur (faux pilotage)
 * Faux pilotage (test/fake-os.ts, Mac) : rien ne s'ouvre ni ne se fusionne pour de vrai ; données dans un dossier temporaire.
 */
import http from "node:http";
import { FAKE_CLIENT_ID, startFakeGitlab } from "./fake-gitlab.ts";
import { startServer, TOKEN } from "./server.ts";

const PORT = Number(process.env.GITLAB_DEMO_PORT || 3930);
const FAKE_PORT = Number(process.env.GITLAB_DEMO_FAKE_PORT || 3931);
const CONTROL_PORT = Number(process.env.GITLAB_DEMO_CONTROL_PORT || 3932);

const gitlab = await startFakeGitlab(FAKE_PORT);
const server = await startServer({
  port: PORT,
  env: { GITLAB_URL: gitlab.url, GITLAB_CLIENT_ID: FAKE_CLIENT_ID, COMPUTER_NAME: "Ordi de démo" },
});

const mask = (s?: string) => s?.replace(/(Bearer |^)(at|rt|code)-[\w-]+/g, "$1$2-…");
const routes: Record<string, () => Promise<unknown>> = {
  "POST /login": async () => {
    const start = await fetch(`${server.url}/oauth/gitlab/start`, { redirect: "manual" });
    const back = await gitlab.authorize(start.headers.get("location")!);
    const done = await fetch(back, { redirect: "manual" });
    return { redirect: done.headers.get("location") };
  },
  "POST /revoke": async () => void gitlab.revokeAll(),
  "POST /expire": async () => void (gitlab.expiresIn = 60),
  "GET /seen": async () =>
    gitlab.seen.map((s) => ({
      ...s,
      url: s.url.replace(/code=[^&]+/, "code=…"),
      authorization: mask(s.authorization),
      body: s.body && JSON.parse(JSON.stringify(s.body, (k, v) => (/token|code|verifier/.test(k) ? "…" : v))),
    })),
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
console.log(
  `Faux GitLab : ${gitlab.url} ; pilotage : http://127.0.0.1:${CONTROL_PORT} (POST /login, /revoke, /expire ; GET /seen, /opened)`,
);
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

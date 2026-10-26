/**
 * Backend réel des tests du Deck sur émulateur (app/src/androidTest/.../DeckTest.kt), lancé par scripts/test-android.sh.
 * Deck de test (deck-emulateur.json), jeton connu, données dans un dossier temporaire.
 * Port 3910 : le backend. Port 3911 : pilotage par les tests (arrêt, redémarrage, touches reçues par le backend).
 * Linux seulement : sur macOS et Windows, toucher une touche agirait vraiment sur cet ordinateur.
 */
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { startServer, type TestServer } from "./server.ts";

if (process.platform === "darwin" || process.platform === "win32") {
  console.error(
    "Linux seulement : ici les touches du deck piloteraient cet ordinateur (sur Mac, scripts/test-android.sh passe par Docker).",
  );
  process.exit(1);
}

const PORT = 3910;
const CONTROL_PORT = 3911;
// 0.0.0.0 dans Docker (port publié vers l'émulateur du Mac) ; 127.0.0.1 en CI, l'émulateur le joint par 10.0.2.2.
const host = process.env.HOST ?? "127.0.0.1";
const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-deck-"));
const env = { DECK_FILE: path.join(path.dirname(fileURLToPath(import.meta.url)), "deck-emulateur.json"), COMPUTER_NAME: "Ordi de test" };

let server: TestServer | undefined;
/** Journal des serveurs arrêtés depuis la dernière remise à zéro, et début du journal du serveur actuel à ignorer. */
let pastLog = "";
let skip = 0;

const start = async () => (server ??= await startServer({ port: PORT, host, dataDir, env }));
async function stop() {
  if (!server) return;
  pastLog += server.output().slice(skip);
  skip = 0;
  await server.stop();
  server = undefined;
}

/** Touches reçues par le backend (POST /deck/run), dans l'ordre : identifiants de tuiles. */
function runs() {
  return (pastLog + (server?.output().slice(skip) ?? "")).split("\n").flatMap((line) => {
    try {
      const entry = JSON.parse(line);
      return entry.msg === "Touche du deck" ? [entry.tile as string] : [];
    } catch {
      return [];
    }
  });
}

const routes: Record<string, () => Promise<unknown>> = {
  "GET /health": async () => ({ running: !!server }),
  "GET /runs": async () => ({ runs: runs() }),
  "POST /stop": stop,
  "POST /start": async () => void (await start()),
  // Avant chaque test : backend en marche, touches perso vides, journal des touches remis à zéro.
  "POST /reset": async () => {
    await start();
    fs.rmSync(path.join(dataDir, "keys.json"), { force: true });
    pastLog = "";
    skip = server!.output().length;
  },
};

await start();
const control = http.createServer((req, res) => {
  const route = routes[`${req.method} ${req.url}`];
  if (!route) return void res.writeHead(404).end();
  route().then(
    (body) => res.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify(body ?? { ok: true })),
    (e: Error) => res.writeHead(500).end(e.message),
  );
});
control.listen(CONTROL_PORT, host, () => console.log(`Backend du deck sur ${host}:${PORT}, pilotage sur ${host}:${CONTROL_PORT}`));

const quit = async () => {
  control.close();
  await stop();
  fs.rmSync(dataDir, { recursive: true, force: true });
  process.exit(0);
};
process.on("SIGTERM", quit);
process.on("SIGINT", quit);

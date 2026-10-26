/**
 * Backend réel des tests du Deck sur émulateur (app/src/androidTest/.../DeckTest.kt), lancé par scripts/test-android.sh.
 * Deck de test (deck-emulateur.json), jeton connu, données dans un dossier temporaire.
 * Port 3910 : le backend. Port 3911 : pilotage par les tests (arrêt, redémarrage, touches reçues par le backend).
 * Pilotage simulé en système non pilotable (test/fake-os.ts, `os: "other"`) : toucher une touche répond 501 comme
 * sous Linux, et rien n'agit sur la machine, quel que soit son système.
 */
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { startServer, type TestServer } from "./server.ts";

const PORT = 3910;
const CONTROL_PORT = 3911;
// L'émulateur joint la boucle locale de l'ordinateur hôte par 10.0.2.2.
const host = "127.0.0.1";
const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-deck-"));
const env = { DECK_FILE: path.join(path.dirname(fileURLToPath(import.meta.url)), "deck-emulateur.json"), COMPUTER_NAME: "Ordi de test" };

let server: TestServer | undefined;
/** Journal des serveurs arrêtés depuis la dernière remise à zéro, et début du journal du serveur actuel à ignorer. */
let pastLog = "";
let skip = 0;

const start = async () => (server ??= await startServer({ os: "other", port: PORT, dataDir, env }));
async function stop() {
  if (!server) return;
  // Journal du serveur arrêté : dans celui de ce script (app/build/test-android/backend.log).
  process.stdout.write(server.output());
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

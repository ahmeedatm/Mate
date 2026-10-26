import { spawn, type ChildProcess } from "node:child_process";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const backendDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
export const TOKEN = "jeton-de-test";

export type TestServer = {
  url: string;
  dataDir: string;
  /** Requête avec le jeton ; `auth: false` pour l'envoyer sans. */
  call: (method: string, route: string, body?: unknown, auth?: boolean) => Promise<{ status: number; json: any }>;
  /** Journal du serveur (une ligne JSON par événement). */
  output: () => string;
  stop: () => Promise<void>;
};

/** `dataDir` donné : gardé à l'arrêt (redémarrage avec la même identité). `env` : variables en plus (DECK_FILE…). */
export type ServerOptions = { port?: number; host?: string; dataDir?: string; env?: Record<string, string> };

function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.once("error", reject);
    srv.listen(0, "127.0.0.1", () => {
      const port = (srv.address() as net.AddressInfo).port;
      srv.close(() => resolve(port));
    });
  });
}

/** Lance le vrai serveur (USB seulement par défaut) avec un dossier de données vide. */
export async function startServer(options: ServerOptions = {}): Promise<TestServer> {
  const port = options.port ?? (await freePort());
  const dataDir = options.dataDir ?? fs.mkdtempSync(path.join(os.tmpdir(), "companion-test-"));
  const docsDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-docs-"));
  const url = `http://127.0.0.1:${port}`;
  let output = "";
  const child: ChildProcess = spawn(process.execPath, ["--import", "tsx", "src/server.ts"], {
    cwd: backendDir,
    env: {
      ...process.env,
      HOST: options.host ?? "127.0.0.1",
      PORT: String(port),
      COMPANION_TOKEN: TOKEN,
      DATA_DIR: dataDir,
      DOCS_DIR: docsDir,
      COMPUTER_NAME: "Ordi de test",
      CALENDAR_ICS_URLS: "",
      NO_ALERT: "1",
      ...options.env,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stdout!.on("data", (d) => (output += d));
  child.stderr!.on("data", (d) => (output += d));

  const call: TestServer["call"] = async (method, route, body, auth = true) => {
    const headers: Record<string, string> = {};
    if (auth) headers.authorization = `Bearer ${TOKEN}`;
    if (body !== undefined) headers["content-type"] = "application/json";
    const res = await fetch(url + route, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await res.text();
    let json: any = text;
    try {
      json = JSON.parse(text);
    } catch {
      // Page HTML : texte brut.
    }
    return { status: res.status, json };
  };

  const deadline = Date.now() + 20_000;
  for (;;) {
    if (child.exitCode !== null) throw new Error(`Le serveur s'est arrêté au démarrage :\n${output}`);
    try {
      if ((await fetch(`${url}/health`)).ok) break;
    } catch {
      // Pas encore à l'écoute.
    }
    if (Date.now() > deadline) {
      child.kill();
      throw new Error(`Le serveur ne répond pas après 20 s :\n${output}`);
    }
    await new Promise((r) => setTimeout(r, 200));
  }

  const stop = async () => {
    if (child.exitCode === null) {
      const exited = new Promise((r) => child.once("exit", r));
      child.kill();
      await exited;
    }
    if (!options.dataDir) fs.rmSync(dataDir, { recursive: true, force: true });
    fs.rmSync(docsDir, { recursive: true, force: true });
  };
  return { url, dataDir, call, output: () => output, stop };
}

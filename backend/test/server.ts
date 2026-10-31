import { spawn, type ChildProcess } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import type { FakeCall, FakeState } from "./fake-os.ts";

const backendDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
export const TOKEN = "jeton-de-test";

/** Code d'appairage, recalculé par les tests comme le fait la tablette (PairingCode.kt) : vérifie aussi la formule. */
export const pairingCode = (channel: string, tablet: string, computer: string) =>
  String(createHash("sha256").update(`mate-pair-1\n${channel}\n${tablet}\n${computer}`).digest().readUInt32BE(0) % 1_000_000).padStart(
    6,
    "0",
  );

export type TestServer = {
  url: string;
  dataDir: string;
  /** Requête avec le jeton ; `auth: false` pour l'envoyer sans. */
  call: (method: string, route: string, body?: unknown, auth?: boolean) => Promise<{ status: number; json: any }>;
  /** Scénario du faux pilotage (test/fake-os.ts), relu par le serveur à chaque appel. */
  setOs: (state: FakeState) => void;
  /** Actions demandées au faux pilotage depuis le démarrage, ou depuis le dernier `clearOsCalls`. */
  osCalls: () => FakeCall[];
  clearOsCalls: () => void;
  /** Journal du serveur (une ligne JSON par événement). */
  output: () => string;
  stop: () => Promise<void>;
};

/**
 * `os` : système simulé. `dataDir` donné : gardé à l'arrêt (redémarrage avec la même identité).
 * `env` : variables en plus (DECK_FILE…).
 */
export type ServerOptions = {
  os?: "mac" | "windows" | "other";
  port?: number;
  host?: string;
  dataDir?: string;
  env?: Record<string, string>;
};

export function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.once("error", reject);
    srv.listen(0, "127.0.0.1", () => {
      const port = (srv.address() as net.AddressInfo).port;
      srv.close(() => resolve(port));
    });
  });
}

/**
 * Lance le vrai serveur (USB seulement par défaut) avec un dossier de données vide. Le pilotage de l'ordinateur (src/os.ts)
 * est remplacé par test/fake-os.ts : aucun test ne lance d'appli ni n'envoie de touches ; `os` : système simulé.
 */
export async function startServer(options: ServerOptions = {}): Promise<TestServer> {
  // Les fichiers de test tournent en parallèle : entre freePort et l'écoute, un autre serveur peut prendre le port.
  for (let attempt = 1; ; attempt++) {
    try {
      return await startOnce(options, options.port ?? (await freePort()));
    } catch (e) {
      if (!(e instanceof PortTaken) || options.port !== undefined || attempt >= 5) throw e;
    }
  }
}

/** Le port répond, mais c'est un autre serveur de test : à relancer ailleurs. */
class PortTaken extends Error {}

async function startOnce(options: ServerOptions, port: number): Promise<TestServer> {
  const dataDir = options.dataDir ?? fs.mkdtempSync(path.join(os.tmpdir(), "companion-test-"));
  // Identité connue d'avance : /health dit si c'est bien ce serveur-ci qui répond sur le port.
  const identityFile = path.join(dataDir, "identity.json");
  if (!fs.existsSync(identityFile)) fs.writeFileSync(identityFile, JSON.stringify({ id: randomUUID(), token: randomUUID() }));
  const expectedId = (JSON.parse(fs.readFileSync(identityFile, "utf8")) as { id: string }).id;
  const docsDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-docs-"));
  const fakeDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-os-"));
  const osState = path.join(fakeDir, "state.json");
  const osLog = path.join(fakeDir, "calls.jsonl");
  const url = `http://127.0.0.1:${port}`;
  let output = "";
  const child: ChildProcess = spawn(process.execPath, ["--import", "tsx", "--import", "./test/fake-os-hook.ts", "src/server.ts"], {
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
      // Pas de vrai Claude Code ni de vrai Orca dans les tests (src/agents-sources.ts) ; les tests des agents donnent leurs faux.
      CLAUDE_BIN: "/inexistant",
      ORCA_BIN: "/inexistant",
      // Pas de vrai GitLab : un GITLAB_TOKEN du poste ne doit pas ouvrir l'onglet GitLab ; les tests donnent leur faux serveur.
      GITLAB_TOKEN: "",
      GITLAB_URL: "",
      GITLAB_CLIENT_ID: "",
      // Pas de vrai GitHub non plus.
      GITHUB_TOKEN: "",
      GITHUB_URL: "",
      GITHUB_CLIENT_ID: "",
      ACCOUNTS_FILE: path.join(backendDir, "test", "accounts-vide.json"),
      FAKE_OS: options.os ?? "mac",
      FAKE_OS_STATE: osState,
      FAKE_OS_LOG: osLog,
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
    let other = false;
    try {
      const res = await fetch(`${url}/health`);
      if (res.ok && ((await res.json()) as { id?: string }).id === expectedId) break;
      other = res.ok;
    } catch {
      // Pas encore à l'écoute.
    }
    if (other) {
      child.kill();
      if (!options.dataDir) fs.rmSync(dataDir, { recursive: true, force: true });
      fs.rmSync(docsDir, { recursive: true, force: true });
      fs.rmSync(fakeDir, { recursive: true, force: true });
      throw new PortTaken(`Port ${port} déjà pris par un autre serveur de test`);
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
    fs.rmSync(fakeDir, { recursive: true, force: true });
  };
  const setOs = (state: FakeState) => fs.writeFileSync(osState, JSON.stringify(state));
  const osCalls = (): FakeCall[] =>
    fs.existsSync(osLog)
      ? fs
          .readFileSync(osLog, "utf8")
          .split("\n")
          .filter(Boolean)
          .map((l) => JSON.parse(l))
      : [];
  const clearOsCalls = () => fs.rmSync(osLog, { force: true });
  return {
    url,
    dataDir,
    call,
    setOs,
    osCalls,
    clearOsCalls,
    output: () => output,
    stop,
  };
}

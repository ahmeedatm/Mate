import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { describe, it } from "node:test";
import { runShell } from "../src/run-command.ts";

/** Vivant et pas zombie : dans le conteneur de la CI, personne ne ramasse le processus tué, qui reste « Z ». */
const alive = (pid: number) => {
  try {
    process.kill(pid, 0);
  } catch {
    return false;
  }
  const stat = `/proc/${pid}/stat`;
  return !fs.existsSync(stat) || fs.readFileSync(stat, "utf8").split(") ")[1]?.[0] !== "Z";
};

describe("runShell", { skip: process.platform === "win32" }, () => {
  it("rend la sortie de la commande", async () => {
    assert.equal(await runShell("echo bonjour", { timeoutMs: 5000 }), "bonjour\n");
  });

  it("au délai, arrête aussi les processus lancés par la commande", async () => {
    const pidFile = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "mate-run-")), "pid");
    await assert.rejects(runShell(`sleep 30 & echo $! > ${pidFile}; wait`, { timeoutMs: 300 }), /La commande a échoué/);
    const child = Number(fs.readFileSync(pidFile, "utf8"));
    await new Promise((r) => setTimeout(r, 100));
    assert.equal(alive(child), false, "le sleep lancé par la commande tourne encore");
  });

  it("une sortie trop longue est refusée", async () => {
    await assert.rejects(runShell("yes | head -c 200000", { timeoutMs: 5000, maxBytes: 1024 }), /La commande a échoué/);
  });
});

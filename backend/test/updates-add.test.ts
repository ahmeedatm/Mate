import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, describe, it } from "node:test";
import { promisify } from "node:util";

// scripts/updates.ts `release` : alimente le serveur de mises à jour de l'ordinateur depuis une release GitHub (fausse `gh`).

const backend = path.resolve(import.meta.dirname, "..");
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "companion-updates-add-"));
after(() => fs.rmSync(tmp, { recursive: true, force: true }));

const release = path.join(tmp, "release");
fs.mkdirSync(release);
const files: Record<string, string> = {
  "companion-0.4.0.apk": "apk",
  "backend-0.4.0-mac.tar.gz": "mac",
  "backend-0.4.0-windows.tar.gz": "windows",
  "Mate-Mac.zip": "zip",
};
for (const [name, content] of Object.entries(files)) fs.writeFileSync(path.join(release, name), content);
fs.writeFileSync(
  path.join(release, "manifest.json"),
  JSON.stringify({
    updates: [
      { package: "app.mate", label: "Mate", versionCode: 400_000, versionName: "0.4.0", file: "companion-0.4.0.apk", size: 3, sha256: "x" },
    ],
    installers: [{ os: "mac", file: "Mate-Mac.zip", size: 3, sha256: "x", version: "0.4.0" }],
    backends: [
      { os: "mac", file: "backend-0.4.0-mac.tar.gz", size: 3, sha256: "x", version: "0.4.0" },
      { os: "windows", file: "backend-0.4.0-windows.tar.gz", size: 7, sha256: "x", version: "0.4.0" },
    ],
  }),
);

const bin = path.join(tmp, "bin");
fs.mkdirSync(bin);
const downloaded = path.join(tmp, "downloaded.log");
fs.writeFileSync(
  path.join(bin, "gh"),
  `#!/usr/bin/env node
const fs = require("fs");
const path = require("path");
const args = process.argv.slice(2);
const at = (flag) => args[args.indexOf(flag) + 1];
if (args[0] !== "release" || args[1] !== "download" || args[2] !== "latest" || at("-R") !== "ahmeedatm/Mate") process.exit(1);
for (const [i, a] of args.entries()) {
  if (a !== "--pattern") continue;
  fs.copyFileSync(path.join(process.env.FAKE_RELEASE, args[i + 1]), path.join(at("--dir"), args[i + 1]));
  fs.appendFileSync(process.env.FAKE_LOG, args[i + 1] + "\\n");
}
`,
  { mode: 0o755 },
);

describe("updates:add release", () => {
  it("ajoute l'APK et les archives backend de la release, sans les installeurs", async () => {
    const data = path.join(tmp, "data");
    await promisify(execFile)(process.execPath, ["--import", "tsx", path.join(backend, "scripts", "updates.ts"), "add", "release"], {
      cwd: backend,
      env: {
        ...process.env,
        PATH: `${bin}${path.delimiter}${process.env.PATH}`,
        DATA_DIR: data,
        FAKE_RELEASE: release,
        FAKE_LOG: downloaded,
      },
    });
    const dir = path.join(data, "updates");
    assert.deepEqual(fs.readdirSync(dir).sort(), [
      "backend-0.4.0-mac.tar.gz",
      "backend-0.4.0-windows.tar.gz",
      "companion-0.4.0.apk",
      "manifest.json",
    ]);
    const manifest = JSON.parse(fs.readFileSync(path.join(dir, "manifest.json"), "utf8"));
    assert.deepEqual(manifest.updates, [
      { package: "app.mate", label: "Mate", versionCode: 400_000, versionName: "0.4.0", file: "companion-0.4.0.apk" },
    ]);
    assert.deepEqual(
      manifest.backends.map((b: any) => [b.os, b.version]),
      [
        ["mac", "0.4.0"],
        ["windows", "0.4.0"],
      ],
    );
    assert.ok(!fs.readFileSync(downloaded, "utf8").includes("Mate-Mac.zip"));
  });
});

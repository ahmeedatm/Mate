import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import http from "node:http";
import type { AddressInfo } from "node:net";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { promisify } from "node:util";

const script = path.resolve(import.meta.dirname, "../../scripts/updates-publish.mjs");
const sha = (data: string) => createHash("sha256").update(data).digest("hex");

/** Dossier dist d'une release : APK, entrée du serveur de mises à jour, et les installeurs voulus. */
async function makeDist(installers: Record<string, string>) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "companion-publish-"));
  const dist = path.join(root, "dist");
  await fs.mkdir(dist);
  await fs.writeFile(path.join(dist, "companion-1.2.0.apk"), "apk");
  await fs.writeFile(
    path.join(dist, "companion-1.2.0.json"),
    JSON.stringify({
      package: "com.unowhy.companion",
      label: "Companion",
      versionCode: 102000,
      versionName: "1.2.0",
      file: "companion-1.2.0.apk",
    }),
  );
  for (const [name, content] of Object.entries(installers)) await fs.writeFile(path.join(dist, name), content);
  return { dist, out: path.join(root, "updates") };
}

const run = (dist: string, out: string, env: Record<string, string> = {}) =>
  promisify(execFile)("node", [script, dist, out], { env: { ...process.env, ...env } });

test("les installeurs présents sont copiés et décrits dans le manifeste", async () => {
  const { dist, out } = await makeDist({ "Compagnon-Unowhy-Mac.zip": "zip", "Compagnon-Unowhy-Windows.exe": "exe!" });
  await run(dist, out);
  const manifest = JSON.parse(await fs.readFile(path.join(out, "manifest.json"), "utf8"));
  assert.equal(manifest.updates.length, 1);
  assert.deepEqual(manifest.installers, [
    { os: "mac", file: "Compagnon-Unowhy-Mac.zip", size: 3, sha256: sha("zip"), version: "1.2.0" },
    { os: "windows", file: "Compagnon-Unowhy-Windows.exe", size: 4, sha256: sha("exe!"), version: "1.2.0" },
  ]);
  assert.equal(await fs.readFile(path.join(out, "Compagnon-Unowhy-Mac.zip"), "utf8"), "zip");
  assert.equal(await fs.readFile(path.join(out, "Compagnon-Unowhy-Windows.exe"), "utf8"), "exe!");
});

test("sans installeur (essai) : pas de clé installers", async () => {
  const { dist, out } = await makeDist({});
  await run(dist, out);
  const manifest = JSON.parse(await fs.readFile(path.join(out, "manifest.json"), "utf8"));
  assert.equal("installers" in manifest, false);
});

/** Faux registre de paquets génériques : dépôt, liste des fichiers, suppression. */
async function fakeRegistry() {
  const files: { id: number; file_name: string }[] = [];
  const bodies = new Map<number, Buffer>();
  const deleted: string[] = [];
  let next = 200;
  const server = http.createServer((req, res) => {
    const url = new URL(req.url!, "http://x");
    const chunks: Buffer[] = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      const json = (body: unknown) => res.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify(body));
      if (req.method === "PUT") {
        const id = next++;
        files.push({ id, file_name: path.basename(url.pathname) });
        bodies.set(id, Buffer.concat(chunks));
        return json({ message: "201 Created" });
      }
      if (req.method === "GET" && url.pathname.endsWith("/packages")) {
        return json([{ id: 7, name: "companion-updates", version: "latest" }]);
      }
      if (req.method === "GET" && url.pathname.endsWith("/package_files")) return json(files);
      if (req.method === "DELETE") {
        const id = Number(path.basename(url.pathname));
        const i = files.findIndex((f) => f.id === id);
        deleted.push(files[i].file_name);
        files.splice(i, 1);
        return json({});
      }
      res.writeHead(404).end();
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/projects/5`;
  return { server, files, deleted, url: `${base}/packages/generic/companion-updates/latest` };
}

test("publication : installeurs déposés avant le manifeste, jamais supprimés comme anciens fichiers", async () => {
  const { dist, out } = await makeDist({ "Compagnon-Unowhy-Mac.zip": "zip", "Compagnon-Unowhy-Windows.exe": "exe!" });
  const registry = await fakeRegistry();
  try {
    // Ancienne release déjà dans le paquet : seule elle doit partir.
    registry.files.push({ id: 100, file_name: "companion-1.1.0.apk" }, { id: 101, file_name: "manifest.json" });
    await run(dist, out, { UPDATES_URL: registry.url, GITLAB_TOKEN: "t" });
    const names = registry.files.map((f) => f.file_name);
    assert.deepEqual(registry.deleted.sort(), ["companion-1.1.0.apk", "manifest.json"]);
    assert.deepEqual(names, ["companion-1.2.0.apk", "Compagnon-Unowhy-Mac.zip", "Compagnon-Unowhy-Windows.exe", "manifest.json"]);
  } finally {
    registry.server.close();
  }
});

import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { promisify } from "node:util";

// scripts/updates-publish.mjs (jobs updates et essai-updates de la CI) contre un faux registre de paquets génériques.

const repo = path.resolve(import.meta.dirname, "..", "..");
const sha = (s: string) => createHash("sha256").update(s).digest("hex");
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "companion-publish-"));
after(() => fs.rmSync(tmp, { recursive: true, force: true }));

type StoredFile = { id: number; name: string; content: string };

/** Faux registre : un paquet par nom/version, plusieurs dépôts d'un même nom gardés (le dernier est servi). */
function fakeRegistry() {
  const packages = new Map<string, { id: number; name: string; version: string; files: StoredFile[] }>();
  let nextId = 1;
  const server = http.createServer((req, res) => {
    if (req.headers["private-token"] !== "jeton") return res.writeHead(401).end();
    const url = new URL(req.url ?? "", "http://x");
    const chunks: Buffer[] = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      const generic = url.pathname.match(/^\/api\/v4\/projects\/1\/packages\/generic\/([^/]+)\/([^/]+)\/(.+)$/);
      if (generic) {
        const [, name, version, file] = generic;
        const key = `${name}/${version}`;
        if (req.method === "PUT") {
          const pkg = packages.get(key) ?? { id: nextId++, name, version, files: [] };
          packages.set(key, pkg);
          pkg.files.push({ id: nextId++, name: file, content: Buffer.concat(chunks).toString() });
          return res.writeHead(201).end("{}");
        }
        const last = packages.get(key)?.files.findLast((f) => f.name === file);
        return last ? res.end(last.content) : res.writeHead(404).end();
      }
      if (url.pathname === "/api/v4/projects/1/packages") {
        const found = [...packages.values()].filter((p) => p.name === url.searchParams.get("package_name"));
        return res.end(JSON.stringify(found.map(({ id, name, version }) => ({ id, name, version }))));
      }
      const files = url.pathname.match(/^\/api\/v4\/projects\/1\/packages\/(\d+)\/package_files(?:\/(\d+))?$/);
      if (files) {
        const pkg = [...packages.values()].find((p) => p.id === Number(files[1]));
        if (!pkg) return res.writeHead(404).end();
        if (req.method === "DELETE") {
          pkg.files = pkg.files.filter((f) => f.id !== Number(files[2]));
          return res.writeHead(204).end();
        }
        return res.end(JSON.stringify(pkg.files.map((f) => ({ id: f.id, file_name: f.name }))));
      }
      res.writeHead(404).end();
    });
  });
  return {
    server,
    names: (pkg: string) => (packages.get(pkg)?.files ?? []).map((f) => f.name).sort(),
    manifest: (pkg: string) => JSON.parse(packages.get(pkg)!.files.findLast((f) => f.name === "manifest.json")!.content),
  };
}

const registry = fakeRegistry();
let port = 0;
before(async () => {
  await new Promise<void>((resolve) => registry.server.listen(0, "127.0.0.1", resolve));
  port = (registry.server.address() as { port: number }).port;
});
after(() => registry.server.close());

const urlOf = (version: string) => `http://127.0.0.1:${port}/api/v4/projects/1/packages/generic/companion-updates/${version}`;

/** dist/ d'un job essai-android (+ essai-backend) : APK, entrée .json, archives backend. */
function dist(version: string, { code = 1000, pkg = "com.unowhy.companion.essai", backend = [] as string[] } = {}) {
  const dir = fs.mkdtempSync(path.join(tmp, "dist-"));
  fs.writeFileSync(path.join(dir, `companion-${version}.apk`), `apk ${version}`);
  fs.writeFileSync(
    path.join(dir, `companion-${version}.json`),
    JSON.stringify({ package: pkg, label: "Companion essai", versionCode: code, versionName: version, file: `companion-${version}.apk` }),
  );
  for (const platform of backend) fs.writeFileSync(path.join(dir, `backend-${platform}.tar.gz`), `backend ${platform} ${version}`);
  return dir;
}

function publish(args: string[], env: Record<string, string> = {}) {
  return promisify(execFile)(process.execPath, ["scripts/updates-publish.mjs", ...args], {
    cwd: repo,
    env: { ...process.env, CI_JOB_TOKEN: "", GITLAB_TOKEN: "jeton", UPDATES_URL: urlOf("essais"), MR_TITLE: "", ...env },
  });
}

const out = () => fs.mkdtempSync(path.join(tmp, "out-")) + "/updates";

describe("publication des essais par MR", () => {
  it("publie l'essai d'une MR avec ses archives backend, titre compris", async () => {
    await publish(["--essai", "109", dist("0.2.2-essai.10", { code: 202_010, backend: ["mac", "windows"] }), out()], {
      MR_TITLE: "Mixeur par appli",
    });
    const [essai] = registry.manifest("companion-updates/essais").essais;
    assert.equal(essai.mr, 109);
    assert.equal(essai.title, "Mixeur par appli");
    assert.equal(essai.package, "com.unowhy.companion.essai");
    assert.equal(essai.versionCode, 202_010);
    assert.equal(essai.size, "apk 0.2.2-essai.10".length);
    assert.equal(essai.sha256, sha("apk 0.2.2-essai.10"));
    assert.deepEqual(essai.backend.mac, {
      file: "backend-0.2.2-essai.10-mac.tar.gz",
      size: "backend mac 0.2.2-essai.10".length,
      sha256: sha("backend mac 0.2.2-essai.10"),
    });
    assert.equal(essai.backend.windows.file, "backend-0.2.2-essai.10-windows.tar.gz");
    assert.deepEqual(registry.names("companion-updates/essais"), [
      "backend-0.2.2-essai.10-mac.tar.gz",
      "backend-0.2.2-essai.10-windows.tar.gz",
      "companion-0.2.2-essai.10.apk",
      "manifest.json",
    ]);
  });

  it("une autre MR s'ajoute sans toucher à la première, sans backend si backend/ n'a pas changé", async () => {
    await publish(["--essai", "110", dist("0.2.2-essai.11", { code: 202_011 }), out()], { MR_TITLE: "Agenda" });
    const { essais } = registry.manifest("companion-updates/essais");
    assert.deepEqual(
      essais.map((e: any) => [e.mr, e.title, e.versionName, Object.keys(e.backend ?? {})]),
      [
        [109, "Mixeur par appli", "0.2.2-essai.10", ["mac", "windows"]],
        [110, "Agenda", "0.2.2-essai.11", []],
      ],
    );
    assert.equal(registry.names("companion-updates/essais").length, 5);
  });

  it("republier une MR remplace seulement la sienne et supprime ses anciens fichiers", async () => {
    await publish(["--essai", "109", dist("0.2.2-essai.12", { code: 202_012 }), out()], { MR_TITLE: "Mixeur par appli" });
    const { essais } = registry.manifest("companion-updates/essais");
    assert.deepEqual(
      essais.map((e: any) => [e.mr, e.versionName, e.backend]),
      [
        [109, "0.2.2-essai.12", undefined],
        [110, "0.2.2-essai.11", undefined],
      ],
    );
    assert.deepEqual(registry.names("companion-updates/essais"), [
      "companion-0.2.2-essai.11.apk",
      "companion-0.2.2-essai.12.apk",
      "manifest.json",
    ]);
  });

  it("retirer une MR (fusion, abandon) supprime son entrée et ses fichiers, pas ceux des autres", async () => {
    await publish(["--retire", "110", out()]);
    assert.deepEqual(
      registry.manifest("companion-updates/essais").essais.map((e: any) => e.mr),
      [109],
    );
    assert.deepEqual(registry.names("companion-updates/essais"), ["companion-0.2.2-essai.12.apk", "manifest.json"]);
    // Déjà retirée : rien ne change.
    await publish(["--retire", "110", out()]);
    assert.deepEqual(registry.names("companion-updates/essais"), ["companion-0.2.2-essai.12.apk", "manifest.json"]);
  });

  it("refuse un essai au paquet de la stable, une seule archive backend, un jeton faux", async () => {
    await assert.rejects(
      publish(["--essai", "111", dist("0.2.2-essai.13", { pkg: "com.unowhy.companion" }), out()]),
      /un essai a le paquet/,
    );
    await assert.rejects(
      publish(["--essai", "111", dist("0.2.2-essai.13", { backend: ["mac"] }), out()]),
      /une archive backend par plateforme/,
    );
    await assert.rejects(publish(["--essai", "111", dist("0.2.2-essai.13"), out()], { GITLAB_TOKEN: "faux" }), /HTTP 401/);
    await assert.rejects(publish(["--essai", "abc", dist("0.2.2-essai.13"), out()]), /Usage/);
    assert.deepEqual(
      registry.manifest("companion-updates/essais").essais.map((e: any) => e.mr),
      [109],
      "rien n'est changé",
    );
  });
});

describe("publication d'une release", () => {
  it("dépose le manifest {updates} de la dernière version et supprime les anciens fichiers", async () => {
    const run = (version: string, code: number) =>
      publish([dist(version, { code, pkg: "com.unowhy.companion" }), out()], { UPDATES_URL: urlOf("latest") });
    await run("0.2.1", 201_000);
    await run("0.2.2", 202_000);
    const { updates } = registry.manifest("companion-updates/latest");
    assert.equal(updates.length, 1);
    assert.equal(updates[0].package, "com.unowhy.companion");
    assert.equal(updates[0].versionName, "0.2.2");
    assert.equal(updates[0].sha256, sha("apk 0.2.2"));
    assert.deepEqual(registry.names("companion-updates/latest"), ["companion-0.2.2.apk", "manifest.json"]);
  });

  it("dépose les installeurs de l'ordinateur avant le manifest et les décrit dans `installers`", async () => {
    const dir = dist("0.3.0", { code: 300_000, pkg: "com.unowhy.companion" });
    fs.writeFileSync(path.join(dir, "Compagnon-Unowhy-Mac.zip"), "zip");
    fs.writeFileSync(path.join(dir, "Compagnon-Unowhy-Windows.exe"), "exe!");
    const local = out();
    await publish([dir, local], { UPDATES_URL: urlOf("latest") });
    const { installers } = registry.manifest("companion-updates/latest");
    assert.deepEqual(installers, [
      { os: "mac", file: "Compagnon-Unowhy-Mac.zip", size: 3, sha256: sha("zip"), version: "0.3.0" },
      { os: "windows", file: "Compagnon-Unowhy-Windows.exe", size: 4, sha256: sha("exe!"), version: "0.3.0" },
    ]);
    assert.equal(fs.readFileSync(path.join(local, "Compagnon-Unowhy-Windows.exe"), "utf8"), "exe!");
    assert.deepEqual(registry.names("companion-updates/latest"), [
      "Compagnon-Unowhy-Mac.zip",
      "Compagnon-Unowhy-Windows.exe",
      "companion-0.3.0.apk",
      "manifest.json",
    ]);
  });

  it("dépose les archives du backend sous le nom de la version et les décrit dans `backends`", async () => {
    const dir = dist("0.3.2", { code: 302_000, pkg: "com.unowhy.companion", backend: ["mac", "windows"] });
    await publish([dir, out()], { UPDATES_URL: urlOf("latest") });
    const { backends } = registry.manifest("companion-updates/latest");
    assert.deepEqual(backends, [
      { os: "mac", file: "backend-0.3.2-mac.tar.gz", size: "backend mac 0.3.2".length, sha256: sha("backend mac 0.3.2"), version: "0.3.2" },
      {
        os: "windows",
        file: "backend-0.3.2-windows.tar.gz",
        size: "backend windows 0.3.2".length,
        sha256: sha("backend windows 0.3.2"),
        version: "0.3.2",
      },
    ]);
    assert.deepEqual(registry.names("companion-updates/latest"), [
      "backend-0.3.2-mac.tar.gz",
      "backend-0.3.2-windows.tar.gz",
      "companion-0.3.2.apk",
      "manifest.json",
    ]);
    await assert.rejects(
      publish([dist("0.3.3", { code: 303_000, pkg: "com.unowhy.companion", backend: ["mac"] }), out()], { UPDATES_URL: urlOf("latest") }),
      /une archive backend par plateforme/,
    );
  });

  it("sans installeur dans dist : pas de clé `installers`", async () => {
    await publish([dist("0.3.1", { code: 301_000, pkg: "com.unowhy.companion" }), out()], { UPDATES_URL: urlOf("latest") });
    assert.equal("installers" in registry.manifest("companion-updates/latest"), false);
  });
});

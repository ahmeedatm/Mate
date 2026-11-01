import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, describe, it } from "node:test";
import { promisify } from "node:util";

// scripts/updates-publish.mjs (publication des releases `latest` et `essais`) contre une fausse CLI `gh` : les releases
// sont des dossiers, un asset est un fichier.

const repo = path.resolve(import.meta.dirname, "..", "..");
const sha = (s: string) => createHash("sha256").update(s).digest("hex");
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "companion-publish-"));
after(() => fs.rmSync(tmp, { recursive: true, force: true }));

const GH_REPO = "ahmeedatm/Mate";
const releases = path.join(tmp, "releases");
fs.mkdirSync(releases);
const bin = path.join(tmp, "bin");
fs.mkdirSync(bin);

/** Fausse `gh` : release view / create / upload / download / delete-asset et pr view, sur le dossier FAKE_GH. */
fs.writeFileSync(
  path.join(bin, "gh"),
  `#!/usr/bin/env node
const fs = require("fs");
const path = require("path");
const args = process.argv.slice(2);
const fail = (message) => { console.error(message); process.exit(1); };
const r = args.indexOf("-R");
if (r < 0 || args[r + 1] !== process.env.FAKE_GH_REPO) fail("Could not resolve to a Repository");
const [group, action, first] = args;
const dir = (tag) => path.join(process.env.FAKE_GH, tag);
const needRelease = (tag) => { if (!fs.existsSync(dir(tag))) fail("release not found"); };
if (group === "release" && action === "view") {
  needRelease(first);
  console.log(JSON.stringify({ assets: fs.readdirSync(dir(first)).filter((f) => f !== ".meta").map((name) => ({ name })) }));
} else if (group === "release" && action === "create") {
  fs.mkdirSync(dir(first));
  fs.writeFileSync(path.join(dir(first), ".meta"), JSON.stringify(args));
} else if (group === "release" && action === "upload") {
  needRelease(first);
  if (!args.includes("--clobber")) fail("asset already exists");
  // Coupure réseau simulée : --clobber a déjà supprimé l'ancien fichier, le nouveau n'arrive pas.
  const cut = args.find((a) => path.basename(a) === process.env.FAKE_GH_CUT);
  if (cut) { fs.rmSync(path.join(dir(first), path.basename(cut)), { force: true }); fail("connection reset"); }
  for (const f of args.slice(3).filter((a, i, all) => !a.startsWith("-") && all[i - 1] !== "-R" && a !== process.env.FAKE_GH_REPO))
    fs.copyFileSync(f, path.join(dir(first), path.basename(f)));
} else if (group === "release" && action === "delete-asset") {
  needRelease(first);
  fs.rmSync(path.join(dir(first), args[3]));
} else if (group === "release" && action === "download") {
  needRelease(first);
  process.stdout.write(fs.readFileSync(path.join(dir(first), args[args.indexOf("--pattern") + 1])));
} else if (group === "pr" && action === "view") {
  const state = JSON.parse(process.env.FAKE_GH_PRS || "{}")[first];
  if (!state) fail("Could not resolve to a PullRequest");
  console.log(state);
} else fail("commande inconnue : " + args.join(" "));
`,
  { mode: 0o755 },
);

const names = (tag: string) =>
  fs
    .readdirSync(path.join(releases, tag))
    .filter((f) => f !== ".meta" && f !== "manifest.bak.json")
    .sort();
const manifest = (tag: string) => JSON.parse(fs.readFileSync(path.join(releases, tag, "manifest.json"), "utf8"));
const meta = (tag: string): string[] => JSON.parse(fs.readFileSync(path.join(releases, tag, ".meta"), "utf8"));

/** dist/ d'une compilation d'essai (+ backend) : APK, entrée .json, archives backend. */
function dist(version: string, { code = 1000, pkg = "app.mate.essai", backend = [] as string[] } = {}) {
  const dir = fs.mkdtempSync(path.join(tmp, "dist-"));
  fs.writeFileSync(path.join(dir, `companion-${version}.apk`), `apk ${version}`);
  fs.writeFileSync(
    path.join(dir, `companion-${version}.json`),
    JSON.stringify({ package: pkg, label: "Mate essai", versionCode: code, versionName: version, file: `companion-${version}.apk` }),
  );
  for (const platform of backend) fs.writeFileSync(path.join(dir, `backend-${platform}.tar.gz`), `backend ${platform} ${version}`);
  return dir;
}

function publish(args: string[], env: Record<string, string> = {}) {
  return promisify(execFile)(process.execPath, [path.join(repo, "scripts", "updates-publish.mjs"), ...args], {
    cwd: repo,
    env: {
      ...process.env,
      PATH: `${bin}${path.delimiter}${process.env.PATH}`,
      FAKE_GH: releases,
      FAKE_GH_REPO: GH_REPO,
      UPDATES_REPO: GH_REPO,
      PR_TITLE: "",
      FAKE_GH_PRS: "{}",
      ...env,
    },
  });
}

const out = () => fs.mkdtempSync(path.join(tmp, "out-")) + "/updates";

describe("publication des essais par PR", () => {
  it("publie l'essai d'une PR avec ses archives backend, titre compris", async () => {
    await publish(["--essai", "109", dist("0.2.2-essai.10", { code: 202_010, backend: ["mac", "windows"] }), out()], {
      PR_TITLE: "Mixeur par appli",
    });
    const [essai] = manifest("essais").essais;
    assert.equal(essai.mr, 109);
    assert.equal(essai.title, "Mixeur par appli");
    assert.equal(essai.package, "app.mate.essai");
    assert.equal(essai.versionCode, 202_010);
    assert.equal(essai.size, "apk 0.2.2-essai.10".length);
    assert.equal(essai.sha256, sha("apk 0.2.2-essai.10"));
    assert.deepEqual(essai.backend.mac, {
      file: "backend-0.2.2-essai.10-mac.tar.gz",
      size: "backend mac 0.2.2-essai.10".length,
      sha256: sha("backend mac 0.2.2-essai.10"),
    });
    assert.equal(essai.backend.windows.file, "backend-0.2.2-essai.10-windows.tar.gz");
    assert.deepEqual(names("essais"), [
      "backend-0.2.2-essai.10-mac.tar.gz",
      "backend-0.2.2-essai.10-windows.tar.gz",
      "companion-0.2.2-essai.10.apk",
      "manifest.json",
    ]);
  });

  it("une autre PR s'ajoute sans toucher à la première, sans backend si backend/ n'a pas changé", async () => {
    await publish(["--essai", "110", dist("0.2.2-essai.11", { code: 202_011 }), out()], { PR_TITLE: "Agenda" });
    const { essais } = manifest("essais");
    assert.deepEqual(
      essais.map((e: any) => [e.mr, e.title, e.versionName, Object.keys(e.backend ?? {})]),
      [
        [109, "Mixeur par appli", "0.2.2-essai.10", ["mac", "windows"]],
        [110, "Agenda", "0.2.2-essai.11", []],
      ],
    );
    assert.equal(names("essais").length, 5);
  });

  it("republier une PR remplace seulement la sienne et supprime ses anciens fichiers", async () => {
    await publish(["--essai", "109", dist("0.2.2-essai.12", { code: 202_012 }), out()], { PR_TITLE: "Mixeur par appli" });
    const { essais } = manifest("essais");
    assert.deepEqual(
      essais.map((e: any) => [e.mr, e.versionName, e.backend]),
      [
        [109, "0.2.2-essai.12", undefined],
        [110, "0.2.2-essai.11", undefined],
      ],
    );
    assert.deepEqual(names("essais"), ["companion-0.2.2-essai.11.apk", "companion-0.2.2-essai.12.apk", "manifest.json"]);
  });

  it("retirer une PR (fusion, abandon) supprime son entrée et ses fichiers, pas ceux des autres", async () => {
    await publish(["--retire", "110", out()]);
    assert.deepEqual(
      manifest("essais").essais.map((e: any) => e.mr),
      [109],
    );
    assert.deepEqual(names("essais"), ["companion-0.2.2-essai.12.apk", "manifest.json"]);
    // Déjà retirée : rien ne change.
    await publish(["--retire", "110", out()]);
    assert.deepEqual(names("essais"), ["companion-0.2.2-essai.12.apk", "manifest.json"]);
  });

  it("refuse un essai au paquet de la stable, une seule archive backend, un dépôt faux ou absent", async () => {
    await assert.rejects(publish(["--essai", "111", dist("0.2.2-essai.13", { pkg: "app.mate" }), out()]), /un essai a le paquet/);
    await assert.rejects(
      publish(["--essai", "111", dist("0.2.2-essai.13", { backend: ["mac"] }), out()]),
      /une archive backend par plateforme/,
    );
    await assert.rejects(publish(["--essai", "111", dist("0.2.2-essai.13"), out()], { UPDATES_REPO: "autre/depot" }), /Could not resolve/);
    await assert.rejects(publish(["--essai", "111", dist("0.2.2-essai.13"), out()], { UPDATES_REPO: "" }), /UPDATES_REPO/);
    await assert.rejects(publish(["--essai", "abc", dist("0.2.2-essai.13"), out()]), /Usage/);
    assert.deepEqual(
      manifest("essais").essais.map((e: any) => e.mr),
      [109],
      "rien n'est changé",
    );
  });

  it("purger les fermées retire les essais dont la PR n'est plus ouverte, garde les ouvertes", async () => {
    await publish(["--essai", "110", dist("0.2.2-essai.13", { code: 202_013, backend: ["mac", "windows"] }), out()]);
    await publish(["--essai", "12", dist("0.2.2-essai.14", { code: 202_014 }), out()]);
    await publish(["--essai", "123", dist("0.2.2-essai.15", { code: 202_015 }), out()]);
    // !12 fusionnée, !110 fermée sans fusion, !109 et !123 ouvertes (!12 ne retire pas !123).
    const prs = JSON.stringify({ "12": "MERGED", "109": "OPEN", "110": "CLOSED", "123": "OPEN" });
    await publish(["--purge-fermees", out()], { FAKE_GH_PRS: prs });
    assert.deepEqual(
      manifest("essais").essais.map((e: any) => e.mr),
      [109, 123],
    );
    assert.deepEqual(names("essais"), ["companion-0.2.2-essai.12.apk", "companion-0.2.2-essai.15.apk", "manifest.json"]);
  });

  it("une PR introuvable reste dans les essais", async () => {
    await publish(["--purge-fermees", out()], { FAKE_GH_PRS: JSON.stringify({ "109": "OPEN" }) });
    assert.deepEqual(
      manifest("essais").essais.map((e: any) => e.mr),
      [109, 123],
    );
  });

  it("une coupure pendant l'envoi du manifest ne fait perdre aucun essai à la publication suivante", async () => {
    await assert.rejects(
      publish(["--essai", "130", dist("0.2.2-essai.16", { code: 202_016 }), out()], { FAKE_GH_CUT: "manifest.json" }),
      /connection reset/,
    );
    assert.ok(!names("essais").includes("manifest.json"));
    await publish(["--essai", "131", dist("0.2.2-essai.17", { code: 202_017 }), out()]);
    assert.deepEqual(
      manifest("essais").essais.map((e: any) => e.mr),
      [109, 123, 130, 131],
    );
    assert.ok(names("essais").includes("companion-0.2.2-essai.12.apk"));
  });

  it("l'essai est publié en pré-release, la release latest ne l'est pas", async () => {
    assert.ok(meta("essais").includes("--prerelease"));
    assert.ok(meta("essais").includes("--latest=false"));
  });
});

describe("publication d'une release", () => {
  it("dépose le manifest {updates} de la dernière version et supprime les anciens fichiers", async () => {
    const run = (version: string, code: number) => publish([dist(version, { code, pkg: "app.mate" }), out()]);
    await run("0.2.1", 201_000);
    await run("0.2.2", 202_000);
    const { updates } = manifest("latest");
    assert.equal(updates.length, 1);
    assert.equal(updates[0].package, "app.mate");
    assert.equal(updates[0].versionName, "0.2.2");
    assert.equal(updates[0].sha256, sha("apk 0.2.2"));
    assert.deepEqual(names("latest"), ["companion-0.2.2.apk", "manifest.json"]);
    assert.ok(!meta("latest").includes("--prerelease"));
  });

  it("dépose les installeurs de l'ordinateur avant le manifest et les décrit dans `installers`", async () => {
    const dir = dist("0.3.0", { code: 300_000, pkg: "app.mate" });
    fs.writeFileSync(path.join(dir, "Mate-Mac.zip"), "zip");
    fs.writeFileSync(path.join(dir, "Mate-Windows.exe"), "exe!");
    const local = out();
    await publish([dir, local]);
    const { installers } = manifest("latest");
    assert.deepEqual(installers, [
      { os: "mac", file: "Mate-Mac.zip", size: 3, sha256: sha("zip"), version: "0.3.0" },
      { os: "windows", file: "Mate-Windows.exe", size: 4, sha256: sha("exe!"), version: "0.3.0" },
    ]);
    assert.equal(fs.readFileSync(path.join(local, "Mate-Windows.exe"), "utf8"), "exe!");
    assert.deepEqual(names("latest"), ["Mate-Mac.zip", "Mate-Windows.exe", "companion-0.3.0.apk", "manifest.json"]);
  });

  it("dépose les archives du backend sous le nom de la version et les décrit dans `backends`", async () => {
    const dir = dist("0.3.2", { code: 302_000, pkg: "app.mate", backend: ["mac", "windows"] });
    await publish([dir, out()]);
    const { backends } = manifest("latest");
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
    assert.deepEqual(names("latest"), ["backend-0.3.2-mac.tar.gz", "backend-0.3.2-windows.tar.gz", "companion-0.3.2.apk", "manifest.json"]);
    await assert.rejects(
      publish([dist("0.3.3", { code: 303_000, pkg: "app.mate", backend: ["mac"] }), out()]),
      /une archive backend par plateforme/,
    );
  });

  it("sans installeur dans dist : pas de clé `installers`", async () => {
    await publish([dist("0.3.1", { code: 301_000, pkg: "app.mate" }), out()]);
    assert.equal("installers" in manifest("latest"), false);
  });
});

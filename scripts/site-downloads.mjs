#!/usr/bin/env node
// Installeurs de l'ordinateur pour le site vitrine (site/downloads/, mis dans l'image par site/Dockerfile) : ceux de la
// dernière release, lus dans le paquet générique companion-updates/latest du registre (même source que la tablette,
// clé `installers` de manifest.json), taille et SHA-256 vérifiés.
//
//   node scripts/site-downloads.mjs site/downloads
//
// En CI : UPDATES_URL et CI_JOB_TOKEN (en-tête JOB-TOKEN). À la main : UPDATES_URL et GITLAB_TOKEN (jeton personnel,
// read_api). Sans release publiée (manifest absent ou sans installeur) : échec, l'image n'aurait rien à télécharger.
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const out = process.argv[2];
const base = process.env.UPDATES_URL;
if (!out || !base) {
  console.error("Usage : UPDATES_URL=<paquet> node scripts/site-downloads.mjs <dossier>");
  process.exit(1);
}
const headers = process.env.CI_JOB_TOKEN
  ? { "JOB-TOKEN": process.env.CI_JOB_TOKEN }
  : process.env.GITLAB_TOKEN
    ? { "PRIVATE-TOKEN": process.env.GITLAB_TOKEN }
    : {};

async function get(name) {
  const res = await fetch(`${base}/${name}`, { headers });
  if (!res.ok) throw new Error(`${name} : HTTP ${res.status}`);
  return Buffer.from(await res.arrayBuffer());
}

const manifest = JSON.parse((await get("manifest.json")).toString("utf8"));
const installers = manifest.installers ?? [];
if (installers.length === 0) throw new Error("manifest.json sans installeurs : aucune release publiée ?");

fs.mkdirSync(out, { recursive: true });
for (const { file, size, sha256, version } of installers) {
  const data = await get(file);
  const sum = createHash("sha256").update(data).digest("hex");
  if (data.length !== size || sum !== sha256) throw new Error(`${file} : taille ou SHA-256 différents du manifest`);
  fs.writeFileSync(path.join(out, file), data);
  console.log(`${file} ${version} (${Math.round(size / 1e6)} Mo) vérifié`);
}

#!/usr/bin/env node
// Liste des villes embarquée dans l'appli (recherche « Choisir ma ville » sans service en ligne) :
// app/src/main/assets/villes.tsv, construite depuis GeoNames (https://www.geonames.org, licence CC BY 4.0,
// citée dans Réglages > À propos). À relancer seulement pour rafraîchir la liste : node scripts/villes.mjs
//
// Gardé : France, DROM-COM et pays francophones voisins dès 1 000 habitants ; le reste du monde dès 50 000.
// Une ligne : nom, région, code pays, latitude, longitude, population, clé de recherche (séparés par des tabulations).
// La clé (nom sans accent ni ponctuation) est calculée ici plutôt que sur la tablette, trop lente pour 24 000 noms :
// même calcul que Cities.normalize, vérifié par CitiesTest.
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const NEAR = new Set(["FR", "BE", "CH", "LU", "MC", "AD", "GP", "MQ", "GF", "RE", "YT", "PM", "BL", "MF", "NC", "PF", "WF"]);
const NEAR_MIN = 1_000;
const WORLD_MIN = 50_000;
const BASE = "https://download.geonames.org/export/dump/";
/** GeoNames nomme les régions en anglais : celles de France en français. */
const FRENCH = { "New Aquitaine": "Nouvelle-Aquitaine", Brittany: "Bretagne", Normandy: "Normandie", Corsica: "Corse" };
const out = path.join(import.meta.dirname, "..", "app", "src", "main", "assets", "villes.tsv");

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "geonames-"));
async function download(name) {
  const res = await fetch(BASE + name);
  if (!res.ok) throw new Error(`${name} : HTTP ${res.status}`);
  const file = path.join(tmp, name);
  fs.writeFileSync(file, Buffer.from(await res.arrayBuffer()));
  return file;
}

const zip = await download("cities1000.zip");
execFileSync("unzip", ["-o", "-q", zip, "-d", tmp]);
const regions = new Map(
  fs
    .readFileSync(await download("admin1CodesASCII.txt"), "utf8")
    .split("\n")
    .filter(Boolean)
    .map((l) => l.split("\t"))
    .map(([code, name]) => [code, name]),
);

const rows = [];
for (const line of fs.readFileSync(path.join(tmp, "cities1000.txt"), "utf8").split("\n")) {
  if (!line) continue;
  // Colonnes GeoNames : 1 nom, 4 latitude, 5 longitude, 7 type de lieu, 8 pays, 10 région (admin1), 14 population.
  const c = line.split("\t");
  const [name, lat, lon, country, admin1, population] = [c[1], c[4], c[5], c[8], c[10], Number(c[14])];
  if (population < (NEAR.has(country) ? NEAR_MIN : WORLD_MIN)) continue;
  // Quartiers et arrondissements (« Lyon 03 », code PPLX) : la ville entière suffit pour la météo.
  if (c[7] === "PPLX") continue;
  const region = regions.get(`${country}.${admin1}`) ?? "";
  rows.push([name, FRENCH[region] ?? region, country, Number(lat).toFixed(4), Number(lon).toFixed(4), population]);
}
/** Comme Cities.normalize (Kotlin). */
const key = (text) =>
  text
    .normalize("NFD")
    .replace(/\p{Mn}+/gu, "")
    .toLowerCase()
    .replace(/[-'’.]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
rows.sort((a, b) => b[5] - a[5]);
for (const r of rows) r.push(key(r[0]));
const header = "# Villes de GeoNames (https://www.geonames.org), licence CC BY 4.0. Généré par scripts/villes.mjs.\n";
fs.writeFileSync(out, header + rows.map((r) => r.join("\t")).join("\n") + "\n");
fs.rmSync(tmp, { recursive: true, force: true });
console.log(`${rows.length} villes → ${path.relative(process.cwd(), out)} (${Math.round(fs.statSync(out).size / 1024)} Ko)`);

#!/usr/bin/env node
// Lignes d'Île-de-France embarquées dans l'appli (picto de chaque passage du widget Transports, sans appel réseau) :
// app/src/main/assets/lignes-idfm.tsv, construite depuis le « Référentiel des lignes de transport en commun d'Île-de-France »
// d'Île-de-France Mobilités (https://data.iledefrance-mobilites.fr, licence ODbL, citée dans Réglages > À propos).
// À relancer seulement pour rafraîchir la liste : node scripts/lignes-idfm.mjs
//
// Une ligne : id (« C01371 », la fin de la LineRef SIRI), nom court, mode (metro | rer | train | tram | bus), fond et texte
// en hexadécimal sans « # » (séparés par des tabulations). Même calcul de mode que TransportLines (Kotlin).
import fs from "node:fs";
import path from "node:path";

const URL =
  "https://data.iledefrance-mobilites.fr/api/explore/v2.1/catalog/datasets/referentiel-des-lignes/exports/csv" +
  "?select=id_line,shortname_line,transportmode,transportsubmode,networkname,colourweb_hexa,textcolourweb_hexa" +
  "&where=status%3D%22active%22";
const out = path.join(import.meta.dirname, "..", "app", "src", "main", "assets", "lignes-idfm.tsv");

/** Le rail est le RER si le réseau le dit, sinon un train (Transilien, TER) ; le reste (bus, câble, navette) : bus. */
function mode(transport, network) {
  if (transport === "metro" || transport === "tram") return transport;
  if (transport === "rail") return /RER/i.test(network) ? "rer" : "train";
  return "bus";
}

const res = await fetch(URL);
if (!res.ok) throw new Error(`Référentiel des lignes : HTTP ${res.status}`);
const [head, ...lines] = (await res.text()).split(/\r?\n/).filter(Boolean);
const col = Object.fromEntries(head.replace(/^﻿/, "").split(";").map((n, i) => [n.replace(/"/g, ""), i]));

const rows = [];
for (const line of lines) {
  const c = line.split(";").map((v) => v.replace(/^"|"$/g, "").trim());
  const [id, name, bg, fg] = [c[col.id_line], c[col.shortname_line], c[col.colourweb_hexa], c[col.textcolourweb_hexa]];
  if (!id || !name || !/^[0-9A-Fa-f]{6}$/.test(bg) || !/^[0-9A-Fa-f]{6}$/.test(fg)) continue;
  rows.push([id, name, mode(c[col.transportmode], c[col.networkname]), bg.toUpperCase(), fg.toUpperCase()]);
}
rows.sort((a, b) => a[0].localeCompare(b[0]));
const header =
  "# Lignes d'Île-de-France Mobilités (https://data.iledefrance-mobilites.fr), licence ODbL. Généré par scripts/lignes-idfm.mjs.\n";
fs.writeFileSync(out, header + rows.map((r) => r.join("\t")).join("\n") + "\n");
console.log(`${rows.length} lignes → ${path.relative(process.cwd(), out)} (${Math.round(fs.statSync(out).size / 1024)} Ko)`);

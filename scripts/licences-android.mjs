#!/usr/bin/env node
// Mentions de licences de l'appli Android : app/src/main/assets/licences.txt, affiché par Réglages > À propos >
// « Licences ». À relancer après tout changement de dépendance (le test LicencesTest le rappelle) :
//   node scripts/licences-android.mjs
//
// Bibliothèques : celles réellement dans l'APK (releaseRuntimeClasspath de Gradle), licence lue dans leur POM
// (cache Gradle, POM parent si besoin). Le reste (polices, icônes, données) est décrit ici, à la main.
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const repo = path.join(import.meta.dirname, "..");
const out = path.join(repo, "app", "src", "main", "assets", "licences.txt");
const texts = path.join(import.meta.dirname, "licences");
const gradleCache = path.join(os.homedir(), ".gradle", "caches", "modules-2", "files-2.1");

const tree = execFileSync(
  path.join(repo, "gradlew"),
  ["-q", ":app:dependencies", "--configuration", "releaseRuntimeClasspath", "-Dorg.gradle.jvmargs=-Xmx2048m", "-Pandroid.enableBuildCache=false"],
  { cwd: repo, encoding: "utf8", maxBuffer: 16 * 1024 * 1024 },
);
const coords = new Set();
for (const m of tree.matchAll(/([\w.-]+):([\w.-]+):(\d[^\s]*)(?: -> (\d[^\s]*))?/g)) coords.add(`${m[1]}:${m[2]}:${m[4] ?? m[3]}`);

function pom(group, artifact, version) {
  const dir = path.join(gradleCache, group, artifact, version);
  if (!fs.existsSync(dir)) return null;
  for (const hash of fs.readdirSync(dir)) {
    const file = path.join(dir, hash, `${artifact}-${version}.pom`);
    if (fs.existsSync(file)) return fs.readFileSync(file, "utf8");
  }
  return null;
}

const tag = (xml, name) => xml.match(new RegExp(`<${name}>\\s*([^<]*?)\\s*</${name}>`))?.[1];

/** Licence du POM, sinon de son parent ; « Apache-2.0 » pour les noms usuels de l'Apache 2.0. */
function licenceOf(group, artifact, version, depth = 0) {
  const xml = pom(group, artifact, version);
  if (!xml) return null;
  const block = xml.match(/<licenses>([\s\S]*?)<\/licenses>/)?.[1];
  const name = block && tag(block, "name");
  if (name) return /apache/i.test(name) && /2/.test(name) ? "Apache-2.0" : name;
  const parent = xml.match(/<parent>([\s\S]*?)<\/parent>/)?.[1];
  if (parent && depth < 4) return licenceOf(tag(parent, "groupId"), tag(parent, "artifactId"), tag(parent, "version"), depth + 1);
  return null;
}

const libs = [...coords]
  .filter((c) => !c.endsWith("-bom") && !/-bom:/.test(c))
  .sort()
  .map((c) => {
    const [group, artifact, version] = c.split(":");
    return { coord: c, licence: licenceOf(group, artifact, version) };
  });
const unknown = libs.filter((l) => !l.licence);
if (unknown.length) throw new Error(`Licence introuvable : ${unknown.map((l) => l.coord).join(", ")} (lancer d'abord ./gradlew assembleRelease)`);
const others = libs.filter((l) => l.licence !== "Apache-2.0");
if (others.length) throw new Error(`Licence autre qu'Apache 2.0, à ajouter à la main : ${others.map((l) => `${l.coord} (${l.licence})`).join(", ")}`);

const text = (file) => fs.readFileSync(path.join(texts, file), "utf8").trim();
const rule = "=".repeat(56);
const content = `Companion Unowhy : composants tiers et licences
${rule}

L'appli utilise les composants suivants. Leur licence demande d'en citer l'auteur et d'en joindre le texte :
c'est l'objet de ce fichier (généré par scripts/licences-android.mjs).

BIBLIOTHÈQUES (licence Apache 2.0, texte plus bas)
${libs.map((l) => `- ${l.coord}`).join("\n")}
Auteurs : The Android Open Source Project / Google LLC (androidx, listenablefuture), JetBrains s.r.o. et
contributeurs Kotlin (kotlin, kotlinx, annotations), The JSpecify Authors (jspecify).

ICÔNES
Icônes Material Symbols et Material Icons, Copyright Google LLC, licence Apache 2.0 (texte plus bas).

POLICES (SIL Open Font License 1.1, texte plus bas)
- Outfit : Copyright 2021 The Outfit Project Authors (https://github.com/Outfitio/Outfit-Fonts)
- Nunito : Copyright 2014 The Nunito Project Authors (https://github.com/googlefonts/nunito)
- Abril Fatface : Copyright (c) 2011, TypeTogether (www.type-together.com), noms de police réservés « Abril » et « Abril Fatface »

DONNÉES
- Prévisions météo : MET Norway (Institut météorologique norvégien), licence CC BY 4.0,
  https://creativecommons.org/licenses/by/4.0/ ; mises en forme pour l'affichage.
- Liste des villes : GeoNames (https://www.geonames.org), licence CC BY 4.0 ; extraite et réduite.

SONS D'AMBIANCE (domaine public, CC0 1.0 : https://creativecommons.org/publicdomain/zero/1.0/ ;
extraits, mis en boucle et à un volume bas par scripts/ambiances.mjs)
- Pluie : « RAIN_Light Thunderstorm, Decrescendo, Water Dripping near Building », HushAndHarmonyNow
  (https://freesound.org/s/851881/)
- Forêt : « birdsongEvening », tenkism (https://freesound.org/s/350456/)
- Japon : « AkasakaHieJinjaCicadas_4mics », markystar (https://freesound.org/s/79086/) ;
  « 7'11 Sliding Door », plasterbrain (https://freesound.org/s/399092/) ;
  « Furin japanese wind chime », ayustat2001 (https://freesound.org/s/501168/)
- Mer : « Ocean Waves on a Tropical Beach », Jarrod Stanley
  (https://commons.wikimedia.org/wiki/File:Ocean_Waves_on_a_Tropical_Beach.ogg)
- Lofi : « Chill lofi inspired [loop edit] », qubodup d'après omfgdude
  (https://opengameart.org/content/chill-lofi-inspired-loop-edit)

${rule}
Apache License 2.0
${rule}

${text("Apache-2.0.txt")}

${rule}
SIL Open Font License 1.1
${rule}

${text("OFL-1.1.txt")}
`;
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, content);
console.log(`${libs.length} bibliothèques → ${path.relative(process.cwd(), out)} (${Math.round(content.length / 1024)} Ko)`);

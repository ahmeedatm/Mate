import fs from "node:fs";
import path from "node:path";

/**
 * Mentions de licences du compagnon installé (LICENCES-TIERS.txt, écrit par scripts/package.ts) : les paquets npm
 * réellement regroupés par esbuild (son `metafile`) et le node embarqué. MIT, ISC, BSD, Apache… demandent tous de
 * joindre l'avis de copyright et le texte de la licence ; esbuild retire les commentaires de licence du code
 * (`legalComments: "none"`), d'où ce fichier.
 */

export type Notice = { name: string; version: string; license: string; text: string };

const LICENSE_FILE = /^(licen[sc]e|copying)(\.(md|txt|markdown))?$/i;

/** Textes types, pour un paquet MIT ou ISC publié sans fichier de licence (la licence exige son texte). */
const STANDARD: Record<string, (holder: string) => string> = {
  MIT: (holder) => `MIT License

Copyright (c) ${holder}

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of
the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.`,
  ISC: (holder) => `ISC License

Copyright (c) ${holder}

Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby
granted, provided that the above copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF
THIS SOFTWARE.`,
};

/** Dossiers des paquets npm d'où viennent les fichiers regroupés (« node_modules/@scope/nom », imbrications comprises). */
export function bundledPackages(inputs: string[]): string[] {
  const dirs = new Set<string>();
  for (const file of inputs) {
    const parts = file.split(/[\\/]/);
    const i = parts.lastIndexOf("node_modules");
    if (i < 0 || i + 1 >= parts.length) continue;
    const end = parts[i + 1].startsWith("@") ? i + 3 : i + 2;
    dirs.add(parts.slice(0, end).join("/"));
  }
  return [...dirs].sort();
}

/**
 * Licence d'un paquet : son fichier LICENSE ; sinon, pour MIT et ISC, le texte type au nom de l'auteur de package.json ;
 * sinon un avis (licence et auteur).
 */
export function noticeOf(dir: string): Notice {
  const pkg = JSON.parse(fs.readFileSync(path.join(dir, "package.json"), "utf8")) as {
    name: string;
    version: string;
    license?: string | { type?: string };
    author?: string | { name?: string };
  };
  const license = (typeof pkg.license === "string" ? pkg.license : pkg.license?.type) ?? "voir le paquet";
  const file = fs.readdirSync(dir).find((f) => LICENSE_FILE.test(f));
  const author = typeof pkg.author === "string" ? pkg.author : pkg.author?.name;
  const holder = author ?? `les auteurs de ${pkg.name}`;
  const text = file
    ? fs.readFileSync(path.join(dir, file), "utf8").trim()
    : (STANDARD[license]?.(holder) ?? `Licence ${license}, Copyright ${holder} (pas de fichier de licence dans le paquet).`);
  return { name: pkg.name, version: pkg.version, license, text };
}

const RULE = "=".repeat(72);

/** Le fichier complet : liste, puis chaque texte ; un même paquet présent en double n'apparaît qu'une fois. */
export function thirdPartyNotices(notices: Notice[], node?: { version: string; license: string }): string {
  const unique = [...new Map(notices.map((n) => [`${n.name}@${n.version}`, n])).values()].sort((a, b) => a.name.localeCompare(b.name));
  const list = unique.map((n) => `- ${n.name} ${n.version} (${n.license})`);
  if (node) list.unshift(`- Node.js ${node.version} (MIT et licences de ses composants, texte complet plus bas)`);
  const sections = unique.map((n) => `${RULE}\n${n.name} ${n.version} (${n.license})\n${RULE}\n\n${n.text}\n`);
  if (node) sections.unshift(`${RULE}\nNode.js ${node.version}\n${RULE}\n\n${node.license.trim()}\n`);
  return [
    "Compagnon Unowhy : composants tiers et licences",
    RULE,
    "",
    "Le compagnon de l'ordinateur contient les composants suivants. Leur licence demande d'en citer les auteurs et",
    "d'en joindre le texte : c'est l'objet de ce fichier (généré par scripts/package.ts).",
    "",
    ...list,
    "",
    ...sections,
  ].join("\n");
}

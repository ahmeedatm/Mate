// Contrôle bloquant de Mate : couleurs, textes et polices en dur.
//
// Vérifie que le code de app/src/main/ passe par le thème (?attr/c_…,
// themeColor, themeFont) et par strings.xml, au lieu de valeurs écrites en dur.
// Il ne regarde QUE les lignes ajoutées : l'existant n'est jamais signalé, seul
// ce qu'une modification apporte l'est.
//
// Deux usages :
//  - hook PreToolUse (Edit, MultiEdit, Write), sans argument : JSON sur stdin,
//    message sur stderr et sortie 2 si un problème est trouvé ;
//  - à la main ou en CI : node .claude/hooks/controle.mjs --diff [base]
//    (défaut origin/master) ; lignes ajoutées de `git diff <base>...HEAD` plus
//    l'arbre de travail ; sortie 1 si problème, 2 si git échoue.
// Échappatoire : « controle: permis <raison> » sur la ligne.
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const PERMIS = "controle: permis";
const MAX_LIGNE = 100;
const FIN_MESSAGE =
  "Le contrôle ne regarde que les lignes ajoutées. Faux positif : ajouter « controle: permis <raison> » sur la ligne et le dire dans la PR.";

const PALETTES = "ajouter la valeur dans palettes.xml pour chaque thème de Themes.kt";
const FIX_COULEUR_XML = `Utiliser ?attr/c_… et ${PALETTES}`;
const FIX_COULEUR_KT = `Utiliser themeColor(context, R.attr.c_…) et ${PALETTES}`;
const FIX_TEXTE = "Ajouter le texte dans res/values/strings.xml puis utiliser @string/… ou getString(R.string.…)";
const FIX_POLICE = "Utiliser le rôle du thème : ?attr/c_font_… en XML, themeFont(context, R.attr.c_font_…) en Kotlin";

const XML_VALEUR = /^res\/(?:layout|drawable|values)[^/]*\/(?!palettes\.xml$|colors\.xml$)[^/]+\.xml$/;
const XML_DRAWABLE = /^res\/drawable[^/]*\//;
const XML_LAYOUT = /^res\/layout[^/]*\/[^/]+\.xml$/;
const XML_RES = /^res\/.*\.xml$/;
const XML_POLICE_PERMIS = /^res\/(?:font\/|values[^/]*\/(?:fonts|palettes)\.xml$)/;
const KOTLIN = /\.kt$/;

const ATTR_VECTORIEL = /android:(?:fillColor|strokeColor)="[^"]*"/g;
const COULEUR_XML = /"#[0-9A-Fa-f]{3,8}"/;
const COULEUR_KT = new RegExp(
  [
    String.raw`Color\.parseColor\("`,
    String.raw`Color\.(?:rgb|argb)\(\s*\d`,
    String.raw`Color\.(?:BLACK|WHITE|RED|GREEN|BLUE|GRAY|DKGRAY|LTGRAY|YELLOW|CYAN|MAGENTA)\b`,
    String.raw`\b0x[Ff]{2}[0-9A-Fa-f]{6}\b`,
  ].join("|"),
);

const CHAINE = String.raw`"((?:[^"\\]|\\.)*)"`;
const TEXTE_XML = /android:(?:text|hint|contentDescription|title)="([^"]*)"/g;
const TEXTE_KT = [
  new RegExp(String.raw`\b(?:text|hint|contentDescription|title)\s*=\s*${CHAINE}`, "g"),
  new RegExp(String.raw`\b(?:setText|setTitle|setMessage)\(\s*${CHAINE}`, "g"),
  new RegExp(String.raw`\bmakeText\([^"]*?${CHAINE}`, "g"),
];

const aUneLettre = (s) => /\p{L}/u.test(s);
const sansGabarit = (s) => s.replace(/\$\{[^}]*\}/g, "").replace(/\$[A-Za-z_]\w*/g, "");
const captures = (re, ligne) => [...ligne.matchAll(re)].map((m) => m[1]);

// Règles : `applique` choisit les fichiers (chemin relatif à app/src/main/),
// `viole` reçoit la ligne et le chemin.
const REGLES = [
  {
    rule: "couleur-en-dur",
    fix: FIX_COULEUR_XML,
    applique: (rel) => XML_VALEUR.test(rel),
    viole: (ligne, rel) => COULEUR_XML.test(XML_DRAWABLE.test(rel) ? ligne.replace(ATTR_VECTORIEL, "") : ligne),
  },
  {
    rule: "couleur-en-dur",
    fix: FIX_COULEUR_KT,
    applique: (rel) => KOTLIN.test(rel),
    viole: (ligne) => COULEUR_KT.test(ligne),
  },
  {
    rule: "texte-en-dur",
    fix: FIX_TEXTE,
    applique: (rel) => XML_LAYOUT.test(rel),
    viole: (ligne) => captures(TEXTE_XML, ligne).some((x) => !/^[@?]/.test(x) && aUneLettre(x)),
  },
  {
    rule: "texte-en-dur",
    fix: FIX_TEXTE,
    applique: (rel) => KOTLIN.test(rel),
    viole: (ligne) => TEXTE_KT.some((re) => captures(re, ligne).some((x) => aUneLettre(sansGabarit(x)))),
  },
  {
    rule: "police-en-dur",
    fix: FIX_POLICE,
    applique: (rel) => XML_RES.test(rel) && !XML_POLICE_PERMIS.test(rel),
    viole: (ligne) => ligne.includes("@font/"),
  },
  {
    rule: "police-en-dur",
    fix: FIX_POLICE,
    applique: (rel) => KOTLIN.test(rel),
    viole: (ligne) => /\bR\.font\./.test(ligne),
  },
];

// Chemin relatif à app/src/main/, ou null si le fichier n'est pas concerné.
const cheminSource = (filePath) => {
  const m = /(?:^|\/)app\/src\/main\/(.*)$/.exec(filePath.replaceAll("\\", "/"));
  return m ? m[1] : null;
};

// Lignes de `after` absentes de `before` (avec le compte : une ligne présente
// une fois avant et deux fois après compte une fois).
export const addedLines = (before, after) => {
  const restant = new Map();
  for (const l of before.split("\n")) restant.set(l, (restant.get(l) ?? 0) + 1);
  return after.split("\n").filter((l) => {
    const n = restant.get(l) ?? 0;
    restant.set(l, Math.max(n - 1, 0));
    return n === 0;
  });
};

export const findProblems = (filePath, lines) => {
  const rel = cheminSource(filePath);
  if (rel === null) return [];
  const regles = REGLES.filter((r) => r.applique(rel));
  return lines.flatMap((line) => {
    if (line.includes(PERMIS)) return [];
    const r = regles.find((x) => x.viole(line, rel));
    return r ? [{ rule: r.rule, line, fix: r.fix }] : [];
  });
};

const message = (relPath, problems) =>
  problems.map(
    (p) => `Contrôle Mate : ${p.rule} dans ${relPath} : « ${p.line.trim().slice(0, MAX_LIGNE)} ». ${p.fix}.`,
  );

const lignesDuHook = ({ tool_name: outil, tool_input: entree }) => {
  if (!entree || typeof entree.file_path !== "string") return [];
  if (outil === "Edit") return addedLines(entree.old_string ?? "", entree.new_string ?? "");
  if (outil === "MultiEdit")
    return (entree.edits ?? []).flatMap((e) => addedLines(e.old_string ?? "", e.new_string ?? ""));
  if (outil === "Write") return addedLines(existsSync(entree.file_path) ? readFileSync(entree.file_path, "utf8") : "", entree.content ?? "");
  return [];
};

const modeHook = () => {
  let evenement;
  try {
    evenement = JSON.parse(readFileSync(0, "utf8"));
  } catch {
    return 0;
  }
  const chemin = evenement?.tool_input?.file_path;
  if (typeof chemin !== "string") return 0;
  const base = process.env.CLAUDE_PROJECT_DIR || process.cwd();
  const rel = relative(base, resolve(base, chemin)).replaceAll("\\", "/");
  const problemes = findProblems(rel, lignesDuHook(evenement));
  if (problemes.length === 0) return 0;
  process.stderr.write([...message(rel, problemes), FIN_MESSAGE].join("\n") + "\n");
  return 2;
};

// Lignes ajoutées par un diff unifié, par fichier (`+++ b/…`).
const ajoutsDuDiff = (diff) => {
  const parFichier = new Map();
  let fichier = null;
  for (const l of diff.split("\n")) {
    if (l.startsWith("+++ ")) fichier = l.startsWith("+++ b/") ? l.slice(6) : null;
    else if (l.startsWith("+") && fichier) parFichier.set(fichier, [...(parFichier.get(fichier) ?? []), l.slice(1)]);
  }
  return parFichier;
};

const modeDiff = (base) => {
  const git = (...args) =>
    execFileSync("git", ["diff", "--no-color", "--no-ext-diff", "--unified=0", ...args], {
      encoding: "utf8", maxBuffer: 256 * 1024 * 1024, stdio: ["ignore", "pipe", "pipe"],
    });
  let ajouts;
  try {
    ajouts = [ajoutsDuDiff(git(`${base}...HEAD`)), ajoutsDuDiff(git("HEAD"))];
  } catch (e) {
    const cause = String(e.stderr || e.message).trim().split("\n")[0];
    console.log(`Contrôle Mate : impossible de lire le diff contre « ${base} » (${cause}).`);
    return 2;
  }
  const sorties = ajouts.flatMap((m) => [...m].flatMap(([f, lignes]) => message(f, findProblems(f, lignes))));
  if (sorties.length === 0) return 0;
  console.log([...sorties, FIN_MESSAGE].join("\n"));
  return 1;
};

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const i = args.indexOf("--diff");
  process.exitCode = i >= 0 ? modeDiff(args[i + 1] ?? "origin/master") : modeHook();
}

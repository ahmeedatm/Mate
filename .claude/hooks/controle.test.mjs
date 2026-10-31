// Tests du contrôle bloquant : node --test .claude/hooks/
import { after, test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync, execFileSync } from "node:child_process";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { findProblems, addedLines } from "./controle.mjs";

const SCRIPT = join(dirname(fileURLToPath(import.meta.url)), "controle.mjs");
const LAYOUT = "app/src/main/res/layout/x.xml";
const KT = "app/src/main/kotlin/app/mate/ui/X.kt";

const rules = (path, lines) => findProblems(path, lines).map((p) => p.rule);

test("bloque une couleur ajoutée dans un layout", () => {
  assert.deepEqual(rules(LAYOUT, ['<View android:background="#FF0000"/>']), ["couleur-en-dur"]);
});

test("bloque Color.parseColor littéral", () => {
  assert.deepEqual(rules(KT, ['val c = Color.parseColor("#FF0000")']), ["couleur-en-dur"]);
});

test("laisse passer Color.parseColor(variable)", () => {
  assert.deepEqual(rules(KT, ["val c = Color.parseColor(hex)"]), []);
});

test("laisse passer une ligne déjà présente", () => {
  assert.deepEqual(addedLines("a\nval white = 0xFFFFFFFF.toInt()", "a\nb\nval white = 0xFFFFFFFF.toInt()"), ["b"]);
});

test("addedLines ignore CRLF et indentation", () => {
  assert.deepEqual(addedLines("a\r\nColor.BLACK\r\n", "a\nColor.BLACK\nb\n"), ["b"]);
  assert.deepEqual(addedLines("  val c = Color.BLACK", "    val c = Color.BLACK"), []);
});

test("addedLines compte les doublons", () => {
  assert.deepEqual(addedLines("x", "x\nx"), ["x"]);
});

test("bloque les autres couleurs Kotlin", () => {
  assert.deepEqual(rules(KT, ["p.color = Color.BLACK"]), ["couleur-en-dur"]);
  assert.deepEqual(rules(KT, ["val w = 0xFFFFFFFF.toInt()"]), ["couleur-en-dur"]);
  assert.deepEqual(rules(KT, ["val c = Color.rgb(1, 2, 3)"]), ["couleur-en-dur"]);
});

test("icône vectorielle", () => {
  assert.deepEqual(rules("app/src/main/res/drawable/ic_x.xml", ['android:fillColor="#FF000000"']), []);
  assert.deepEqual(rules("app/src/main/res/drawable/ic_x.xml", ['android:strokeColor="#FF000000"']), []);
  assert.deepEqual(rules("app/src/main/res/drawable/bg.xml", ['<solid android:color="#FF0000"/>']), ["couleur-en-dur"]);
});

test("palettes.xml permis", () => {
  assert.deepEqual(rules("app/src/main/res/values/palettes.xml", ['<item name="c_primary">#FF0000</item>', 'x="#FF0000"']), []);
});

test("couleur sans guillemets dans values", () => {
  const v = "app/src/main/res/values/styles.xml";
  assert.deepEqual(rules(v, ['<item name="android:background">#FF0000</item>']), ["couleur-en-dur"]);
  assert.deepEqual(rules(v, ['<color name="x"> #F00 </color>']), ["couleur-en-dur"]);
  assert.deepEqual(rules("app/src/main/res/values/colors.xml", ['<color name="x">#FF0000</color>']), []);
});

test("échappements Kotlin", () => {
  assert.deepEqual(rules(KT, ['text = "•"', 'label.setText("\\n")']), []);
});

test("bloque un texte en dur dans un layout", () => {
  assert.deepEqual(rules(LAYOUT, ['<TextView android:text="Bonjour"/>']), ["texte-en-dur"]);
  assert.deepEqual(rules(LAYOUT, ['<TextView android:text="@string/bonjour"/>']), []);
});

test("chiffres seuls", () => {
  assert.deepEqual(rules(LAYOUT, ['<TextView android:text="1"/>']), []);
});

test("gabarit Kotlin", () => {
  assert.deepEqual(rules(KT, ['contentDescription = "${a} : ${b}"']), []);
  assert.deepEqual(rules(KT, ['contentDescription = "Volume $niveau"']), ["texte-en-dur"]);
});

test("bloque setText littéral", () => {
  assert.deepEqual(rules(KT, ['label.setText("Valider")']), ["texte-en-dur"]);
  assert.deepEqual(rules(KT, ['label.setText(getString(R.string.valider))']), []);
  assert.deepEqual(rules(KT, ['Toast.makeText(ctx, "Fait", Toast.LENGTH_SHORT)']), ["texte-en-dur"]);
});

test("bloque @font/ dans un layout", () => {
  assert.deepEqual(rules(LAYOUT, ['android:fontFamily="@font/inter"']), ["police-en-dur"]);
  assert.deepEqual(rules(KT, ["val f = ResourcesCompat.getFont(ctx, R.font.inter)"]), ["police-en-dur"]);
});

test("fonts.xml permis", () => {
  assert.deepEqual(rules("app/src/main/res/values/fonts.xml", ['<item name="c_font">@font/inter</item>']), []);
  assert.deepEqual(rules("app/src/main/res/font/inter.xml", ['<font android:font="@font/inter_r"/>']), []);
});

test("hors app/src/main ignoré", () => {
  assert.deepEqual(rules("app/src/test/X.kt", ["val c = Color.BLACK"]), []);
  assert.deepEqual(rules("backend/src/x.ts", ['const c = "#FF0000"']), []);
});

test("chemins Windows", () => {
  assert.deepEqual(
    rules("C:\\w\\app\\src\\main\\res\\layout\\x.xml", ['<View android:background="#FF0000"/>']),
    ["couleur-en-dur"],
  );
});

test("controle: permis", () => {
  assert.deepEqual(rules(KT, ["val c = Color.BLACK // controle: permis fond d'un cadre photo"]), []);
});

const TEMP = [];
const dirTemp = () => {
  const d = mkdtempSync(join(tmpdir(), "controle-"));
  TEMP.push(d);
  return d;
};
after(() => TEMP.forEach((d) => rmSync(d, { recursive: true, force: true })));

const lancer = (args, input, opts = {}) =>
  spawnSync("node", [SCRIPT, ...args], { input, encoding: "utf8", ...opts });

test("hook Edit bloquant sort en 2 avec le message", () => {
  const input = JSON.stringify({
    tool_name: "Edit",
    tool_input: { file_path: "/tmp/proj/" + LAYOUT, old_string: "", new_string: '<View android:background="#FF0000"/>' },
  });
  const r = lancer([], input, { env: { ...process.env, CLAUDE_PROJECT_DIR: "/tmp/proj" } });
  assert.equal(r.status, 2);
  assert.match(r.stderr, /^Contrôle Mate : couleur-en-dur dans app\/src\/main\/res\/layout\/x\.xml : « <View android:background="#FF0000"\/> »\. /m);
  assert.match(r.stderr, /Le contrôle ne regarde que les lignes ajoutées\. Faux positif : ajouter « controle: permis <raison> » sur la ligne et le dire dans la PR\./);
});

test("hook Write d'un fichier neuf", () => {
  const input = JSON.stringify({
    tool_name: "Write",
    tool_input: { file_path: "/tmp/proj/" + KT, content: "val c = Color.BLACK\n" },
  });
  const r = lancer([], input, { env: { ...process.env, CLAUDE_PROJECT_DIR: "/tmp/proj" } });
  assert.equal(r.status, 2);
});

test("hook MultiEdit sans problème", () => {
  const input = JSON.stringify({
    tool_name: "MultiEdit",
    tool_input: { file_path: "/tmp/proj/" + KT, edits: [{ old_string: "a", new_string: "b" }] },
  });
  const r = lancer([], input, { env: { ...process.env, CLAUDE_PROJECT_DIR: "/tmp/proj" } });
  assert.equal(r.status, 0);
  assert.equal(r.stderr, "");
});

test("entrée illisible", () => {
  const r = lancer([], "pas du json");
  assert.equal(r.status, 0);
  assert.equal(r.stderr, "");
});

test("--diff : lignes ajoutées seulement, sortie 1 puis 0", () => {
  const dir = dirTemp();
  const git = (...a) => execFileSync("git", a, { cwd: dir, encoding: "utf8" });
  git("init", "-q", "-b", "master");
  const f = join(dir, LAYOUT);
  mkdirSync(dirname(f), { recursive: true });
  writeFileSync(f, '<View android:background="#FF0000"/>\n');
  git("add", ".");
  git("-c", "commit.gpgsign=false", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "base");
  const ok = lancer(["--diff", "master"], "", { cwd: dir });
  assert.equal(ok.status, 0, ok.stdout);
  writeFileSync(f, '<View android:background="#FF0000"/>\n<View android:background="#00FF00"/>\n');
  const ko = lancer(["--diff", "master"], "", { cwd: dir });
  assert.equal(ko.status, 1);
  assert.match(ko.stdout, /couleur-en-dur dans app\/src\/main\/res\/layout\/x\.xml : « <View android:background="#00FF00"\/> »/);
  assert.doesNotMatch(ko.stdout, /FF0000/);
  const env = { ...process.env, GIT_CONFIG_COUNT: "1", GIT_CONFIG_KEY_0: "diff.noprefix", GIT_CONFIG_VALUE_0: "true" };
  assert.equal(lancer(["--diff", "master"], "", { cwd: dir, env }).status, 1);
});

test("--diff : base introuvable sort en 2", () => {
  const dir = dirTemp();
  execFileSync("git", ["init", "-q"], { cwd: dir });
  const r = lancer(["--diff", "origin/inexistante"], "", { cwd: dir });
  assert.equal(r.status, 2);
  assert.match(r.stdout, /^Contrôle Mate : /);
});

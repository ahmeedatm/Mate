import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { describe, it } from "node:test";
import { TEMPLATES } from "../src/tablet.ts";

// Les templates existent des deux côtés (TabletTemplate dans l'appli, TEMPLATES ici) : mêmes ids, même ordre, mêmes textes.

const root = path.resolve(import.meta.dirname, "../..");
const kotlin = fs.readFileSync(path.join(root, "app/src/main/kotlin/app/mate/ui/home/TabletTemplates.kt"), "utf8");
const strings = fs.readFileSync(path.join(root, "app/src/main/res/values/strings.xml"), "utf8");

const text = (name: string) => {
  const m = strings.match(new RegExp(`<string name="${name}">(.*?)</string>`));
  assert.ok(m, name);
  return m[1].replaceAll("\\'", "'");
};

describe("templates de la tablette et du compagnon", () => {
  const app = [...kotlin.matchAll(/^ {4}[A-Z]+\("(\w+)", R\.string\.(template_\w+), R\.string\.(template_\w+)/gm)].map((m) => ({
    id: m[1],
    title: m[2],
    description: m[3],
  }));

  it("ont les mêmes ids dans le même ordre", () => {
    assert.deepEqual(
      app.map((t) => t.id),
      TEMPLATES.map((t) => t.id),
    );
  });

  it("ont le même titre et la même description", () => {
    for (const [i, t] of TEMPLATES.entries()) {
      assert.equal(text(app[i].title), t.title, t.id);
      assert.equal(text(app[i].description), t.description, t.id);
    }
  });
});

import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { after, beforeEach, describe, it } from "node:test";
import { config } from "../src/config.ts";
import { CACHE_MS, loadMetrics, MAX_METRICS } from "../src/metrics.ts";

const file = path.join(config.dataDir, "metrics.json");
const write = (metrics: unknown) => fs.writeFile(file, JSON.stringify({ metrics }));
beforeEach(() => fs.rm(file, { force: true }));
after(() => fs.rm(file, { force: true }));

describe("loadMetrics", () => {
  it("sans fichier : rien, et aucune commande lancée", async () => {
    const out = await loadMetrics(async () => assert.fail("aucune commande"), 1_000_000);
    assert.deepEqual(out, { items: [] });
  });

  it("lance les commandes écrites dans le fichier et rend la première ligne de chacune", async () => {
    await write([
      { label: "Dossier", command: "du-dossier" },
      { label: "Build", command: "etat-build" },
    ]);
    const seen: string[] = [];
    const out = await loadMetrics(async (cmd) => (seen.push(cmd), cmd === "du-dossier" ? "  1,2 Go \nsuite" : "vert"), 2_000_000);
    assert.deepEqual(seen, ["du-dossier", "etat-build"]);
    assert.deepEqual(out, {
      items: [
        { label: "Dossier", value: "1,2 Go" },
        { label: "Build", value: "vert" },
      ],
    });
  });

  it("une commande qui échoue ou ne dit rien donne une valeur nulle, les autres restent", async () => {
    await write([
      { label: "Casse", command: "a" },
      { label: "Muette", command: "b" },
      { label: "Bonne", command: "c" },
    ]);
    const out = await loadMetrics(async (cmd) => {
      if (cmd === "a") throw new Error("boum");
      return cmd === "b" ? "\n  \n" : "42";
    }, 3_000_000);
    assert.deepEqual(
      out.items.map((i) => i.value),
      [null, null, "42"],
    );
  });

  it("coupe une valeur trop longue et garde quatre valeurs au plus", async () => {
    await write(Array.from({ length: MAX_METRICS + 2 }, (_, i) => ({ label: `M${i}`, command: `c${i}` })));
    const out = await loadMetrics(async () => "x".repeat(500), 4_000_000);
    assert.equal(out.items.length, MAX_METRICS);
    assert.ok((out.items[0].value ?? "").length <= 40);
  });

  it("ignore les entrées mal formées", async () => {
    await write([{ label: "", command: "a" }, { label: "Sans commande" }, "texte", { label: "Ok", command: "b" }]);
    const out = await loadMetrics(async () => "1", 5_000_000);
    assert.deepEqual(out.items, [{ label: "Ok", value: "1" }]);
  });

  it("un fichier illisible donne une liste vide", async () => {
    await fs.writeFile(file, "pas du json");
    assert.deepEqual(await loadMetrics(async () => assert.fail("aucune commande"), 6_000_000), { items: [] });
  });

  it("garde la réponse en cache un court instant, puis relance", async () => {
    await write([{ label: "N", command: "n" }]);
    let runs = 0;
    const run = async () => String(++runs);
    assert.equal((await loadMetrics(run, 7_000_000)).items[0].value, "1");
    assert.equal((await loadMetrics(run, 7_000_000 + CACHE_MS - 1)).items[0].value, "1");
    assert.equal((await loadMetrics(run, 7_000_000 + CACHE_MS)).items[0].value, "2");
  });

  it("deux lectures en même temps ne lancent les commandes qu'une fois", async () => {
    await write([{ label: "Lente", command: "lente" }]);
    let runs = 0;
    const run = async () => (runs++, await new Promise((r) => setTimeout(r, 30)), "ok");
    const [a, b] = await Promise.all([loadMetrics(run, 9_000_000), loadMetrics(run, 9_000_000)]);
    assert.equal(runs, 1);
    assert.deepEqual(a, b);
  });

  it("un fichier changé ne ressert pas l'ancien cache", async () => {
    await write([{ label: "A", command: "a" }]);
    await loadMetrics(async () => "1", 8_000_000);
    await write([{ label: "B", command: "b" }]);
    assert.equal((await loadMetrics(async () => "2", 8_000_001)).items[0].label, "B");
  });
});

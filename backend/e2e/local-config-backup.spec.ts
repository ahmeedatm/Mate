import fs from "node:fs";
import path from "node:path";
import { expect, test } from "./fixtures.ts";

test.describe.configure({ mode: "serial" });

const KEY = { label: "Micro", steps: [{ type: "system", command: "mute-mic" }] };

test.beforeEach(async ({ server }) => {
  fs.writeFileSync(path.join(server.dataDir, "keys.json"), JSON.stringify({ slots: [KEY] }));
  fs.rmSync(path.join(server.dataDir, "links.json"), { force: true });
});

test.describe("page locale : sauvegarde", () => {
  test("enregistre un fichier, puis ne reprend que ce qui est coché", async ({ page, server }) => {
    await page.goto("/");
    const section = page.locator("#pf-sauvegarde");
    await expect(section.getByRole("heading", { name: "Sauvegarde" })).toBeVisible();

    const download = page.waitForEvent("download");
    await section.getByRole("button", { name: "Enregistrer", exact: true }).click();
    const file = await download;
    expect(file.suggestedFilename()).toMatch(/^mate-config-\d{4}-\d{2}-\d{2}\.json$/);
    const saved = path.join(server.dataDir, "sauvegarde-essai.json");
    await file.saveAs(saved);
    expect(JSON.parse(fs.readFileSync(saved, "utf8")).parts.keys.slots[0].label).toBe("Micro");

    // Sur « un autre ordinateur » : plus de touche.
    fs.writeFileSync(path.join(server.dataDir, "keys.json"), JSON.stringify({ slots: [] }));
    await section.locator("#pf-bk-file").setInputFiles(saved);
    await expect(section.locator("#pf-bk-parts input")).toHaveCount(4);
    await section.getByLabel("Plugins").uncheck();
    await section.getByRole("button", { name: "Reprendre ce qui est coché" }).click();
    await expect(section.locator("#pf-bk-msg")).toContainText("Repris : Touches de la page Perso");
    expect(JSON.parse(fs.readFileSync(path.join(server.dataDir, "keys.json"), "utf8")).slots[0].label).toBe("Micro");
  });

  test("refuse un fichier qui n'est pas une sauvegarde Mate", async ({ page, server }) => {
    await page.goto("/");
    const bad = path.join(server.dataDir, "pas-une-sauvegarde.json");
    fs.writeFileSync(bad, JSON.stringify({ hello: 1 }));
    await page.locator("#pf-bk-file").setInputFiles(bad);
    await expect(page.locator("#pf-bk-msg")).toHaveText("Ce fichier n'est pas une sauvegarde Mate.");
    await expect(page.locator("#pf-bk-parts")).toBeHidden();
  });
});

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
    await section.getByRole("button", { name: "Créer le fichier" }).click();
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

  test("montre les touches du fichier avant de les reprendre, et exige de les avoir relues", async ({ page, server }) => {
    await page.goto("/");
    const risky = {
      format: "mate-config-1",
      exportedAt: "2026-11-01T10:00:00.000Z",
      parts: {
        keys: {
          slots: [
            { label: "Micro", steps: [{ type: "system", command: "mute-mic" }] },
            null,
            {
              label: "Piège",
              steps: [
                { type: "app", name: "Notes" },
                { type: "text", text: "<img src=x onerror=alert(1)>" },
                { type: "keys", keys: "cmd+q" },
              ],
            },
          ],
        },
        pages: { pages: [{ title: "Réunions", slots: [{ label: "Ouvrir", steps: [{ type: "url", url: "https://exemple.com/a" }] }] }] },
      },
    };
    const file = path.join(server.dataDir, "sauvegarde-risquee.json");
    fs.writeFileSync(file, JSON.stringify(risky));
    fs.writeFileSync(path.join(server.dataDir, "keys.json"), JSON.stringify({ slots: [] }));
    const section = page.locator("#pf-sauvegarde");
    await section.locator("#pf-bk-file").setInputFiles(file);

    const preview = section.locator("#pf-bk-preview");
    await expect(preview).toContainText("Piège");
    await expect(preview).toContainText("app : Notes");
    await expect(preview).toContainText("text : <img src=x onerror=alert(1)>");
    await expect(preview).toContainText("keys : cmd+q");
    await expect(preview).toContainText("Réunions");
    await expect(preview).toContainText("url : https://exemple.com/a");
    await expect(preview.locator("img")).toHaveCount(0);

    const go = section.getByRole("button", { name: "Reprendre ce qui est coché" });
    await expect(go).toBeDisabled();
    // Sans les touches à risque (page Perso décochée), seule l'URL reste : rien à relire.
    await section.getByLabel("Touches de la page Perso").uncheck();
    await expect(go).toBeEnabled();
    await section.getByLabel("Touches de la page Perso").check();
    await expect(go).toBeDisabled();
    await section.getByLabel("J'ai relu ces touches").check();
    await expect(go).toBeEnabled();
    await go.click();
    await expect(section.locator("#pf-bk-msg")).toContainText("Repris");
    expect(JSON.parse(fs.readFileSync(path.join(server.dataDir, "keys.json"), "utf8")).slots[2].label).toBe("Piège");
  });
});

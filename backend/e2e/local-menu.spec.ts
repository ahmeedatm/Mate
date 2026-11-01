import fs from "node:fs";
import path from "node:path";
import { expect, test } from "./fixtures.ts";

test.beforeEach(({ server }) => {
  fs.rmSync(path.join(server.dataDir, "menu.json"), { force: true });
});

test.describe("page locale : menu de la semaine", () => {
  test("règle le menu, le garde au rechargement et le rend à la tablette", async ({ page, server }) => {
    await page.goto("/");
    const menu = page.locator("#pf-menu");
    await expect(menu.getByRole("heading", { name: "Menu de la semaine" })).toBeVisible();
    await expect(menu.locator("#pf-menu-days input")).toHaveCount(14);

    await menu.getByLabel("Lundi, midi").fill("  Pâtes au pesto ");
    await menu.getByLabel("Lundi, soir").fill("Soupe");
    await menu.getByLabel("Dimanche, midi").fill("Rôti");
    await menu.getByRole("button", { name: "Valider le menu" }).click();
    await expect(menu.locator("#pf-menu-msg")).toHaveText("Menu enregistré.");
    await expect(menu.getByLabel("Lundi, midi")).toHaveValue("Pâtes au pesto");

    const days = (await server.call("GET", "/menu")).json.days;
    expect(days[0]).toEqual({ lunch: "Pâtes au pesto", dinner: "Soupe" });
    expect(days[6]).toEqual({ lunch: "Rôti", dinner: "" });
    expect(days[3]).toEqual({ lunch: "", dinner: "" });

    await page.reload();
    await expect(page.locator("#pf-menu").getByLabel("Dimanche, midi")).toHaveValue("Rôti");
  });
});

import { expect, test as base } from "@playwright/test";
import { startFakeGitlab, type FakeGitlab } from "../test/fake-gitlab.ts";
import { startServer, type TestServer } from "../test/server.ts";

const TOKEN = "glpat-jeton-e2e-de-test";

/** Connexion GitLab par la page locale : un faux GitLab, un compagnon relié à lui, le navigateur colle un jeton. */
const test = base.extend<{}, { gitlab: FakeGitlab; server: TestServer }>({
  gitlab: [
    async ({}, use) => {
      const gitlab = await startFakeGitlab();
      gitlab.tokens[TOKEN] = ["api"];
      await use(gitlab);
      await gitlab.stop();
    },
    { scope: "worker" },
  ],
  server: [
    async ({ gitlab }, use) => {
      const server = await startServer({ env: { GITLAB_URL: gitlab.url } });
      await use(server);
      await server.stop();
    },
    { scope: "worker" },
  ],
  baseURL: async ({ server }, use) => use(server.url),
});

test.describe.configure({ mode: "serial" });

test("coller un jeton, voir le compte, déconnecter", async ({ page, gitlab }) => {
  await page.goto("/?section=gitlab");
  const card = page.locator("#gitlab");
  await expect(card).toBeVisible();
  await expect(card).toHaveClass(/focus/);
  await expect(card.locator("#gitlabstate")).toHaveText("Non connecté.");

  // Le lien ouvre la page de création de jeton de GitLab, nom et portée déjà remplis.
  const create = card.getByRole("link", { name: "Créer un jeton" });
  await expect(create).toHaveAttribute("href", `${gitlab.url}/-/user_settings/personal_access_tokens?name=Mate&scopes=api`);
  await expect(create).toHaveAttribute("target", "_blank");
  await expect(card.locator("#cxlist li")).toHaveCount(4);

  // Jeton refusé par GitLab : phrase claire, le champ reste, pas de compte.
  await card.getByLabel("Jeton GitLab").fill("glpat-jeton-inconnu-123");
  await card.getByRole("button", { name: "Enregistrer" }).click();
  await expect(card.locator("#gitlabmsg")).toHaveText(/^GitLab refuse ce jeton/);
  await expect(card.locator(".account")).toHaveCount(0);

  await card.getByLabel("Jeton GitLab").fill(TOKEN);
  await card.getByRole("button", { name: "Enregistrer" }).click();
  await expect(card.locator("#gitlabmsg")).toHaveText(/^✓ GitLab connecté/);
  await expect(card.locator(".account")).toContainText("GitLab · camille");
  await expect(card.getByLabel("Jeton GitLab")).toBeHidden();
  await expect(card.getByRole("link", { name: "Créer un jeton" })).toBeHidden();
  // Le jeton n'est jamais réaffiché dans la page.
  await expect(page.locator("body")).not.toContainText(TOKEN);

  await card.getByRole("button", { name: "Déconnecter GitLab" }).click();
  await expect(card.locator(".account")).toHaveCount(0);
  await expect(card.getByLabel("Jeton GitLab")).toBeVisible();
  await expect(card.getByLabel("Jeton GitLab")).toHaveValue("");

  // L'icône du connecteur ouvre la modale des étapes.
  await page.getByRole("button", { name: "Fermer" }).click();
  const tile = page.locator(".cx", { hasText: "GitLab" });
  await expect(tile).toContainText("Non connecté");
  await tile.click();
  await expect(page.locator("#cxtitle")).toHaveText("Relier GitLab");
  await expect(card.locator("#cxlist li")).toHaveCount(4);
  await expect(card.getByRole("link", { name: "Créer un jeton" })).toBeVisible();
});

test("jeton collé vide : message, rien n'est envoyé", async ({ page }) => {
  await page.goto("/?section=gitlab");
  await page.locator("#gitlab").getByRole("button", { name: "Enregistrer" }).click();
  await expect(page.locator("#gitlabmsg")).toHaveText("Colle d'abord le jeton.");
});

test("l'icône GitLab reste cliquable même sans connexion", async ({ page }) => {
  const bare = await startServer({ env: { GITLAB_TOKEN: "" } });
  try {
    await page.goto(bare.url + "/");
    await expect(page.locator("#name")).toHaveText("Ordi de test");
    const tile = page.locator(".cx", { hasText: "GitLab" });
    await expect(tile).toContainText("Non connecté");
    await expect(tile).not.toHaveClass(/na/);
    await tile.click();
    await expect(page.locator("#gitlabstate")).toHaveText("Non connecté.");
    await expect(page.getByLabel("Jeton GitLab")).toBeVisible();
  } finally {
    await bare.stop();
  }
});

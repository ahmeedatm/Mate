import { expect, test as base } from "@playwright/test";
import { FAKE_GITHUB_CLIENT_ID, startFakeGithub, type FakeGithub } from "../test/fake-github.ts";
import { startServer, type TestServer } from "../test/server.ts";

/** Connexion GitHub par la page locale (device flow) : un faux GitHub, un compagnon relié à lui, le navigateur fait le parcours. */
const test = base.extend<{}, { github: FakeGithub; server: TestServer }>({
  github: [
    async ({}, use) => {
      const github = await startFakeGithub();
      await use(github);
      await github.stop();
    },
    { scope: "worker" },
  ],
  server: [
    async ({ github }, use) => {
      const server = await startServer({ env: { GITHUB_URL: github.url, GITHUB_CLIENT_ID: FAKE_GITHUB_CLIENT_ID } });
      await use(server);
      await server.stop();
    },
    { scope: "worker" },
  ],
  baseURL: async ({ server }, use) => use(server.url),
});

test.describe.configure({ mode: "serial" });

test("connecter : code affiché, autorisé sur GitHub, compte affiché, déconnecter", async ({ page, github }) => {
  await page.goto("/?section=github");
  const card = page.locator("#github");
  await expect(card).toBeVisible();
  await expect(card.locator("#githubstate")).toHaveText("Non connecté.");

  await card.getByRole("link", { name: "Se connecter avec GitHub" }).click();
  await expect(page).toHaveURL(/\/\?section=github$/);
  await expect(card.locator("#githubusercode")).toHaveText(github.userCode);
  await expect(card.getByRole("link", { name: "Ouvrir GitHub" })).toHaveAttribute("href", `${github.url}/login/device`);

  github.approve();
  await expect(card.locator(".account")).toContainText("GitHub · camille");
  await expect(page.locator("#githubmsg")).toHaveText(/^✓ GitHub connecté/);
  await expect(card.locator("#githubcode")).toBeHidden();
  await expect(page.locator(".cx", { hasText: "GitHub" })).toContainText("Connecté");

  await card.getByRole("button", { name: "Déconnecter GitHub" }).click();
  await expect(card.locator(".account")).toHaveCount(0);
  await expect(card.getByRole("link", { name: "Se connecter avec GitHub" })).toBeVisible();
});

test("refusé sur GitHub : message d'annulation, pas de compte", async ({ page, github }) => {
  await page.goto("/oauth/github/start");
  await expect(page.locator("#githubusercode")).toHaveText(github.userCode);
  github.deny();
  await expect(page.locator("#githubmsg")).toHaveText("Connexion annulée.");
  await expect(page.locator("#github .account")).toHaveCount(0);
});

test("icône GitHub grisée quand rien n'est réglé", async ({ page }) => {
  const bare = await startServer({ env: { GITHUB_TOKEN: "" } });
  try {
    await page.goto(bare.url + "/");
    await expect(page.locator("#name")).toHaveText("Ordi de test");
    const tile = page.locator(".cx", { hasText: "GitHub" });
    await expect(tile).toContainText("Non connecté");
    await expect(tile).toHaveClass(/na/);
  } finally {
    await bare.stop();
  }
});

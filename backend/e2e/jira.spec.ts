import http from "node:http";
import type { AddressInfo } from "node:net";
import { expect, test as base } from "@playwright/test";
import { startServer, type TestServer } from "../test/server.ts";

const EMAIL = "camille@exemple.test";
const TOKEN = "ATATT3xFfGF0-jeton-e2e-de-test";

type FakeJira = { site: string; host: string; stop: () => Promise<void> };

/** Faux Jira : accepte un seul compte (e-mail + jeton) sur `GET /rest/api/3/myself`. */
async function startFakeJira(): Promise<FakeJira> {
  const expected = "Basic " + Buffer.from(`${EMAIL}:${TOKEN}`).toString("base64");
  const server = http.createServer((req, res) => {
    const ok = req.url === "/rest/api/3/myself" && req.headers.authorization === expected;
    res.writeHead(ok ? 200 : 401, { "content-type": "application/json" });
    res.end(ok ? JSON.stringify({ accountId: "acc-1" }) : "{}");
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  const host = `127.0.0.1:${(server.address() as AddressInfo).port}`;
  return { site: `http://${host}`, host, stop: () => new Promise((r) => server.close(() => r())) };
}

/** Connexion Jira par la page locale : un faux Jira, un compagnon neuf, le navigateur colle site, e-mail et jeton. */
const test = base.extend<{}, { jira: FakeJira; server: TestServer }>({
  jira: [
    async ({}, use) => {
      const jira = await startFakeJira();
      await use(jira);
      await jira.stop();
    },
    { scope: "worker" },
  ],
  server: [
    async ({}, use) => {
      const server = await startServer();
      await use(server);
      await server.stop();
    },
    { scope: "worker" },
  ],
  baseURL: async ({ server }, use) => use(server.url),
});

test.describe.configure({ mode: "serial" });

test("coller site, e-mail et jeton, voir le compte, déconnecter", async ({ page, jira }) => {
  await page.goto("/?section=jira");
  const card = page.locator("#jira");
  await expect(card).toBeVisible();
  await expect(card).toHaveClass(/focus/);
  await expect(page.locator("#cxtitle")).toHaveText("Relier Jira");

  // Le lien ouvre la page des jetons d'API d'Atlassian dans un autre onglet.
  const create = card.getByRole("link", { name: "Créer un jeton" });
  await expect(create).toHaveAttribute("href", "https://id.atlassian.com/manage-profile/security/api-tokens");
  await expect(create).toHaveAttribute("target", "_blank");
  await expect(card.locator("#cxlist li")).toHaveCount(4);

  // Champ vide : message, rien n'est envoyé.
  await card.getByRole("button", { name: "Enregistrer" }).click();
  await expect(card.locator("#jiramsg")).toHaveText("Remplis le site, l'e-mail et le jeton.");

  // Jeton refusé par Jira : phrase claire, les champs restent, pas de compte.
  await card.getByLabel("Site Jira").fill(jira.site);
  await card.getByLabel("E-mail Jira").fill(EMAIL);
  await card.getByLabel("Jeton Jira").fill("jeton-inconnu-123456");
  await card.getByRole("button", { name: "Enregistrer" }).click();
  await expect(card.locator("#jiramsg")).toHaveText(/^Jira refuse ces identifiants/);
  await expect(card.locator(".account")).toHaveCount(0);

  await card.getByLabel("Jeton Jira").fill(TOKEN);
  await card.getByRole("button", { name: "Enregistrer" }).click();
  await expect(card.locator("#jiramsg")).toHaveText(/^✓ Jira connecté/);
  await expect(card.locator(".account")).toContainText(`Connecté à ${jira.host}`);
  await expect(card.getByLabel("Jeton Jira")).toBeHidden();
  await expect(card.getByLabel("E-mail Jira")).toBeHidden();
  await expect(card.getByRole("link", { name: "Créer un jeton" })).toBeHidden();
  // Ni le jeton ni l'e-mail ne sont réaffichés dans la page.
  await expect(page.locator("body")).not.toContainText(TOKEN);
  await expect(page.locator("body")).not.toContainText(EMAIL);

  await card.getByRole("button", { name: "Déconnecter Jira" }).click();
  await expect(card.locator(".account")).toHaveCount(0);
  await expect(card.getByLabel("Jeton Jira")).toBeVisible();
  await expect(card.getByLabel("Jeton Jira")).toHaveValue("");

  // L'icône du connecteur ouvre la modale des étapes.
  await page.getByRole("button", { name: "Fermer" }).click();
  const tile = page.locator(".cx", { hasText: "Jira" });
  await expect(tile).toContainText("Non connecté");
  await tile.click();
  await expect(page.locator("#cxtitle")).toHaveText("Relier Jira");
  await expect(card.locator("#cxlist li")).toHaveCount(4);
  await expect(card.getByRole("link", { name: "Créer un jeton" })).toBeVisible();
});

test("identifiants du .env : dits, sans bouton Déconnecter", async ({ page, jira }) => {
  const withEnv = await startServer({ env: { JIRA_SITE: jira.site, JIRA_EMAIL: EMAIL, JIRA_API_TOKEN: TOKEN } });
  try {
    await page.goto(withEnv.url + "/?section=jira");
    const card = page.locator("#jira");
    await expect(card.locator("#jirastate")).toHaveText(`Connecté à ${jira.host} avec le fichier .env de l'ordinateur.`);
    await expect(card.locator(".account")).toContainText(`Connecté à ${jira.host}`);
    await expect(card.getByRole("button", { name: "Déconnecter Jira" })).toHaveCount(0);
    await expect(card.getByLabel("Jeton Jira")).toBeHidden();
    await expect(page.locator(".cx", { hasText: "Jira" })).toContainText("Connecté");
  } finally {
    await withEnv.stop();
  }
});

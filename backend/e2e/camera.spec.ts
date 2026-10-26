import { expect, pushFrame, test } from "./fixtures.ts";

test.describe("visualiseur /camera", () => {
  test.afterEach(async ({ server }) => {
    await server.call("POST", "/camera/state", { status: "off", rotation: 0 });
  });

  test("caméra en veille tant que la tablette n'envoie rien", async ({ page }) => {
    await page.goto("/camera");
    await expect(page.locator("#label")).toHaveText("En veille");
    await expect(page.getByText("Caméra en veille")).toBeVisible();
    await expect(page.locator("#frame")).toBeHidden();
  });

  test("affiche le flux en direct, puis l'image figée", async ({ page, server }) => {
    await page.goto("/camera");
    await expect(page.locator("#label")).toHaveText("En veille");

    await server.call("POST", "/camera/state", { status: "live", rotation: 0 });
    await pushFrame(server, page);
    // La tablette envoie en continu : sans image depuis 3 s, le serveur repasse en veille.
    const feed = setInterval(() => pushFrame(server, page).catch(() => {}), 500);
    try {
      await expect(page.locator("#label")).toHaveText("En direct");
      await expect(page.getByText("Caméra en veille")).toBeHidden();
      await expect(page.locator("#frame")).toBeVisible();
      await expect.poll(() => page.locator("#frame").evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(64);
      await expect.poll(async () => (await server.call("GET", "/camera/status")).json.viewers).toBeGreaterThan(0);
    } finally {
      clearInterval(feed);
    }

    await server.call("POST", "/camera/state", { status: "frozen" });
    await expect(page.locator("#label")).toHaveText("Image figée");
    await expect(page.locator("#frame")).toBeVisible();
  });

  test("suit la rotation de la tablette, Pivoter et la touche R ajoutent un demi-tour", async ({ page, server }) => {
    await page.goto("/camera");
    await page.evaluate(() => localStorage.removeItem("flip"));
    await pushFrame(server, page);
    await server.call("POST", "/camera/state", { status: "frozen", rotation: 90 });
    const frame = page.locator("#frame");
    await expect(frame).toHaveAttribute("style", /rotate\(90deg\)/);
    await expect(frame).toHaveClass(/sideways/);

    await page.getByRole("button", { name: "Pivoter" }).click();
    await expect(frame).toHaveAttribute("style", /rotate\(270deg\)/);
    // Le demi-tour est gardé au rechargement.
    await page.reload();
    await expect(frame).toHaveAttribute("style", /rotate\(270deg\)/);

    await page.keyboard.press("r");
    await expect(frame).toHaveAttribute("style", /rotate\(90deg\)/);
  });
});

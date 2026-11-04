import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import type { AddressInfo } from "node:net";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";

// Les modules du compagnon lisent DATA_DIR et JIRA_TEST_HTTP au chargement : posés avant de les importer.
const dir = fs.mkdtempSync(path.join(os.tmpdir(), "jira-race-"));
process.env.DATA_DIR = dir;
process.env.JIRA_TEST_HTTP = "1";
for (const name of ["JIRA_SITE", "JIRA_EMAIL", "JIRA_API_TOKEN"]) delete process.env[name];
const { connectJira, disconnectJira } = await import("../src/jira-auth.ts");
const { forgetJiraAccount, worklogSecondsOn } = await import("../src/jira.ts");

const FIRST = { email: "premier@exemple.test", token: "jeton-du-premier-compte" };
const SECOND = { email: "second@exemple.test", token: "jeton-du-second-compte" };
const seen: { url: string; email: string }[] = [];
let slowFirstMyself = 0;
let release: (() => void) | undefined;
const jiraServer = http.createServer((req, res) => {
  req.resume();
  req.on("end", () => {
    const email = Buffer.from((req.headers.authorization ?? "").replace(/^Basic /, ""), "base64")
      .toString()
      .split(":")[0];
    seen.push({ url: req.url ?? "", email });
    res.writeHead(200, { "content-type": "application/json" });
    const body = req.url === "/rest/api/3/myself" ? { accountId: `acc-${email}` } : { issues: [] };
    // Le premier compte reste en attente jusqu'à `release` : la lecture est « en vol ».
    if (req.url === "/rest/api/3/myself" && email === FIRST.email && slowFirstMyself-- > 0) {
      release = () => res.end(JSON.stringify(body));
      return;
    }
    res.end(JSON.stringify(body));
  });
});
let site = "";
before(async () => {
  await new Promise<void>((r) => jiraServer.listen(0, "127.0.0.1", r));
  site = `http://127.0.0.1:${(jiraServer.address() as AddressInfo).port}`;
});
after(async () => {
  await disconnectJira();
  jiraServer.close();
  fs.rmSync(dir, { recursive: true, force: true });
});

describe("jira : lecture en vol pendant un changement de compte", () => {
  it("ni l'identifiant ni le temps saisi lus avec l'ancien compte ne sont gardés pour le nouveau", async () => {
    await connectJira({ site, ...FIRST });
    slowFirstMyself = 1;
    const inFlight = worklogSecondsOn(new Date(), true);
    await new Promise((r) => setTimeout(r, 100));
    assert.ok(release, "la lecture du premier compte attend Jira");

    const { changed } = await connectJira({ site, ...SECOND });
    await forgetJiraAccount(changed);
    release!();
    await inFlight;

    seen.length = 0;
    await worklogSecondsOn(new Date());
    assert.ok(
      seen.some((s) => s.url === "/rest/api/3/myself" && s.email === SECOND.email),
      "l'identifiant du nouveau compte est relu",
    );
    assert.ok(
      seen.some((s) => s.url === "/rest/api/3/search/jql" && s.email === SECOND.email),
      "le temps saisi est relu, pas repris de l'ancien compte",
    );
  });
});

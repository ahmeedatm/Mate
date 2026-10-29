import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  appNameFromId,
  NOTIFICATION_TEXT_MAX,
  NOTIFICATIONS_MAX,
  NOTIFICATIONS_TTL_MS,
  NotificationHistory,
  parseToast,
  watchNotifications,
  type NotificationBatch,
  type NotificationItem,
  type RawNotification,
} from "../src/notifications.ts";

const toast = (texts: string) => `<toast launch="x"><visual><binding template="ToastGeneric">${texts}</binding></visual><actions/></toast>`;

describe("lecture d'un toast Windows", () => {
  it("premier texte = titre, les suivants = texte, sans l'attribution", () => {
    const xml = toast(
      `<text hint-maxLines="1">Marie Dupont</text><text>Réunion déplacée</text><text>à 15 h</text><text placement="attribution">via Outlook</text>`,
    );
    assert.deepEqual(parseToast(xml), { title: "Marie Dupont", body: "Réunion déplacée à 15 h" });
  });

  it("décode les entités et les CDATA, réduit les espaces", () => {
    const xml = toast(`<text>Tom &amp; Léa &#233;crivent</text><text><![CDATA[a < b]]>  et\n\t&lt;c&gt; &#x1F600;</text>`);
    assert.deepEqual(parseToast(xml), { title: "Tom & Léa écrivent", body: "a < b et <c> 😀" });
  });

  it("sans texte : titre et texte vides ; textes vides ignorés", () => {
    assert.deepEqual(parseToast(toast("<text/><text></text>")), { title: "", body: "" });
    assert.deepEqual(parseToast("pas du xml"), { title: "", body: "" });
  });

  it("coupe un texte trop long", () => {
    const { body } = parseToast(toast(`<text>t</text><text>${"a".repeat(NOTIFICATION_TEXT_MAX + 50)}</text>`));
    assert.equal(body.length, NOTIFICATION_TEXT_MAX + 1);
    assert.ok(body.endsWith("…"));
  });
});

describe("nom d'appli tiré de son identifiant", () => {
  it("AppUserModelID Windows et bundle id Mac", () => {
    assert.equal(appNameFromId("Microsoft.OutlookForWindows_8wekyb3d8bbwe!Microsoft.OutlookforWindows"), "Outlook");
    assert.equal(appNameFromId("MSTeams_8wekyb3d8bbwe!MSTeams"), "MSTeams");
    assert.equal(appNameFromId("com.squirrel.Discord.Discord"), "Discord");
    assert.equal(appNameFromId("C:\\Program Files\\App\\chatgpt.exe"), "chatgpt");
    assert.equal(appNameFromId("com.microsoft.Outlook"), "Outlook");
  });
});

describe("historique des notifications", () => {
  const n = (title: string, at: number, body = ""): RawNotification => ({ app: "Outlook", title, body, at });

  it("la plus récente en haut, identifiants distincts, sans titre ni texte ignorée", () => {
    const history = new NotificationHistory(() => 10_000);
    const a = history.add(n("a", 1000));
    history.add(n("c", 3000));
    history.add(n("b", 2000));
    assert.equal(history.add(n("", 4000)), undefined);
    assert.deepEqual(
      history.list().map((item) => item.title),
      ["c", "b", "a"],
    );
    assert.equal(new Set(history.list().map((item) => item.id)).size, 3);
    assert.equal(history.remove(a!.id), true);
    assert.equal(history.remove(a!.id), false);
    history.clear();
    assert.deepEqual(history.list(), []);
  });

  it("garde 100 notifications et 24 h au plus", () => {
    let now = NOTIFICATIONS_TTL_MS * 2;
    const history = new NotificationHistory(() => now);
    for (let i = 0; i < NOTIFICATIONS_MAX + 10; i++) history.add(n(`n${i}`, now - 1000 + i));
    assert.equal(history.list().length, NOTIFICATIONS_MAX);
    assert.equal(history.list()[0].title, `n${NOTIFICATIONS_MAX + 9}`);
    now += NOTIFICATIONS_TTL_MS;
    assert.deepEqual(history.list(), []);
  });
});

describe("surveillance des notifications", () => {
  const tick = () => new Promise((resolve) => setTimeout(resolve, 40));

  it("reprend les notifications déjà là sans les envoyer, puis envoie chaque nouvelle", async () => {
    const all: RawNotification[] = [{ app: "Teams", title: "ancienne", body: "", at: Date.now() - 1000 }];
    const read = async (cursor?: string): Promise<NotificationBatch> => ({
      cursor: String(all.length),
      items: all.slice(Number(cursor ?? 0)),
    });
    const history = new NotificationHistory();
    const pushed: NotificationItem[] = [];
    const stop = watchNotifications(read, history, (item) => pushed.push(item), 10);
    try {
      await tick();
      assert.equal(pushed.length, 0);
      all.push({ app: "Outlook", title: "nouvelle", body: "texte", at: Date.now() });
      await tick();
      assert.deepEqual(
        pushed.map((item) => item.title),
        ["nouvelle"],
      );
      assert.deepEqual(
        history.list().map((item) => item.title),
        ["nouvelle", "ancienne"],
      );
    } finally {
      stop();
    }
  });

  it("signale une base illisible une seule fois par cause, puis reprend", async () => {
    let failing = true;
    let calls = 0;
    const read = async (cursor?: string): Promise<NotificationBatch> => {
      calls++;
      if (failing) throw new Error("base verrouillée");
      return { cursor: cursor ?? "0", items: [] };
    };
    const errors: string[] = [];
    const stop = watchNotifications(
      read,
      new NotificationHistory(),
      () => {},
      10,
      (e) => errors.push(e.message),
    );
    try {
      await tick();
      assert.ok(calls >= 2);
      assert.deepEqual(errors, ["base verrouillée"]);
      failing = false;
      await tick();
      assert.deepEqual(errors, ["base verrouillée"]);
    } finally {
      stop();
    }
  });
});

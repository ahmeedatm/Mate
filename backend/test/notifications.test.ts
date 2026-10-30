import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { describe, it } from "node:test";
import {
  appNameFromId,
  NOTIFICATION_TEXT_MAX,
  NOTIFICATIONS_MAX,
  NOTIFICATIONS_TTL_MS,
  NotificationHistory,
  notificationsRead,
  parseToast,
  setNotificationsRead,
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

  it("une notification supprimée ne revient pas si le système la redonne", () => {
    const history = new NotificationHistory(() => 10_000);
    const a = history.add(n("Teams", 1000, "Marie"))!;
    assert.equal(history.remove(a.id), true);
    // La surveillance relit le système et la redonne (même contenu) : ignorée.
    assert.equal(history.add(n("Teams", 1000, "Marie")), undefined);
    assert.deepEqual(history.list(), []);
    // Une autre notification de la même appli passe toujours.
    assert.ok(history.add(n("Teams", 2000, "Léa")));
  });

  it("tout effacer garde les supprimées ignorées", () => {
    const history = new NotificationHistory(() => 10_000);
    history.add(n("a", 1000));
    history.add(n("b", 2000));
    history.clear();
    assert.equal(history.add(n("a", 1000)), undefined);
    assert.equal(history.add(n("b", 2000)), undefined);
  });

  it("les supprimées tiennent après un redémarrage (empreintes sur le disque)", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "mate-dismiss-"));
    try {
      const before = new NotificationHistory(() => 10_000);
      before.setStore(dir);
      const a = before.add(n("Teams", 1000, "Marie"))!;
      before.remove(a.id);
      // « Redémarrage » : une nouvelle instance relit le fichier d'empreintes.
      const after = new NotificationHistory(() => 10_000);
      after.setStore(dir);
      assert.equal(after.add(n("Teams", 1000, "Marie")), undefined);
      assert.ok(after.add(n("Teams", 2000, "Léa")));
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });

  it("l'empreinte d'une supprimée est purgée après 24 h", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "mate-dismiss-"));
    try {
      let now = NOTIFICATIONS_TTL_MS * 2;
      const history = new NotificationHistory(() => now);
      history.setStore(dir);
      const a = history.add(n("Teams", now - 1000, "Marie"))!;
      history.remove(a.id);
      now += NOTIFICATIONS_TTL_MS + 2000; // l'empreinte a plus de 24 h
      history.list(); // purge
      // Une notification de même contenu mais récente repasse (l'ancienne empreinte a été oubliée).
      assert.ok(history.add(n("Teams", now - 500, "Marie")));
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
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

  it("signale la lisibilité à chaque passage (vrai si lu, faux + l'erreur sinon)", async () => {
    let failing = true;
    const read = async (): Promise<NotificationBatch> => {
      if (failing) throw Object.assign(new Error("accès complet au disque à donner"), { statusCode: 503 });
      return { cursor: "0", items: [] };
    };
    const states: { ok: boolean; code?: number }[] = [];
    const stop = watchNotifications(
      read,
      new NotificationHistory(),
      () => {},
      10,
      () => {},
      (ok, e) => states.push({ ok, code: (e as { statusCode?: number } | undefined)?.statusCode }),
    );
    try {
      await tick();
      assert.deepEqual(states.at(-1), { ok: false, code: 503 });
      failing = false;
      await tick();
      assert.deepEqual(states.at(-1), { ok: true, code: undefined });
    } finally {
      stop();
    }
  });

  it("mémorise la dernière lisibilité pour la route (raison + code)", () => {
    setNotificationsRead(true);
    assert.deepEqual(notificationsRead(), { ok: true, reason: "", code: 0 });
    setNotificationsRead(false, Object.assign(new Error("accès complet au disque"), { statusCode: 503 }));
    assert.deepEqual(notificationsRead(), { ok: false, reason: "accès complet au disque", code: 503 });
    // Système non pris en charge : code 501 transmis tel quel.
    setNotificationsRead(false, Object.assign(new Error("non pris en charge"), { statusCode: 501 }));
    assert.equal(notificationsRead().code, 501);
    setNotificationsRead(true);
  });
});

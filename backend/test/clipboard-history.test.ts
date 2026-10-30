import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ClipboardHistory, HISTORY_MAX, HISTORY_TEXT_MAX, HISTORY_TTL_MS, looksSecret, watchClipboard } from "../src/clipboard-history.ts";
import type { ClipboardContent } from "../src/screens.ts";

describe("historique du presse-papiers", () => {
  it("met le plus récent en haut et fait remonter un texte déjà présent, sans doublon", () => {
    const history = new ClipboardHistory();
    for (const text of ["un", "deux", "trois"]) history.add(text);
    const first = history.list()[2];
    history.add("un");
    assert.deepEqual(
      history.list().map((item) => item.text),
      ["un", "trois", "deux"],
    );
    assert.equal(history.list()[0].id, first.id);
  });

  it("garde 20 textes au plus", () => {
    const history = new ClipboardHistory();
    for (let i = 0; i < HISTORY_MAX + 5; i++) history.add(`texte ${i}`);
    const texts = history.list().map((item) => item.text);
    assert.equal(texts.length, HISTORY_MAX);
    assert.equal(texts[0], `texte ${HISTORY_MAX + 4}`);
  });

  it("oublie un texte au bout d'une heure", () => {
    let now = 1_000_000;
    const history = new ClipboardHistory(() => now);
    history.add("ancien");
    now += HISTORY_TTL_MS - 1;
    history.add("récent");
    assert.equal(history.list().length, 2);
    now += 1;
    assert.deepEqual(
      history.list().map((item) => item.text),
      ["récent"],
    );
  });

  it("ignore un texte vide, fait d'espaces ou trop long", () => {
    const history = new ClipboardHistory();
    for (const text of ["", "  \n\t", "a".repeat(HISTORY_TEXT_MAX + 1)]) history.add(text);
    assert.deepEqual(history.list(), []);
    history.add("a".repeat(HISTORY_TEXT_MAX));
    assert.equal(history.list().length, 1);
  });

  it("retrouve, retire et vide par identifiant", () => {
    const history = new ClipboardHistory();
    history.add("un");
    history.add("deux");
    const [deux, un] = history.list();
    assert.equal(history.get(un.id)?.text, "un");
    assert.equal(history.remove(un.id), true);
    assert.equal(history.remove(un.id), false);
    assert.equal(history.get(un.id), undefined);
    assert.notEqual(deux.id, un.id);
    history.clear();
    assert.deepEqual(history.list(), []);
  });

  it("masque ce qui ressemble à un code ou un mot de passe", () => {
    for (const text of ["482913", "Ab3$kL9z", "hunter2", "C:\\Users\\bruno"]) assert.equal(looksSecret(text), true, text);
    for (const text of [
      "bonjour",
      "élève",
      "abc",
      "deux mots 42",
      "https://exemple.fr/a1",
      "www.exemple.fr",
      "bruno@example.com",
      "a".repeat(30) + "1".repeat(35),
    ]) {
      assert.equal(looksSecret(text), false, text);
    }
  });
});

describe("surveillance du presse-papiers", () => {
  /** Faux système : le repère change à chaque `copy`. */
  function fakeClipboard(initial: ClipboardContent) {
    let content = initial;
    let stamp = 0;
    let reads = 0;
    let failing = false;
    return {
      sources: {
        stamp: async () => {
          if (failing) throw new Error("CLIPBOARD_UNAVAILABLE");
          return String(stamp);
        },
        read: async () => {
          reads++;
          return content;
        },
      },
      copy(next: ClipboardContent) {
        content = next;
        stamp++;
      },
      reads: () => reads,
      fail(on: boolean) {
        failing = on;
      },
    };
  }

  const tick = () => new Promise((resolve) => setTimeout(resolve, 40));

  it("note le texte présent au démarrage puis chaque nouvelle copie, en ne relisant le contenu qu'au changement", async () => {
    const clip = fakeClipboard({ kind: "text", text: "au démarrage" });
    const history = new ClipboardHistory();
    const stop = watchClipboard(clip.sources, history, 10);
    try {
      await tick();
      assert.equal(clip.reads(), 1);
      clip.copy({ kind: "text", text: "code 1234" });
      await tick();
      assert.equal(clip.reads(), 2);
      assert.deepEqual(
        history.list().map((item) => item.text),
        ["code 1234", "au démarrage"],
      );
    } finally {
      stop();
    }
  });

  it("n'ajoute ni contenu protégé, ni image, ni rien quand le presse-papiers est indisponible", async () => {
    const clip = fakeClipboard({ kind: "protected" });
    const history = new ClipboardHistory();
    const stop = watchClipboard(clip.sources, history, 10);
    try {
      await tick();
      clip.copy({ kind: "image" });
      await tick();
      clip.fail(true);
      clip.copy({ kind: "text", text: "pas lu" });
      await tick();
      assert.deepEqual(history.list(), []);
      clip.fail(false);
      await tick();
      assert.deepEqual(
        history.list().map((item) => item.text),
        ["pas lu"],
      );
    } finally {
      stop();
    }
  });
});

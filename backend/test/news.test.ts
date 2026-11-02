import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { loadNews, parseFeed, setNewsFeed, shownLink } from "../src/news.ts";

const rss = `<?xml version="1.0"?><rss version="2.0"><channel><title>Le Blog</title>
<item><title><![CDATA[Premier & <b>gras</b>]]></title><link>https://exemple.fr/a</link></item>
<item><title>Deuxi&egrave;me &amp; &#233;t&#xE9;</title><link> https://exemple.fr/b </link></item>
<item><title>Piège</title><link>javascript:alert(1)</link></item>
<item><title>Sans lien</title></item>
<item><title>Trois</title><link>https://exemple.fr/c</link></item>
<item><title>Quatre</title><link>https://exemple.fr/d</link></item>
<item><title>Cinq</title><link>https://exemple.fr/e</link></item>
<item><title>Six</title><link>https://exemple.fr/f</link></item>
</channel></rss>`;

const atom = `<feed xmlns="http://www.w3.org/2005/Atom"><title>Sorties</title>
<entry><title type="html">v1.2 &lt;3</title><link rel="alternate" href="https://exemple.fr/v1.2"/></entry>
<entry><title>v1.1</title><link rel="self" href="https://exemple.fr/self"/><link rel="alternate" href="https://exemple.fr/v1.1"/></entry>
</feed>`;

describe("parseFeed", () => {
  it("lit un RSS : titres nettoyés, liens http(s) seulement, cinq au plus", () => {
    const feed = parseFeed(rss);
    assert.equal(feed.title, "Le Blog");
    assert.deepEqual(
      feed.items.map((i) => i.title),
      ["Premier & gras", "Deuxième & été", "Trois", "Quatre", "Cinq"],
    );
    assert.equal(feed.items[1].link, "https://exemple.fr/b");
  });

  it("lit un Atom et prend le lien alternate", () => {
    const feed = parseFeed(atom);
    assert.equal(feed.title, "Sorties");
    assert.deepEqual(feed.items, [
      { title: "v1.2 <3", link: "https://exemple.fr/v1.2" },
      { title: "v1.1", link: "https://exemple.fr/v1.1" },
    ]);
  });

  it("reste rapide sur un flux piégé de 1 Mo", () => {
    const start = Date.now();
    parseFeed(`<rss><channel>${"<".repeat(1_000_000)}`);
    parseFeed(`<rss><channel>${"<item>".repeat(170_000)}`);
    parseFeed(`<rss><channel><item><title>${"<link ".repeat(160_000)}`);
    assert.ok(Date.now() - start < 2000, `trop lent : ${Date.now() - start} ms`);
  });

  it("refuse ce qui n'est pas un flux", () => {
    assert.throws(() => parseFeed("<html><body>Bonjour</body></html>"), /flux/);
  });
});

describe("loadNews", () => {
  it("sans flux réglé : rien, et aucun téléchargement", async () => {
    await setNewsFeed(null);
    const news = await loadNews(async () => assert.fail("pas de téléchargement"));
    assert.deepEqual(news, { feed: null, title: null, items: [] });
  });

  it("télécharge le flux réglé", async () => {
    await setNewsFeed("https://exemple.fr/rss");
    const asked: string[] = [];
    const news = await loadNews(async (url) => {
      asked.push(url);
      return Buffer.from(atom);
    });
    assert.deepEqual(asked, ["https://exemple.fr/rss"]);
    assert.equal(news.items.length, 2);
    await setNewsFeed(null);
  });

  it("refuse une adresse qui n'est pas http(s) ou qui porte un mot de passe", async () => {
    await assert.rejects(setNewsFeed("file:///etc/passwd"), /adresse/i);
    await assert.rejects(setNewsFeed("https://moi:mdp@exemple.fr/rss"), /adresse/i);
  });

  it("ne laisse ouvrir que les liens de la dernière liste montrée", async () => {
    await setNewsFeed("https://exemple.fr/rss");
    await loadNews(async () => Buffer.from(atom));
    assert.equal(shownLink(0), "https://exemple.fr/v1.2");
    assert.equal(shownLink(2), null);
    await assert.rejects(loadNews(async () => Buffer.from("<html></html>")));
    assert.equal(shownLink(0), null);
    await loadNews(async () => Buffer.from(atom));
    await setNewsFeed(null);
    assert.equal(shownLink(0), null);
  });
});

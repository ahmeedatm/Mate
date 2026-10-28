import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { fallbackSessions, playerScript, playingApps, sessionOfNowPlaying, siteName, tabTitle, thumbOfTabs } from "../src/mac/media.ts";

describe("lecture en cours (Mac)", () => {
  it("Spotify et Musique pilotés par AppleScript, les autres non", () => {
    assert.equal(playerScript("com.spotify.client", "play-pause"), 'tell application "Spotify" to playpause');
    assert.equal(playerScript("Music", "next"), 'tell application "Music" to next track');
    assert.equal(playerScript("Spotify", "seek", 61_400), 'tell application "Spotify" to set player position to 61');
    assert.equal(playerScript("com.operasoftware.OperaGX", "play-pause"), undefined);
  });

  it("MediaRemote : appli, état, position avancée depuis l'instant noté", () => {
    // Relevé sur le Mac d'Eliot (YouTube Music dans Opera GX).
    const raw = {
      bundle: "com.operasoftware.OperaGX",
      name: "Opera GX",
      title: "Cold Water",
      artist: "Major Lazer",
      album: "Cold Water",
      duration: 185.35,
      elapsed: 76.08,
      rate: 1,
      timestampMs: 1_000_000,
      hasArt: 0,
    };
    const s = sessionOfNowPlaying(raw, 1_002_000)!;
    assert.deepEqual(
      [s.app, s.name, s.status, s.positionMs, s.durationMs, s.canSeek, s.hasThumb],
      ["com.operasoftware.OperaGX", "Opera GX", "playing", 78080, 185350, true, true],
    );
    assert.equal(sessionOfNowPlaying({ ...raw, rate: 0 }, 1_002_000)?.positionMs, 76080);
    assert.equal(sessionOfNowPlaying({}), undefined);
    // `playing` (MediaRemote) l'emporte sur la vitesse de lecture.
    assert.equal(sessionOfNowPlaying({ ...raw, rate: 1, playing: false })?.status, "paused");
  });

  it("pochette de secours : image YouTube de l'onglet du même titre", () => {
    const tabs = [
      { url: "https://music.youtube.com/", tabTitle: "YouTube Music" },
      { url: "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RD", tabTitle: "Ghost | YouTube Music" },
    ];
    assert.equal(thumbOfTabs(tabs, "Ghost"), "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg");
    assert.equal(thumbOfTabs(tabs, "Autre"), undefined);
  });

  it("secours : nom du site et titre de l'onglet nettoyé", () => {
    assert.equal(siteName("https://music.youtube.com/watch?v=abc"), "YouTube Music");
    assert.equal(siteName("https://www.youtube.com/watch?v=abc"), "YouTube");
    assert.equal(siteName("https://example.com/"), undefined);
    assert.equal(tabTitle("(3) Mon clip - YouTube", "YouTube"), "Mon clip");
    assert.equal(tabTitle("BU DUNYO | YouTube Music", "YouTube Music"), "BU DUNYO");
    assert.equal(tabTitle("YouTube Music", "YouTube Music"), "");
  });

  it("secours : le navigateur qui joue du son passe devant", () => {
    const playing = playingApps('   pid 535(Opera): [0x1] 00:04:30 NoIdleSleepAssertion named: "Playing audio"  \n');
    const sessions = fallbackSessions(
      [
        { kind: "tab", app: "Google Chrome", url: "https://music.youtube.com/", tabTitle: "YouTube Music" },
        { kind: "tab", app: "Google Chrome", url: "https://music.youtube.com/watch?v=x", tabTitle: "Titre | YouTube Music" },
        { kind: "tab", app: "Opera", url: "https://www.youtube.com/watch?v=y", tabTitle: "Clip - YouTube" },
      ],
      playing,
    );
    assert.deepEqual(
      sessions.map((s) => [s.app, s.title, s.status, s.current]),
      [
        ["Google Chrome", "Titre", "paused", false],
        ["Opera", "Clip", "playing", true],
      ],
    );
  });
});

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  enrichBrowserSession,
  extensionBrowser,
  fallbackSessions,
  HOLD_MS,
  heldSession,
  mergeSessions,
  type MediaSession,
  playerScript,
  playingApps,
  sessionOfNowPlaying,
  siteName,
  tabTitle,
  thumbOfTabs,
} from "../src/mac/media.ts";

describe("lecture en cours (Mac)", () => {
  const generic = {
    app: "com.google.Chrome",
    name: "Google Chrome",
    current: true,
    title: "Un site est en train de lire un contenu multimédia",
    artist: "",
    album: "",
    hasThumb: true,
    status: "paused" as const,
    canPlayPause: true,
    canNext: true,
    canPrevious: true,
    canSeek: true,
    positionMs: 75,
    durationMs: 63_161,
  };

  it("titre générique du navigateur : titre de l'onglet du site connu et nom du site", () => {
    const tabs = [
      { app: "Google Chrome", url: "https://mail.google.com/", tabTitle: "Boîte de réception", active: true },
      { app: "Google Chrome", url: "https://www.youtube.com/watch?v=abc", tabTitle: "(2) Lo-fi beats - YouTube", active: false },
    ];
    const s = enrichBrowserSession(generic, tabs);
    assert.equal(s.title, "Lo-fi beats");
    assert.equal(s.name, "YouTube");
    assert.equal(s.app, "com.google.Chrome");
    assert.equal(s.durationMs, 63_161);
  });

  it("titre générique sans site connu : rien n'est montré d'un onglet quelconque (confidentialité)", () => {
    const tabs = [
      { app: "Google Chrome", url: "https://intranet.example.fr/rh/salaires", tabTitle: "Grille des salaires 2027", active: true },
    ];
    assert.deepEqual(enrichBrowserSession(generic, tabs), generic);
  });

  it("plusieurs sites connus : l'onglet actif d'abord", () => {
    const tabs = [
      { app: "Google Chrome", url: "https://www.youtube.com/watch?v=a", tabTitle: "Vieux clip - YouTube", active: false },
      { app: "Google Chrome", url: "https://www.twitch.tv/x", tabTitle: "Live du soir - Twitch", active: true },
    ];
    const s = enrichBrowserSession(generic, tabs);
    assert.equal(s.name, "Twitch");
    assert.equal(s.title, "Live du soir");
  });

  it("un vrai titre (Spotify, YouTube avec Media Session) reste tel quel", () => {
    const spotify = { ...generic, app: "com.spotify.client", name: "Spotify", title: "Cold Water", artist: "Major Lazer" };
    assert.deepEqual(enrichBrowserSession(spotify, []), spotify);
    const titled = { ...generic, title: "Cold Water" };
    assert.deepEqual(
      enrichBrowserSession(titled, [{ app: "Google Chrome", url: "https://www.youtube.com/", tabTitle: "X - YouTube", active: true }]),
      titled,
    );
  });

  it("session enrichie : les commandes vont toujours au navigateur, pas au site affiché", () => {
    const tabs = [{ app: "Google Chrome", url: "https://www.youtube.com/watch?v=abc", tabTitle: "Lo-fi beats - YouTube", active: true }];
    const s = enrichBrowserSession(generic, tabs);
    assert.equal(s.name, "YouTube");
    assert.equal(extensionBrowser(s), "Google Chrome");
    assert.equal(extensionBrowser(generic), "Google Chrome");
  });

  it("titre générique sans onglet lisible : rien ne change", () => {
    assert.deepEqual(enrichBrowserSession(generic, []), generic);
  });

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

  it("changement de morceau : Spotify en pause ne remplace pas YouTube Music pendant quelques secondes", () => {
    // Relevé du 29/10 : Opera GX « LOST. » qui joue, rien, Spotify « unravel » en pause ~5 s, puis le morceau suivant.
    const s = (app: string, title: string, status: string) => ({ app, title, status }) as MediaSession;
    const opera = s("com.operasoftware.OperaGX", "LOST.", "playing");
    const playing = { session: opera, atMs: 1_000_000 };
    const spotify = s("com.spotify.client", "unravel", "paused");
    assert.equal(heldSession(playing, undefined, 1_001_000), opera);
    assert.equal(heldSession(playing, spotify, 1_005_000), opera);
    // Le même site mis en pause, une autre appli qui joue, ou la pause qui dure : la lecture fraîche l'emporte.
    const paused = s("com.operasoftware.OperaGX", "LOST.", "paused");
    assert.equal(heldSession(playing, paused, 1_002_000), paused);
    const music = s("com.spotify.client", "unravel", "playing");
    assert.equal(heldSession(playing, music, 1_002_000), music);
    assert.equal(heldSession(playing, spotify, 1_000_000 + HOLD_MS + 1), spotify);
    assert.equal(heldSession(undefined, spotify, 1_000_000), spotify);
  });

  it("toutes les lectures : ordre et identifiants du secours, sans doublon, devant ce qui joue", () => {
    const s = (app: string, name: string, title: string, status: string) => ({ app, name, title, status, current: false }) as MediaSession;
    const ytm = s("Opera|YouTube Music", "YouTube Music", "Bécane - A COLORS SHOW", "playing");
    const yt = s("Opera|YouTube", "YouTube", "Clip", "playing");
    const spotifyApp = s("Spotify", "Spotify", "unravel", "paused");
    const rows = (r: { sessions: MediaSession[] }) => r.sessions.map((m) => [m.app, m.status, m.current]);
    // Relevé du 29/10 : MediaRemote figé sur Spotify en pause, YouTube Music joue dans Opera GX.
    const stuck = s("com.spotify.client", "Spotify", "unravel", "paused");
    const merged = mergeSessions(stuck, [spotifyApp, ytm]);
    assert.deepEqual(rows(merged), [
      ["Spotify", "paused", false],
      ["Opera|YouTube Music", "playing", true],
    ]);
    assert.equal(merged.remote, "Spotify");
    // MediaRemote suit YouTube Music : il prend l'identifiant et la place de l'onglet, l'autre onglet d'Opera ne joue pas.
    const remote = s("com.operasoftware.OperaGX", "Opera GX", "Bécane", "playing");
    const followed = mergeSessions(remote, [spotifyApp, ytm, yt]);
    assert.deepEqual(rows(followed), [
      ["Spotify", "paused", false],
      ["Opera|YouTube Music", "playing", true],
      ["Opera|YouTube", "paused", false],
    ]);
    assert.deepEqual(
      [followed.remote, followed.sessions[1].name, followed.sessions[1].title],
      ["Opera|YouTube Music", "YouTube Music", "Bécane"],
    );
    // MediaRemote muet : le secours tel quel ; appli inconnue du secours : en tête.
    assert.deepEqual(rows(mergeSessions(undefined, [spotifyApp, ytm])), [
      ["Spotify", "paused", false],
      ["Opera|YouTube Music", "playing", true],
    ]);
    assert.equal(
      mergeSessions(s("com.apple.podcasts", "Podcasts", "Épisode", "paused"), [spotifyApp]).sessions[0].app,
      "com.apple.podcasts",
    );
  });

  it("deux lectures à la fois : MediaRemote passe de l'une à l'autre, identifiants et ordre ne bougent pas", () => {
    const s = (app: string, name: string, title: string, status: string) => ({ app, name, title, status, current: false }) as MediaSession;
    const spotifyApp = s("Spotify", "Spotify", "unravel", "playing");
    const ytm = s("Opera|YouTube Music", "YouTube Music", "Hold On", "playing");
    const order = (primary: MediaSession) => mergeSessions(primary, [spotifyApp, ytm]).sessions.map((m) => [m.app, m.status]);
    const both = [
      ["Spotify", "playing"],
      ["Opera|YouTube Music", "playing"],
    ];
    assert.deepEqual(order(s("com.spotify.client", "Spotify", "unravel", "playing")), both);
    assert.deepEqual(order(s("com.operasoftware.OperaGX", "Opera GX", "Hold On", "playing")), both);
  });

  it("YouTube Music en pause (onglet sans titre) : gardé avec le dernier morceau, sans doublon si MediaRemote le suit", () => {
    const s = (app: string, name: string, title: string, status: string, artist = "") =>
      ({ app, name, title, artist, status, current: false }) as MediaSession;
    const recall = new Map();
    // MediaRemote suit YouTube Music qui joue : le morceau est retenu pour l'onglet.
    const remote = s("com.operasoftware.OperaGX", "Opera GX", "Hold On", "playing", "Chord Overstreet");
    mergeSessions(remote, [s("Opera|YouTube Music", "YouTube Music", "Hold On", "playing")], recall);
    // En pause, l'onglet n'a plus de titre : MediaRemote le suit encore, une seule lecture, sous l'identifiant de l'onglet.
    const idle = s("Opera|YouTube Music", "YouTube Music", "", "paused");
    assert.deepEqual(
      mergeSessions({ ...remote, status: "paused" }, [idle], recall).sessions.map((m) => [m.app, m.title]),
      [["Opera|YouTube Music", "Hold On"]],
    );
    // Relevé du 29/10 : Spotify relancé depuis la tablette, MediaRemote passe sur Spotify ; YouTube Music reste.
    const spotify = s("com.spotify.client", "Spotify", "unravel", "playing");
    assert.deepEqual(
      mergeSessions(spotify, [idle], recall).sessions.map((m) => [m.app, m.title, m.artist, m.status, m.current]),
      [
        ["com.spotify.client", "unravel", "", "playing", true],
        ["Opera|YouTube Music", "Hold On", "Chord Overstreet", "paused", false],
      ],
    );
    // Rien de retenu : le nom du site.
    assert.equal(mergeSessions(spotify, [idle]).sessions[1].title, "YouTube Music");
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
        ["Google Chrome|YouTube Music", "Titre", "paused", false],
        ["Opera|YouTube", "Clip", "playing", true],
      ],
    );
  });
});

describe("titre générique d'un navigateur et toutes les lectures", () => {
  const chrome: MediaSession = {
    app: "com.google.Chrome",
    name: "Google Chrome",
    current: true,
    title: "Un site est en train de lire un contenu multimédia",
    artist: "",
    album: "",
    hasThumb: false,
    status: "playing",
    canPlayPause: true,
    canNext: true,
    canPrevious: true,
    canSeek: false,
    positionMs: null,
    durationMs: null,
  };
  const tab = (site: string, title: string): MediaSession => ({
    ...chrome,
    app: `Google Chrome|${site}`,
    name: site,
    title,
    current: false,
  });

  it("la session enrichie retrouve son onglet : une seule lecture, commandes toujours au navigateur", () => {
    const enriched = enrichBrowserSession(chrome, [
      { app: "Google Chrome", url: "https://www.youtube.com/watch?v=a", tabTitle: "Nightcall - YouTube", active: true },
    ]);
    const { sessions } = mergeSessions(enriched, [tab("YouTube", "Nightcall"), tab("Twitch", "Live du soir")]);
    const yt = sessions.find((s) => s.name === "YouTube")!;
    assert.equal(sessions.filter((s) => s.title === "Nightcall").length, 1);
    assert.equal(extensionBrowser(yt), "Google Chrome");
    // L'autre onglet du même navigateur ne joue pas : MediaRemote dit que c'est celui de YouTube.
    assert.equal(sessions.find((s) => s.name === "Twitch")!.status, "paused");
  });
});

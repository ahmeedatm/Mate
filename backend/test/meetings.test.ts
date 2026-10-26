import "./fake-os-hook.ts";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { beforeEach, describe, it } from "node:test";
import { calls, state } from "./fake-os.ts";
import { meetingKind, meetingLink } from "../src/meeting-links.ts";

const dir = await fs.mkdtemp(path.join(os.tmpdir(), "companion-meetings-"));
const ics = path.join(dir, "agenda.ics");
process.env.DATA_DIR = dir;
process.env.CALENDAR_ICS_URLS = ics;
const { autoJoinEnabled, joinMeeting, setAutoJoin } = await import("../src/meetings.ts");
const { dayRange, getEvents } = await import("../src/calendar.ts");

const TEAMS = "https://teams.microsoft.com/l/meetup-join/19%3ameeting_abc%40thread.v2/0?context=%7b%7d";
const ZOOM = "https://unowhy.zoom.us/j/123456789?pwd=Ab.c-1";
const MEET = "https://meet.google.com/abc-defg-hij";

describe("liens de visio", () => {
  it("trouve le premier lien dans la description puis le lieu", () => {
    assert.equal(meetingLink(`Rejoindre : <${TEAMS}> merci`), TEAMS);
    assert.equal(meetingLink(undefined, null, "", `Salle 2 ou ${MEET} (repli)`), MEET);
    assert.equal(meetingLink(`Lien zoom ${ZOOM}`, TEAMS), ZOOM, "le premier texte passe avant");
    assert.equal(meetingLink("https://teams.microsoft.com/meet/4012345678?p=x"), "https://teams.microsoft.com/meet/4012345678?p=x");
    assert.equal(meetingLink("https://acme.webex.com/meet/jdupont"), "https://acme.webex.com/meet/jdupont");
  });

  it("ignore ce qui n'est pas un lien de réunion", () => {
    assert.equal(meetingLink("https://teams.microsoft.com/", "https://meet.google.com/landing", "https://zoom.us/pricing"), undefined);
    assert.equal(meetingLink("http://meet.google.com/abc-defg-hij"), undefined, "https seulement");
    assert.equal(meetingLink(), undefined);
  });

  it("reconnaît le service", () => {
    assert.equal(meetingKind(TEAMS), "teams");
    assert.equal(meetingKind("https://teams.live.com/meet/9876"), "teams");
    assert.equal(meetingKind(ZOOM), "zoom");
    assert.equal(meetingKind(MEET), "meet");
    assert.equal(meetingKind("https://acme.webex.com/acme/j.php?MTID=1"), "webex");
    assert.equal(meetingKind("https://example.com"), undefined);
  });
});

const pad = (n: number) => String(n).padStart(2, "0");
const d = new Date();
const day = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`;
const vevent = (uid: string, title: string, extra: string) =>
  `BEGIN:VEVENT\r\nUID:${uid}\r\nDTSTAMP:${day}T080000Z\r\nDTSTART:${day}T100000\r\nDTEND:${day}T110000\r\nSUMMARY:${title}\r\n${extra}END:VEVENT\r\n`;
await fs.writeFile(
  ics,
  "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//FR\r\n" +
    vevent("t", "Daily", `DESCRIPTION:Rejoindre ${TEAMS}\r\n`) +
    vevent("z", "Client", `LOCATION:${ZOOM}\r\n`) +
    vevent("m", "Atelier", `DESCRIPTION:${MEET}\r\n`) +
    vevent("s", "Sans visio", "LOCATION:Salle Turing\r\n") +
    "END:VCALENDAR\r\n",
);
const idOf = async (title: string) => (await getEvents(...dayRange(1))).events.find((e) => e.title === title)!.id;

describe("rejoindre une réunion", () => {
  beforeEach(() => {
    calls.length = 0;
    delete state.fail;
  });

  it("Teams et Zoom : ouverts dans leur appli", async () => {
    assert.equal((await joinMeeting(await idOf("Daily")))?.kind, "teams");
    assert.equal((await joinMeeting(await idOf("Client")))?.kind, "zoom");
    assert.deepEqual(
      calls.map((c) => c.args[0]),
      [TEAMS.replace("https://teams.microsoft.com/", "msteams:/"), "zoommtg://zoom.us/join?confno=123456789&pwd=Ab.c-1"],
    );
  });

  it("appli absente : repli sur le navigateur", async () => {
    state.fail = ["openTarget msteams:"];
    await joinMeeting(await idOf("Daily"));
    assert.deepEqual(
      calls.map((c) => c.args[0]),
      [TEAMS],
    );
  });

  it("Google Meet : directement dans le navigateur", async () => {
    const joined = await joinMeeting(await idOf("Atelier"));
    assert.equal(joined?.event.title, "Atelier");
    assert.deepEqual(
      calls.map((c) => c.args[0]),
      [MEET],
    );
  });

  it("rien pour un événement sans lien ou inconnu", async () => {
    assert.equal(await joinMeeting(await idOf("Sans visio")), undefined);
    assert.equal(await joinMeeting("inconnu"), undefined);
    assert.deepEqual(calls, []);
  });

  it("ouverture automatique : désactivée par défaut, réglable", async () => {
    assert.equal(await autoJoinEnabled(), false);
    await setAutoJoin(true);
    assert.equal(await autoJoinEnabled(), true);
    await setAutoJoin(false);
    assert.equal(await autoJoinEnabled(), false);
  });
});

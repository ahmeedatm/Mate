import assert from "node:assert/strict";
import { test } from "node:test";
import { checkOpenTarget, type OpenPlatform } from "../src/open-guard.ts";

const ok = (target: string, platform: OpenPlatform) => assert.doesNotThrow(() => checkOpenTarget(target, platform), target);
const refused = (target: string, platform: OpenPlatform) =>
  assert.throws(() => checkOpenTarget(target, platform), (e: Error & { statusCode?: number }) => e.statusCode === 400 && e.message.length > 0, target);

test("liens http(s), mailto et applis de réunion acceptés", () => {
  for (const p of ["mac", "windows"] as const) {
    ok("https://example.com/a?b=c", p);
    ok("HTTP://example.com", p);
    ok("mailto:a@b.fr", p);
    ok("msteams:/l/meetup-join/x", p);
    ok("zoommtg://zoom.us/join?confno=1", p);
  }
});

test("schémas propres à un système", () => {
  ok("ms-settings:network", "windows");
  refused("ms-settings:network", "mac");
  ok("x-apple.systempreferences:com.apple.preference.security", "mac");
  refused("x-apple.systempreferences:x", "windows");
});

test("schémas non attendus refusés", () => {
  for (const p of ["mac", "windows"] as const) {
    refused("file:///etc/passwd", p);
    refused("shell:AppsFolder\\x", p);
    refused("javascript:alert(1)", p);
    refused("ms-msdt:/id x", p);
  }
});

test("documents et dossiers acceptés", () => {
  ok("C:\\Users\\a\\Partage\\cours.pdf", "windows");
  ok("C:\\Users\\a\\Partage\\Dossier", "windows");
  ok("/Users/a/Partage/photo.jpg", "mac");
  ok("/Users/a/Partage/Dossier", "mac");
});

test("programmes et scripts refusés", () => {
  refused("C:\\Partage\\setup.exe", "windows");
  refused("C:\\Partage\\SETUP.EXE", "windows");
  refused("C:\\Partage\\run.bat", "windows");
  refused("C:\\Partage\\a.exe.", "windows");
  refused("C:\\Partage\\a.exe ", "windows");
  refused("C:\\Partage\\raccourci.lnk", "windows");
  refused("/Users/a/Partage/Tool.app", "mac");
  refused("/Users/a/Partage/Tool.app/", "mac");
  refused("/Users/a/Partage/go.command", "mac");
  refused("/Users/a/Partage/go.sh", "mac");
});

test("noms nus, chemins relatifs, options et chemins réseau refusés", () => {
  refused("calc", "windows");
  refused("..\\x.pdf", "windows");
  refused("\\\\hote\\partage\\x.pdf", "windows");
  refused("-a Terminal", "mac");
  refused("", "mac");
  refused("   ", "windows");
});

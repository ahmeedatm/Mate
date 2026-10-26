import fs from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";
import { sortedNames } from "../driver-common.ts";
import { run } from "./run.ts";

const APP_DIRS = ["/Applications", "/System/Applications", "/System/Applications/Utilities", "/Applications/Utilities"];

export async function findAppBundle(name: string): Promise<string | undefined> {
  const local = APP_DIRS.map((dir) => path.join(dir, `${name}.app`)).find((c) => existsSync(c));
  if (local) return local;
  const found = await run("mdfind", [
    `kMDItemContentType == 'com.apple.application-bundle' && kMDItemFSName == '${name.replace(/'/g, "")}.app'`,
  ]).catch(() => "");
  return found.split("\n").find(Boolean);
}

/** Applis installées (noms acceptés par `open -a`), pour choisir une appli depuis la tablette. */
export async function listApps(): Promise<string[]> {
  const found = await run("mdfind", ["kMDItemContentType == 'com.apple.application-bundle'"]).catch(() => "");
  const names = found
    .split("\n")
    // Pas les applis internes à une autre appli ni les composants système (…/Library/…).
    .filter((p) => p.endsWith(".app") && !p.slice(0, -4).includes(".app/") && !p.includes("/Library/"))
    .map((p) => path.basename(p, ".app"))
    .filter((n) => !/uninstall|url handler/i.test(n));
  return sortedNames(names);
}

/** Applis installées, gardées 5 min : le profil de base du deck ne montre que celles-là. */
let installed: { at: number; names: Set<string> } | undefined;
export async function isInstalled(name: string): Promise<boolean> {
  if (!installed || Date.now() - installed.at > 5 * 60_000) installed = { at: Date.now(), names: new Set(await listApps()) };
  return installed.names.has(name);
}

/** Raccourcis de l'appli Raccourcis (macOS 12+). */
export async function listShortcuts(): Promise<string[]> {
  const out = await run("shortcuts", ["list"]).catch(() => "");
  return out
    .split("\n")
    .map((s) => s.trim())
    .filter(Boolean)
    .sort((a, b) => a.localeCompare(b, "fr"));
}

/** Icône de l'appli : extraite de son .icns, convertie en PNG 96 px dans `out`. */
export async function appIcon(name: string, out: string): Promise<boolean> {
  const bundle = await findAppBundle(name);
  if (!bundle) return false;
  const iconName = await run("plutil", [
    "-extract",
    "CFBundleIconFile",
    "raw",
    "-o",
    "-",
    path.join(bundle, "Contents", "Info.plist"),
  ]).catch(() => "");
  if (!iconName) return false;
  const icns = path.join(bundle, "Contents", "Resources", iconName.endsWith(".icns") ? iconName : `${iconName}.icns`);
  if (!existsSync(icns)) return false;
  await fs.mkdir(path.dirname(out), { recursive: true });
  await run("sips", ["-s", "format", "png", "-Z", "96", icns, "--out", out]).catch(() => undefined);
  return existsSync(out);
}

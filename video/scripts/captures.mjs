#!/usr/bin/env node
/**
 * Refait toutes les captures d'écran de l'appli UnoMate (émulateur, backend de démo) pour les vidéos.
 * Voir video/scripts/captures.md. Lancer depuis n'importe où : `node video/scripts/captures.mjs [options]`.
 *
 *   --no-build           garde l'APK debug déjà construit
 *   --only sylvie,sam    seulement ces phases (sylvie gwen camille aimee sam sacha commun)
 *   --date 2026-10-29    jour affiché par la tablette (défaut : jour de l'ordinateur, l'agenda de démo suit)
 *   --serial emulator-N  réutilise un émulateur déjà lancé (jamais arrêté par le script)
 *
 * Sécurité : toutes les commandes adb passent par `adb()`, qui impose `-s emulator-<port>` (une vraie tablette est
 * souvent branchée). Les processus lancés sont arrêtés par leur PID, jamais par motif ni par port.
 */
import { spawn, spawnSync } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(here, "..", "..");
const OUT = path.join(ROOT, "video", "captures");
const BACKEND_DIR = path.join(ROOT, "backend");
const APK = path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk");
const PKG = "com.unowhy.unomate";
const TOKEN = "jeton-de-test";
const AVD = "companion_api29";
const W = 1280;
const H = 800;

// ---------------------------------------------------------------------------------------------------------------- options
const args = process.argv.slice(2);
const flag = (name) => args.includes(name);
const opt = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
};
const only = opt("--only")?.split(",");
const reuseSerial = opt("--serial");
const dateArg = opt("--date");
const today = new Date();
const DAY = dateArg ?? `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
if (!/^\d{4}-\d{2}-\d{2}$/.test(DAY)) throw new Error(`--date attendu AAAA-MM-JJ, reçu ${DAY}`);
if (reuseSerial && !/^emulator-\d+$/.test(reuseSerial)) throw new Error("--serial doit être de la forme emulator-<port> (jamais une vraie tablette)");

const log = (msg) => console.log(`[${new Date().toTimeString().slice(0, 8)}] ${msg}`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sdk = fs.readFileSync(path.join(ROOT, "local.properties"), "utf8").match(/^sdk\.dir=(.*)$/m)?.[1]?.trim() ?? path.join(os.homedir(), "Library/Android/sdk");
const ADB = path.join(sdk, "platform-tools", "adb");
const EMULATOR = path.join(sdk, "emulator", "emulator");

// ------------------------------------------------------------------------------------------------ processus et nettoyage
let SERIAL = reuseSerial ?? "";
const owned = []; // { name, pid, kids }
/** Dossier de travail (journaux, données du backend de démo) : supprimé à la fin, gardé si une capture a échoué. */
const TMP = path.join(os.tmpdir(), `captures-${process.pid}`);
fs.mkdirSync(TMP, { recursive: true });
let keepTmp = false;
let timeChanged = false;
let reversed = null;
let cleaned = false;
let origin = null; // état d'origine de l'émulateur, à remettre

const alive = (pid) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
};

/** Descendants d'un PID (instantané de `ps`). */
function descendants(pid) {
  const ps = spawnSync("ps", ["-A", "-o", "pid=,ppid="], { encoding: "utf8" }).stdout.trim().split("\n");
  const children = new Map();
  for (const line of ps) {
    const [p, pp] = line.trim().split(/\s+/).map(Number);
    if (!children.has(pp)) children.set(pp, []);
    children.get(pp).push(p);
  }
  const out = [];
  const queue = [pid];
  while (queue.length) for (const c of children.get(queue.shift()) ?? []) out.push(c), queue.push(c);
  return out;
}

function sleepSync(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

/** Arrête un processus lancé par ce script : SIGTERM, attente, SIGKILL ; puis ses descendants encore vivants. */
function stopProc(p, graceMs = 10_000) {
  const kids = [...new Set([...(p.kids ?? []), ...descendants(p.pid)])];
  const wait = (pid, ms) => {
    for (let t = 0; t < ms && alive(pid); t += 200) sleepSync(200);
  };
  if (alive(p.pid)) {
    process.kill(p.pid, "SIGTERM");
    wait(p.pid, graceMs);
    if (alive(p.pid)) process.kill(p.pid, "SIGKILL");
  }
  // Seulement les descendants encore rattachés au processus arrêté (ou orphelins) : un PID réutilisé n'est jamais touché.
  const ours = (k) => [p.pid, 1, ...kids].includes(Number(spawnSync("ps", ["-o", "ppid=", "-p", String(k)], { encoding: "utf8" }).stdout.trim()));
  for (const k of kids) {
    if (alive(k) && ours(k)) {
      try {
        process.kill(k, "SIGTERM");
      } catch {}
      wait(k, 3000);
      if (alive(k)) {
        try {
          process.kill(k, "SIGKILL");
        } catch {}
      }
    }
  }
  const i = owned.indexOf(p);
  if (i >= 0) owned.splice(i, 1);
}

function cleanup() {
  if (cleaned) return;
  cleaned = true;
  log("nettoyage");
  try {
    if (SERIAL && origin) {
      try {
        adb(["shell", "settings", "put", "global", "auto_time", origin.autoTime]);
        adb(["shell", "settings", "put", "global", "window_animation_scale", "1"]);
        adb(["shell", "settings", "put", "global", "transition_animation_scale", "1"]);
        adb(["shell", "settings", "put", "global", "animator_duration_scale", "1"]);
        setLocale(origin.locale);
        if (reversed) adb(["reverse", "--remove", `tcp:${reversed}`]);
        if (timeChanged) {
          // Heure de l'ordinateur remise (l'heure automatique ne se resynchronise pas toujours seule).
          const n = new Date();
          const two = (v) => String(v).padStart(2, "0");
          shell(`date ${two(n.getMonth() + 1)}${two(n.getDate())}${two(n.getHours())}${two(n.getMinutes())}${n.getFullYear()}.${two(n.getSeconds())}`);
        }
      } catch (e) {
        log(`remise en état partielle : ${e.message}`);
      }
    }
  } finally {
    for (const p of [...owned].reverse()) {
      log(`arrêt de ${p.name} (PID ${p.pid})`);
      try {
        stopProc(p, p.name === "émulateur" ? 25_000 : 10_000);
      } catch (e) {
        log(`arrêt de ${p.name} : ${e.message}`);
      }
    }
    if (keepTmp) console.log(`journaux gardés : ${TMP}`);
    else fs.rmSync(TMP, { recursive: true, force: true });
  }
}
process.on("exit", cleanup);
for (const sig of ["SIGINT", "SIGTERM", "SIGHUP"]) {
  process.on(sig, () => {
    cleanup();
    process.exit(130);
  });
}

// ------------------------------------------------------------------------------------------------------------------ adb
/** Seule porte vers adb : impose `-s emulator-<port>`. */
function adb(a, { timeout = 30_000, binary = false, input } = {}) {
  if (!/^emulator-\d+$/.test(SERIAL)) throw new Error(`série d'émulateur invalide : « ${SERIAL} »`);
  const r = spawnSync(ADB, ["-s", SERIAL, ...a], { encoding: binary ? "buffer" : "utf8", timeout, maxBuffer: 1 << 28, input });
  if (r.error) throw r.error;
  return binary ? r.stdout : r.stdout.toString();
}
const shell = (cmd, o) => adb(["shell", cmd], o);

function freePort(start, end, step = 1, extra = 0) {
  const tryBind = (host, port) => {
    const r = spawnSync(process.execPath, ["-e", `const s=require("net").createServer();s.on("error",()=>process.exit(1));s.listen(${port},"${host}",()=>s.close(()=>process.exit(0)))`]);
    return r.status === 0;
  };
  for (let p = start; p <= end; p += step) {
    if ([0, extra].every((d) => tryBind("0.0.0.0", p + d) && tryBind("127.0.0.1", p + d))) return p;
  }
  throw new Error(`aucun port libre entre ${start} et ${end}`);
}

// Outil `Loc` (dex de 1,8 Ko, source ci-dessous) : change la langue du système via app_process, ce que `setprop` ne fait pas
// sur cette image (dates relatives de « Fichiers de l'ordi » en français).
//   Object am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
//   Configuration c = (Configuration) am.getClass().getMethod("getConfiguration").invoke(am);
//   c.setLocale(Locale.forLanguageTag(a[0])); c.getClass().getField("userSetLocale").setBoolean(c, true);
//   am.getClass().getMethod("updatePersistentConfiguration", Configuration.class).invoke(am, c);
const LOC_DEX =
  "ZGV4CjAzNwCuJC8Q84ZUnsz0cDZQymadUGlEk8Q4gK0ABwAAcAAAAHhWNBIAAAAAAAAAAEgGAAAmAAAAcAAAAA8AAAAIAQAACgAAAEQBAAAAAAAAAAAAAAsAAAC8AQAAAQAAABQCAADMBAAANAIAAFIDAABaAwAAXQMAAGEDAABmAwAAbQMAAJIDAACuAwAAwQMAANgDAADsAwAAAAQAABsEAAA3BAAASwQAAFUEAABYBAAAXAQAAGEEAABkBAAAeAQAAI0EAACiBAAAvwQAAM8EAADYBAAA4gQAAPQEAAD+BAAACQUAABUFAAAdBQAAIwUAAC8FAAA6BQAAWQUAAGgFAABvBQAABAAAAAUAAAAGAAAABwAAAAgAAAAJAAAACgAAAAsAAAAMAAAADQAAAA8AAAASAAAAEwAAABQAAAAVAAAAAQAAAAMAAAAAAAAAAgAAAAMAAAAkAwAAAwAAAAUAAAAsAwAAAgAAAAcAAAAkAwAAAwAAAAgAAAA0AwAAAgAAAAkAAAAkAwAADwAAAAoAAAAAAAAAEQAAAAoAAAA8AwAAEAAAAAoAAABEAwAAEAAAAAoAAABMAwAAAAAGAAAAAAAAAAkAHwAAAAEACAAhAAAAAwABABgAAAADAAMAGwAAAAMABAAcAAAABQAGAAAAAAAFAAAAGQAAAAcABwAgAAAACAACAB4AAAAJAAUAFwAAAAAAAAABAAAABQAAAAAAAAAOAAAAMAYAABcGAAAAAAAAAQABAAEAAAAOAwAABAAAAHAQBgAAAA4ABgABAAMAAAASAwAAWQAAABoAFgBxEAMAAAAMABIBIxIMABoDHQBuMAUAMAIMABICIxMNAG4wCQAgAwwAbhAHAAAADAIaAxoAIxQMAG4wBQAyBAwCIxMNAG4wCQACAwwCHwIBAEYFBQFxEAoABQAMBW4gAgBSAG4QBwACAAwFGgMjAG4gBAA1AAwFEhNuMAgAJQNuEAcAAAAMBSMzDAAcBAEATQQDARoBIgBuMAUAFQMMBSQQDQACAAwBbjAJAAUBDgADAA4ABQEADgEWDwEUD5bhARcPAAAAAQAAAAYAAAACAAAABQANAAIAAAAGAAwAAgAAAAUACwABAAAACQAAAAEAAAAOAAY8aW5pdD4AAUwAAkxMAANMTEwABUxMb2M7ACNMYW5kcm9pZC9jb250ZW50L3Jlcy9Db25maWd1cmF0aW9uOwAaTGRhbHZpay9hbm5vdGF0aW9uL1Rocm93czsAEUxqYXZhL2xhbmcvQ2xhc3M7ABVMamF2YS9sYW5nL0V4Y2VwdGlvbjsAEkxqYXZhL2xhbmcvT2JqZWN0OwASTGphdmEvbGFuZy9TdHJpbmc7ABlMamF2YS9sYW5nL3JlZmxlY3QvRmllbGQ7ABpMamF2YS9sYW5nL3JlZmxlY3QvTWV0aG9kOwASTGphdmEvdXRpbC9Mb2NhbGU7AAhMb2MuamF2YQABVgACVkwAA1ZMWgABWgASW0xqYXZhL2xhbmcvQ2xhc3M7ABNbTGphdmEvbGFuZy9PYmplY3Q7ABNbTGphdmEvbGFuZy9TdHJpbmc7ABthbmRyb2lkLmFwcC5BY3Rpdml0eU1hbmFnZXIADmZvckxhbmd1YWdlVGFnAAdmb3JOYW1lAAhnZXRDbGFzcwAQZ2V0Q29uZmlndXJhdGlvbgAIZ2V0RmllbGQACWdldE1ldGhvZAAKZ2V0U2VydmljZQAGaW52b2tlAARtYWluAApzZXRCb29sZWFuAAlzZXRMb2NhbGUAHXVwZGF0ZVBlcnNpc3RlbnRDb25maWd1cmF0aW9uAA11c2VyU2V0TG9jYWxlAAV2YWx1ZQCdAX5+RDh7ImJhY2tlbmQiOiJkZXgiLCJjb21waWxhdGlvbi1tb2RlIjoiZGVidWciLCJoYXMtY2hlY2tzdW1zIjpmYWxzZSwibWluLWFwaSI6MjQsInNoYS0xIjoiYTdhZDE4YTcwNDYwYjc5OWQwNDgyZTQ5N2MxMDlhNzViZjdmOTFkZSIsInZlcnNpb24iOiI4LjEwLjktZGV2In0AAgIBJBwBGAQAAAIAAIGABLQEAQnMBAAAAAEAAAAPBgAAAAAAAAAAAAABAAAAAAAAAAEAAAAoBgAADwAAAAAAAAABAAAAAAAAAAEAAAAmAAAAcAAAAAIAAAAPAAAACAEAAAMAAAAKAAAARAEAAAUAAAALAAAAvAEAAAYAAAABAAAAFAIAAAEgAAACAAAANAIAAAMgAAACAAAADgMAAAEQAAAGAAAAJAMAAAIgAAAmAAAAUgMAAAQgAAABAAAADwYAAAAgAAABAAAAFwYAAAMQAAABAAAAKAYAAAYgAAABAAAAMAYAAAAQAAABAAAASAYAAA==";

function setLocale(tag) {
  adb(["push", writeTmp("loc.dex", Buffer.from(LOC_DEX, "base64")), "/data/local/tmp/loc.dex"]);
  shell(`CLASSPATH=/data/local/tmp/loc.dex app_process / Loc ${tag}`);
}

function writeTmp(name, data) {
  const file = path.join(TMP, name);
  fs.writeFileSync(file, data);
  return file;
}

// ----------------------------------------------------------------------------------------------------------- émulateur
async function startEmulator() {
  const port = freePort(5620, 5680, 2, 1);
  SERIAL = `emulator-${port}`;
  const logFile = path.join(TMP, "emulateur.log");
  const fd = fs.openSync(logFile, "w");
  // -read-only : l'AVD de l'autre session n'est pas touché, l'état repart de zéro à chaque lancement.
  const child = spawn(
    EMULATOR,
    ["-avd", AVD, "-port", String(port), "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot", "-read-only", "-gpu", "swiftshader_indirect"],
    { stdio: ["ignore", fd, fd], env: { ...process.env, ANDROID_SDK_ROOT: sdk } },
  );
  owned.push({ name: "émulateur", pid: child.pid });
  log(`émulateur ${AVD} lancé sur ${SERIAL} (PID ${child.pid}, journal ${logFile})`);
  child.on("exit", (code) => log(`émulateur arrêté (code ${code})`));
  await waitFor(() => shell("getprop sys.boot_completed", { timeout: 8000 }).trim() === "1", 240_000, "démarrage de l'émulateur");
  owned.find((p) => p.pid === child.pid).kids = descendants(child.pid);
}

async function waitFor(check, timeoutMs, what, every = 1500) {
  const end = Date.now() + timeoutMs;
  for (;;) {
    try {
      if (await check()) return;
    } catch {}
    if (Date.now() > end) throw new Error(`délai dépassé : ${what}`);
    await sleep(every);
  }
}

async function prepareEmulator() {
  adb(["root"]);
  await waitFor(() => shell("id", { timeout: 8000 }).includes("uid=0"), 60_000, "adb root");
  origin = {
    autoTime: shell("settings get global auto_time").trim() || "1",
    locale: shell("getprop persist.sys.locale").trim() || shell("getprop ro.product.locale").trim() || "en-US",
  };
  for (const k of ["window_animation_scale", "transition_animation_scale", "animator_duration_scale"]) shell(`settings put global ${k} 0`);
  shell("settings put global auto_time 0");
  shell("settings put global stay_on_while_plugged_in 7");
  shell("settings put system screen_off_timeout 2147483647");
  shell("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard");
  setLocale("fr-FR");
  // Le réseau doit être validé avant les captures (sinon l'icône WiFi de la barre d'état est barrée) : l'heure d'abord, car
  // un système resté sur l'heure d'origine de l'image refuse la validation.
  setTime(12, 0);
  const validated = () => shell("dumpsys connectivity", { timeout: 20_000 }).includes("VALIDATED");
  await waitFor(validated, 90_000, "réseau validé", 3000).catch(() => log("⚠ réseau non validé : l'icône WiFi peut être barrée"));
  log(`émulateur prêt (langue d'origine ${origin.locale}, heure auto ${origin.autoTime})`);
}

function setTime(hh, mm) {
  const [y, mo, d] = DAY.split("-");
  timeChanged = true;
  shell(`date ${mo}${d}${String(hh).padStart(2, "0")}${String(mm).padStart(2, "0")}${y}.05`);
}

// ------------------------------------------------------------------------------------------------------------- backend
let ports = null;
let backend = null;
let backendInfo = null;

async function startBackend(persona, { hh, mm }) {
  await stopBackend();
  ports ??= (() => {
    const main = freePort(3970, 3999);
    const rest = [];
    let from = main + 1;
    for (let i = 0; i < 4; i++) {
      const p = freePort(from, 3999);
      rest.push(p);
      from = p + 1;
    }
    return { main, ics: rest[0], control: rest[1], jira: rest[2], gitlab: rest[3] };
  })();
  const dir = path.join(TMP, "backend");
  const env = {
    ...process.env,
    DEMO_PERSONA: persona,
    DEMO_PORT: String(ports.main),
    DEMO_ICS_PORT: String(ports.ics),
    DEMO_CONTROL_PORT: String(ports.control),
    DEMO_JIRA_PORT: String(ports.jira),
    DEMO_GITLAB_PORT: String(ports.gitlab),
    DEMO_DIR: dir,
    DEMO_NAME: "PC du bureau",
    DEMO_NOW: `${DAY}T${String(hh).padStart(2, "0")}:${String(mm).padStart(2, "0")}:00`,
  };
  const logFile = path.join(TMP, `backend-${persona}.log`);
  const fd = fs.openSync(logFile, "w");
  const child = spawn(process.execPath, ["--import", "tsx", "../video/demo-backend.mts"], { cwd: BACKEND_DIR, env, stdio: ["ignore", fd, fd] });
  const proc = { name: `backend ${persona}`, pid: child.pid, kids: [] };
  owned.push(proc);
  backend = proc;
  let exited = false;
  child.on("exit", () => (exited = true));
  await waitFor(
    async () => {
      if (exited) throw new Error(`le backend de démo s'est arrêté :\n${fs.readFileSync(logFile, "utf8")}`);
      const r = await fetch(`http://127.0.0.1:${ports.main}/health`);
      backendInfo = await r.json();
      return fs.readFileSync(logFile, "utf8").includes("Backend de démo");
    },
    60_000,
    `backend de démo ${persona}`,
    500,
  ).catch((e) => {
    if (exited) throw e;
    throw e;
  });
  proc.kids = descendants(child.pid);
  log(`backend ${persona} sur 127.0.0.1:${ports.main} (PID ${child.pid}, serveur PID ${proc.kids.join(",")})`);
}

async function stopBackend() {
  if (!backend) return;
  const p = backend;
  backend = null;
  stopProc(p);
}

const api = async (method, route, body) => {
  const res = await fetch(`http://127.0.0.1:${ports.main}${route}`, {
    method,
    headers: { authorization: `Bearer ${TOKEN}`, ...(body ? { "content-type": "application/json" } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  return res.json().catch(() => ({}));
};
const control = (route, body) =>
  fetch(`http://127.0.0.1:${ports.control}${route}`, { method: "POST", body: JSON.stringify(body) }).then((r) => r.text());

// -------------------------------------------------------------------------------------------- préférences de la tablette
const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

/** Écrit shared_prefs/companion.xml : ordinateur appairé (adresse locale + jeton), thème, disposition de l'Accueil. */
function writePrefs({ theme, scale, layout, reduceMotion = true }) {
  const comp = [{ id: backendInfo.id, name: backendInfo.name, url: `http://127.0.0.1:${ports.main}`, token: TOKEN, os: backendInfo.os, lastUsedMs: 1788000000000, pin: null }];
  const items = [
    `<string name="computers">${esc(JSON.stringify(comp))}</string>`,
    `<string name="active_computer">${backendInfo.id}</string>`,
    `<string name="theme">${theme}</string>`,
    `<float name="text_scale" value="${scale}" />`,
    `<boolean name="wake_word" value="false" />`,
    `<boolean name="night_dim" value="false" />`,
    `<boolean name="tour_done" value="true" />`,
    // Sans animation continue : uiautomator ne peut lire l'écran d'un appel en cours autrement (« could not get idle state »).
    `<boolean name="reduce_motion" value="${reduceMotion}" />`,
    `<string name="home_layout_cadran">${esc(layout(backendInfo.id))}</string>`,
  ];
  const xml = `<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n  ${items.join("\n  ")}\n</map>\n`;
  shell(`am force-stop ${PKG}`);
  adb(["push", writeTmp("companion.xml", xml), "/data/local/tmp/companion.xml"]);
  const uid = shell(`stat -c %u /data/data/${PKG}`).trim();
  const d = `/data/data/${PKG}/shared_prefs`;
  shell(`mkdir -p ${d} && rm -f ${d}/*.xml && cp /data/local/tmp/companion.xml ${d}/companion.xml && chown ${uid}:${uid} ${d} ${d}/companion.xml && chmod 660 ${d}/companion.xml && restorecon -R ${d}`);
}

// Dispositions de l'Accueil (JSON v2 de HomeLayout). Réunion est ajoutée par l'appli (rôles non posés).
const CITY = { city: "Lyon", lat: 45.7491, lon: 4.8479 };
const wd = (t, s, p, c, r, cfg = {}) => ({ t, s, p, c, r, cfg });
const kref = (slot, label, glyph, color = "") => ({ id: `k-${slot}`, label, glyph, color });
const keysCfg = (comp, keys) => ({ byComputer: { [comp]: { keys } } });
const layoutJson = (names, widgets) => JSON.stringify({ v: 2, pages: names.length, names, game: -1, home: 0, deck: -1, call: -1, roles: false, widgets });
/** Page « Ma journée » : salutation, ligne du jour, prochain rendez-vous, tâches, applis, touches. */
const journee = (p, keys) => [
  wd("day", "W", p, 0, 0, CITY),
  wd("dayline", "W", p, 0, 1),
  wd("next", "V", p, 0, 2),
  wd("tasks", "V", p, 1, 2),
  wd("apps", "V", p, 2, 2),
  wd("keys", "V", p, 3, 2, keys),
];
const defaultKeys = { defaultPage: "Raccourcis" };

const SYLVIE_KEYS = [
  kref(0, "Export FEC", "📤", "#1565C0"),
  kref(1, "Somme auto", "∑"),
  kref(2, "Filtre", "🔎"),
  kref(3, "Rapprochement", "🏦", "#2E7D32"),
  kref(4, "Libellé TVA", "%"),
  kref(5, "Relance client", "✉"),
  kref(6, "Libellé acompte", "€"),
  kref(7, "Dossier suivant", "🗂"),
  kref(8, "Capture", "📷"),
];
const SAM_KEYS = [
  kref(0, "Courrier type", "✉", "#1565C0"),
  kref(1, "Accusé réception", "✔", "#2E7D32"),
  kref(2, "Signature", "✍"),
  kref(3, "Enregistrer", "💾"),
  kref(4, "Copier", "⎘"),
  kref(5, "Coller", "⎗"),
];

// ------------------------------------------------------------------------------------------------- lecture de l'écran
const decode = (s) =>
  s
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(Number(d)))
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");

function dumpNodes(tries = 3) {
  let last = "";
  for (let i = 0; i < tries; i++) {
    const out = adb(["exec-out", "uiautomator", "dump", "/dev/stdout"], { timeout: 20_000 });
    const xml = out.slice(0, out.lastIndexOf("</hierarchy>") + 12);
    last = out.slice(0, 200);
    if (xml.includes("<hierarchy")) {
      const nodes = [];
      for (const m of xml.matchAll(/<node [^>]*>/g)) {
        const attr = (k) => decode(m[0].match(new RegExp(` ${k}="([^"]*)"`))?.[1] ?? "");
        const b = attr("bounds").match(/\d+/g)?.map(Number);
        if (!b) continue;
        nodes.push({ text: attr("text"), desc: attr("content-desc"), clickable: attr("clickable") === "true", b });
      }
      return nodes;
    }
  }
  throw new Error(`uiautomator dump ne répond pas (${last.trim() || "aucune sortie"})`);
}

const norm = (s) => s.replace(/\s+/g, " ").trim().toLowerCase();
/** sel : texte exact (texte ou description, sans casse), RegExp, ou { text|desc|re, nth }. */
function matcher(sel) {
  const s = typeof sel === "string" || sel instanceof RegExp ? { any: sel } : sel;
  const test = (v, want) => (want instanceof RegExp ? want.test(v) : norm(v) === norm(want));
  return (n) => {
    if (n.b[2] <= n.b[0] || n.b[3] <= n.b[1]) return false;
    if (s.any !== undefined) return (n.text && test(n.text, s.any)) || (n.desc && test(n.desc, s.any));
    if (s.text !== undefined) return test(n.text, s.text);
    if (s.desc !== undefined) return test(n.desc, s.desc);
    return false;
  };
}
const label = (sel) => (typeof sel === "string" || sel instanceof RegExp ? String(sel) : JSON.stringify(sel));

class ScreenError extends Error {}

/** Dernier toucher : la barre fixe s'efface à moitié après 60 s sans toucher. */
let touched = Date.now();
const touch = () => (touched = Date.now());

/** Outils de navigation d'un écran. */
function toolkit() {
  const t = {
    sleep,
    async find(sel, timeout = 8000) {
      const m = matcher(sel);
      const nth = typeof sel === "object" && !(sel instanceof RegExp) ? (sel.nth ?? 0) : 0;
      const end = Date.now() + timeout;
      for (;;) {
        const hits = dumpNodes().filter(m);
        // Les nœuds à cheval sur le bord sont écartés au profit d'un nœud entièrement visible.
        if (hits[nth]) return hits[nth];
        if (Date.now() > end) throw new ScreenError(`introuvable à l'écran : ${label(sel)}`);
        await sleep(600);
      }
    },
    async has(sel, timeout = 0) {
      try {
        await t.find(sel, timeout);
        return true;
      } catch {
        return false;
      }
    },
    async tap(sel, timeout) {
      const n = await t.find(sel, timeout);
      const x = Math.min(W - 2, Math.max(2, Math.round((n.b[0] + n.b[2]) / 2)));
      const y = Math.min(H - 2, Math.max(2, Math.round((n.b[1] + n.b[3]) / 2)));
      shell(`input tap ${x} ${y}`);
      touch();
      await sleep(900);
      return n;
    },
    async longPress(sel, ms = 1300) {
      const n = await t.find(sel);
      const x = Math.round((n.b[0] + n.b[2]) / 2);
      const y = Math.round((n.b[1] + n.b[3]) / 2);
      shell(`input swipe ${x} ${y} ${x} ${y} ${ms}`);
      touch();
      await sleep(900);
    },
    swipe(x1, y1, x2, y2, ms = 500) {
      shell(`input swipe ${x1} ${y1} ${x2} ${y2} ${ms}`);
      touch();
    },
    /** Défile vers le bas jusqu'à voir `sel`. */
    async scrollTo(sel, tries = 8) {
      for (let i = 0; i < tries; i++) {
        if (await t.has(sel)) return;
        t.swipe(640, 650, 640, 250, 500);
        await sleep(1200);
      }
      await t.find(sel, 1000);
    },
    /** Amène `sel` près du haut de l'écran (défilement lent, sans élan). */
    async alignTop(sel) {
      const n = await t.find(sel);
      const dy = n.b[1] - 70;
      if (dy > 30) {
        t.swipe(640, 700, 640, 700 - Math.min(dy, 600), 1500);
        await sleep(1200);
      }
    },
    key: (k) => shell(`input keyevent ${k}`),
    /** Page du rail (profil nommé). */
    profile: (name) => t.tap({ desc: name }),
  };
  return t;
}

// ---------------------------------------------------------------------------------------------------- capture et contrôle
function decodePng(buf) {
  if (buf.length < 100 || buf.readUInt32BE(0) !== 0x89504e47) throw new Error("pas un PNG");
  const width = buf.readUInt32BE(16);
  const height = buf.readUInt32BE(20);
  const bitDepth = buf[24];
  const colorType = buf[25];
  const bpp = colorType === 6 ? 4 : colorType === 2 ? 3 : 0;
  if (bitDepth !== 8 || !bpp) throw new Error(`PNG ${bitDepth} bits, type ${colorType} non géré`);
  const idat = [];
  for (let p = 8; p < buf.length; ) {
    const len = buf.readUInt32BE(p);
    if (buf.toString("latin1", p + 4, p + 8) === "IDAT") idat.push(buf.subarray(p + 8, p + 8 + len));
    p += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = width * bpp;
  const px = Buffer.alloc(stride * height);
  for (let y = 0; y < height; y++) {
    const f = raw[y * (stride + 1)];
    const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? px[y * stride + x - bpp] : 0;
      const b = y > 0 ? px[(y - 1) * stride + x] : 0;
      const c = x >= bpp && y > 0 ? px[(y - 1) * stride + x - bpp] : 0;
      const pr = f === 0 ? 0 : f === 1 ? a : f === 2 ? b : f === 3 ? (a + b) >> 1 : (() => {
        const p = a + b - c;
        const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      })();
      px[y * stride + x] = (line[x] + pr) & 255;
    }
  }
  return { width, height, bpp, px };
}

/** Vérifie un PNG : dimensions, image non noire (couleurs distinctes et écart-type de la luminance). */
function inspect(buf) {
  if (!buf.length) return { ok: false, why: "fichier vide" };
  let img;
  try {
    img = decodePng(buf);
  } catch (e) {
    return { ok: false, why: `PNG illisible (${e.message})` };
  }
  if (img.width !== W || img.height !== H) return { ok: false, why: `taille ${img.width}×${img.height} au lieu de ${W}×${H}` };
  const colors = new Set();
  let sum = 0;
  let sum2 = 0;
  let n = 0;
  for (let i = 0; i < img.px.length; i += img.bpp * 7) {
    const r = img.px[i], g = img.px[i + 1], b = img.px[i + 2];
    colors.add(((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3));
    const l = 0.299 * r + 0.587 * g + 0.114 * b;
    sum += l;
    sum2 += l * l;
    n++;
  }
  const mean = sum / n;
  const sd = Math.sqrt(Math.max(0, sum2 / n - mean * mean));
  if (colors.size < 25 || sd < 6) return { ok: false, why: `image quasi unie (${colors.size} couleurs, écart-type ${sd.toFixed(1)})` };
  return { ok: true };
}

const BRANDS =
  /\b(teams|outlook|excel|word|powerpoint|chrome|canva|hubspot|slack|spotify|discord|figma|notion|youtube|twitch|netflix|safari|firefox|edge|opera|zoom|webex|microsoft|google|apple|windows|macos|jira|gitlab|github|claude|orca|linkedin|instagram|facebook|whatsapp|vs ?code|docker)\b/gi;
const brandsOn = (nodes) => {
  const found = new Set();
  for (const n of nodes) for (const v of [n.text, n.desc]) for (const m of v.matchAll(BRANDS)) found.add(m[0].toLowerCase());
  return [...found];
};

const index = []; // { file, screen, persona, time, status, note }
const hashes = new Map();

/** Capture `<file>.png` : écrit dans un .tmp, vérifie, renomme. `brandsOk` : marques attendues (fonction de l'appli). */
async function capture(file, screen, ph, { brandsOk = [] } = {}) {
  // Barre fixe au repos : un toucher dans la marge la réveille (jamais juste après un toucher : il fermerait une boîte de dialogue).
  if (Date.now() - touched > 40_000) shell(`input tap ${W - 8} ${H - 8}`);
  await sleep(1800);
  const entry = { file: `${file}.png`, screen, persona: ph.persona, time: ph.timeLabel, status: "OK", note: "" };
  try {
    let nodes = [];
    try {
      nodes = dumpNodes(1);
    } catch {}
    let buf = Buffer.alloc(0);
    for (let i = 0; i < 3 && !buf.length; i++) buf = adb(["exec-out", "screencap", "-p"], { binary: true, timeout: 30_000 });
    const tmp = path.join(OUT, `${file}.png.tmp`);
    fs.writeFileSync(tmp, buf);
    const check = inspect(buf);
    const hash = crypto.createHash("sha256").update(buf).digest("hex");
    const twin = [...hashes].find(([, h]) => h === hash)?.[0];
    if (!check.ok) {
      entry.status = "ÉCHEC";
      entry.note = check.why;
    } else if (twin) {
      entry.status = "ÉCHEC";
      entry.note = `identique à ${twin}`;
    }
    if (entry.status === "OK") {
      fs.renameSync(tmp, path.join(OUT, `${file}.png`));
      hashes.set(`${file}.png`, hash);
      const found = brandsOn(nodes);
      const expected = found.filter((b) => brandsOk.includes(b));
      const stray = found.filter((b) => !brandsOk.includes(b));
      if (expected.length) entry.note = `marque du code de l'appli : ${expected.join(", ")}`;
      if (stray.length) {
        entry.status = "À VÉRIFIER";
        entry.note = `marque à l'écran : ${stray.join(", ")}${expected.length ? ` (+ ${entry.note})` : ""}`;
      }
    } else {
      fs.rmSync(tmp, { force: true });
    }
  } catch (e) {
    entry.status = "ÉCHEC";
    entry.note = e.message.split("\n")[0];
  }
  log(`${entry.status === "OK" ? "ok    " : entry.status.toLowerCase()} ${entry.file}${entry.note ? ` (${entry.note})` : ""}`);
  index.push(entry);
}

// ----------------------------------------------------------------------------------------------------------------- phases
/**
 * Une phase = un backend de démo (persona), une heure, un thème, une disposition, puis des écrans.
 * Chaque écran : { file, name, run(t, ph), brandsOk? } ; une erreur de navigation = ÉCHEC dans l'index, la phase continue.
 */
const PHASES = [
  {
    id: "sylvie",
    persona: "sylvie",
    time: [8, 30],
    layout: (c) => layoutJson(["Ma journée", "Deck"], [...journee(0, keysCfg(c, SYLVIE_KEYS.slice(0, 6))), wd("apps", "W", 1, 0, 0), wd("dayline", "W", 1, 0, 1), wd("keys", "XL", 1, 0, 2, keysCfg(c, SYLVIE_KEYS))]),
    screens: [
      { file: "sylvie-accueil-ma-journee", name: "Accueil, Ma journée", run: async () => {} },
      { file: "sylvie-deck-touches-compta", name: "Profil Deck, touches de comptabilité", run: async (t) => t.profile("Deck") },
      {
        file: "sylvie-editeur-touche-macro",
        name: "Éditeur de la macro Export FEC",
        restart: true,
        run: async (t, ph) => {
          await control("/media", { on: false });
          await t.profile("Deck");
          await t.longPress({ text: "Export FEC" });
          await t.find(/enregistrer/i);
        },
        after: async () => control("/media", { on: true }),
      },
    ],
  },
  {
    id: "gwen",
    persona: "gwen",
    time: [10, 30],
    layout: (c) => layoutJson(["Ma journée", "Outils"], [...journee(0, defaultKeys), wd("apps", "XL", 1, 0, 0), wd("agenda", "L", 1, 0, 2), wd("tasks", "L", 1, 2, 2)]),
    screens: [
      { file: "gwen-accueil-rejoindre", name: "Agenda, bouton Rejoindre le webinaire", run: async (t) => t.find(/rejoindre/i) },
      { file: "gwen-applis", name: "Profil Outils, toutes les applis", run: async (t) => t.profile("Outils") },
      {
        file: "gwen-reunion-en-appel",
        name: "Réunion en appel",
        restart: true,
        run: async (t) => {
          await control("/os", {
            inCall: ["Visio"],
            voice: { Visio: { found: true, connected: true, streaming: false, channel: null, people: [], muted: false, deafened: false, mic: null, deaf: null } },
          });
          // L'écran d'un appel actif bouge en continu : pas de lecture par uiautomator, l'appli bascule seule sur Réunion.
          await t.sleep(9000);
        },
        after: async () => control("/os", { inCall: [], voice: {} }),
      },
    ],
  },
  {
    id: "camille",
    persona: "camille",
    time: [12, 30],
    layout: (c) =>
      layoutJson(["Travail", "Perso"], [
        ...journee(0, defaultKeys),
        wd("hero", "W", 1, 0, 0, CITY),
        wd("dayline", "W", 1, 0, 1),
        wd("focus", "V", 1, 0, 2),
        wd("media", "M", 1, 1, 2),
        wd("weather", "M", 1, 1, 3, CITY),
        wd("water", "S", 1, 3, 2),
      ]),
    screens: [
      { file: "camille-profil-travail", name: "Profil Travail", run: async () => {} },
      { file: "camille-profil-perso", name: "Profil Perso", run: async (t) => t.profile("Perso") },
      {
        file: "camille-reunion-micro-coupe",
        name: "Réunion, micro coupé",
        restart: true,
        run: async (t) => {
          await control("/os", {
            inCall: ["Visio"],
            voice: { Visio: { found: true, connected: true, streaming: false, channel: null, people: [], muted: true, deafened: false, mic: null, deaf: null } },
          });
          await t.sleep(5000);
          await t.find(/micro/i, 20_000);
        },
        after: async () => control("/os", { inCall: [], voice: {} }),
      },
      { file: "camille-focus", name: "Focus 25:00", restart: true, run: async (t) => (await t.tap({ desc: "Focus" }), t.find("25:00")) },
    ],
  },
  {
    id: "aimee",
    persona: "aimee",
    time: [14, 29],
    layout: (c) => layoutJson(["Ma journée", "Fenêtres"], [...journee(0, defaultKeys), wd("apps", "W", 1, 0, 0), wd("dayline", "W", 1, 0, 1), wd("open", "XL", 1, 0, 2)]),
    screens: [
      { file: "aimee-accueil-ma-journee", name: "Ma journée, réunion dans 1 min", run: async (t) => t.find(/dans 1 min/i) },
      {
        file: "aimee-fichiers-ordi",
        name: "Fichiers de l'ordi",
        restart: true,
        run: async (t) => {
          await t.tap({ desc: "Ordi" });
          await t.tap("Fichiers de l'ordi");
          await t.find("Dossier partagé");
        },
      },
      { file: "aimee-ouvertes", name: "Fenêtres ouvertes sur les écrans", run: async (t) => t.profile("Fenêtres") },
    ],
  },
  {
    id: "sam",
    persona: "sam",
    time: [17, 0],
    theme: "CONTRASTE",
    layout: (c) => layoutJson(["Accueil", "Touches"], [...journee(0, keysCfg(c, SAM_KEYS)), wd("apps", "W", 1, 0, 0), wd("dayline", "W", 1, 0, 1), wd("keys", "XL", 1, 0, 2, keysCfg(c, SAM_KEYS))]),
    screens: [
      { file: "sam-accueil-contraste", name: "Accueil, Contraste élevé", run: async () => {} },
      { file: "sam-grandes-touches", name: "Grandes touches, Contraste élevé", run: async (t) => t.profile("Touches") },
      {
        file: "sam-reglages-accessibilite",
        name: "Réglages, Accessibilité, taille du texte",
        restart: true,
        reduceMotion: false,
        run: async (t) => {
          await t.tap({ desc: "Réglages" });
          await t.scrollTo("Taille du texte");
          await t.alignTop({ text: "Accessibilité" });
        },
      },
      {
        file: "sam-reglages-texte-grand",
        name: "Réglages, texte « Très grande »",
        restart: true,
        reduceMotion: false,
        run: async (t) => {
          await t.tap({ desc: "Réglages" });
          await t.scrollTo("Taille du texte");
          await t.tap("Très grande");
          await t.sleep(2500);
          await t.scrollTo("Taille du texte");
          await t.alignTop({ text: "Accessibilité" });
        },
        after: async () => shell(`am force-stop ${PKG}`),
      },
    ],
    // Le texte « Très grande » est enregistré : l'écran suivant repart d'une préférence neuve.
  },
  {
    id: "sacha",
    persona: "sacha",
    time: [19, 10],
    theme: "CADRAN_NUIT",
    layout: (c) =>
      layoutJson(["Accueil", "Agents", "Revues", "Tickets"], [
        ...journee(0, defaultKeys),
        wd("hero", "W", 1, 0, 0, CITY),
        wd("dayline", "W", 1, 0, 1),
        wd("agents", "XL", 1, 0, 2),
        wd("hero", "W", 2, 0, 0, CITY),
        wd("dayline", "W", 2, 0, 1),
        wd("gitlab", "XL", 2, 0, 2),
        wd("hero", "W", 3, 0, 0, CITY),
        wd("dayline", "W", 3, 0, 1),
        wd("jira", "XL", 3, 0, 2),
      ]),
    screens: [
      { file: "sacha-agents", name: "Agents Claude Code", brandsOk: ["claude"], run: async (t) => t.profile("Agents") },
      { file: "sacha-gitlab", name: "GitLab : mes MR et à relire", brandsOk: ["gitlab"], run: async (t) => t.profile("Revues") },
      { file: "sacha-jira", name: "Jira : tickets en colonnes", brandsOk: ["jira"], run: async (t) => t.profile("Tickets") },
    ],
  },
  {
    id: "commun",
    persona: "gwen",
    time: [11, 0],
    layout: (c) =>
      layoutJson(["Ma journée", "Son"], [
        ...journee(0, defaultKeys),
        wd("mixer", "XL", 1, 0, 0),
        wd("media", "L", 1, 0, 2),
        wd("focus", "L", 1, 2, 2),
      ]),
    screens: [
      { file: "commun-accueil-jour", name: "Accueil, Cadran jour", run: async () => {} },
      { file: "commun-barre-profils", name: "Barre et liste des profils", restart: true, run: async (t) => t.tap({ desc: "Ajouter un écran" }) },
      {
        file: "commun-modeles-profil",
        name: "Ajouter un écran, modèles",
        restart: true,
        run: async (t) => {
          await t.tap({ desc: "Ajouter un écran" });
          await t.tap(/depuis un modèle/i);
          await t.find("Bureau");
        },
      },
      { file: "commun-son", name: "Son : console de mixage", restart: true, run: async (t) => t.profile("Son") },
    ],
  },
  {
    id: "commun-nuit",
    persona: "sacha",
    time: [19, 10],
    theme: "CADRAN_NUIT",
    layout: (c) =>
      layoutJson(["Ma journée", "Média"], [...journee(0, defaultKeys), wd("hero", "W", 1, 0, 0, CITY), wd("dayline", "W", 1, 0, 1), wd("media", "L", 1, 0, 2), wd("weather", "M", 1, 2, 2, CITY), wd("water", "M", 1, 2, 3)]),
    screens: [
      { file: "commun-accueil-nuit", name: "Accueil, Cadran nuit", run: async () => {} },
      { file: "commun-media", name: "Média : lecture en cours", restart: true, run: async (t) => t.profile("Média") },
    ],
  },
];

async function launchApp(t) {
  shell(`am force-stop ${PKG}`);
  shell(`am start -n ${PKG}/.MainActivity`);
  touch();
  await waitFor(() => dumpNodes().some(matcher({ desc: "Parler" })), 40_000, "affichage de l'appli");
  await sleep(2500);
}

async function runPhase(ph) {
  const [hh, mm] = ph.time;
  ph.timeLabel = `${hh}:${String(mm).padStart(2, "0")}`;
  log(`=== ${ph.id} (${ph.persona}, ${ph.timeLabel}${ph.theme ? `, ${ph.theme}` : ""}) ===`);
  await startBackend(ph.persona, { hh, mm });
  const choices = Object.fromEntries(((await api("GET", "/plugins")).plugins ?? []).map((p) => [p.id, true]));
  await api("POST", "/plugins", { choices });
  adb(["reverse", `tcp:${ports.main}`, `tcp:${ports.main}`]);
  reversed = ports.main;
  const t = toolkit();
  const prefs = (screen = {}) => writePrefs({ theme: ph.theme ?? "CADRAN", scale: 1.0, layout: ph.layout, reduceMotion: screen.reduceMotion ?? true });
  prefs();
  setTime(hh, mm);
  await launchApp(t);
  let fresh = true;
  for (const s of ph.screens) {
    log(`-- ${s.file}`);
    try {
      if (s.restart && (!fresh || s.reduceMotion === false)) {
        prefs(s);
        setTime(hh, mm);
        await launchApp(t);
      }
      setTime(hh, mm);
      await sleep(1200);
      fresh = false;
      await s.run(t, ph);
      await capture(s.file, s.name, ph, { brandsOk: s.brandsOk });
    } catch (e) {
      log(`ÉCHEC ${s.file} : ${e.message}`);
      index.push({ file: `${s.file}.png`, screen: s.name, persona: ph.persona, time: ph.timeLabel, status: "ÉCHEC", note: e.message.split("\n")[0] });
      fresh = false;
      try {
        prefs();
        await launchApp(t);
      } catch {}
    } finally {
      await s.after?.();
    }
  }
}

/** Lignes de l'index précédent : un lancement partiel (--only) garde celles des autres phases. */
function previousRows() {
  const file = path.join(OUT, "INDEX.md");
  if (!fs.existsSync(file)) return new Map();
  const rows = new Map();
  for (const line of fs.readFileSync(file, "utf8").split("\n")) {
    const m = line.match(/^\| (\S+\.png) \| (.*?) \| (\S+) \| (\S+) \| (.*?) \| (.*?) \|$/);
    if (m) rows.set(m[1], { file: m[1], screen: m[2], persona: m[3], time: m[4], status: m[5], note: m[6] });
  }
  return rows;
}

function writeIndex(durationS) {
  const before = previousRows();
  const fresh = new Map(index.map((e) => [e.file, e]));
  const all = PHASES.flatMap((ph) => ph.screens.map((s) => `${s.file}.png`));
  const rows = all.map((f) => fresh.get(f) ?? before.get(f)).filter((e) => e && fs.existsSync(path.join(OUT, e.file)));
  const ok = rows.filter((e) => e.status === "OK").length;
  const md = `# Captures de l'émulateur (companion_api29, 1280×800)

Générées par \`video/scripts/captures.mjs\` (voir \`captures.md\`) : vraies captures de l'appli debug, backend de démo (\`video/demo-backend.mts\`, faux pilotage, données factices). Jour affiché : ${DAY}. Horloge de l'émulateur réglée à l'heure du persona.

${ok} sur ${rows.length} captures OK. Dernier lancement : ${index.length} captures en ${Math.round(durationS)} s${only ? ` (--only ${only.join(",")})` : ""}.

| Fichier | Écran | Persona | Heure | Statut | Note |
|---|---|---|---|---|---|
${rows.map((e) => `| ${e.file} | ${e.screen} | ${e.persona} | ${e.time} | ${e.status} | ${e.note} |`).join("\n")}
`;
  fs.writeFileSync(path.join(OUT, "INDEX.md"), md);
}

// ------------------------------------------------------------------------------------------------------------------ main
async function main() {
  const started = Date.now();
  fs.mkdirSync(OUT, { recursive: true });
  const phases = only ? PHASES.filter((p) => only.includes(p.id) || (p.id.startsWith("commun") && only.includes("commun"))) : PHASES;
  if (!phases.length) throw new Error(`aucune phase pour --only ${only}`);
  if (!flag("--no-build")) {
    log("construction de l'APK debug (Gradle)");
    const r = spawnSync("./gradlew", [":app:assembleDebug", "-q"], { cwd: ROOT, stdio: "inherit" });
    if (r.status !== 0) throw new Error("Gradle a échoué");
  }
  if (!fs.existsSync(APK)) throw new Error(`APK introuvable : ${APK}`);
  if (!reuseSerial) await startEmulator();
  await prepareEmulator();
  log("installation de l'APK");
  const inst = adb(["install", "-r", "-g", APK], { timeout: 120_000 });
  if (!inst.includes("Success")) throw new Error(`installation : ${inst}`);
  for (const ph of phases) {
    try {
      await runPhase(ph);
    } catch (e) {
      log(`phase ${ph.id} interrompue : ${e.message}`);
      for (const s of ph.screens.filter((s) => !index.some((i) => i.file === `${s.file}.png`)))
        index.push({ file: `${s.file}.png`, screen: s.name, persona: ph.persona, time: ph.timeLabel ?? "", status: "ÉCHEC", note: `phase interrompue : ${e.message.split("\n")[0]}` });
    }
  }
  writeIndex((Date.now() - started) / 1000);
  const bad = index.filter((e) => e.status !== "OK" && !e.note.startsWith("marque du code"));
  keepTmp = bad.length > 0;
  log(`terminé en ${Math.round((Date.now() - started) / 1000)} s : ${index.length - bad.length} sur ${index.length} captures OK`);
  for (const e of bad) log(`  ${e.status} ${e.file} : ${e.note}`);
}

try {
  await main();
} catch (e) {
  console.error(e);
  keepTmp = true;
  process.exitCode = 1;
} finally {
  cleanup();
}

#!/usr/bin/env node
/**
 * Refait toutes les captures d'écran de l'appli Mate (émulateur, démo par personas de l'appli) pour les vidéos.
 * Voir video/scripts/captures.md. Lancer depuis n'importe où : `node video/scripts/captures.mjs [options]`.
 *
 *   --no-build           garde l'APK debug déjà construit
 *   --only nour,sam      seulement ces phases (commun nour gwen camille aimee sam sacha)
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
const PKG = "app.mate";
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
const DAY =
  dateArg ?? `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
if (!/^\d{4}-\d{2}-\d{2}$/.test(DAY)) throw new Error(`--date attendu AAAA-MM-JJ, reçu ${DAY}`);
if (reuseSerial && !/^emulator-\d+$/.test(reuseSerial))
  throw new Error("--serial doit être de la forme emulator-<port> (jamais une vraie tablette)");

const log = (msg) => console.log(`[${new Date().toTimeString().slice(0, 8)}] ${msg}`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sdk =
  fs
    .readFileSync(path.join(ROOT, "local.properties"), "utf8")
    .match(/^sdk\.dir=(.*)$/m)?.[1]
    ?.trim() ?? path.join(os.homedir(), "Library/Android/sdk");
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
  while (queue.length) for (const c of children.get(queue.shift()) ?? []) (out.push(c), queue.push(c));
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
  const ours = (k) =>
    [p.pid, 1, ...kids].includes(
      Number(
        spawnSync("ps", ["-o", "ppid=", "-p", String(k)], {
          encoding: "utf8",
        }).stdout.trim(),
      ),
    );
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
          shell(
            `date ${two(n.getMonth() + 1)}${two(n.getDate())}${two(n.getHours())}${two(n.getMinutes())}${n.getFullYear()}.${two(n.getSeconds())}`,
          );
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
  const r = spawnSync(ADB, ["-s", SERIAL, ...a], {
    encoding: binary ? "buffer" : "utf8",
    timeout,
    maxBuffer: 1 << 28,
    input,
  });
  if (r.error) throw r.error;
  return binary ? r.stdout : r.stdout.toString();
}
const shell = (cmd, o) => adb(["shell", cmd], o);

function freePort(start, end, step = 1, extra = 0) {
  const tryBind = (host, port) => {
    const r = spawnSync(process.execPath, [
      "-e",
      `const s=require("net").createServer();s.on("error",()=>process.exit(1));s.listen(${port},"${host}",()=>s.close(()=>process.exit(0)))`,
    ]);
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
    [
      "-avd",
      AVD,
      "-port",
      String(port),
      "-no-window",
      "-no-audio",
      "-no-boot-anim",
      "-no-snapshot",
      "-read-only",
      "-gpu",
      "swiftshader_indirect",
    ],
    {
      stdio: ["ignore", fd, fd],
      env: { ...process.env, ANDROID_SDK_ROOT: sdk },
    },
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

async function startBackend(persona, at) {
  await stopBackend();
  ports ??= { main: freePort(3970, 3999) };
  const dir = path.join(TMP, "backend");
  fs.rmSync(dir, { recursive: true, force: true });
  const env = {
    ...process.env,
    DEMO_PORT: String(ports.main),
    DEMO_DIR: dir,
    DEMO_NAME: "PC du bureau",
    ...(at
      ? {
          DEMO_NOW: `${DAY}T${String(at.hh).padStart(2, "0")}:${String(at.mm).padStart(2, "0")}:00`,
        }
      : {}),
  };
  const logFile = path.join(TMP, `backend-${persona.toLowerCase()}.log`);
  const fd = fs.openSync(logFile, "w");
  const child = spawn(process.execPath, ["--import", "tsx", "../video/demo-backend.mts"], {
    cwd: BACKEND_DIR,
    env,
    stdio: ["ignore", fd, fd],
  });
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
    headers: {
      authorization: `Bearer ${TOKEN}`,
      ...(body ? { "content-type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  return res.json().catch(() => ({}));
};
// -------------------------------------------------------------------------------------------- préférences de la tablette
const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

/** Écrit shared_prefs/companion.xml : ordinateur appairé (adresse locale + jeton), thème. Le persona est choisi ensuite dans l'appli. */
function writePrefs({ theme = "CADRAN", scale = 1.0, reduceMotion = true } = {}) {
  const comp = [
    {
      id: backendInfo.id,
      name: backendInfo.name,
      url: `http://127.0.0.1:${ports.main}`,
      token: TOKEN,
      os: backendInfo.os,
      lastUsedMs: 1788000000000,
      pin: null,
    },
  ];
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
  ];
  const xml = `<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n  ${items.join("\n  ")}\n</map>\n`;
  shell(`am force-stop ${PKG}`);
  adb(["push", writeTmp("companion.xml", xml), "/data/local/tmp/companion.xml"]);
  const uid = shell(`stat -c %u /data/data/${PKG}`).trim();
  const d = `/data/data/${PKG}/shared_prefs`;
  shell(
    `mkdir -p ${d} && rm -f ${d}/*.xml && cp /data/local/tmp/companion.xml ${d}/companion.xml && chown ${uid}:${uid} ${d} ${d}/companion.xml && chmod 660 ${d}/companion.xml && restorecon -R ${d}`,
  );
}

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
    const out = adb(["exec-out", "uiautomator", "dump", "/dev/stdout"], {
      timeout: 20_000,
    });
    const xml = out.slice(0, out.lastIndexOf("</hierarchy>") + 12);
    last = out.slice(0, 200);
    if (xml.includes("<hierarchy")) {
      const nodes = [];
      for (const m of xml.matchAll(/<node [^>]*>/g)) {
        const attr = (k) => decode(m[0].match(new RegExp(` ${k}="([^"]*)"`))?.[1] ?? "");
        const b = attr("bounds").match(/\d+/g)?.map(Number);
        if (!b) continue;
        nodes.push({
          text: attr("text"),
          desc: attr("content-desc"),
          clickable: attr("clickable") === "true",
          b,
        });
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

/** Places des entrées de la barre (description vers bornes), relevées à l'arrivée du persona. */
const railSpots = new Map();

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
        if (Date.now() > end) {
          const seen = dumpNodes(1)
            .map((n) => n.text || n.desc)
            .filter(Boolean)
            .slice(0, 40)
            .join(" | ");
          throw new ScreenError(`introuvable à l'écran : ${label(sel)} (visible : ${seen})`);
        }
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
    /**
     * Page du rail (profil nommé). Un écran qui bouge sans cesse (lecture, chrono) peut empêcher uiautomator de lire
     * l'écran : on touche alors la place relevée à l'arrivée du persona.
     */
    async profile(name) {
      try {
        await t.tap({ desc: name }, 4000);
      } catch (e) {
        const b = railSpots.get(name);
        if (!b) throw e;
        shell(`input tap ${Math.round((b[0] + b[2]) / 2)} ${Math.round((b[1] + b[3]) / 2)}`);
        touch();
        await sleep(900);
      }
    },
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
  for (let p = 8; p < buf.length;) {
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
      const pr =
        f === 0
          ? 0
          : f === 1
            ? a
            : f === 2
              ? b
              : f === 3
                ? (a + b) >> 1
                : (() => {
                    const p = a + b - c;
                    const pa = Math.abs(p - a),
                      pb = Math.abs(p - b),
                      pc = Math.abs(p - c);
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
  if (img.width !== W || img.height !== H)
    return {
      ok: false,
      why: `taille ${img.width}×${img.height} au lieu de ${W}×${H}`,
    };
  const colors = new Set();
  let sum = 0;
  let sum2 = 0;
  let n = 0;
  for (let i = 0; i < img.px.length; i += img.bpp * 7) {
    const r = img.px[i],
      g = img.px[i + 1],
      b = img.px[i + 2];
    colors.add(((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3));
    const l = 0.299 * r + 0.587 * g + 0.114 * b;
    sum += l;
    sum2 += l * l;
    n++;
  }
  const mean = sum / n;
  const sd = Math.sqrt(Math.max(0, sum2 / n - mean * mean));
  if (colors.size < 25 || sd < 6)
    return {
      ok: false,
      why: `image quasi unie (${colors.size} couleurs, écart-type ${sd.toFixed(1)})`,
    };
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
  const entry = {
    file: `${file}.png`,
    screen,
    persona: ph.persona ?? "—",
    time: ph.timeLabel,
    status: "OK",
    note: "",
  };
  try {
    let nodes = [];
    try {
      nodes = dumpNodes(1);
    } catch {}
    let buf = Buffer.alloc(0);
    for (let i = 0; i < 3 && !buf.length; i++)
      buf = adb(["exec-out", "screencap", "-p"], {
        binary: true,
        timeout: 30_000,
      });
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
      const expected = found.filter((b) => brandsOk === "all" || brandsOk.includes(b));
      const stray = found.filter((b) => brandsOk !== "all" && !brandsOk.includes(b));
      if (expected.length) entry.note = `marques de la démo de l'appli : ${expected.join(", ")}`;
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
 * Une phase = un backend de démo, une heure (`live` : l'heure réelle, pour l'alerte de réunion « dans N min » que le
 * backend calcule sur sa propre horloge), puis des écrans. Le persona est choisi dans l'appli (Réglages > Démo) : c'est
 * elle qui pose écrans de la barre, thème, texte, touches, agenda, GitLab, Jira. Les écrans se suivent sans relancer
 * l'appli (une relance effacerait le choix). Chaque écran : { file, name, run(t, ph) } ; une erreur de navigation =
 * ÉCHEC dans l'index, la phase continue. Les marques viennent des données de démo de l'appli : notées dans l'index.
 */
const PHASES = [
  {
    id: "commun",
    persona: null,
    time: [9, 0],
    screens: [
      {
        file: "commun-choix-persona",
        name: "Réglages > Démo : les six personas",
        run: async (t) => {
          await openPersonaDialog(t);
          touch();
        },
      },
    ],
  },
  {
    id: "nour",
    persona: "Nour",
    time: [8, 30],
    screens: [
      {
        file: "nour-excel",
        name: "Écran Excel : touches Excel, libellés, fenêtres",
        run: async () => {},
      },
      {
        file: "nour-dossiers",
        name: "Écran Dossiers : touches, applis, fenêtres",
        run: async (t) => t.profile("Dossiers"),
      },
      {
        file: "nour-cloture",
        name: "Accueil : widget Clôture (checklist, échéance)",
        run: async (t) => t.profile("Accueil"),
      },
    ],
  },
  {
    id: "gwen",
    persona: "Gwen",
    time: [10, 30],
    screens: [
      {
        file: "gwen-accueil",
        name: "Accueil : agenda, tâches, modèles de messages",
        run: async () => {},
      },
      {
        file: "gwen-presentation",
        name: "Écran Présentation : diapos, chrono, micro",
        run: async (t) => t.profile("Présentation"),
      },
      {
        file: "gwen-campagnes",
        name: "Écran Campagnes : réseaux, modèles, applis",
        run: async (t) => t.profile("Campagnes"),
      },
    ],
  },
  {
    id: "camille",
    persona: "Camille",
    time: [12, 30],
    screens: [
      {
        file: "camille-travail",
        name: "Écran Travail : Je m'absente, Focus, prochain rendez-vous",
        run: async () => {},
      },
      {
        file: "camille-absente",
        name: "Je m'absente : absence en cours, Reprendre",
        run: async (t) => {
          // Le titre du widget porte le même texte : le bouton est le second.
          await t.tap({ text: "Je m'absente", nth: 1 });
          await t.find("Reprendre");
        },
      },
      {
        file: "camille-maison",
        name: "Écran Maison : touches de la maison, média, tâches",
        run: async (t) => {
          await t.tap("Reprendre");
          await t.profile("Maison");
        },
      },
    ],
  },
  {
    id: "aimee",
    persona: "Aimée",
    time: [14, 29],
    // L'horloge tourne librement : l'alerte part à 14:30, 5 min avant la réunion déplacée à 14:35.
    flow: true,
    async afterChoose(t) {
      // La démo pose « Point client Boréal » 6 min après le choix, à l'heure de l'ordinateur : on le remet à 14:35 du jour affiché.
      const { events = [] } = await api("GET", "/calendar?days=1");
      const ev = events.find((e) => e.title === "Point client Boréal" && e.localId);
      if (!ev) throw new Error("réunion « Point client Boréal » introuvable dans l'agenda");
      const start = new Date(`${DAY}T14:35:00`).getTime();
      await api("PUT", `/calendar/events/${encodeURIComponent(ev.localId)}`, {
        title: ev.title,
        startMs: start,
        endMs: start + 30 * 60_000,
        allDay: false,
      });
      // L'appli relit l'agenda au démarrage : relance sans toucher aux préférences (le persona reste choisi).
      setTime(14, 29);
      await launchApp(t);
      await sleep(3000);
    },
    screens: [
      {
        file: "aimee-accueil",
        name: "Accueil : prochaine réunion, agenda, tâches",
        run: async () => {},
      },
      {
        file: "aimee-alerte",
        name: "Alerte plein écran 5 min avant la réunion",
        run: async (t) => {
          await t.find("Touche l'écran pour fermer", 150_000);
          // Pas de toucher de réveil de la barre avant la capture : il fermerait l'alerte.
          touch();
        },
      },
      {
        file: "aimee-projets",
        name: "Écran Projets : dossiers d'un projet, fenêtres",
        run: async (t) => {
          shell(`input tap ${W / 2} ${H / 2}`);
          await t.sleep(1200);
          await t.profile("Projets");
        },
      },
      {
        file: "aimee-jira",
        name: "Écran Jira : tickets en colonnes",
        run: async (t) => t.profile("Jira"),
      },
    ],
  },
  {
    id: "sam",
    persona: "Sam",
    time: [17, 0],
    screens: [
      {
        file: "sam-accueil",
        name: "Accueil : Contraste élevé, texte agrandi, grandes touches",
        run: async () => {},
      },
      {
        file: "sam-reglages-retour-parle",
        name: "Réglages, Accessibilité : retour parlé, taille du texte",
        run: async (t) => {
          await openSettings();
          // Les interrupteurs n'exposent pas leur libellé à uiautomator : on cherche le texte d'aide sous l'interrupteur.
          await t.scrollTo(/Chaque touche lancée est annoncée/);
          await t.alignTop(/Lire les bandeaux à voix haute|Plus rien ne bouge en continu/);
        },
      },
    ],
  },
  {
    id: "sacha",
    persona: "Sacha",
    time: [19, 10],
    screens: [
      {
        file: "sacha-code",
        name: "Écran Code : touches Git, tests, déploiement, agents",
        run: async () => {},
      },
      {
        file: "sacha-gitlab",
        name: "Écran GitLab : MR et pipelines (console de déploiement)",
        run: async (t) => t.profile("GitLab"),
      },
      {
        file: "sacha-jira",
        name: "Écran Jira : tickets en colonnes",
        run: async (t) => t.profile("Jira"),
      },
    ],
  },
];

/** L'icône des Réglages est rognée en bas de la barre (le toucher n'y arrive pas) : l'ordre `mode` du compagnon l'ouvre. */
async function openSettings() {
  await waitFor(
    async () => (await api("POST", "/tablet/command", { type: "mode", mode: "settings" })).ok,
    30_000,
    "ouverture des Réglages",
    2000,
  );
  await sleep(1500);
}

/** Réglages > Démo > Choisir un persona : ouvre la boîte des six personas. */
async function openPersonaDialog(t) {
  await openSettings();
  await t.scrollTo("Choisir un persona");
  // Le défilement a de l'élan : on retouche le bouton tant que la boîte des personas n'est pas ouverte.
  await waitFor(
    async () => {
      await sleep(1200);
      if (await t.has(/choisis un persona/)) return true;
      await t.tap("Choisir un persona");
      return t.has(/choisis un persona/, 3000);
    },
    40_000,
    "boîte des personas",
    500,
  );
}

/** Réglages > Démo > Choisir un persona : l'appli pose écrans, thème, texte et réglages, le compagnon pose les données. */
async function choosePersona(t, name) {
  await openPersonaDialog(t);
  await t.tap({ text: name });
  // L'activité est recréée : on attend la barre, puis la fin des chargements (agenda, GitLab, Jira).
  await sleep(2500);
  await waitFor(() => dumpNodes().some(matcher({ desc: "Parler" })), 40_000, "retour sur l'Accueil du persona");
  railSpots.clear();
  for (const n of dumpNodes()) if (n.desc && n.b[0] < 130) railSpots.set(n.desc, n.b);
  await sleep(4000);
}

async function launchApp(t) {
  shell(`am force-stop ${PKG}`);
  shell(`am start -n ${PKG}/app.mate.MainActivity`);
  touch();
  await waitFor(() => dumpNodes().some(matcher({ desc: "Parler" })), 40_000, "affichage de l'appli");
  await sleep(2500);
}

async function runPhase(ph) {
  const live = ph.time === "live";
  const now = new Date();
  const [hh, mm] = live ? [now.getHours(), now.getMinutes()] : ph.time;
  ph.timeLabel = live ? "heure réelle" : `${hh}:${String(mm).padStart(2, "0")}`;
  log(`=== ${ph.id} (${ph.persona ?? "sans persona"}, ${ph.timeLabel}) ===`);
  await startBackend(ph.persona ?? "commun", live ? null : { hh, mm });
  // Plugins déjà choisis : sinon l'écran de première utilisation recouvre l'appli. Le persona coupe ensuite les siens.
  const choices = Object.fromEntries(((await api("GET", "/plugins")).plugins ?? []).map((p) => [p.id, true]));
  await api("POST", "/plugins", { choices });
  adb(["reverse", `tcp:${ports.main}`, `tcp:${ports.main}`]);
  reversed = ports.main;
  const t = toolkit();
  writePrefs();
  // L'heure réelle n'est posée qu'une fois : la refixer à chaque écran arrêterait l'horloge de la tablette.
  const pin = () => !live && !ph.flow && setTime(hh, mm);
  setTime(hh, mm);
  await launchApp(t);
  if (ph.persona) {
    try {
      await choosePersona(t, ph.persona);
    } catch (e) {
      throw new Error(`choix du persona ${ph.persona} : ${e.message}`);
    }
  }
  await ph.afterChoose?.(t);
  for (const s of ph.screens) {
    log(`-- ${s.file}`);
    try {
      pin();
      await sleep(1200);
      await s.run(t, ph);
      await capture(s.file, s.name, ph, { brandsOk: "all" });
    } catch (e) {
      log(`ÉCHEC ${s.file} : ${e.message}`);
      index.push({
        file: `${s.file}.png`,
        screen: s.name,
        persona: ph.persona ?? "—",
        time: ph.timeLabel,
        status: "ÉCHEC",
        note: e.message.split("\n")[0],
      });
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
    if (m)
      rows.set(m[1], {
        file: m[1],
        screen: m[2],
        persona: m[3],
        time: m[4],
        status: m[5],
        note: m[6],
      });
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
  const phases = only ? PHASES.filter((p) => only.includes(p.id)) : PHASES;
  if (!phases.length) throw new Error(`aucune phase pour --only ${only}`);
  if (!flag("--no-build")) {
    log("construction de l'APK debug (Gradle)");
    const r = spawnSync("./gradlew", [":app:assembleDebug", "-q"], {
      cwd: ROOT,
      stdio: "inherit",
    });
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
        index.push({
          file: `${s.file}.png`,
          screen: s.name,
          persona: ph.persona ?? "—",
          time: ph.timeLabel ?? "",
          status: "ÉCHEC",
          note: `phase interrompue : ${e.message.split("\n")[0]}`,
        });
    }
  }
  writeIndex((Date.now() - started) / 1000);
  const bad = index.filter((e) => e.status !== "OK" && !e.note.startsWith("marques de la démo"));
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

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { AnswerError } from "../src/agents.ts";
import { createDesktop, spotOf, titleMatches, WINDOWS_HOSTS, type DeskLookup, type DeskWindow } from "../src/agents-desktop.ts";

const win = (hwnd: number, cls: string, title = "", exe = "C:\\x.exe"): DeskWindow => ({ hwnd, pid: hwnd * 10, exe, title, cls });
const lookup = (part: Partial<DeskLookup>): DeskLookup => ({ console: null, chain: [], fallback: null, ...part });
const NAMES = ["Corrige le parseur"];

describe("fenêtre d'une session sous Windows", () => {
  it("titleMatches : titre de Claude (symbole d'état ôté), égal, sans casse ; jamais « contient »", () => {
    assert.equal(titleMatches("✳ Corrige le parseur", NAMES), true);
    assert.equal(titleMatches("⠐ corrige LE parseur ", NAMES), true);
    assert.equal(titleMatches("Corrige le parseur - PowerShell", NAMES), false);
    assert.equal(titleMatches("C:\\work\\projet", ["projet"]), false);
    assert.equal(titleMatches("abc", ["abc"]), false, "nom trop court");
    assert.equal(titleMatches("", NAMES), false);
  });

  it("spotOf : console classique sûre, Terminal selon l'onglet, parent ou hôte deviné : devant seulement", () => {
    assert.deepEqual(spotOf(lookup({ console: win(1, "ConsoleWindowClass") }), NAMES), {
      window: win(1, "ConsoleWindowClass"),
      kind: "console",
      canSend: true,
    });
    const wt = (title: string) => lookup({ console: win(2, "CASCADIA_HOSTING_WINDOW_CLASS", title) });
    assert.equal(spotOf(wt("✳ Corrige le parseur"), NAMES)?.canSend, true);
    assert.equal(spotOf(wt("PowerShell"), NAMES)?.canSend, false, "un autre onglet est affiché");
    assert.equal(spotOf(wt("PowerShell"), NAMES)?.kind, "tabs");
    const code = lookup({
      chain: [
        { ...win(0, ""), ppid: 3 },
        { ...win(3, "Chrome_WidgetWin_1", "projet - Visual Studio Code"), ppid: 1 },
      ],
    });
    const spot = spotOf(code, NAMES);
    assert.deepEqual([spot?.kind, spot?.canSend, spot?.window.hwnd], ["window", false, 3]);
    const guess = spotOf(lookup({ fallback: win(4, "CASCADIA_HOSTING_WINDOW_CLASS", "✳ Corrige le parseur") }), NAMES);
    assert.deepEqual([guess?.kind, guess?.canSend], ["guess", false], "deviné : jamais écrire");
    assert.equal(spotOf(lookup({}), NAMES), null);
    assert.ok(WINDOWS_HOSTS.includes("windowsterminal.exe") && WINDOWS_HOSTS.every((h) => h === h.toLowerCase()));
  });

  /** Faux pilote : `front` = fenêtre de devant, changée par `raise` sauf `stuck` ; `switchAfter` la change après N frappes. */
  function fake(found: DeskLookup, opts: { stuck?: boolean; switchAfter?: number } = {}) {
    const chrome = win(99, "Chrome_WidgetWin_1", "Chrome");
    let front = chrome;
    const log: string[] = [];
    let typed = 0;
    const hit = () => {
      if (++typed === opts.switchAfter) front = chrome;
    };
    const desktop = createDesktop({
      processWindows: async (pid, hosts) => {
        log.push(`chercher ${pid} (${hosts.length} hôtes)`);
        return found;
      },
      frontWindow: async () => front,
      raiseWindow: async (hwnd) => {
        log.push(`devant ${hwnd}`);
        const all = [found.console, ...found.chain, found.fallback];
        if (!opts.stuck) front = all.find((w) => w?.hwnd === hwnd)!;
      },
      typeText: async (text) => {
        log.push(`coller ${text}`);
        hit();
      },
      sendKeys: async (combo) => {
        log.push(`touches ${combo}`);
        hit();
      },
    });
    return { desktop, log };
  }

  it("écrire : devant, vidée (Ctrl-U), collée, Entrée ; Échap pour interrompre", async () => {
    const { desktop, log } = fake(lookup({ console: win(1, "ConsoleWindowClass") }));
    await desktop.send(42, NAMES, "continue");
    await desktop.interrupt(42, NAMES);
    const search = `chercher 42 (${WINDOWS_HOSTS.length} hôtes)`;
    assert.deepEqual(log, [search, "devant 1", "touches ctrl+u", "coller continue", "touches enter", search, "devant 1", "touches escape"]);
  });

  it("refus : fenêtre pas sûre, premier plan refusé, fenêtre changée en cours (pas d'Entrée)", async () => {
    const is409 = (re: RegExp) => (e: AnswerError) => e instanceof AnswerError && e.statusCode === 409 && re.test(e.message);
    const code = fake(lookup({ chain: [{ ...win(3, "Chrome_WidgetWin_1"), ppid: 1 }] }));
    await assert.rejects(code.desktop.send(42, NAMES, "x"), is409(/sans risque/));
    assert.ok(!code.log.some((l) => l.startsWith("devant")), "rien amené devant pour écrire");
    const tab = fake(lookup({ console: win(2, "CASCADIA_HOSTING_WINDOW_CLASS", "PowerShell") }));
    await assert.rejects(tab.desktop.send(42, NAMES, "x"), is409(/onglet/));
    const stuck = fake(lookup({ console: win(1, "ConsoleWindowClass") }), { stuck: true });
    await assert.rejects(stuck.desktop.send(42, NAMES, "x"), is409(/rien n'a été tapé/));
    assert.ok(!stuck.log.some((l) => l.startsWith("touches") || l.startsWith("coller")));
    const moved = fake(lookup({ console: win(1, "ConsoleWindowClass") }), { switchAfter: 2 });
    await assert.rejects(moved.desktop.send(42, NAMES, "x"), is409(/sans Entrée/));
    assert.ok(!moved.log.includes("touches enter"));
  });

  it("amener devant : la fenêtre trouvée, même devinée ; aucune : 409", async () => {
    const guess = fake(lookup({ fallback: win(4, "CASCADIA_HOSTING_WINDOW_CLASS") }));
    await guess.desktop.focus(42, NAMES);
    assert.ok(guess.log.includes("devant 4"));
    await assert.rejects(fake(lookup({})).desktop.focus(42, NAMES), (e: AnswerError) => e.statusCode === 409);
  });
});

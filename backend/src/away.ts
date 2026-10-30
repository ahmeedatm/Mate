import path from "node:path";
import { config } from "./config.ts";
import { logFailure } from "./log.ts";
import type { Layout, OpenAction, OpenWindows, WindowTarget } from "./screens.ts";
import { readJson, removeJson, writeJson } from "./store.ts";

/**
 * « Je m'absente / Reprendre » : à l'absence, on garde les fenêtres ouvertes de l'ordinateur (appli, titre, écran) ; à la
 * reprise, on les remet devant, sur leur écran. L'ordinateur ne donne pas l'ordre de premier plan, seulement la fenêtre
 * de devant : les autres sont remises dans l'ordre de la liste, celle de devant en dernier. Les fenêtres réduites ou sur
 * un autre bureau ne sont pas gardées ; une fenêtre disparue (ou au titre changé) est ignorée. La position exacte n'est
 * pas lue par les pilotes : une fenêtre est remise sur son écran à la même place relative (`keep`), pas au pixel près.
 */

export type SavedWindow = { key: string; title: string; display: number | null };
export type Saved = { at: number; windows: SavedWindow[] };

/** Ce dont l'absence a besoin de l'ordinateur et de la sauvegarde : le vrai pilotage et un fichier, ou la démo et la mémoire. */
export type AwayDeps = {
  list: () => Promise<OpenWindows>;
  control: (key: string, window: WindowTarget, action: OpenAction, opts?: { display?: number; layout: Layout }) => Promise<unknown>;
  load: () => Promise<Saved | null>;
  save: (saved: Saved) => Promise<void>;
  clear: () => Promise<void>;
};

const file = () => path.join(config.dataDir, "away.json");

/** Sauvegarde dans .data/away.json : elle survit à un redémarrage du compagnon pendant l'absence. */
export const fileStore = (): Pick<AwayDeps, "load" | "save" | "clear"> => ({
  load: async () => {
    const saved = await readJson<Partial<Saved>>(file(), () => ({}));
    return Array.isArray(saved.windows) ? { at: saved.at ?? 0, windows: saved.windows } : null;
  },
  save: (saved) => writeJson(file(), saved),
  clear: () => removeJson(file()),
});

/** Fenêtres à garder, de l'arrière vers l'avant : celle de devant en dernier. */
export function snapshot(open: OpenWindows): SavedWindow[] {
  const wins = open.apps.flatMap((app) =>
    app.windows
      .filter((w) => !w.minimized && !w.elsewhere)
      .map((w) => ({ w, saved: { key: app.key, title: w.title, display: w.display } })),
  );
  return [...wins.filter((x) => !x.w.front), ...wins.filter((x) => x.w.front)].map((x) => x.saved);
}

/** Garde les fenêtres ouvertes ; répond leur nombre. Une absence déjà gardée est remplacée. */
export async function startAway(deps: AwayDeps): Promise<{ saved: number }> {
  const windows = snapshot(await deps.list());
  await deps.save({ at: Date.now(), windows });
  return { saved: windows.length };
}

/** Remet les fenêtres gardées devant, l'arrière d'abord ; répond le nombre remis. Sans sauvegarde : 0, rien n'est touché. */
export async function resumeAway(deps: AwayDeps): Promise<{ restored: number; saved: boolean }> {
  const saved = await deps.load();
  if (!saved) return { restored: 0, saved: false };
  const now = await deps.list();
  const pool = now.apps.flatMap((app) => app.windows.map((w) => ({ key: app.key, w })));
  let restored = 0;
  for (const keep of saved.windows) {
    const at = pool.findIndex((x) => x.key === keep.key && x.w.title === keep.title);
    if (at < 0) continue;
    const [{ w }] = pool.splice(at, 1);
    const target: WindowTarget = { index: w.index, title: w.title };
    try {
      if (keep.display !== null && w.display !== keep.display) {
        await deps.control(keep.key, target, "place", { display: keep.display, layout: "keep" });
      }
      await deps.control(keep.key, target, "focus");
      restored++;
    } catch (e) {
      // Une fenêtre qui résiste n'empêche pas de remettre les autres.
      logFailure("Remise d'une fenêtre après l'absence", e);
    }
  }
  await deps.clear();
  return { restored, saved: true };
}

import type { ClipboardContent } from "./screens.ts";

/**
 * Historique des textes copiés sur l'ordinateur, pour les recopier depuis la tablette (spec
 * docs/superpowers/specs/2026-10-29-historique-presse-papiers-design.md). En mémoire seulement : rien sur le disque,
 * tout disparaît à l'arrêt du compagnon. Le contenu n'est jamais écrit dans le journal.
 */

export const HISTORY_MAX = 20;
export const HISTORY_TTL_MS = 60 * 60 * 1000;
/** Au-delà, le texte n'entre pas dans l'historique (fait pour les petits textes : codes, adresses, identifiants). */
export const HISTORY_TEXT_MAX = 10_000;

export type HistoryItem = { id: string; text: string; at: number; secret: boolean };

/** Un seul « mot » qui ressemble à un code ou un mot de passe : à masquer sur la tablette (l'œil le montre). */
export function looksSecret(text: string): boolean {
  if (!/^\S{4,64}$/.test(text)) return false;
  if (/^(https?:\/\/|www\.)/i.test(text) || /^[^@\s]+@[^@\s]+\.[a-z]{2,}$/i.test(text)) return false;
  // Chiffre ou symbole ASCII (les lettres accentuées ne comptent pas : « élève » reste lisible).
  return /[0-9!-\/:-@\[-`{-~]/.test(text);
}

export class ClipboardHistory {
  private items: HistoryItem[] = [];
  private next = 1;

  constructor(private readonly now: () => number = Date.now) {}

  /** Texte vide, trop long ou fait d'espaces : ignoré. Un texte déjà présent remonte en haut, avec le même identifiant. */
  add(text: string) {
    if (!text.trim() || text.length > HISTORY_TEXT_MAX) return;
    const id = this.items.find((item) => item.text === text)?.id ?? String(this.next++);
    this.items = this.items.filter((item) => item.text !== text);
    this.items.unshift({ id, text, at: this.now(), secret: looksSecret(text) });
    this.items.length = Math.min(this.items.length, HISTORY_MAX);
  }

  /** Le plus récent en premier, sans les entrées de plus d'une heure. */
  list(): HistoryItem[] {
    const since = this.now() - HISTORY_TTL_MS;
    this.items = this.items.filter((item) => item.at > since);
    return [...this.items];
  }

  get(id: string): HistoryItem | undefined {
    return this.list().find((item) => item.id === id);
  }

  remove(id: string): boolean {
    const before = this.items.length;
    this.items = this.items.filter((item) => item.id !== id);
    return this.items.length < before;
  }

  clear() {
    this.items = [];
  }
}

export const clipboardHistory = new ClipboardHistory();

export type ClipboardSources = {
  /** Repère qui change à chaque copie (numéro de séquence Windows, changeCount du Mac). */
  stamp: () => Promise<string>;
  read: () => Promise<ClipboardContent>;
};

/**
 * Surveille le presse-papiers : relit le repère toutes les `intervalMs`, et le contenu seulement quand il a changé.
 * Un contenu `protected` (gestionnaire de mots de passe), une image ou une erreur (session sans bureau, pilote absent)
 * n'ajoutent rien. Renvoie l'arrêt.
 */
export function watchClipboard(sources: ClipboardSources, history: ClipboardHistory, intervalMs: number): () => void {
  let last: string | undefined;
  let busy = false;
  const tick = async () => {
    if (busy) return;
    busy = true;
    try {
      const stamp = await sources.stamp();
      if (stamp === last) return;
      const content = await sources.read();
      last = stamp;
      if (content.kind === "text") history.add(content.text);
    } catch {
      // Rien à faire : le tick suivant réessaie. Pas de journal, il pourrait citer le contenu.
    } finally {
      busy = false;
    }
  };
  void tick();
  const timer = setInterval(() => void tick(), intervalMs);
  timer.unref();
  return () => clearInterval(timer);
}

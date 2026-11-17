import { EN } from "./i18n-en.ts";

/**
 * Messages du compagnon en anglais (Réglages > Langue de la tablette, choix Français / English de la page locale).
 * Le code garde ses phrases en français ; `EN` les traduit à la sortie : texte exact, ou gabarit dont `{}` marque une
 * valeur insérée (nom, nombre). Un message absent du catalogue reste en français.
 */
export type Lang = "fr" | "en";

/** Première langue de l'en-tête Accept-Language : la tablette envoie `fr` ou `en`, la page locale aussi. */
export function langOf(header: string | undefined): Lang {
  return /^\s*en\b/i.test(header ?? "") ? "en" : "fr";
}

type Pattern = { re: RegExp; en: string };
let patterns: Pattern[] | null = null;

/** Gabarits compilés une fois : `{}` devient un groupe, le reste est pris tel quel. */
function compiled(): Pattern[] {
  patterns ??= Object.entries(EN)
    .filter(([fr]) => fr.includes("{}"))
    .map(([fr, en]) => ({
      re: new RegExp(
        "^" +
          fr
            .split("{}")
            .map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"))
            .join("(.+?)") +
          "$",
        "s",
      ),
      en,
    }));
  return patterns;
}

export function translate(text: string, lang: Lang): string {
  if (lang === "fr") return text;
  const exact = EN[text];
  if (exact !== undefined) return exact;
  for (const { re, en } of compiled()) {
    const m = re.exec(text);
    if (m) {
      let i = 1;
      return en.replace(/\{\}/g, () => m[i++] ?? "");
    }
  }
  return text;
}

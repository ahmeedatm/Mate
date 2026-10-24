export type MeetingKind = "teams" | "meet" | "zoom" | "webex";

// Liens de visio cherchés dans la description et le lieu des événements. Teams : ancien format meetup-join ou
// nouveau meet/<numéro> (lien « Rejoindre la réunion maintenant » d'Outlook).
const MEETING_LINKS: [MeetingKind, RegExp][] = [
  ["teams", /https:\/\/teams\.(?:microsoft|live)\.com\/(?:l\/meetup-join|meet)\/[^\s<>"]+/],
  ["meet", /https:\/\/meet\.google\.com\/[a-z]{3}-[a-z]{4}-[a-z]{3}/],
  ["zoom", /https:\/\/(?:[\w-]+\.)?zoom\.us\/(?:j|my|s|w)\/[^\s<>"]+/],
  ["webex", /https:\/\/[\w-]+\.webex\.com\/[^\s<>"]+/],
];

/** Premier lien de visio trouvé dans ces textes (description, lieu…). */
export function meetingLink(...texts: (string | undefined | null)[]): string | undefined {
  for (const t of texts) {
    if (!t) continue;
    for (const [, re] of MEETING_LINKS) {
      const found = t.match(re)?.[0];
      if (found) return found;
    }
  }
  return undefined;
}

export function meetingKind(url: string): MeetingKind | undefined {
  return MEETING_LINKS.find(([, re]) => re.test(url))?.[0];
}

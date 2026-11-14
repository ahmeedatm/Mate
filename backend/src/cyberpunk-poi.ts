import { block } from "./cyberpunk.ts";

/**
 * Points d'intérêt de la carte du jeu pour la tablette : le mod écrit `mappins.json` (variante, phase, icône que le jeu
 * donne par `MappinUIUtils.MappinToTexturePart`) ; ici chaque point reçoit sa catégorie de filtre. Design :
 * docs/specs/2026-11-14-cyberpunk-points-interet-design.md.
 */

export type PoiCategory = "quest" | "gig" | "ncpd" | "travel" | "shop" | "ripperdoc" | "other";

/** Pièces de `mappin_icons.inkatlas` extraites du jeu (servies en `poi_<nom>.png`) ; une autre icône reste à null. */
export const POI_ICONS = [
  "quest",
  "minor_quest",
  "quest_giver",
  "quest_excmark",
  "gigs",
  "fixer",
  "map_bounty",
  "hunt_for_psycho",
  "fast_travel",
  "fast_travel_metro",
  "metro",
  "ripperdoc",
  "gun",
  "clothes",
  "food_vendor",
  "bar",
  "drink",
  "medicine",
  "junk_shop",
  "glamdoc",
  "black_market_vendor",
  "melee",
  "netservice",
  "prostitute",
  "tech",
  "vendor_owned",
  "wandering_merchant",
  "ico_hairdresser",
  "ico_wardrobe",
  "body_modifications",
  "dropbox",
  "car_to_buy",
  "motorcycle_to_buy",
  "apartment_to_buy",
  "apartment",
  "tarot_card",
  "dogtown_gate",
  "car",
  "motorcycle",
  "smugglers",
  "outpost",
  "hidden_stash",
  "gang_watch",
  "resource",
  "racing",
  "convoy",
  "courier",
  "client_in_distress",
  "retrieving",
  "sabotage",
  "thievery",
  "bounty_hunt",
  "street_story",
  "minor_activity",
  "dynamic_event",
  "relic_access_point",
  "undiscovered",
];
const KNOWN_ICONS = new Set(POI_ICONS);

/** Variante du jeu (`gamedataMappinVariant`) → catégorie ; une variante = une catégorie. */
const CATEGORIES: Record<string, PoiCategory> = {
  DefaultQuestVariant: "quest",
  ExclamationMarkVariant: "quest",
  QuestGiverVariant: "quest",
  QuestionMarkVariant: "quest",
  FixerVariant: "gig",
  RetrievingVariant: "gig",
  ThieveryVariant: "gig",
  SabotageVariant: "gig",
  BountyHuntVariant: "gig",
  ClientInDistressVariant: "gig",
  GangWatchVariant: "ncpd",
  HiddenStashVariant: "ncpd",
  OutpostVariant: "ncpd",
  HuntForPsychoVariant: "ncpd",
  Zzz06_NCPDGigVariant: "ncpd",
  SmugglersDenVariant: "ncpd",
  ResourceVariant: "ncpd",
  FailedCrossingVariant: "ncpd",
  FastTravelVariant: "travel",
  Zzz17_NCARTVariant: "travel",
  ServicePointRipperdocVariant: "ripperdoc",
  ServicePointCyberwareVariant: "ripperdoc",
  ServicePointBarVariant: "shop",
  ServicePointClothesVariant: "shop",
  ServicePointFoodVariant: "shop",
  ServicePointGunsVariant: "shop",
  ServicePointJunkVariant: "shop",
  ServicePointMedsVariant: "shop",
  ServicePointTechVariant: "shop",
  ServicePointMeleeTrainerVariant: "shop",
  ServicePointNetTrainerVariant: "shop",
  ServicePointProstituteVariant: "shop",
  Zzz14_ServicePointBlackMarketVariant: "shop",
  Zzz21_ServicePointGlamdocVariant: "shop",
  OpenVendorVariant: "shop",
};

/** Catégorie d'une variante ; inconnue : quête si le jeu dit que c'est un mappin de quête, sinon Autres. */
export function categoryOf(variant: string, quest: boolean): PoiCategory {
  return CATEGORIES[variant] ?? (quest ? "quest" : "other");
}

const DIM_PHASES = new Set(["UndiscoveredPhase", "CompletedPhase"]);

type RawPoint = { id?: unknown; x?: unknown; y?: unknown; v?: unknown; ph?: unknown; icon?: unknown; name?: unknown; q?: unknown };

const text = (v: unknown) => (typeof v === "string" && v !== "" ? v : null);
const finite = (v: unknown): v is number => typeof v === "number" && Number.isFinite(v);

function toPoint(raw: unknown) {
  if (!raw || typeof raw !== "object") return null;
  const m = raw as RawPoint;
  if (!finite(m.x) || !finite(m.y) || m.id === undefined) return null;
  const icon = text(m.icon);
  return {
    id: String(m.id),
    x: m.x,
    y: m.y,
    cat: categoryOf(String(m.v ?? ""), m.q === true),
    icon: icon && KNOWN_ICONS.has(icon) ? icon : null,
    name: text(m.name),
    dim: DIM_PHASES.has(String(m.ph ?? "")),
  };
}

/** `GET /cyberpunk/mappins` : les points de la carte du jeu, catégorie et icône comprises. */
export async function mappins() {
  const raw = (await block("mappins")) as { mappins?: unknown };
  const list: unknown[] = Array.isArray(raw.mappins) ? raw.mappins : [];
  return { v: 1, points: list.map(toPoint).filter((p) => p !== null) };
}

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { BG, COLORS, decodeBuildings, renderTile, tileIndex, type Affine } from "../src/cyberpunk-blocks.ts";
import { fakeBuildings as fake } from "./cyberpunk-blocks-fake.ts";

/** 1 px par mètre, origine du jeu au pixel (100, 300), nord en haut. */
const AFF: Affine = [1, 0, 100, 0, -1, 300];
const px = (tile: Buffer, x: number, y: number) => [...tile.subarray((y * 512 + x) * 3, (y * 512 + x) * 3 + 3)];

describe("cubes de la carte 3D du jeu", () => {
  it("décodage : coins au sol, hauteur au-dessus du sol voisin, quartier", () => {
    const { manifest, bin } = fake({
      watson: [
        { x: 0, y: 0, z: 5, w: 20, d: 10, h: 10 },
        { x: 30, y: 0, z: 40, w: 10, d: 10, h: 60 },
      ],
    });
    const b = decodeBuildings(manifest, bin);
    assert.equal(b.n, 2);
    const i = 0;
    // Boîte 0 : 20 m sur x, 10 m sur y, centrée en (0, 0).
    const xs = [0, 2, 4, 6].map((k) => b.corners[i * 8 + k]).sort((a, c) => a - c);
    const ys = [1, 3, 5, 7].map((k) => b.corners[i * 8 + k]).sort((a, c) => a - c);
    assert.deepEqual(
      xs.map((v) => Math.round(v)),
      [-10, -10, 10, 10],
    );
    assert.deepEqual(
      ys.map((v) => Math.round(v)),
      [-5, -5, 5, 5],
    );
    // Le sol est le bas le plus bas du voisinage (boîte 0 : 0 m) : la tour posée sur lui fait 70 m de haut.
    assert.equal(Math.round(b.base[0]), 0);
    assert.equal(Math.round(b.top[0]), 10);
    assert.equal(Math.round(b.top[1]), 70);
    assert.equal(
      b.district[0],
      COLORS.findIndex((c) => c.name === "watson"),
    );
  });

  it("Pacifica garde le sud-ouest, côte comprise ; le reste de son groupe devient les Badlands", () => {
    const { manifest, bin } = fake({
      pacifica: [
        { x: -2000, y: -3000, z: 0, w: 10, d: 10, h: 10 },
        { x: -4800, y: -5800, z: 0, w: 10, d: 10, h: 10 },
        { x: 3000, y: -1000, z: 0, w: 10, d: 10, h: 10 },
        { x: -200, y: -3500, z: 0, w: 10, d: 10, h: 10 },
        { x: -2000, y: 4500, z: 0, w: 10, d: 10, h: 10 },
      ],
    });
    const b = decodeBuildings(manifest, bin);
    assert.deepEqual(
      [...b.district].map((d) => COLORS[d].name),
      ["pacifica", "pacifica", "badlands", "badlands", "badlands"],
    );
  });

  it("des données d'une autre forme sont refusées", () => {
    const { manifest, bin } = fake({ watson: [{ x: 0, y: 0, z: 0, w: 10, d: 10, h: 10 }] });
    assert.throws(() => decodeBuildings({ format: "autre" }, bin));
    assert.throws(() => decodeBuildings(manifest, bin.subarray(0, 8)));
  });

  it("rendu : toit à la couleur du quartier, levé vers le nord selon la hauteur ; vide au fond", () => {
    const { manifest, bin } = fake({
      heywood: [{ x: 0, y: 0, z: 0, w: 40, d: 40, h: 2 }],
      westbrook: [{ x: 200, y: 0, z: 100, w: 40, d: 40, h: 200 }],
    });
    const b = decodeBuildings(manifest, bin);
    const index = tileIndex(b, AFF, 1);
    const tile = renderTile(b, index.get(0) ?? [], 0, 0, AFF);
    const heywood = COLORS.find((c) => c.name === "heywood")!.rgb;
    // Bloc bas : son toit est au centre, teinte de Heywood (même ordre de grandeur sur chaque canal).
    const roof = px(tile, 100, 300);
    assert.ok(roof[1] > roof[0] && roof[1] > roof[2], `vert attendu : ${roof}`);
    assert.ok(roof[1] < heywood[1], "toit bas : plus sombre que la couleur pleine");
    // Tour de 200 m : toit décalé de 0,3 × 200 = 60 m vers le nord (vers le haut de l'image).
    const top = px(tile, 300, 240);
    const westbrook = COLORS.find((c) => c.name === "westbrook")!.rgb;
    assert.ok(top[0] > top[1] && top[2] > top[1], `magenta attendu : ${top}`);
    assert.ok(top[0] > px(tile, 300, 300)[0], "le toit est plus clair que le mur sous lui");
    assert.ok(top[0] <= westbrook[0] * 1.35 + 1);
    // Loin de tout : couleur de fond.
    assert.deepEqual(px(tile, 50, 50), BG);
  });

  it("index : une boîte va dans chaque tuile qu'elle touche, toit compris", () => {
    const { manifest, bin } = fake({ watson: [{ x: 412, y: 0, z: 100, w: 4, d: 4, h: 200 }] });
    const b = decodeBuildings(manifest, bin);
    // Pied en (512, 300) environ : à cheval sur les tuiles 0 et 1 ; toit 60 px plus haut, même rangée.
    const index = tileIndex(b, AFF, 2);
    assert.deepEqual([...index.keys()].sort(), [0, 1]);
  });

  it("aucun quartier vif n'a la teinte du joueur, des repères ou des charcudocs", () => {
    // cp_jaune #f3e600, cp_cyan #4ef2f5, cp_poi_charcudoc #ff5f56 (colors.xml de la tablette).
    const hsv = ([r, g, b]: number[]) => {
      const max = Math.max(r, g, b);
      const d = max - Math.min(r, g, b);
      const h = d === 0 ? 0 : max === r ? ((g - b) / d + 6) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
      return { h: h * 60, s: max === 0 ? 0 : d / max };
    };
    const overlays = [
      [243, 230, 0],
      [78, 242, 245],
      [255, 95, 86],
    ].map((c) => hsv(c).h);
    for (const c of COLORS) {
      const { h, s } = hsv(c.rgb);
      if (s < 0.35) continue; // gris et sable : pas de teinte à confondre
      for (const o of overlays)
        assert.ok(Math.min(Math.abs(h - o), 360 - Math.abs(h - o)) >= 25, `${c.name} trop près de la teinte ${o.toFixed(0)}°`);
    }
  });
});

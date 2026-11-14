export type FakeBox = { x: number; y: number; z: number; w: number; d: number; h: number };

/**
 * Faux `buildings.bin` d'Open2077 : par boîte, u16 position (scène x, y haut, z = −y du jeu) sur la boîte du quartier,
 * i8 quaternion (/127, ici sans rotation), u16 taille (scène x, y, z) sur `maxSize`.
 */
export function fakeBuildings(districts: Record<string, FakeBox[]>) {
  const recs: Buffer[] = [];
  const list: unknown[] = [];
  let offset = 0;
  for (const [name, boxes] of Object.entries(districts)) {
    const scene = boxes.map((b) => [b.x, b.z, -b.y]);
    const lo = [0, 1, 2].map((i) => Math.min(...scene.map((s) => s[i])));
    const span = [0, 1, 2].map((i) => Math.max(Math.max(...scene.map((s) => s[i])) - lo[i], 1e-3));
    const maxSize = Math.max(...boxes.flatMap((b) => [b.w, b.d, b.h]));
    boxes.forEach((b, k) => {
      const r = Buffer.alloc(16);
      scene[k].forEach((v, i) => r.writeUInt16LE(Math.round(((v - lo[i]) / span[i]) * 65535), i * 2));
      r.writeInt8(127, 9); // quaternion (0, 0, 0, 1)
      [b.w, b.h, b.d].forEach((v, i) => r.writeUInt16LE(Math.round((v / maxSize) * 65535), 10 + i * 2));
      recs.push(r);
    });
    list.push({ name, offset, count: boxes.length, min: lo, span, maxSize });
    offset += boxes.length;
  }
  return { manifest: { format: "op77_map3d/1", buildings: { stride: 16, count: offset, districts: list } }, bin: Buffer.concat(recs) };
}

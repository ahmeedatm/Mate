# Écrit compositions/parcours.html de la démo produit : tablette fixe à gauche, chapitre par persona à droite.
S = 1000 / 1356            # échelle de la tablette
TAB_L, TAB_T = 150, 120    # coin haut gauche de la tablette
SX, SY = TAB_L + 38 * S, TAB_T + 38 * S   # origine de l'écran
CX, CY = TAB_L + 500, TAB_T + 876 * S / 2  # centre de la tablette

def fx(x): return SX + x * S
def fy(y): return SY + y * S

import json
CH = json.load(open("chapitres.json"))["chapitres"]
OUV, DUR, FIN = 4.0, 12.0, 76.0
TOTAL = 84.0
def sx_h(h): return 192 + (h - 8) / 12 * 1536

o = []; w = o.append
w('''<template>
  <style>
    #root { position: absolute; inset: 0; overflow: hidden; font-family: "Outfit", sans-serif; color: #1e1b1b; }
    #d-fond { position: absolute; inset: 0; background: #faf2f1; }
    #d-tabw { position: absolute; left: %.1fpx; top: %.1fpx; width: 1000px; height: %.1fpx; }
    #d-cadre { position: absolute; inset: 0; border-radius: 30px; background: #0e0d0d; border: 2px solid #4d4545; }
    #d-ecran { position: absolute; left: %.1fpx; top: %.1fpx; width: 1280px; height: 800px; transform: scale(%.5f); transform-origin: 0 0; overflow: hidden; border-radius: 4px; background: #faf2f1; }
    .ecran { position: absolute; left: 0; top: 0; width: 1280px; height: 800px; }
    .onde { position: absolute; width: 60px; height: 60px; margin: -30px 0 0 -30px; border-radius: 50%%; border: 4px solid #8c8181; opacity: 0; }
    .zoom { position: absolute; overflow: hidden; border-radius: 18px; border: 2px solid #d0c4c3; background: #faf2f1; }
    .zoom.nuit { border-color: #4d4545; background: #100d0d; }
    .zoom img { position: absolute; }
    .col { position: absolute; left: 1230px; top: 150px; width: 498px; }
    .col .nom { font-weight: 300; font-size: 64px; line-height: 70px; letter-spacing: -0.03em; }
    .col .metier { margin-top: 10px; font-weight: 400; font-size: 30px; line-height: 38px; color: #4d4545; }
    .col .cit { margin-top: 40px; font-weight: 300; font-size: 36px; line-height: 46px; }
    .col .lab { opacity: 0; position: absolute; left: 0; top: 470px; width: 498px; font-weight: 500; font-size: 30px; line-height: 38px; }
    .col .lab::before { content: ""; display: inline-block; width: 14px; height: 14px; border-radius: 50%%; background: #ca0538; margin-right: 14px; vertical-align: 3px; }
    .col.nuit { color: #e9e1e0; }
    .col.nuit .metier { color: #d0c4c3; }
    .col.nuit .lab::before { background: #ff5165; }
    #d-som { position: absolute; left: 0; top: 0; width: 1920px; height: 1080px; }
    #d-piste { position: absolute; left: 192px; top: 878px; width: 1536px; height: 4px; border-radius: 2px; background: #8c8181; transform-origin: 0 50%%; }
    .spt { position: absolute; top: 880px; width: 0; height: 0; }
    .spt i { position: absolute; left: -8px; top: -8px; width: 16px; height: 16px; border-radius: 50%%; background: #8c8181; }
    .spt b { position: absolute; left: -100px; top: 22px; width: 200px; text-align: center; font-weight: 400; font-size: 28px; color: #6f6666; }
    #d-maint { position: absolute; top: 880px; left: 0; width: 0; height: 0; }
    #d-maint i { position: absolute; left: -13px; top: -13px; width: 26px; height: 26px; border-radius: 50%%; background: #ca0538; }
    #d-titre { position: absolute; left: 1230px; top: 250px; width: 498px; }
    #d-titre h1 { font-weight: 300; font-size: 72px; line-height: 80px; letter-spacing: -0.035em; }
    #d-titre p { margin-top: 24px; font-weight: 400; font-size: 32px; line-height: 40px; color: #4d4545; }
    #d-fin { position: absolute; left: 1230px; top: 230px; width: 498px; }
    #d-fin svg { width: 132px; height: 132px; display: block; }
    #d-fin .nom { margin-top: 28px; font-weight: 300; font-size: 72px; line-height: 78px; letter-spacing: -0.035em; }
    #d-fin img { display: block; height: 30px; margin-top: 14px; }
    #d-fin p { margin-top: 40px; font-weight: 400; font-size: 32px; line-height: 42px; color: #4d4545; }
  </style>
  <div id="root" data-composition-id="parcours" data-width="1920" data-height="1080">
    <div id="d-fond"></div>
''' % (TAB_L, TAB_T, 876 * S, 38 * S, 38 * S, S))
# Tablette et écrans
w(f'    <div id="d-tabw">\n      <div id="d-cadre"></div>\n      <div id="d-ecran">\n')
w(f'        <img id="d-e-ouv" class="clip ecran" src="assets/screens/commun-accueil-cadran-jour.png" alt="" data-start="0" data-duration="{OUV + 0.4}" data-track-index="2" />\n')
for i, c in enumerate(CH):
    t0 = OUV + i * DUR
    for k, (sub, st, du) in enumerate([("A", t0, 5.6), ("B", t0 + 5.6, DUR - 5.6 + (0.4 if i < 5 else 0))]):
        e = c[sub]
        if "vid" in e:
            w(f'        <video id="d-e-{c["id"]}-{sub}" class="clip ecran" src="assets/screens/{e["vid"]}.mp4" data-start="{st}" data-duration="{du}" data-media-start="{e["ms"]}" data-track-index="2" muted playsinline></video>\n')
        else:
            w(f'        <img id="d-e-{c["id"]}-{sub}" class="clip ecran" src="assets/screens/{e["img"]}.png" alt="" data-start="{st}" data-duration="{du}" data-track-index="2" />\n')
        if "tap" in e:
            x, y = e["tap"]
            w(f'        <div id="d-o-{c["id"]}-{sub}" class="onde" style="left:{x}px;top:{y}px"></div>\n')
w(f'        <img id="d-e-fin" class="clip ecran" src="assets/screens/commun-accueil-cadran-jour.png" alt="" data-start="{FIN}" data-duration="{TOTAL - FIN}" data-track-index="2" />\n')
w('      </div>\n    </div>\n')
# Gros plans
for i, c in enumerate(CH):
    t0 = OUV + i * DUR
    for sub, zs in (("A", t0 + 2.0), ("B", t0 + 5.6 + 1.6)):
        e = c[sub]
        if "zoom" not in e:
            continue
        x, y, zw, zh = e["zoom"]
        k = min(1.75 * S, 1060 / zw, 600 / zh)
        W, H = zw * k, zh * k
        L, T = CX - W / 2, CY - H / 2
        cls = "zoom nuit" if c.get("nuit") else "zoom"
        w(f'    <div id="d-z-{c["id"]}-{sub}" class="clip {cls}" data-start="{zs:.2f}" data-duration="3.4" data-track-index="3" style="left:{L:.1f}px;top:{T:.1f}px;width:{W:.1f}px;height:{H:.1f}px">\n')
        w(f'      <img src="assets/screens/{e["img"]}.png" alt="" style="left:{-x * k:.1f}px;top:{-y * k:.1f}px;width:{1280 * k:.1f}px;height:{800 * k:.1f}px" />\n    </div>\n')
        e["_z"] = (zs, (fx(x) + zw * S / 2) - CX, (fy(y) + zh * S / 2) - CY, S / k)
# Ouverture
w(f'    <div id="d-titre" class="clip" data-start="0" data-duration="{OUV}" data-track-index="4"><h1>Six personnes, une tablette</h1><p>UnoMate, le compagnon de bureau Unowhy</p></div>\n')
# Colonnes
for i, c in enumerate(CH):
    t0 = OUV + i * DUR
    cls = "col nuit" if c.get("nuit") else "col"
    w(f'    <div id="d-c-{c["id"]}" class="clip {cls}" data-start="{t0}" data-duration="{DUR}" data-track-index="4">\n')
    la, lb = (c[x]["lab"].replace(" :", "\u00a0:") for x in ("A", "B"))
    cit = c["cit"].replace("« ", "«\u00a0").replace(" »", "\u00a0»").replace(" ;", "\u00a0;").replace(" :", "\u00a0:")
    w(f'      <div class="nom">{c["nom"]}</div><div class="metier">{c["metier"]}</div><div class="cit">{cit}</div>\n')
    w(f'      <div class="lab lab-a">{la}</div><div class="lab lab-b" data-layout-allow-overlap>{lb}</div>\n    </div>\n')
# Fin
w(f'''    <div id="d-fin" class="clip" data-start="{FIN}" data-duration="{TOTAL - FIN}" data-track-index="4">
      <svg viewBox="0 0 256 256"><rect width="256" height="256" rx="60" fill="#CA0538" /><path d="M48 128 H152" stroke="#FFFFFF" stroke-width="14" stroke-linecap="round" /><path d="M152 128 H208" stroke="#FFFFFF" stroke-opacity=".4" stroke-width="14" stroke-linecap="round" /><circle cx="152" cy="128" r="40" fill="none" stroke="#FFFFFF" stroke-opacity=".55" stroke-width="7" /><circle cx="152" cy="128" r="20" fill="#FFFFFF" /></svg>
      <div class="nom">UnoMate</div><img src="assets/brand/unowhy.svg" alt="Unowhy" />
      <p>Mac et Windows, en WiFi.<br />Sur la tablette Unowhy 4.2.</p>
    </div>
''')
# Sommaire
w('    <div id="d-som">\n      <div id="d-piste"></div>\n')
for c in CH:
    w(f'      <div id="d-s-{c["id"]}" class="spt" style="left:{sx_h(c["h"]):.1f}px"><i></i><b>{c["nom"].split(",")[0]}</b></div>\n')
w(f'      <div id="d-maint" style="left:{sx_h(CH[0]["h"]):.1f}px"><i></i></div>\n    </div>\n')
w('  </div>\n  <script>\n    const tl = gsap.timeline({ paused: true });\n')
J = lambda s: w('    ' + s + '\n')
# Ouverture
J('tl.fromTo("#d-piste", { scaleX: 0 }, { scaleX: 1, duration: 1.2, ease: "expo.out" }, 0.3);')
J('tl.fromTo(".spt", { opacity: 0 }, { opacity: 1, duration: 0.25, stagger: 0.08, ease: "none" }, 0.6);')
J('tl.fromTo("#d-maint i", { scale: 0 }, { scale: 1, duration: 0.4, ease: "back.out(1.6)" }, 1.4);')
J('tl.fromTo("#d-titre h1", { opacity: 0, filter: "blur(8px)" }, { opacity: 1, filter: "blur(0px)", duration: 0.2, ease: "power2.out" }, 0.4);')
J('tl.fromTo("#d-titre p", { opacity: 0, filter: "blur(8px)" }, { opacity: 1, filter: "blur(0px)", duration: 0.2, ease: "power2.out" }, 0.8);')
J(f'tl.to("#d-titre", {{ opacity: 0, filter: "blur(8px)", duration: 0.2, ease: "power2.in" }}, {OUV - 0.35});')
for i, c in enumerate(CH):
    t0 = OUV + i * DUR
    cid = c["id"]
    # Le point « maintenant » glisse vers la persona.
    if i > 0:
        J(f'tl.to("#d-maint", {{ x: {sx_h(c["h"]) - sx_h(CH[0]["h"]):.1f}, duration: 0.6, ease: "power3.inOut" }}, {t0 - 0.3});')
    J(f'tl.fromTo("#d-s-{cid} b", {{ color: "#6f6666" }}, {{ color: "{"#e9e1e0" if c.get("nuit") else "#1e1b1b"}", duration: 0.3, ease: "none", immediateRender: false }}, {t0});')
    # Colonne : nom, métier, citation, mot à mot de bloc en bloc.
    for j, sel in enumerate([".nom", ".metier", ".cit"]):
        J(f'tl.fromTo("#d-c-{cid} {sel}", {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.2, ease: "power2.out" }}, {t0 + 0.15 + j * 0.12:.2f});')
    J(f'tl.to("#d-c-{cid} > *", {{ opacity: 0, filter: "blur(6px)", duration: 0.2, ease: "power2.in" }}, {t0 + DUR - 0.3:.2f});')
    for sub, st in (("A", t0), ("B", t0 + 5.6)):
        e = c[sub]
        J(f'tl.fromTo("#d-e-{cid}-{sub}", {{ opacity: 0, scale: 0.94 }}, {{ opacity: 1, scale: 1, duration: 0.4, ease: "expo.out" }}, {st:.2f});')
        J(f'tl.fromTo("#d-c-{cid} .lab-{sub.lower()}", {{ opacity: 0, filter: "blur(6px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.2, ease: "power2.out", immediateRender: false }}, {st + 0.6:.2f});')
        if sub == "A":
            J(f'tl.to("#d-c-{cid} .lab-a", {{ opacity: 0, duration: 0.15, ease: "power2.in" }}, {t0 + 5.45:.2f});')
        if "tap" in e:
            J(f'tl.fromTo("#d-o-{cid}-{sub}", {{ opacity: 0.9, scale: 0.6 }}, {{ opacity: 0, scale: 2.2, duration: 0.5, ease: "power2.out", immediateRender: false }}, {st + 1.3:.2f});')
        if "_z" in e:
            zs, dx, dy, sc = e["_z"]
            J(f'tl.fromTo("#d-z-{cid}-{sub}", {{ x: {dx:.1f}, y: {dy:.1f}, scale: {sc:.3f}, opacity: 0 }}, {{ x: 0, y: 0, scale: 1, opacity: 1, duration: 0.5, ease: "power3.out" }}, {zs:.2f});')
            J(f'tl.to("#d-z-{cid}-{sub}", {{ x: {dx:.1f}, y: {dy:.1f}, scale: {sc:.3f}, opacity: 0, duration: 0.4, ease: "power2.in" }}, {zs + 2.95:.2f});')
            J(f'tl.to("#d-tabw", {{ opacity: 0.3, duration: 0.4, ease: "power2.out" }}, {zs:.2f});')
            J(f'tl.to("#d-tabw", {{ opacity: 1, duration: 0.35, ease: "power2.in" }}, {zs + 2.95:.2f});')
    if c.get("nuit"):
        J(f'tl.to("#d-fond", {{ backgroundColor: "#100d0d", duration: 0.5, ease: "power2.inOut" }}, {t0 - 0.2:.2f});')
        J(f'tl.to(".spt b", {{ color: "#998e8e", duration: 0.5, ease: "none" }}, {t0 - 0.2:.2f});')
        J(f'tl.to("#d-fond", {{ backgroundColor: "#faf2f1", duration: 0.5, ease: "power2.inOut" }}, {FIN - 0.3:.2f});')
# Fin : tous les points s'allument, la marque entre.
J(f'tl.to("#d-maint i", {{ scale: 0, duration: 0.25, ease: "power2.in" }}, {FIN - 0.1});')
J(f'tl.to(".spt i", {{ backgroundColor: "#ca0538", scale: 1.25, duration: 0.3, stagger: 0.1, ease: "back.out(1.6)" }}, {FIN + 0.2});')
J(f'tl.to(".spt b", {{ color: "#1e1b1b", duration: 0.3, stagger: 0.1, ease: "none" }}, {FIN + 0.2});')
J(f'tl.fromTo("#d-e-fin", {{ opacity: 0, scale: 0.94 }}, {{ opacity: 1, scale: 1, duration: 0.4, ease: "expo.out" }}, {FIN});')
J(f'tl.fromTo("#d-fin svg", {{ opacity: 0, scale: 0.7 }}, {{ opacity: 1, scale: 1, duration: 0.6, ease: "back.out(1.2)" }}, {FIN + 0.9});')
J(f'tl.fromTo("#d-fin .nom, #d-fin img", {{ clipPath: "inset(0 100% 0 0)" }}, {{ clipPath: "inset(0 0% 0 0)", duration: 0.6, ease: "power3.inOut" }}, {FIN + 1.3});')
J(f'tl.fromTo("#d-fin p", {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.2, ease: "power2.out" }}, {FIN + 2.1});')
w('    window.__timelines["parcours"] = tl;\n  </script>\n</template>\n')
open("compositions/parcours.html", "w").write("".join(o))
# Instants des touchers, pour les clics de la racine.
taps = []
for i, c in enumerate(CH):
    t0 = OUV + i * DUR
    for sub, st in (("A", t0), ("B", t0 + 5.6)):
        if "tap" in c[sub]:
            taps.append(round(st + 1.3 + 0.033, 3))
import re
idx = open("index.html").read()
idx = re.sub(r'      <audio id="clic-.*\n', "", idx)
clics = "".join(f'      <audio id="clic-{i + 1}" class="clip" src="assets/sfx/clic.mp3" data-start="{t}" data-duration="0.37" data-track-index="6" data-volume="1.6"></audio>\n' for i, t in enumerate(taps))
open("index.html", "w").write(idx.replace("    </div>\n    <script>", clics + "    </div>\n    <script>"))

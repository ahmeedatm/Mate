/**
 * Panneau de configuration, partie sauvegarde : enregistrer la configuration dans un fichier, la reprendre sur cet ou un autre
 * ordinateur en choisissant ce qu'on importe. Routes : src/routes/local-backup.ts. Branché par local-config.ts.
 * Le JS client tient dans un template literal : pas de backtick, de `${` ni d'antislash. Aucun innerHTML.
 */
export const BACKUP_CSS = `
  .pf-bk-parts { padding: 4px 0; }
`;

export const BACKUP_HTML = `
  <section id="pf-sauvegarde" data-title="Sauvegarde" data-icon="backup">
    <div class="sechead"><h2>Sauvegarde</h2><span class="dim">Touches, pages, applis, sites, plugins, profils et widgets, dans un fichier. Aucun jeton n'y entre, mais le texte et les adresses de tes touches y sont tels quels : garde le fichier pour toi.</span></div>
    <div class="card group">
      <div class="row"><span class="grow">Enregistrer la configuration<span class="pf-hint">Un fichier à garder, pour réinstaller ou changer d'ordinateur.</span></span><button type="button" class="ghost" id="pf-bk-save">Créer le fichier</button></div>
      <div class="row"><span class="grow">Reprendre un fichier<span class="pf-hint">Tu choisis ensuite ce qui est repris.</span></span><button type="button" class="ghost" id="pf-bk-pick">Choisir un fichier</button><input type="file" id="pf-bk-file" accept=".json,application/json" aria-label="Fichier de sauvegarde" hidden></div>
      <div class="pf-bk-parts" id="pf-bk-parts" hidden></div>
      <div class="row" id="pf-bk-go-row" hidden><span class="grow"></span><button type="button" id="pf-bk-restore">Reprendre ce qui est coché</button></div>
      <div class="dim note pf-msg" id="pf-bk-msg" role="status"></div>
    </div>
  </section>
`;

export const BACKUP_JS = `
  const pfBkNames = {
    keys: "Touches de la page Perso", pages: "Pages de touches", apps: "Applis du Deck", links: "Sites de la page Média",
    plugins: "Plugins", layout: "Profils et widgets de la tablette",
  };
  let pfBkBackup = null;

  function pfBkSay(text, kind) { pfSay(document.getElementById("pf-bk-msg"), text, kind); }

  async function pfBkSave() {
    pfBkSay("");
    try {
      const res = await fetch("/local/config/backup");
      if (!res.ok) throw new Error("Erreur " + res.status);
      const blob = new Blob([JSON.stringify(await res.json(), null, 2)], { type: "application/json" });
      const link = document.createElement("a");
      link.href = URL.createObjectURL(blob);
      link.download = "mate-config-" + new Date().toISOString().slice(0, 10) + ".json";
      document.body.append(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(link.href), 10000);
      pfBkSay("Fichier enregistré.", "ok");
    } catch (e) {
      pfBkSay("Impossible de lire la configuration : le compagnon ne répond pas.", "ko");
    }
  }

  async function pfBkRead(file) {
    pfBkBackup = null;
    const box = document.getElementById("pf-bk-parts");
    box.replaceChildren();
    box.hidden = true;
    document.getElementById("pf-bk-go-row").hidden = true;
    try {
      const json = JSON.parse(await file.text());
      if (json.format !== "mate-config-1" || typeof json.parts !== "object" || json.parts === null) throw new Error("format");
      pfBkBackup = json;
    } catch (e) {
      pfBkSay("Ce fichier n'est pas une sauvegarde Mate.", "ko");
      return;
    }
    const found = Object.keys(pfBkNames).filter((p) => pfBkBackup.parts[p]);
    if (!found.length) { pfBkSay("Cette sauvegarde est vide.", "ko"); return; }
    found.forEach((p) => {
      const row = document.createElement("label");
      row.className = "row";
      const text = document.createElement("span");
      text.className = "grow";
      text.textContent = pfBkNames[p];
      const box1 = document.createElement("input");
      box1.type = "checkbox";
      box1.checked = true;
      box1.dataset.part = p;
      row.append(text, box1);
      box.append(row);
    });
    box.hidden = false;
    document.getElementById("pf-bk-go-row").hidden = false;
    pfBkSay("Cela remplace ce qui existe ici pour chaque partie cochée.");
  }

  async function pfBkRestore() {
    const parts = [...document.querySelectorAll("#pf-bk-parts input:checked")].map((i) => i.dataset.part);
    if (!pfBkBackup || !parts.length) { pfBkSay("Coche au moins une partie.", "ko"); return; }
    const go = document.getElementById("pf-bk-restore");
    go.disabled = true;
    try {
      const res = await fetch("/local/config/backup", {
        method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ backup: pfBkBackup, parts: parts }),
      });
      const json = await res.json().catch(() => ({}));
      if (!res.ok) { pfBkSay(json.error || json.message || "Erreur " + res.status, "ko"); return; }
      const skipped = Object.keys(json.skipped);
      const done = json.imported.map((p) => pfBkNames[p]).join(", ");
      const notes = Object.keys(json.notes).map((p) => pfBkNames[p] + " : " + json.notes[p]).join(" ; ");
      const wait = skipped.length ? "Pas repris : " + skipped.map((p) => pfBkNames[p] + " : " + json.skipped[p]).join(" ; ") : "";
      pfBkSay((done ? "Repris : " + done + ". " : "") + wait + (notes ? " " + notes + "." : ""), skipped.length ? "ko" : "ok");
    } catch (e) {
      pfBkSay("Le compagnon ne répond pas.", "ko");
    } finally {
      go.disabled = false;
    }
  }

  document.getElementById("pf-bk-save").onclick = pfBkSave;
  document.getElementById("pf-bk-pick").onclick = () => document.getElementById("pf-bk-file").click();
  document.getElementById("pf-bk-file").onchange = (e) => { if (e.target.files[0]) pfBkRead(e.target.files[0]); e.target.value = ""; };
  document.getElementById("pf-bk-restore").onclick = pfBkRestore;
`;

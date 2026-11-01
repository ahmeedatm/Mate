/**
 * Panneau de configuration, partie sauvegarde : enregistrer la configuration dans un fichier, la reprendre sur cet ou un autre
 * ordinateur en choisissant ce qu'on importe. Routes : src/routes/local-backup.ts. Branché par local-config.ts.
 * Le JS client tient dans un template literal : pas de backtick, de `${` ni d'antislash. Aucun innerHTML.
 */
export const BACKUP_CSS = `
  .pf-bk-parts { padding: 4px 0; }
  .pf-bk-preview { padding: 8px 16px; max-height: 280px; overflow: auto; font-size: 13px; }
  .pf-bk-preview h3 { margin: 8px 0 2px; font-size: 13px; }
  .pf-bk-preview ul { margin: 0 0 4px; padding-left: 18px; }
  .pf-bk-preview li { overflow-wrap: anywhere; }
`;

export const BACKUP_HTML = `
  <section id="pf-sauvegarde" data-title="Sauvegarde" data-icon="backup">
    <div class="sechead"><h2>Sauvegarde</h2><span class="dim">Touches, pages, applis, sites, plugins, profils et widgets, dans un fichier. Aucun jeton n'y entre, mais le texte et les adresses de tes touches, les notes et les listes de tes widgets y sont tels quels : garde le fichier pour toi, ne le dépose pas dans un dossier partagé.</span></div>
    <div class="card group">
      <div class="row"><span class="grow">Enregistrer la configuration<span class="pf-hint">Un fichier à garder, pour réinstaller ou changer d'ordinateur.</span></span><button type="button" class="ghost" id="pf-bk-save">Créer le fichier</button></div>
      <div class="row"><span class="grow">Reprendre un fichier<span class="pf-hint">Tu choisis ensuite ce qui est repris.</span></span><button type="button" class="ghost" id="pf-bk-pick">Choisir un fichier</button><input type="file" id="pf-bk-file" accept=".json,application/json" aria-label="Fichier de sauvegarde" hidden></div>
      <div class="pf-bk-parts" id="pf-bk-parts" hidden></div>
      <div class="pf-bk-preview" id="pf-bk-preview" hidden></div>
      <label class="row" id="pf-bk-ack-row" hidden><span class="grow">Ce fichier lance des applis, tape du texte ou envoie des touches : relis-le avant de le reprendre.</span><input type="checkbox" id="pf-bk-ack" aria-label="J'ai relu ces touches"></label>
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
  const pfBkRisky = ["app", "text", "keys"];

  // Touches d'une partie du fichier, dans l'ordre : { page, key } (page vide pour la page Perso).
  function pfBkKeys(part) {
    const slots = (s) => (Array.isArray(s) ? s.filter((k) => k && typeof k === "object") : []);
    if (part === "keys") return slots(pfBkBackup.parts.keys && pfBkBackup.parts.keys.slots).map((key) => ({ page: "", key: key }));
    const pages = (pfBkBackup.parts.pages && pfBkBackup.parts.pages.pages) || [];
    return pages.flatMap((pg) => slots(pg && pg.slots).map((key) => ({ page: String((pg && pg.title) || ""), key: key })));
  }

  // « type : détail » : le nom, le texte, l'adresse, les touches ou la commande de l'étape.
  function pfBkStepText(step) {
    const detail = step.name || step.text || step.url || step.keys || step.command || step.app || (step.ms !== undefined ? step.ms + " ms" : "");
    return String(step.type) + " : " + String(detail);
  }

  function pfBkSteps(key) {
    return [].concat(Array.isArray(key.steps) ? key.steps : [], Array.isArray(key.hold) ? key.hold : []).filter((s) => s && typeof s === "object");
  }

  function pfBkIsRisky(part) {
    return pfBkKeys(part).some((e) => pfBkSteps(e.key).some((s) => pfBkRisky.includes(s.type)));
  }

  function pfBkRenderPreview(found) {
    const box = document.getElementById("pf-bk-preview");
    box.replaceChildren();
    found.filter((p) => p === "keys" || p === "pages").forEach((part) => {
      const title = document.createElement("h3");
      title.textContent = pfBkNames[part];
      box.append(title);
      pfBkKeys(part).forEach((e) => {
        const item = document.createElement("div");
        item.textContent = (e.page ? e.page + " : " : "") + String(e.key.label || "(sans nom)");
        const list = document.createElement("ul");
        pfBkSteps(e.key).forEach((s) => {
          const li = document.createElement("li");
          li.textContent = pfBkStepText(s);
          list.append(li);
        });
        box.append(item, list);
      });
    });
    box.hidden = !box.childNodes.length;
  }

  // « Reprendre » attend la case « J'ai relu » tant qu'une partie cochée contient une étape app, text ou keys.
  function pfBkSync() {
    const parts = [...document.querySelectorAll("#pf-bk-parts input:checked")].map((i) => i.dataset.part);
    const ack = parts.some(pfBkIsRisky);
    document.getElementById("pf-bk-ack-row").hidden = !ack;
    if (!ack) document.getElementById("pf-bk-ack").checked = false;
    document.getElementById("pf-bk-restore").disabled = ack && !document.getElementById("pf-bk-ack").checked;
  }

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
    document.getElementById("pf-bk-preview").hidden = true;
    document.getElementById("pf-bk-ack-row").hidden = true;
    document.getElementById("pf-bk-ack").checked = false;
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
      box1.onchange = pfBkSync;
      row.append(text, box1);
      box.append(row);
    });
    box.hidden = false;
    pfBkRenderPreview(found);
    document.getElementById("pf-bk-go-row").hidden = false;
    pfBkSync();
    pfBkSay("Cela remplace ce qui existe ici pour chaque partie cochée.");
  }

  async function pfBkRestore() {
    if (document.getElementById("pf-bk-restore").disabled) return;
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
      pfBkSync();
    }
  }

  document.getElementById("pf-bk-save").onclick = pfBkSave;
  document.getElementById("pf-bk-pick").onclick = () => document.getElementById("pf-bk-file").click();
  document.getElementById("pf-bk-file").onchange = (e) => { if (e.target.files[0]) pfBkRead(e.target.files[0]); e.target.value = ""; };
  document.getElementById("pf-bk-restore").onclick = pfBkRestore;
  document.getElementById("pf-bk-ack").onchange = pfBkSync;
`;

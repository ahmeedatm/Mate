/**
 * Panneau de configuration, partie menu : un plat à midi et un le soir pour chaque jour de la semaine, montrés par le widget
 * « Repas du jour » de la tablette. Branché par local-config.ts. Routes : src/routes/menu.ts.
 * Le JS client tient dans un template literal : pas de backtick, de `${` ni d'antislash. Aucun innerHTML.
 */
export const MENU_CSS = `
  .pf-menu-day { flex: 0 0 96px; font-weight: 500; }
  .pf-menu-meal { flex: 1 1 160px; display: flex; align-items: center; gap: 8px; min-width: 0; }
  .pf-menu-meal span { flex: none; font-size: 14px; color: var(--ink-2); }
  .pf-menu-meal input { flex: 1 1 auto; min-width: 0; }
  @media (max-width: 820px) { .pf-menu-day { flex-basis: 100%; } }
`;

const DAYS = ["Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche"];

export const MENU_HTML = `
  <section id="pf-menu" data-title="Menu de la semaine" data-icon="restaurant">
    <div class="sechead"><h2>Menu de la semaine</h2><span class="dim">Le widget « Repas du jour » de la tablette montre celui d'aujourd'hui et de demain. Laisse une case vide quand il n'y a pas de repas.</span></div>
    <div class="card group" id="pf-menu-days"></div>
    <div class="pf-foot">
      <p class="pf-msg" id="pf-menu-msg" role="status"></p>
      <button type="button" id="pf-menu-save">Valider le menu</button>
    </div>
  </section>
`;

export const MENU_JS = `
  const pfMenuDays = ${JSON.stringify(DAYS)};
  const pfMenuMeals = [["lunch", "Midi"], ["dinner", "Soir"]];

  function pfMenuRow(name, index) {
    const row = document.createElement("div");
    row.className = "row";
    const label = document.createElement("span");
    label.className = "pf-menu-day";
    label.textContent = name;
    row.append(label);
    pfMenuMeals.forEach((meal) => {
      const input = document.createElement("input");
      input.type = "text";
      input.maxLength = 80;
      input.autocomplete = "off";
      input.setAttribute("aria-label", name + ", " + meal[1].toLowerCase());
      input.dataset.day = String(index);
      input.dataset.meal = meal[0];
      const cell = document.createElement("label");
      cell.className = "pf-menu-meal";
      const tag = document.createElement("span");
      tag.textContent = meal[1];
      cell.append(tag, input);
      row.append(cell);
    });
    return row;
  }

  async function pfMenuLoad() {
    const box = document.getElementById("pf-menu-days");
    const msg = document.getElementById("pf-menu-msg");
    try {
      const res = await fetch("/local/config/menu");
      if (!res.ok) throw new Error("menu illisible");
      const days = (await res.json()).days;
      box.replaceChildren(...pfMenuDays.map(pfMenuRow));
      box.querySelectorAll("input").forEach((input) => { input.value = days[Number(input.dataset.day)][input.dataset.meal]; });
    } catch (e) {
      pfSay(msg, "Le menu ne s'est pas chargé : recharge la page.", "ko");
    }
  }

  async function pfMenuSave() {
    const msg = document.getElementById("pf-menu-msg");
    const days = pfMenuDays.map(() => ({ lunch: "", dinner: "" }));
    document.querySelectorAll("#pf-menu-days input").forEach((input) => { days[Number(input.dataset.day)][input.dataset.meal] = input.value; });
    try {
      const res = await fetch("/local/config/menu", { method: "PUT", headers: { "content-type": "application/json" }, body: JSON.stringify({ days: days }) });
      const json = await res.json().catch(() => ({}));
      if (!res.ok) return pfSay(msg, json.error || "Le menu ne s'est pas enregistré : réessaie.", "ko");
      document.querySelectorAll("#pf-menu-days input").forEach((input) => { input.value = json.days[Number(input.dataset.day)][input.dataset.meal]; });
      pfSay(msg, "Menu enregistré.", "ok");
    } catch (e) {
      pfSay(msg, "Le compagnon ne répond pas", "ko");
    }
  }

  document.getElementById("pf-menu-save").onclick = pfMenuSave;
  pfMenuLoad();
`;

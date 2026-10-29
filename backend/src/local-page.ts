/**
 * Page http://127.0.0.1:3000/ ouverte sur l'ordinateur (ouverte par l'installation) : état du compagnon,
 * marche à suivre sur la tablette, réglages de la tablette connectée (thème, écran, son) et, pendant un appairage,
 * la case où saisir le code affiché par la tablette.
 * Lue sans jeton, en local seulement (voir isLocalPage dans server.ts).
 */
export const LOCAL_PAGE = `<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Compagnon Unowhy</title>
<style>
  :root { color-scheme: light dark; --bg: #f5f6f8; --card: #fff; --text: #1b1f24; --dim: #5f6b7a; --accent: #2f6fed; --ok: #1f9d55; --ko: #d64545; }
  @media (prefers-color-scheme: dark) { :root { --bg: #14171c; --card: #1e232b; --text: #e8ecf1; --dim: #9aa6b5; --accent: #6d9bff; } }
  body { margin: 0; background: var(--bg); color: var(--text); font: 16px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
  main { max-width: 640px; margin: 0 auto; padding: 32px 16px; }
  h1 { font-size: 24px; margin: 0 0 4px; }
  .card { background: var(--card); border-radius: 14px; padding: 20px 22px; margin-top: 16px; }
  .dim { color: var(--dim); }
  .dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 8px; background: var(--ko); }
  .dot.on { background: var(--ok); }
  code { font-size: 15px; }
  ol { margin: 8px 0 0; padding-left: 22px; }
  #paircode { font: 600 40px/1.2 ui-monospace, "SF Mono", Consolas, monospace; letter-spacing: 6px; text-align: center; color: var(--accent); }
  #pairing { display: none; border: 2px solid var(--accent); }
  input { width: 100%; box-sizing: border-box; font: inherit; padding: 10px 12px; border-radius: 10px; border: 1px solid var(--dim); background: var(--bg); color: var(--text); margin-top: 10px; }
  button { font: inherit; padding: 8px 16px; border-radius: 10px; border: 0; background: var(--accent); color: #fff; margin-top: 10px; cursor: pointer; }
  button.ghost { background: transparent; color: var(--dim); border: 1px solid var(--dim); margin-left: 8px; }
  details { margin-top: 8px; }
  /* Ouverte depuis la tablette (« Relier mon agenda ») : la carte Agenda est mise en avant. */
  .card.focus { border: 2px solid var(--accent); }
  .card.warn { border: 2px solid var(--ko); }
  .account { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
  .account .grow { flex: 1; }
  .account button { margin-top: 0; }
  a.button { display: inline-block; font: inherit; padding: 10px 18px; border-radius: 10px; background: var(--accent); color: #fff;
    text-decoration: none; margin: 10px 8px 0 0; }
  .ok { color: var(--ok); } .ko { color: var(--ko); }
  .row { display: flex; align-items: center; gap: 12px; margin-top: 14px; }
  .row label { flex: 1; }
  .row input[type=range] { flex: 1; margin: 0; padding: 0; border: 0; accent-color: var(--accent); }
  .row input[type=checkbox] { width: 20px; height: 20px; margin: 0; accent-color: var(--accent); }
  .row .value { min-width: 48px; text-align: right; font-variant-numeric: tabular-nums; }
  .themes { display: flex; gap: 8px; margin-top: 10px; }
  .themes button { flex: 1; margin: 0; background: transparent; color: var(--text); border: 1px solid var(--dim); }
  .themes button[aria-pressed=true] { background: var(--accent); color: #fff; border-color: var(--accent); }
</style>
</head>
<body>
<main>
  <h1 id="name">Compagnon Unowhy</h1>
  <div class="dim" id="os"></div>

  <!-- Mac sans l'autorisation Accessibilité : fenêtres et raccourcis du deck ne marchent pas encore. -->
  <div class="card warn" id="access" hidden>
    <b>Une autorisation manque sur ce Mac</b>
    <div class="dim" style="margin-top:6px">Pour placer les fenêtres et envoyer les raccourcis du deck, autorise le compagnon dans
      Réglages Système › Confidentialité et sécurité › <b>Accessibilité</b>, puis active l'interrupteur.</div>
    <button id="accessopen" style="margin-top:12px">Ouvrir le réglage</button>
  </div>

  <!-- Le code vient de la tablette : il prouve qu'elle parle bien à cet ordinateur (voir src/pairing.ts). -->
  <div class="card" id="pairing">
    <div><b id="tablet">La tablette</b> demande à se connecter. Saisis le code affiché sur la tablette :</div>
    <form id="pairform">
      <input id="paircode" inputmode="numeric" autocomplete="off" maxlength="7" placeholder="000 000" aria-label="Code affiché sur la tablette">
      <button type="submit">Connecter la tablette</button>
    </form>
    <div class="dim" id="pairmsg">Valable 2 minutes. Ne saisis ici qu'un code affiché par ta tablette.</div>
  </div>

  <div class="card">
    <div><span class="dot" id="dot"></span><span id="state">Tablette non connectée</span></div>
    <div class="dim" style="margin-top:8px">Adresse de cet ordinateur : <code id="addr">…</code></div>
  </div>

  <!-- Tablette connectée : son état et ses réglages, changés d'ici (ordre « settings », voir routes/local.ts). -->
  <div class="card" id="tabletcard" hidden>
    <b>Réglages de la tablette</b>
    <div class="dim" id="tabletinfo"></div>
    <div class="dim" id="tabletold" hidden style="margin-top:8px">Mets à jour l'appli de la tablette pour la régler d'ici.</div>
    <div id="tabletsettings">
      <div style="margin-top:14px">Thème</div>
      <div class="themes" id="themes">
        <button data-theme="CADRAN">Cadran</button><button data-theme="CADRAN_NUIT">Cadran nuit</button><button data-theme="RETRO">Rétro</button>
      </div>
      <div class="row"><label for="brightness">Luminosité de l'écran</label><input type="range" id="brightness" min="5" max="100"><span class="value" id="brightnessvalue"></span></div>
      <div class="row"><label for="volume">Volume du haut-parleur</label><input type="range" id="volume" min="0" max="100"><span class="value" id="volumevalue"></span></div>
      <div class="row"><label for="nightdim">Atténuer l'écran la nuit (22 h – 7 h)</label><input type="checkbox" id="nightdim"></div>
      <div class="row"><label for="focusdnd">Ne pas déranger sur l'ordinateur pendant la concentration</label><input type="checkbox" id="focusdnd"></div>
      <div class="dim" id="tabletmsg" style="margin-top:10px"></div>
    </div>
  </div>

  <div class="card">
    <b>Connecter une tablette</b>
    <ol>
      <li>Tablette et ordinateur sur le même WiFi.</li>
      <li>Sur la tablette : <b>Réglages › Ordinateurs › Ajouter</b>, puis toucher cet ordinateur.</li>
      <li>Saisir ici le code qui s'affiche sur la tablette.</li>
    </ol>
    <div class="dim" style="margin-top:8px">Ordinateur absent de la liste : « Saisir l'adresse » avec l'adresse ci-dessus.</div>
  </div>

  <div class="card" id="agenda">
    <b>Agenda de cet ordinateur</b>
    <div class="dim" id="calstate">…</div>
    <!-- Comptes connectés et boutons « Se connecter avec … » (si l'appli est enregistrée, voir src/accounts.ts). -->
    <div id="accounts"></div>
    <div id="accountmsg" class="dim" style="margin-top:8px"></div>
    <details id="ics" style="margin-top:12px">
      <summary id="icssummary">Lien ICS de ton agenda</summary>
      <input id="calurl" placeholder="Lien ICS (https://… ou webcal://…)" autocomplete="off">
      <button id="calsave">Enregistrer</button><button class="ghost" id="calclear">Retirer</button>
      <div id="calmsg" class="dim" style="margin-top:8px"></div>
      <details class="dim" id="icshelp">
        <summary>Où trouver le lien ICS ?</summary>
        <b>Outlook</b> (outlook.office.com) : Paramètres › Calendrier › Calendriers partagés › Publier un calendrier, choisir
        « Peut afficher tous les détails », Publier, puis copier le lien <b>ICS</b>.<br>
        <b>Google Agenda</b> : Paramètres › (ton agenda) › Intégrer l'agenda › Adresse secrète au format iCal.<br>
        <b>iCloud</b> : app Calendrier › clic droit sur l'agenda › Partager › Agenda public, copier le lien webcal://.
      </details>
    </details>
  </div>
</main>
<script>
  const OS = { mac: "macOS", windows: "Windows", other: "Linux (pilotage indisponible)" };
  async function refresh() {
    try {
      const s = await (await fetch("/local/status")).json();
      document.getElementById("name").textContent = s.name;
      document.getElementById("access").hidden = s.accessibility !== false;
      document.getElementById("os").textContent = "Compagnon prêt · " + (OS[s.os] || s.os);
      document.getElementById("addr").textContent = s.addresses.map((a) => a + ":" + (s.tlsPort || s.port)).join("  ·  ") || "aucune (pas de réseau)";
      document.getElementById("dot").className = "dot" + (s.tablet ? " on" : "");
      document.getElementById("state").textContent = s.tablet ? "Tablette connectée" : "Tablette non connectée";
      const c = s.calendar, n = c.env + c.saved.length + c.accounts.length;
      document.getElementById("calstate").textContent = n === 0 ? "Aucun agenda : la tablette propose de le relier."
        : (c.saved.length ? "Lien enregistré : " + c.saved.join(", ") : "") + (c.env ? (c.saved.length ? " · " : "") + c.env + " lien(s) dans .env" : "");
      renderAccounts(c);
      renderTablet(s.tabletInfo);
      const p = s.pairing, card = document.getElementById("pairing");
      const shown = card.style.display === "block";
      card.style.display = p ? "block" : "none";
      if (p) {
        document.getElementById("tablet").textContent = p.tablet;
        if (!shown) document.getElementById("paircode").focus();
      }
    } catch (e) {
      document.getElementById("os").textContent = "Compagnon arrêté";
    }
  }
  // Comptes : reconstruits seulement s'ils changent (la page se rafraîchit toutes les 1,5 s).
  let accountsKey = "", firstRender = true;
  function renderAccounts(c) {
    const key = JSON.stringify([c.accounts, c.providers]);
    if (key !== accountsKey) {
      accountsKey = key;
      const box = document.getElementById("accounts");
      box.replaceChildren();
      for (const a of c.accounts) {
        const row = document.createElement("div");
        row.className = "account";
        const label = document.createElement("span");
        label.className = "grow";
        label.innerHTML = a.expired ? '<span class="ko">⚠</span> ' : '<span class="ok">✓</span> ';
        label.append(a.label + " · " + a.email + (a.expired ? " (à reconnecter)" : ""));
        const off = document.createElement("button");
        off.className = "ghost";
        off.textContent = "Déconnecter";
        off.onclick = async () => { await fetch("/local/accounts/" + a.provider, { method: "DELETE" }); refresh(); };
        row.append(label);
        if (a.expired) row.append(connectLink(a.provider, "Reconnecter"));
        row.append(off);
        box.append(row);
      }
      for (const p of c.providers) {
        if (!c.accounts.some((a) => a.provider === p.provider)) box.append(connectLink(p.provider, "Se connecter avec " + p.label));
      }
      // Avec un compte possible, le lien ICS devient la solution de repli.
      document.getElementById("icssummary").textContent = c.providers.length ? "Autre agenda : lien ICS" : "Lien ICS de ton agenda";
    }
    if (firstRender) {
      firstRender = false;
      if (!c.providers.length) document.getElementById("ics").open = true;
      if (new URLSearchParams(location.search).get("section") === "agenda" && !c.providers.length) {
        document.getElementById("icshelp").open = true;
        document.getElementById("calurl").focus();
      }
    }
  }
  // Réglages de la tablette : pas réécrits pendant un réglage en cours (curseur tenu, envoi pas encore fini).
  let tabletBusyUntil = 0;
  const percent = (v) => Math.round(v * 100);
  function renderTablet(t) {
    document.getElementById("tabletcard").hidden = !t;
    if (!t) return;
    const b = t.battery;
    document.getElementById("tabletinfo").textContent = [
      t.model, t.app && "appli " + t.app, b && "batterie " + b.level + " %" + (b.charging ? " (en charge)" : ""),
    ].filter(Boolean).join(" · ");
    const st = t.settings;
    document.getElementById("tabletold").hidden = !!st;
    document.getElementById("tabletsettings").hidden = !st;
    if (!st || Date.now() < tabletBusyUntil) return;
    for (const btn of document.querySelectorAll("#themes button")) btn.setAttribute("aria-pressed", String(btn.dataset.theme === st.theme));
    for (const id of ["brightness", "volume"]) {
      document.getElementById(id).value = percent(st[id]);
      document.getElementById(id + "value").textContent = percent(st[id]) + " %";
    }
    document.getElementById("nightdim").checked = st.nightDim;
    document.getElementById("focusdnd").checked = st.focusDnd;
  }
  async function saveTablet(change) {
    const msg = document.getElementById("tabletmsg");
    tabletBusyUntil = Date.now() + 20000;
    msg.textContent = "Envoi à la tablette…";
    try {
      const res = await fetch("/local/tablet/settings", { method: "PUT", headers: { "content-type": "application/json" }, body: JSON.stringify(change) });
      const r = await res.json();
      msg.textContent = res.ok ? "Réglé sur la tablette." : "Pas réglé : " + (r.error || r.message);
    } catch (e) {
      msg.textContent = "Compagnon injoignable";
    }
    tabletBusyUntil = 0;
    refresh();
  }
  for (const btn of document.querySelectorAll("#themes button")) {
    btn.onclick = () => {
      for (const other of document.querySelectorAll("#themes button")) other.setAttribute("aria-pressed", String(other === btn));
      saveTablet({ theme: btn.dataset.theme });
    };
  }
  for (const id of ["brightness", "volume"]) {
    const range = document.getElementById(id);
    // Valeur affichée pendant le glisser, envoyée au lâcher.
    range.oninput = () => {
      tabletBusyUntil = Date.now() + 20000;
      document.getElementById(id + "value").textContent = range.value + " %";
    };
    range.onchange = () => saveTablet({ [id]: range.value / 100 });
  }
  document.getElementById("nightdim").onchange = (e) => saveTablet({ nightDim: e.target.checked });
  document.getElementById("focusdnd").onchange = (e) => saveTablet({ focusDnd: e.target.checked });
  function connectLink(provider, text) {
    const link = document.createElement("a");
    link.className = "button";
    link.href = "/oauth/" + provider + "/start";
    link.textContent = text;
    return link;
  }
  const ACCOUNT_MSG = {
    ok: "✓ Compte connecté : l'agenda arrive sur la tablette dans la minute.",
    refused: "Connexion annulée.",
    error: "La connexion a échoué. Réessaie ; sinon colle un lien ICS ci-dessous.",
  };
  const accountResult = new URLSearchParams(location.search).get("account");
  if (ACCOUNT_MSG[accountResult]) document.getElementById("accountmsg").textContent = ACCOUNT_MSG[accountResult];

  async function saveCalendar(urls) {
    const msg = document.getElementById("calmsg");
    msg.textContent = "Vérification…";
    try {
      const res = await fetch("/local/calendar", { method: "PUT", headers: { "content-type": "application/json" }, body: JSON.stringify({ urls }) });
      const r = await res.json();
      if (!res.ok) msg.textContent = "Lien refusé : " + (r.message || r.error);
      else if (!urls.length) msg.textContent = "Agenda retiré.";
      else msg.textContent = r.ok ? "Agenda enregistré : " + r.today + " événement(s) aujourd'hui." : "Enregistré, mais illisible : " + r.error;
      document.getElementById("calurl").value = "";
      refresh();
    } catch (e) {
      msg.textContent = "Compagnon injoignable";
    }
  }
  document.getElementById("pairform").onsubmit = async (e) => {
    e.preventDefault();
    const input = document.getElementById("paircode"), msg = document.getElementById("pairmsg");
    try {
      const res = await fetch("/local/pair", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ code: input.value }) });
      const json = await res.json();
      msg.textContent = res.ok ? "Tablette connectée : « " + json.tablet + " »." : json.error;
      if (res.ok) input.value = "";
    } catch (err) {
      msg.textContent = "Compagnon injoignable";
    }
  };
  document.getElementById("accessopen").onclick = async () => {
    await fetch("/system/accessibility", { method: "POST" });
  };
  document.getElementById("calsave").onclick = () => {
    const url = document.getElementById("calurl").value.trim();
    if (url) saveCalendar([url]);
  };
  document.getElementById("calclear").onclick = () => saveCalendar([]);
  if (new URLSearchParams(location.search).get("section") === "agenda") {
    const card = document.getElementById("agenda");
    card.classList.add("focus");
    card.scrollIntoView({ block: "center" });
  }
  refresh();
  setInterval(refresh, 1500);
</script>
</body>
</html>
`;

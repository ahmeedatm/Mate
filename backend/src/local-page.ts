import { CADRAN_CSS, FAVICON, ICON_SVG } from "./web-style.ts";

/**
 * Page http://127.0.0.1:3000/ ouverte sur l'ordinateur (le panneau de l'appli : relancer « Compagnon Unowhy » la rouvre) :
 * état du compagnon, marche à suivre sur la tablette, réglages de la tablette connectée (thème, écran, son), la case
 * où saisir le code d'appairage affiché par la tablette, et la carte « Cette appli » (lancement au démarrage,
 * pare-feu, ancienne installation, mise à jour, quitter, tout oublier).
 * Lue sans jeton, en local seulement (voir isLocalPage dans server.ts).
 */
export const LOCAL_PAGE = `<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Compagnon Unowhy</title>
${FAVICON}
<style>
  ${CADRAN_CSS}
  * { box-sizing: border-box; }
  /* Les display posés plus bas (boutons, lignes) ne doivent pas l'emporter sur l'attribut hidden. */
  [hidden] { display: none !important; }
  body { margin: 0; background: var(--bg); color: var(--ink); font: 400 16px/1.5 Outfit, system-ui, sans-serif; -webkit-font-smoothing: antialiased; }
  main { max-width: 720px; margin: 0 auto; padding: 48px 24px 64px; }
  header { display: flex; align-items: center; gap: 16px; margin-bottom: 32px; }
  header .logo svg { display: block; width: 56px; height: 56px; }
  h1 { font: 500 28px/34px Outfit, sans-serif; letter-spacing: -.01em; margin: 0; }
  h2 { font: 500 15px/20px Outfit, sans-serif; color: var(--ink-2); margin: 36px 0 10px 28px; }
  header p { margin: 0; color: var(--ink-2); font-size: 15px; }
  b { font-weight: 500; }
  .card { background: var(--tile); border-radius: 32px; padding: 24px 28px; margin-top: 16px; }
  h2 + .card { margin-top: 0; }
  .dim { color: var(--ink-2); font-size: 15px; }
  /* Liste à lignes : séparateur fin qui commence au texte, comme les listes de l'appli. */
  .card.liste { padding: 0; overflow: hidden; }
  .ligne { display: flex; align-items: center; gap: 16px; padding: 18px 28px; min-height: 64px; }
  .ligne + .ligne { border-top: 1px solid var(--line); margin-left: 28px; padding-left: 0; }
  .ligne .t { flex: 1; }
  .dot { width: 10px; height: 10px; border-radius: 5px; background: var(--ink-3); flex: none; }
  .dot.on { background: var(--ok); }
  code { font: 500 15px Outfit, sans-serif; font-feature-settings: "tnum"; color: var(--ink); background: var(--tile-high); padding: 4px 10px; border-radius: 8px; }
  ol { margin: 0; padding-left: 20px; color: var(--ink-2); font-size: 15px; }
  ol li { padding: 4px 0; }
  ol b { color: var(--ink); }
  /* Code d'appairage : grands chiffres fins, et la ligne du jour qui avance pendant ses 2 minutes. */
  #pairing { display: none; padding: 32px 36px 28px; }
  #paircode { font: 300 64px/1.1 Outfit, sans-serif; font-feature-settings: "tnum"; letter-spacing: .04em; height: auto; padding: 14px 0 18px;
    border-radius: 0; outline: 0; background: transparent; margin: 0; }
  #paircode::placeholder { color: var(--line); }
  .minuteur { --p: 0; position: relative; height: 2px; background: var(--line); border-radius: 1px; margin-bottom: 14px; }
  .minuteur i { position: absolute; left: 0; top: 0; height: 2px; width: calc(var(--p) * 100%); background: var(--ink-3); border-radius: 1px; transition: width 1.5s linear; }
  .minuteur b { position: absolute; left: calc(var(--p) * 100%); top: -5px; width: 12px; height: 12px; margin-left: -6px; border-radius: 6px;
    background: var(--now); box-shadow: 0 0 0 3px var(--tile); transition: left 1.5s linear; }
  input { width: 100%; font: 400 15px Outfit, sans-serif; height: 48px; padding: 0 20px; border-radius: 24px; border: 0; outline: 1px solid var(--line);
    background: var(--bg); color: var(--ink); margin-top: 12px; }
  input:focus { outline: 2px solid var(--ink); }
  button, a.button { display: inline-flex; align-items: center; height: 48px; padding: 0 22px; border-radius: 24px; border: 0; font: 500 15px Outfit, sans-serif;
    background: var(--ink); color: var(--bg); margin-top: 12px; cursor: pointer; text-decoration: none; }
  button:hover, a.button:hover { opacity: .88; }
  button.ghost { background: var(--tile-high); color: var(--ink); margin-left: 8px; }
  a.button { margin-right: 8px; }
  details { margin-top: 12px; }
  summary { cursor: pointer; color: var(--ink-2); font-size: 15px; }
  details.dim { line-height: 1.6; }
  /* Ouverte depuis la tablette (« Relier mon agenda », « Régler depuis l'ordi ») : la carte demandée est mise en avant. */
  .card.focus { outline: 2px solid var(--now); }
  .card.warn { outline: 2px solid var(--now); }
  .account { display: flex; align-items: center; gap: 8px; margin-top: 12px; }
  .account .grow { flex: 1; }
  .account button { margin-top: 0; }
  .ok { color: var(--ok); } .ko { color: var(--ko); }
  .row { display: flex; align-items: center; gap: 16px; margin-top: 16px; min-height: 32px; }
  .row label { flex: 1; }
  .row input[type=range] { flex: 1; height: auto; margin: 0; padding: 0; outline: 0; background: transparent; accent-color: var(--now); }
  .row input[type=checkbox] { width: 20px; height: 20px; margin: 0; padding: 0; outline: 0; accent-color: var(--now); }
  .row .grow { flex: 1; }
  .row button { margin-top: 0; }
  .row .value { min-width: 48px; text-align: right; font-feature-settings: "tnum"; color: var(--ink-2); }
  .label { margin-top: 18px; }
  /* Pastilles de thème : comme les puces de l'appli (sélection = fond doux et contour de la couleur « maintenant »). */
  .themes { display: flex; gap: 10px; margin-top: 10px; }
  .themes button { flex: 1; justify-content: center; margin: 0; background: var(--tile-high); color: var(--ink); outline: 1px solid transparent; }
  .themes button[aria-pressed=true] { background: var(--now-soft); color: var(--now); outline-color: var(--now); }
  #tabletinfo { margin-top: 2px; }
</style>
</head>
<body>
<main>
  <header><span class="logo">${ICON_SVG}</span><div><h1 id="name">Compagnon Unowhy</h1><p id="os"></p></div></header>

  <!-- Mac sans l'autorisation Accessibilité : fenêtres et raccourcis du deck ne marchent pas encore. -->
  <div class="card warn" id="access" hidden>
    <b>Une autorisation manque sur ce Mac</b>
    <div class="dim" style="margin-top:6px">Pour placer les fenêtres et envoyer les raccourcis du deck, autorise le compagnon dans
      Réglages Système › Confidentialité et sécurité › <b>Accessibilité</b>, puis active l'interrupteur.</div>
    <button id="accessopen">Ouvrir le réglage</button>
  </div>

  <!-- Le code vient de la tablette : il prouve qu'elle parle bien à cet ordinateur (voir src/pairing.ts). -->
  <div class="card" id="pairing">
    <div class="dim"><b id="tablet">La tablette</b> demande à se connecter. Saisis le code affiché sur la tablette :</div>
    <form id="pairform">
      <input id="paircode" inputmode="numeric" autocomplete="off" maxlength="7" placeholder="000 000" aria-label="Code affiché sur la tablette">
      <div class="minuteur" id="pairtimer"><i></i><b></b></div>
      <button type="submit">Connecter la tablette</button>
    </form>
    <div class="dim" id="pairmsg" style="margin-top:12px">Valable 2 minutes. Ne saisis ici qu'un code affiché par ta tablette.</div>
  </div>

  <!-- Tablette connectée : sa carte passe juste sous l'en-tête, avant l'état et l'adresse. Son état et ses réglages, changés d'ici (ordre « settings », voir routes/local.ts). -->
  <section id="tabletcard" hidden>
    <h2>Réglages de la tablette</h2>
    <div class="card">
      <div class="dim" id="tabletinfo"></div>
      <div class="row" id="updaterow" hidden><span class="grow" id="updatestate"></span><button id="updatebtn" hidden></button></div>
      <div class="dim" id="tabletold" hidden style="margin-top:8px">Mets à jour l'appli de la tablette pour la régler d'ici.</div>
      <div id="tabletsettings">
        <div class="label">Thème</div>
        <div class="themes" id="themes">
          <button data-theme="CADRAN">Cadran</button><button data-theme="CADRAN_NUIT">Cadran nuit</button><button data-theme="RETRO">Rétro</button>
        </div>
        <div class="row"><label for="brightness">Luminosité de l'écran</label><input type="range" id="brightness" min="5" max="100"><span class="value" id="brightnessvalue"></span></div>
        <div class="row"><label for="volume">Volume du haut-parleur</label><input type="range" id="volume" min="0" max="100"><span class="value" id="volumevalue"></span></div>
        <div class="row"><label for="nightdim">Atténuer l'écran la nuit (22 h – 7 h)</label><input type="checkbox" id="nightdim"></div>
        <div class="row"><label for="focusdnd">Ne pas déranger sur l'ordinateur pendant la concentration</label><input type="checkbox" id="focusdnd"></div>
        <div class="dim" id="tabletmsg" style="margin-top:12px"></div>
      </div>
    </div>
  </section>

  <div class="card liste">
    <div class="ligne"><span class="t" id="state">Tablette non connectée</span><span class="dot" id="dot"></span></div>
    <div class="ligne"><span class="t">Adresse de cet ordinateur</span><code id="addr">…</code></div>
  </div>

  <h2>Connecter une tablette</h2>
  <div class="card">
    <ol>
      <li>Tablette et ordinateur sur le même WiFi.</li>
      <li>Sur la tablette : <b>Réglages › Ordinateurs › Ajouter</b>, puis toucher cet ordinateur.</li>
      <li>Saisir ici le code qui s'affiche sur la tablette.</li>
    </ol>
    <div class="dim" style="margin-top:8px">Ordinateur absent de la liste : « Saisir l'adresse » avec l'adresse ci-dessus.</div>
  </div>

  <h2>Agenda de cet ordinateur</h2>
  <div class="card" id="agenda">
    <div class="dim" id="calstate">…</div>
    <!-- Comptes connectés et boutons « Se connecter avec … » (si l'appli est enregistrée, voir src/accounts.ts). -->
    <div id="accounts"></div>
    <div id="accountmsg" class="dim" style="margin-top:8px"></div>
    <details id="ics">
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

  <!-- Compte GitLab (onglet GitLab du Deck) : visible si l'appli OAuth est réglée, un compte connecté ou GITLAB_TOKEN (src/gitlab-auth.ts). -->
  <div class="card" id="gitlab" hidden>
    <b>GitLab</b>
    <div class="dim" id="gitlabstate"></div>
    <div id="gitlabaccount"></div>
    <div id="gitlabmsg" class="dim" style="margin-top:8px"></div>
  </div>
  <!-- Appli portable seulement (voir src/routes/portable.ts) : rien n'est installé, ces réglages sont ceux de l'utilisateur. -->
  <section id="panel" hidden>
    <h2>Cette appli</h2>
    <div class="card">
      <div class="dim" id="appinfo"></div>
      <div class="row"><label for="autostart">Lancer au démarrage de l'ordinateur</label><input type="checkbox" id="autostart"></div>
      <div class="dim">Rien n'est inscrit tant que la case n'est pas cochée. Sinon, rouvre « Compagnon Unowhy » quand tu en as besoin.</div>
      <div id="firewallrow" hidden>
        <div class="row"><span class="grow" id="firewallstate"></span><button id="firewallopen">Ouvrir le pare-feu</button></div>
        <div class="dim">Réseaux privés seulement. Windows demande l'accord d'un administrateur.</div>
      </div>
      <div id="legacyrow" hidden>
        <div class="label"><b>Ancienne installation trouvée</b></div>
        <div class="row"><span class="grow dim" id="legacylist"></span><button id="legacyremove">Retirer</button></div>
        <div class="dim">Tes réglages, ton agenda et l'appairage de la tablette sont gardés.</div>
      </div>
      <div class="row"><span class="grow dim" id="appupdatestate">Mise à jour depuis la tablette (même WiFi).</span><button id="updatecheck">Chercher</button><button id="updateinstall" hidden>Installer</button></div>
      <div class="dim">Extension du navigateur (onglets, page Média) : à charger en mode développeur depuis <code id="extpath"></code></div>
      <div class="row"><span class="grow"></span><button class="ghost" id="quit">Quitter</button><button class="ghost" id="forget">Tout oublier</button></div>
      <div class="dim">Tout oublier retire le lancement au démarrage, l'appairage, l'agenda et les tâches de cet ordinateur.</div>
      <div id="panelmsg" class="dim" style="margin-top:12px"></div>
    </div>
  </section>

  <!-- Politique de confidentialité (config/confidentialite.txt) ; compagnon installé : licences des composants tiers (LICENCES-TIERS.txt, scripts/package.ts). -->
  <p class="dim">
    <a href="/local/confidentialite" target="_blank">Confidentialité</a>
    <span id="licences" hidden> · <a href="/local/licences" target="_blank">Licences des composants</a></span>
  </p>
</main>
<script>
  const OS = { mac: "macOS", windows: "Windows", other: "Linux (pilotage indisponible)" };
  let version = null;
  async function refresh() {
    try {
      const s = await (await fetch("/local/status")).json();
      // Mise à jour posée : le nouveau compagnon répond avec une autre version, la page se recharge.
      if (version !== null && s.version !== version) return location.reload();
      version = s.version;
      document.getElementById("name").textContent = s.name;
      document.getElementById("licences").hidden = !s.licences;
      document.getElementById("access").hidden = s.accessibility !== false;
      document.getElementById("os").textContent = "Compagnon prêt · " + (OS[s.os] || s.os);
      document.getElementById("addr").textContent = s.addresses.map((a) => a + ":" + (s.tlsPort || s.port)).join("  ·  ") || "aucune (pas de réseau)";
      document.getElementById("dot").className = "dot" + (s.tablet ? " on" : "");
      document.getElementById("state").textContent = s.tablet ? "Tablette connectée" : "Tablette non connectée";
      const c = s.calendar, n = c.env + c.saved.length + c.accounts.length;
      document.getElementById("calstate").textContent = n === 0 ? "Aucun agenda : la tablette propose de le relier."
        : (c.saved.length ? "Lien enregistré : " + c.saved.join(", ") : "") + (c.env ? (c.saved.length ? " · " : "") + c.env + " lien(s) dans .env" : "");
      renderAccounts(c);
      renderGitlab(s.gitlab);
      renderTablet(s.tabletInfo);
      document.title = s.tabletInfo ? "Compagnon · " + (s.tabletInfo.name || "tablette") : "Compagnon Unowhy";
      const p = s.pairing, card = document.getElementById("pairing");
      const shown = card.style.display === "block";
      card.style.display = p ? "block" : "none";
      if (p) {
        document.getElementById("tablet").textContent = p.tablet;
        // Ligne du jour : le point avance pendant la validité du code.
        if (p.ttlS) document.getElementById("pairtimer").style.setProperty("--p", String(1 - p.remainingS / p.ttlS));
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
        label.innerHTML = a.expired || a.mailMissing ? '<span class="ko">⚠</span> ' : '<span class="ok">✓</span> ';
        label.append(a.label + " · " + a.email + (a.expired ? " (à reconnecter)" : a.mailMissing ? " (à reconnecter pour le courrier)" : ""));
        const off = document.createElement("button");
        off.className = "ghost";
        off.textContent = "Déconnecter";
        off.onclick = async () => { await fetch("/local/accounts/" + a.provider, { method: "DELETE" }); refresh(); };
        row.append(label);
        if (a.expired || a.mailMissing) row.append(connectLink(a.provider, "Reconnecter"));
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
  // GitLab : reconstruit seulement s'il change (même raison que les comptes).
  let gitlabKey = "";
  function renderGitlab(g) {
    document.getElementById("gitlab").hidden = !(g.available || g.account || g.token);
    const key = JSON.stringify(g);
    if (key === gitlabKey) return;
    gitlabKey = key;
    const box = document.getElementById("gitlabaccount");
    box.replaceChildren();
    const state = document.getElementById("gitlabstate");
    if (g.account) {
      state.textContent = "La tablette lit et fusionne tes MR avec ce compte.";
      const row = document.createElement("div");
      row.className = "account";
      const label = document.createElement("span");
      label.className = "grow";
      label.innerHTML = g.account.expired ? '<span class="ko">⚠</span> ' : '<span class="ok">✓</span> ';
      label.append("GitLab" + (g.account.username ? " · " + g.account.username : "") + (g.account.expired ? " (à reconnecter)" : ""));
      const off = document.createElement("button");
      off.className = "ghost";
      off.textContent = "Déconnecter GitLab";
      off.onclick = async () => { await fetch("/local/accounts/gitlab", { method: "DELETE" }); refresh(); };
      row.append(label);
      if (g.account.expired) row.append(connectLink("gitlab", "Reconnecter"));
      row.append(off);
      box.append(row);
    } else {
      state.textContent = g.token ? "Jeton GITLAB_TOKEN du fichier .env utilisé. Le compte connecté, s'il y en a un, passe avant." : "Pas connecté.";
      if (g.available) box.append(connectLink("gitlab", "Se connecter avec GitLab"));
    }
  }
  // Réglages de la tablette : pas réécrits pendant un réglage en cours (curseur tenu, envoi pas encore fini).
  let tabletBusyUntil = 0;
  const percent = (v) => Math.round(v * 100);
  let tabletFocused = false;
  function renderTablet(t) {
    document.getElementById("tabletcard").hidden = !t;
    if (!t) return;
    // « Régler depuis l'ordi » : carte mise en avant une fois, dès que la tablette est vue connectée.
    if (!tabletFocused && new URLSearchParams(location.search).get("section") === "tablette") {
      tabletFocused = true;
      const card = document.querySelector("#tabletcard .card");
      card.classList.add("focus");
      card.scrollIntoView({ block: "center" });
    }
    const b = t.battery;
    document.getElementById("tabletinfo").textContent = [
      t.model, t.app && "appli " + t.app, b && "batterie " + b.level + " %" + (b.charging ? " (en charge)" : ""),
    ].filter(Boolean).join(" · ");
    renderUpdate(t);
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
  // Mise à jour de l'appli de la tablette : état remonté par la tablette (RemoteUpdate). Recherche lancée seule une fois
  // par version de l'appli : à l'ouverture, puis après une installation (l'appli relancée n'a encore rien cherché).
  // updateNote : refus de la dernière demande, gardé jusqu'à la suivante (sinon effacé au rafraîchissement).
  let checkedFor = null, updateBusy = false, updateNote = null;
  function renderUpdate(t) {
    const u = t.update;
    document.getElementById("updaterow").hidden = !u;
    if (!u || updateBusy) return;
    let text, action = null;
    if (u.installing) text = "Installation de la version " + u.available + " en cours : la tablette redémarre l'appli à la fin.";
    else if (!u.kiosk) text = "Mise à jour d'ici possible seulement en kiosque (pas dans l'appli d'essai).";
    else if (u.error) [text, action] = [u.error, "check"];
    else if (!u.checked) text = "Recherche d'une mise à jour…";
    else if (u.available) [text, action] = ["Version " + u.available + " disponible (installée : " + t.app + ").", "install"];
    else [text, action] = ["Appli à jour (" + t.app + ").", "check"];
    if (updateNote) text = updateNote;
    document.getElementById("updatestate").textContent = text;
    const btn = document.getElementById("updatebtn");
    btn.hidden = !action;
    btn.textContent = action === "install" ? "Mettre à jour" : u.error ? "Réessayer" : "Rechercher";
    btn.dataset.action = action || "";
    if (u.kiosk && !u.checked && checkedFor !== t.app) {
      checkedFor = t.app;
      sendUpdate("check");
    }
  }
  async function sendUpdate(action) {
    updateBusy = true;
    updateNote = null;
    const state = document.getElementById("updatestate"), btn = document.getElementById("updatebtn");
    state.textContent = action === "install" ? "Lancement de l'installation…" : "Recherche d'une mise à jour…";
    btn.hidden = true;
    try {
      const res = await fetch("/local/tablet/update", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ action }) });
      const r = await res.json();
      updateBusy = false;
      if (!res.ok) updateNote = "Pas fait : " + (r.error || r.message);
      refresh();
    } catch (e) {
      updateBusy = false;
      updateNote = "Compagnon injoignable";
      state.textContent = updateNote;
    }
  }
  document.getElementById("updatebtn").onclick = (e) => sendUpdate(e.target.dataset.action);
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
  const GITLAB_MSG = {
    ok: "✓ GitLab connecté : l'onglet GitLab de la tablette se remplit dans quelques secondes.",
    refused: "Connexion annulée.",
    error: "La connexion à GitLab a échoué. Réessaie.",
  };
  const gitlabResult = new URLSearchParams(location.search).get("gitlab");
  if (GITLAB_MSG[gitlabResult]) document.getElementById("gitlabmsg").textContent = GITLAB_MSG[gitlabResult];
  const section = new URLSearchParams(location.search).get("section");
  if (section === "agenda" || section === "gitlab") {
    const card = document.getElementById(section);
    card.classList.add("focus");
    card.scrollIntoView({ block: "center" });
  }

  // Carte « Cette appli » : lue au chargement et après chaque action, pas à chaque rafraîchissement.
  const $ = (id) => document.getElementById(id);
  const say = (text) => { $("panelmsg").textContent = text; };
  async function post(url, body) {
    const res = await fetch(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body || {}) });
    const json = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(json.error || json.message || "Échec");
    return json;
  }
  async function loadPanel() {
    try {
      const p = await (await fetch("/local/panel")).json();
      $("panel").hidden = !p.portable;
      if (!p.portable) return;
      $("appinfo").textContent = "Version " + p.version + " · " + p.app;
      $("autostart").checked = p.autostart;
      $("firewallrow").hidden = p.firewall === null;
      $("firewallstate").textContent = p.firewall ? "✓ Pare-feu ouvert : la tablette peut joindre ce PC." : "⚠ Pare-feu fermé : la tablette ne trouvera pas ce PC.";
      $("firewallopen").hidden = p.firewall === true;
      $("legacyrow").hidden = p.legacy.length === 0;
      $("legacylist").textContent = p.legacy.join(" · ");
      $("extpath").textContent = p.extension;
    } catch (e) {
      $("panel").hidden = true;
    }
  }
  // last : l'action arrête ou relance le compagnon, la carte ne se relit pas (elle resterait vide).
  async function act(fn, okText, last) {
    say("…");
    try { say(okText ? okText(await fn()) : ""); } catch (e) { say(e.message); }
    if (!last) loadPanel();
  }
  $("autostart").onchange = (e) => act(() => post("/local/autostart", { on: e.target.checked }),
    (r) => r.autostart ? "Lancement au démarrage activé." : "Lancement au démarrage retiré.");
  $("firewallopen").onclick = () => act(() => post("/local/firewall"), (r) => r.ok ? "Pare-feu ouvert." : "Pare-feu inchangé : l'accord administrateur a été refusé.");
  $("legacyremove").onclick = () => act(() => post("/local/legacy/remove"),
    (r) => r.failed.length ? "Pas pu retirer : " + r.failed.join(", ") : "Ancienne installation retirée.");
  $("updatecheck").onclick = () => act(async () => {
    const r = await (await fetch("/local/update")).json().then((j) => j.error ? Promise.reject(new Error(j.error)) : j);
    $("updateinstall").hidden = !r.available;
    $("updateinstall").textContent = "Installer la version " + r.available;
    $("appupdatestate").textContent = r.available ? "Version " + r.available + " sur la tablette (tu as la " + r.current + ")." : "À jour (version " + r.current + ").";
  });
  $("updateinstall").onclick = () => act(() => post("/local/update"), (r) => "Version " + r.version + " téléchargée : l'appli redémarre, la page se recharge.", true);
  $("quit").onclick = () => act(() => post("/local/quit"), () => "Compagnon arrêté. Rouvre « Compagnon Unowhy » pour le relancer.", true);
  $("forget").onclick = () => {
    if (confirm("Tout oublier ? La tablette devra être appairée à nouveau, l'agenda et les tâches de cet ordinateur sont effacés.")) {
      act(() => post("/local/forget", { sure: true }), () => "Tout est oublié. Tu peux supprimer l'appli.", true);
    }
  };
  refresh();
  loadPanel();
  setInterval(refresh, 1500);
</script>
</body>
</html>
`;

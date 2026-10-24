/**
 * Page http://127.0.0.1:3000/ ouverte sur l'ordinateur (ouverte par l'installation) : état du compagnon,
 * marche à suivre sur la tablette et, pendant un appairage, le code à saisir en grand.
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
  #code { font: 600 64px/1.1 ui-monospace, "SF Mono", Consolas, monospace; letter-spacing: 6px; color: var(--accent); margin: 8px 0; }
  #pairing { display: none; border: 2px solid var(--accent); }
  input { width: 100%; box-sizing: border-box; font: inherit; padding: 10px 12px; border-radius: 10px; border: 1px solid var(--dim); background: var(--bg); color: var(--text); margin-top: 10px; }
  button { font: inherit; padding: 8px 16px; border-radius: 10px; border: 0; background: var(--accent); color: #fff; margin-top: 10px; cursor: pointer; }
  button.ghost { background: transparent; color: var(--dim); border: 1px solid var(--dim); margin-left: 8px; }
  details { margin-top: 8px; }
</style>
</head>
<body>
<main>
  <h1 id="name">Compagnon Unowhy</h1>
  <div class="dim" id="os"></div>

  <div class="card" id="pairing">
    <div>Code à saisir sur <b id="tablet">la tablette</b> :</div>
    <div id="code"></div>
    <div class="dim">Valable 2 minutes.</div>
  </div>

  <div class="card">
    <div><span class="dot" id="dot"></span><span id="state">Tablette non connectée</span></div>
    <div class="dim" style="margin-top:8px">Adresse de cet ordinateur : <code id="addr">…</code></div>
  </div>

  <div class="card">
    <b>Connecter une tablette</b>
    <ol>
      <li>Tablette et ordinateur sur le même WiFi.</li>
      <li>Sur la tablette : <b>Réglages › Ordinateurs › Ajouter</b>, puis toucher cet ordinateur.</li>
      <li>Saisir le code qui s'affiche ici.</li>
    </ol>
    <div class="dim" style="margin-top:8px">Ordinateur absent de la liste : « Saisir l'adresse » avec l'adresse ci-dessus.</div>
  </div>

  <div class="card">
    <b>Agenda de cet ordinateur</b>
    <div class="dim" id="calstate">…</div>
    <input id="calurl" placeholder="Lien ICS (https://… ou webcal://…)" autocomplete="off">
    <button id="calsave">Enregistrer</button><button class="ghost" id="calclear">Retirer</button>
    <div id="calmsg" class="dim" style="margin-top:8px"></div>
    <details class="dim">
      <summary>Où trouver le lien ICS ?</summary>
      <b>Outlook</b> (outlook.office.com) : Paramètres › Calendrier › Calendriers partagés › Publier un calendrier, choisir
      « Peut afficher tous les détails », Publier, puis copier le lien <b>ICS</b>.<br>
      <b>Google Agenda</b> : Paramètres › (ton agenda) › Intégrer l'agenda › Adresse secrète au format iCal.<br>
      <b>iCloud</b> : app Calendrier › clic droit sur l'agenda › Partager › Agenda public, copier le lien webcal://.
    </details>
  </div>
</main>
<script>
  const OS = { mac: "macOS", windows: "Windows", other: "Linux (pilotage indisponible)" };
  async function refresh() {
    try {
      const s = await (await fetch("/local/status")).json();
      document.getElementById("name").textContent = s.name;
      document.getElementById("os").textContent = "Compagnon prêt · " + (OS[s.os] || s.os);
      document.getElementById("addr").textContent = s.addresses.map((a) => a + ":" + s.port).join("  ·  ") || "aucune (pas de réseau)";
      document.getElementById("dot").className = "dot" + (s.tablet ? " on" : "");
      document.getElementById("state").textContent = s.tablet ? "Tablette connectée" : "Tablette non connectée";
      const c = s.calendar, n = c.env + c.saved.length;
      document.getElementById("calstate").textContent = n === 0 ? "Aucun agenda : la carte Agenda est masquée sur la tablette."
        : (c.saved.length ? "Lien enregistré : " + c.saved.join(", ") : "") + (c.env ? (c.saved.length ? " · " : "") + c.env + " lien(s) dans .env" : "");
      const p = s.pairing;
      document.getElementById("pairing").style.display = p ? "block" : "none";
      if (p) {
        document.getElementById("code").textContent = p.code.slice(0, 3) + " " + p.code.slice(3);
        document.getElementById("tablet").textContent = p.tablet;
      }
    } catch (e) {
      document.getElementById("os").textContent = "Compagnon arrêté";
    }
  }
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
  document.getElementById("calsave").onclick = () => {
    const url = document.getElementById("calurl").value.trim();
    if (url) saveCalendar([url]);
  };
  document.getElementById("calclear").onclick = () => saveCalendar([]);
  refresh();
  setInterval(refresh, 1500);
</script>
</body>
</html>
`;

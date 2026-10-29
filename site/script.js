// Ligne du jour : le point rouge suit l'heure locale entre 8 h et 20 h.
(function () {
  var now = new Date();
  var h = now.getHours() + now.getMinutes() / 60;
  var part = Math.min(1, Math.max(0, (h - 8) / 12));
  document.querySelector(".jour").style.setProperty("--passe", (part * 100).toFixed(1) + "%");

  // Bouton principal : l'OS du visiteur est mis en avant. Ailleurs (mobile, Linux), il mène à la section.
  var ua = navigator.userAgent;
  var os = /Android|iPhone|iPad|iPod/.test(ua) ? "" : /Windows/.test(ua) ? "win" : /Macintosh|Mac OS X/.test(ua) ? "mac" : "";
  var cta = document.getElementById("cta");
  if (os) {
    document.documentElement.dataset.os = os;
    var cible = document.querySelector('.dl [data-os="' + os + '"] .btn');
    cta.textContent = cible.textContent;
    cta.href = cible.getAttribute("href");
    cta.setAttribute("download", "");
  }
})();

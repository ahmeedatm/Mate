// Ligne du jour : le point rouge suit l'heure locale entre 8 h et 20 h.
(function () {
  var now = new Date();
  var h = now.getHours() + now.getMinutes() / 60;
  var part = Math.min(1, Math.max(0, (h - 8) / 12));
  document.querySelector(".jour").style.setProperty("--passe", (part * 100).toFixed(1) + "%");

  // Téléchargement : l'OS du visiteur passe en premier, l'autre bouton en secondaire.
  var ua = navigator.userAgent;
  var os = /Android|iPhone|iPad|iPod/.test(ua) ? "" : /Windows/.test(ua) ? "win" : /Macintosh|Mac OS X/.test(ua) ? "mac" : "";
  if (os) document.documentElement.dataset.os = os;
})();

// Exemples d'écrans : la pastille de l'exemple visible est marquée ; un toucher sur une pastille fait défiler
// le défilé seul, sans bouger la page. Sans JS, les pastilles restent des ancres.
(function () {
  var defile = document.querySelector(".defile");
  if (!defile) return;
  var dots = Array.prototype.slice.call(document.querySelectorAll(".pastille"));
  var cards = Array.prototype.slice.call(defile.querySelectorAll(".exemple"));

  function mark(i) {
    dots.forEach(function (d, j) { d.setAttribute("aria-current", j === i ? "true" : "false"); });
  }
  dots.forEach(function (dot, i) {
    dot.addEventListener("click", function (e) {
      e.preventDefault();
      defile.scrollTo({ left: cards[i].offsetLeft - cards[0].offsetLeft, behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
      mark(i);
    });
  });
  var seen = new IntersectionObserver(function (entries) {
    entries.forEach(function (en) { if (en.intersectionRatio >= .6) mark(cards.indexOf(en.target)); });
  }, { root: defile, threshold: [.6] });
  cards.forEach(function (c) { seen.observe(c); });
  mark(0);
})();

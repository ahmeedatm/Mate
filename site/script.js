// Sans ce script, la page reste complète : les moments de la ligne du jour changent l'écran en CSS seul.

(function () {
  var reduit = matchMedia("(prefers-reduced-motion: reduce)").matches;

  // Ligne du jour : le point rouge suit l'heure locale entre 8 h et 20 h et bat une fois par minute.
  var jour = document.querySelector(".jour");
  var point = document.querySelector(".maintenant");
  function heure() {
    var d = new Date();
    return d.getHours() + d.getMinutes() / 60;
  }
  function placer(battre) {
    var h = heure();
    // Hors de la journée, pas de « maintenant » sur la ligne.
    point.hidden = h < 8 || h > 20;
    var part = Math.min(1, Math.max(0, (h - 8) / 12));
    jour.style.setProperty("--part", part.toFixed(4));
    if (battre && !reduit) {
      point.classList.remove("bat");
      void point.offsetWidth;
      point.classList.add("bat");
    }
  }
  if (jour) {
    placer(true);
    // Battement calé sur la minute pleine, comme MinuteClock sur la tablette.
    setTimeout(function () {
      placer(true);
      setInterval(function () { placer(true); }, 60000);
    }, (60 - new Date().getSeconds()) * 1000);

    // Le moment affiché d'abord : le dernier déjà passé dans la journée du visiteur.
    var choix = document.querySelectorAll(".moments input");
    var h = heure(), garde = choix[0];
    Array.prototype.forEach.call(choix, function (input) {
      var debut = parseFloat(input.parentNode.style.getPropertyValue("--h"));
      if (debut <= h) garde = input;
    });
    garde.checked = true;
  }

  // Minuteur du code d'appairage : le point avance pendant ses 2 minutes de validité, puis repart.
  var code = document.querySelector(".code");
  if (code && !reduit) {
    var depart = Date.now();
    setInterval(function () {
      var fait = ((Date.now() - depart) % 120000) / 120000;
      code.style.setProperty("--fait-part", fait.toFixed(4));
      var reste = Math.ceil(120 - fait * 120);
      code.querySelector(".code-reste").textContent = "Valable encore " + Math.floor(reste / 60) + " min " + String(reste % 60).padStart(2, "0");
    }, 1000);
  }

  // Téléchargement : l'OS du visiteur passe en premier, l'autre bouton en secondaire.
  var ua = navigator.userAgent;
  var os = /Android|iPhone|iPad|iPod/.test(ua) ? "mobile" : /Windows/.test(ua) ? "win" : /Macintosh|Mac OS X/.test(ua) ? "mac" : "";
  if (os) document.documentElement.dataset.os = os;

  // Bandeau collant : ton de surface dès qu'on a quitté le haut de page ; le lien de la section visible est marqué.
  var entete = document.querySelector(".top");
  function suivre() { entete.classList.toggle("defile", window.scrollY > 8); }
  suivre();
  window.addEventListener("scroll", suivre, { passive: true });
  var liens = Array.prototype.slice.call(document.querySelectorAll(".top nav a:not(.btn)"));
  if ("IntersectionObserver" in window) {
    var visibles = new IntersectionObserver(function (entrees) {
      entrees.forEach(function (en) {
        if (!en.isIntersecting) return;
        liens.forEach(function (l) { l.setAttribute("aria-current", l.hash === "#" + en.target.id ? "true" : "false"); });
      });
    }, { rootMargin: "-45% 0px -50% 0px" });
    liens.forEach(function (l) { var cible = document.querySelector(l.hash); if (cible) visibles.observe(cible); });
  }

  // Entrées : chaque élément monte une fois quand sa section arrive, décalé de 80 ms sur ses voisins.
  var cibles = document.querySelectorAll("main > section:not(.hero) .apparait, .feuille");
  if (!("IntersectionObserver" in window) || reduit) {
    Array.prototype.forEach.call(cibles, function (el) { el.classList.add("vu"); });
  } else {
    var vus = new IntersectionObserver(function (entrees) {
      var rang = 0;
      entrees.forEach(function (en) {
        if (!en.isIntersecting) return;
        en.target.style.setProperty("--delai", rang++ * 80 + "ms");
        en.target.classList.add("vu");
        vus.unobserve(en.target);
      });
    }, { rootMargin: "0px 0px -8% 0px", threshold: 0.05 });
    Array.prototype.forEach.call(cibles, function (el) { vus.observe(el); });
  }

  // Vidéo : lue seulement quand elle est à l'écran ; avec « réduire les animations », ses contrôles la laissent au visiteur.
  // Le bouton la met en pause pour de bon : elle ne reprend plus seule au défilement.
  var video = document.querySelector(".feuille video");
  var bouton = document.querySelector(".lecture");
  if (video) {
    var enPause = reduit;
    function etiqueter() {
      bouton.textContent = enPause ? "Lire la vidéo" : "Mettre en pause";
      bouton.setAttribute("aria-pressed", enPause ? "true" : "false");
    }
    function lire() { var p = video.play(); if (p) p.catch(function () { enPause = true; etiqueter(); }); }
    bouton.hidden = false;
    etiqueter();
    bouton.addEventListener("click", function () {
      enPause = !enPause;
      etiqueter();
      if (enPause) video.pause(); else lire();
    });
    if ("IntersectionObserver" in window) {
      new IntersectionObserver(function (entrees) {
        entrees.forEach(function (en) {
          if (en.isIntersecting && !enPause) lire();
          else video.pause();
        });
      }, { threshold: 0.4 }).observe(video);
    }
  }
})();

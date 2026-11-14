/*
 * Pont entre Mate et la carte 3D op77_map3d (Open2077, MIT), embarquée dans l'appli de la tablette.
 * Le moteur parle à « Open77 » par window.Open77.on / emit : Mate joue ce rôle.
 * - Mate -> carte : MateMap3d.fire(nom, json) appelé par la tablette (WebView.evaluateJavascript).
 * - carte -> Mate : les événements utiles partent vers l'objet natif MateNative.emit(nom, json).
 * Les données de la ville (open77://…/web/data/x) sont lues sous ./data/x, servies par la tablette.
 */
(function () {
  "use strict";
  var handlers = {};
  var FORWARD = { "map3d:hello": 1, "map3d:ready": 1, "map3d:waypoint": 1, "map3d:error": 1, "map3d:arrived": 1 };

  window.Open77 = {
    on: function (name, cb) {
      (handlers[name] = handlers[name] || []).push(cb);
      return function () {
        handlers[name] = (handlers[name] || []).filter(function (h) {
          return h !== cb;
        });
      };
    },
    emit: function (name, data) {
      if (!FORWARD[name] || !window.MateNative) return;
      try {
        window.MateNative.emit(name, JSON.stringify(data || {}));
      } catch (e) {}
    },
  };

  window.MateMap3d = {
    fire: function (name, json) {
      var data = typeof json === "string" ? JSON.parse(json) : json;
      (handlers[name] || []).forEach(function (cb) {
        try {
          cb(data);
        } catch (e) {
          if (window.MateNative) window.MateNative.emit("map3d:error", JSON.stringify({ message: String(e) }));
        }
      });
    },
  };

  var nativeFetch = window.fetch;
  window.fetch = function (url, opts) {
    if (typeof url === "string" && url.indexOf("open77://") === 0) url = "./data/" + url.split("/").pop();
    return nativeFetch.call(this, url, opts);
  };

  // Pincer pour zoomer : le moteur ne connaît que la molette, on la lui donne.
  var pinch = null;
  function gap(t) {
    return Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY);
  }
  document.addEventListener(
    "touchstart",
    function (e) {
      if (e.touches.length === 2) pinch = gap(e.touches);
    },
    { passive: true },
  );
  document.addEventListener(
    "touchmove",
    function (e) {
      if (e.touches.length !== 2 || !pinch) return;
      var canvas = document.querySelector(".m3-full canvas") || document.querySelector("canvas");
      if (!canvas) return;
      var d = gap(e.touches);
      var cx = (e.touches[0].clientX + e.touches[1].clientX) / 2;
      var cy = (e.touches[0].clientY + e.touches[1].clientY) / 2;
      canvas.dispatchEvent(new WheelEvent("wheel", { deltaY: (pinch - d) * 4, clientX: cx, clientY: cy, bubbles: true, cancelable: true }));
      pinch = d;
    },
    { passive: true },
  );
  document.addEventListener("touchend", function (e) {
    if (e.touches.length < 2) pinch = null;
  });
})();

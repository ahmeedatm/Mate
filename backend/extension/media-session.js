// Exécuté dans la page avant ses propres scripts (sites de vidéo et de musique) : garde les actions que le site
// déclare à Media Session (morceau suivant, précédent…), celles des touches multimédias, pour que l'extension les
// déclenche depuis la page Média de la tablette (background.js, controlPage).
(() => {
  const session = navigator.mediaSession;
  if (!session || session.__mate) return;
  const handlers = {};
  const setActionHandler = session.setActionHandler.bind(session);
  session.setActionHandler = (action, handler) => {
    handlers[action] = handler;
    return setActionHandler(action, handler);
  };
  Object.defineProperty(session, "__mate", { value: handlers });
})();

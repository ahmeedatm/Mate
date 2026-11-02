package app.mate.ui.home

import app.mate.net.News

/** Phrase à mettre sous le titre de la tuile Actualités quand il n'y a pas de titres à montrer (ou que la lecture a échoué). */
enum class NewsNote { ERROR, NO_FEED, EMPTY }

/** `news` : dernière lecture réussie ; `hasError` : la lecture qui vient d'avoir lieu a échoué. Null : rien à dire, les titres parlent. */
fun newsNote(news: News?, hasError: Boolean): NewsNote? = when {
    hasError -> NewsNote.ERROR
    news?.feed == null -> NewsNote.NO_FEED
    news.items.isEmpty() -> NewsNote.EMPTY
    else -> null
}

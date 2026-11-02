package app.mate.ui.home

import app.mate.net.News
import app.mate.net.NewsItem
import org.junit.Assert.assertEquals
import org.junit.Test

class NewsNoteTest {
    private val item = NewsItem("Titre")

    @Test
    fun errorWinsEvenWithOldTitles() = assertEquals(NewsNote.ERROR, newsNote(News("https://a.fr/rss", null, listOf(item)), hasError = true))

    @Test
    fun noReadYetAndNoErrorIsNoFeed() = assertEquals(NewsNote.NO_FEED, newsNote(null, hasError = false))

    @Test
    fun noFeedSetAsksForOne() = assertEquals(NewsNote.NO_FEED, newsNote(News(null, null, emptyList()), hasError = false))

    @Test
    fun feedWithoutArticle() = assertEquals(NewsNote.EMPTY, newsNote(News("https://a.fr/rss", null, emptyList()), hasError = false))

    @Test
    fun titlesShownNeedNoNote() = assertEquals(null, newsNote(News("https://a.fr/rss", null, listOf(item)), hasError = false))
}

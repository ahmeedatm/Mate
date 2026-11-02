package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsApiTest {
    @Test
    fun readsFeedTitleAndItemsInOrder() {
        val news = NewsApi.parse(
            """{"feed":"https://exemple.fr/rss","title":"Le Blog","items":[
                {"title":"Premier","link":"https://exemple.fr/a"},{"title":"Deuxième","link":"https://exemple.fr/b"}]}""",
        )
        assertEquals("https://exemple.fr/rss", news.feed)
        assertEquals("Le Blog", news.title)
        assertEquals(listOf("Premier", "Deuxième"), news.titles)
    }

    @Test
    fun noFeedSetMeansEmptyNews() {
        val news = NewsApi.parse("""{"feed":null,"title":null,"items":[]}""")
        assertNull(news.feed)
        assertNull(news.title)
        assertTrue(news.titles.isEmpty())
    }

    @Test
    fun keepsAtMostFiveTitles() {
        val items = (1..8).joinToString(",") { """{"title":"T$it","link":"https://exemple.fr/$it"}""" }
        assertEquals(5, NewsApi.parse("""{"feed":"https://exemple.fr/rss","title":null,"items":[$items]}""").titles.size)
    }

    @Test
    fun skipsAnItemWithoutTitle() {
        val news = NewsApi.parse("""{"feed":"https://exemple.fr/rss","title":null,"items":[{"link":"https://exemple.fr/a"},{"title":"Bon","link":"https://exemple.fr/b"}]}""")
        assertEquals(listOf("Bon"), news.titles)
    }
}

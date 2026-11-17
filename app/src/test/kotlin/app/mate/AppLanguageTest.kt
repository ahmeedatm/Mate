package app.mate

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppLanguageTest {

    private val context = FakeContext()

    @After
    fun reset() {
        AppLanguage.current = AppLanguage.FRENCH
    }

    @Test
    fun `francais par defaut, puis langue retenue`() {
        assertEquals(AppLanguage.FRENCH, AppLanguage.of(context))
        Prefs.setLanguage(context, AppLanguage.ENGLISH)
        assertEquals(AppLanguage.ENGLISH, AppLanguage.of(context))
    }

    @Test
    fun `cle inconnue ou vide = francais`() {
        assertEquals(AppLanguage.FRENCH, AppLanguage.fromKey(null))
        assertEquals(AppLanguage.FRENCH, AppLanguage.fromKey("de"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromKey("en"))
    }

    @Test
    fun `la langue en cours donne la locale des dates et l'en-tete du compagnon`() {
        assertEquals("fr", AppLanguage.acceptLanguage())
        AppLanguage.current = AppLanguage.ENGLISH
        assertEquals(Locale.UK, AppLanguage.locale)
        assertEquals("en", AppLanguage.acceptLanguage())
    }
}

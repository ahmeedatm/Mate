package com.unowhy.companion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unowhy.companion.net.HttpException
import com.unowhy.companion.ui.errorText
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/** Phrases d'erreur affichées (vraies ressources : getString n'existe pas sur la JVM des tests unitaires). */
@RunWith(AndroidJUnit4::class)
class ErrorsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun sansOrdinateur() {
        // clear() en mémoire : un apply() du test précédent n'est peut-être pas encore sur le disque.
        val files = File(context.applicationInfo.dataDir, "shared_prefs").list().orEmpty().map { it.removeSuffix(".xml") }
        (files + "companion" + "water").distinct().forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun appairer() =
        Computers.save(context, Computer("ordi-test", "Bureau", "http://127.0.0.1:9", "jeton", "mac"), makeActive = true)

    @Test
    fun ordinateurInjoignable() {
        assertEquals(context.getString(R.string.error_no_computer), context.errorText(IOException("refused")))
        appairer()
        assertEquals(context.getString(R.string.error_unreachable, "Bureau"), context.errorText(IOException("refused")))
    }

    @Test
    fun jetonRefuse() {
        appairer()
        assertEquals(context.getString(R.string.error_reauth, "Bureau"), context.errorText(HttpException(401, "Unauthorized")))
    }

    @Test
    fun messageDeLOrdinateurAfficheTelQuel() {
        assertEquals("Discord n'est pas installé.", context.errorText(HttpException(500, "Discord n'est pas installé.")))
        assertEquals(context.getString(R.string.error_computer), context.errorText(HttpException(500, "")))
    }

    @Test
    fun routeInconnueOuReponseIllisible() {
        assertEquals(context.getString(R.string.error_bad_reply), context.errorText(HttpException(404, "Route GET:/deck/voice not found")))
        assertEquals(context.getString(R.string.error_bad_reply), context.errorText(JSONException("bad")))
    }

    @Test
    fun refusDeLAppliEtErreurInconnue() {
        assertEquals(context.getString(R.string.error_no_computer), context.errorText(UserFacingException(R.string.error_no_computer)))
        assertEquals(context.getString(R.string.error_generic), context.errorText(IllegalStateException("boom")))
        assertEquals(context.getString(R.string.error_generic), context.errorText(null))
    }

    @Test(expected = CancellationException::class)
    fun annulationRelancee() {
        context.errorText(CancellationException())
    }
}

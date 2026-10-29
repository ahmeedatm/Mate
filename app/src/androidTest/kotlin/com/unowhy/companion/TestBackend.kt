package com.unowhy.companion

import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Backend réel des tests du Deck (backend/test/deck-emulateur.ts, lancé par scripts/test-android.sh) :
 * l'émulateur le joint en 127.0.0.1 (adb reverse posé par le script), comme une tablette en USB : l'appli n'envoie pas
 * son jeton en HTTP en clair à une autre adresse. Port de pilotage : arrêt, redémarrage, touches reçues.
 */
object TestBackend {
    /** Ports libres choisis par le script à chaque lancement (arguments d'instrumentation), sinon ceux d'un lancement à la main. */
    private val args = InstrumentationRegistry.getArguments()
    private val url = "http://127.0.0.1:${args.getString("backendPort", "3910")}"
    private val controlUrl = "http://127.0.0.1:${args.getString("controlPort", "3911")}"
    /** COMPANION_TOKEN du backend de test (backend/test/server.ts). */
    private const val TOKEN = "jeton-de-test"

    /** Backend en marche, touches perso vides, journal des touches remis à zéro. */
    fun reset() = control("POST", "/reset")

    fun stop() = control("POST", "/stop")

    fun start() = control("POST", "/start")

    /** Identifiants des tuiles touchées, vus par le backend. */
    fun runs(): List<String> = JSONObject(control("GET", "/runs")).getJSONArray("runs").let { a -> (0 until a.length()).map { a.getString(it) } }

    /** Touche perso enregistrée par le backend, ou null. */
    fun persoKey(slot: Int): JSONObject? = JSONObject(request("GET", "$url/deck/keys/$slot", TOKEN)).optJSONObject("key")

    /** Appairage sans dialogue : l'ordinateur de test devient l'ordinateur actif, avec son vrai identifiant. */
    fun pair(context: Context) {
        val health = JSONObject(request("GET", "$url/health"))
        Computers.save(context, Computer(health.getString("id"), health.getString("name"), url, TOKEN, health.getString("os")), makeActive = true)
    }

    private fun control(method: String, path: String) = request(method, controlUrl + path)

    private fun request(method: String, url: String, token: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5_000
        conn.readTimeout = 20_000
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        try {
            check(conn.responseCode in 200..299) { "$method $url : HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

/** Réessaie `check` jusqu'à ce qu'il passe : les réponses du backend arrivent hors du fil principal, qu'Espresso n'attend pas. */
fun eventually(timeoutMs: Long = 10_000, check: () -> Unit) {
    val end = SystemClock.uptimeMillis() + timeoutMs
    while (true) {
        try {
            return check()
        } catch (e: Throwable) {
            if (SystemClock.uptimeMillis() > end) throw e
            Thread.sleep(250)
        }
    }
}

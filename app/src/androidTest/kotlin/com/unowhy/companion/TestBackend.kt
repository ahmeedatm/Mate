package com.unowhy.companion

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Backend réel des tests du Deck (backend/test/deck-emulateur.ts, lancé par scripts/test-android.sh) :
 * l'émulateur le joint par 10.0.2.2, l'adresse de l'ordinateur hôte. Port 3911 : arrêt, redémarrage, touches reçues.
 */
object TestBackend {
    const val URL = "http://10.0.2.2:3910"
    private const val CONTROL = "http://10.0.2.2:3911"
    /** COMPANION_TOKEN du backend de test (backend/test/server.ts). */
    private const val TOKEN = "jeton-de-test"

    /** Backend en marche, touches perso vides, journal des touches remis à zéro. */
    fun reset() = control("POST", "/reset")

    fun stop() = control("POST", "/stop")

    fun start() = control("POST", "/start")

    /** Identifiants des tuiles touchées, vus par le backend. */
    fun runs(): List<String> = JSONObject(control("GET", "/runs")).getJSONArray("runs").let { a -> (0 until a.length()).map { a.getString(it) } }

    /** Touche perso enregistrée par le backend, ou null. */
    fun persoKey(slot: Int): JSONObject? = JSONObject(request("GET", "$URL/deck/keys/$slot", TOKEN)).optJSONObject("key")

    /** Appairage sans dialogue : l'ordinateur de test devient l'ordinateur actif, avec son vrai identifiant. */
    fun pair(context: Context) {
        val health = JSONObject(request("GET", "$URL/health"))
        Computers.save(context, Computer(health.getString("id"), health.getString("name"), URL, TOKEN, health.getString("os")), makeActive = true)
    }

    private fun control(method: String, path: String) = request(method, CONTROL + path)

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

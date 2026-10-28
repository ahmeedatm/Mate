package com.unowhy.companion.remote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Base64
import com.unowhy.companion.BuildConfig
import com.unowhy.companion.Computer
import com.unowhy.companion.Computers
import com.unowhy.companion.MainActivity
import com.unowhy.companion.audio.Speaker
import com.unowhy.companion.focus.FocusTimer
import com.unowhy.companion.focus.NoisePlayer
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.Connection
import com.unowhy.companion.net.DocEntry
import com.unowhy.companion.net.HttpException
import com.unowhy.companion.net.Link
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale

/**
 * Ordres envoyés par l'ordinateur (Claude via l'extension MCP du backend, voir backend/src/mcp.ts).
 * La tablette n'expose aucun serveur : elle garde une requête ouverte vers le backend
 * (long-polling), exécute les ordres reçus sur le thread principal et renvoie le résultat.
 */
class RemoteChannel(private val activity: MainActivity) {

    /** Résultats des derniers ordres : l'ordinateur peut renvoyer un ordre déjà exécuté (connexion WiFi coupée). */
    private val recent = object : LinkedHashMap<String, JSONObject>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>) = size > 20
    }

    /**
     * Boucle tant que l'appli est à l'écran. `onStatus` : pastille de connexion ; `onSwitched` : un autre
     * ordinateur enregistré répond alors que l'actif est injoignable, il devient l'actif (la boucle s'arrête,
     * l'activité en relance une).
     */
    suspend fun run(onStatus: (Link) -> Unit, onSwitched: (Computer) -> Unit) {
        var online = false
        var lastResolve = 0L
        /** Début de la coupure en cours (0 : en ligne) : on ne change d'ordinateur qu'après [SWITCH_AFTER_MS]. */
        var offlineSince = 0L
        while (true) {
            val computer = Computers.active(activity)
            if (computer == null) {
                onStatus(Link(null, Link.State.NONE))
                delay(5_000)
                continue
            }
            val client = BackendClient(computer.url, computer.token)
            val commands = try {
                // Pastille à jour tout de suite, sans attendre la fin d'un poll (jusqu'à 25 s).
                if (!online) {
                    when (val probe = Connection.probe(activity, computer)) {
                        is Connection.Probe.Ok -> onStatus(Link(probe.computer, Link.State.ONLINE))
                        Connection.Probe.Unauthorized -> throw HttpException(401, "Jeton refusé")
                        Connection.Probe.Mismatch -> throw IOException("Un autre ordinateur répond à cette adresse")
                    }
                    online = true
                    offlineSince = 0L
                }
                client.tablet.poll(state())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                online = false
                if (e is HttpException && e.code == 401) {
                    // Jeton refusé (ordinateur réinstallé ?) : il faut refaire l'appairage, chercher ailleurs ne sert à rien.
                    onStatus(Link(computer, Link.State.UNAUTHORIZED))
                    delay(10_000)
                    continue
                }
                onStatus(Link(computer, Link.State.OFFLINE))
                if (offlineSince == 0L) offlineSince = SystemClock.elapsedRealtime()
                if (SystemClock.elapsedRealtime() - lastResolve > RESOLVE_EVERY_MS || lastResolve == 0L) {
                    lastResolve = SystemClock.elapsedRealtime()
                    // Coupure courte (ordinateur qui redémarre, WiFi qui décroche) : on attend l'actif, peut-être à une nouvelle adresse.
                    val found = Connection.resolve(activity, allowSwitch = SystemClock.elapsedRealtime() - offlineSince > SWITCH_AFTER_MS)
                    if (found != null && found.id != computer.id) {
                        onSwitched(found)
                        return
                    }
                    // Même ordinateur, peut-être à une nouvelle adresse : nouvel essai tout de suite.
                    if (found != null) continue
                }
                delay(5_000)
                continue
            }
            for (c in commands) {
                val result = recent[c.id] ?: try {
                    JSONObject().put("ok", true).putOpt("data", execute(c.command))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
                }
                recent[c.id] = result
                runCatching { client.tablet.sendResult(c.id, result, state()) }
            }
        }
    }

    private suspend fun execute(command: JSONObject): Any? = when (val type = command.getString("type")) {
        "mode" -> activity.showMode(command.getString("mode")).let { null }
        "message" -> {
            val text = command.getString("text")
            activity.showBanner(text, command.optInt("seconds", 10))
            if (command.optBoolean("speak") && !Speaker.speak(text)) {
                error("Message affiché, mais la synthèse vocale est indisponible (pas de voix française ?)")
            }
            null
        }
        "document" -> {
            val doc = command.optJSONObject("doc")
            if (doc == null) activity.closeDocument()
            else activity.openDocument(
                DocEntry(doc.getString("name"), doc.getString("path"), doc.getString("kind"), doc.getLong("size"), doc.getLong("mtimeMs"))
            )
            null
        }
        "focus" -> focusCommand(command)
        "noise" -> {
            if (command.has("volume")) NoisePlayer.setVolume(command.getDouble("volume").toFloat())
            when (val kind = command.getString("kind")) {
                "off" -> NoisePlayer.stop()
                else -> NoisePlayer.play(NoisePlayer.Kind.valueOf(kind.uppercase(Locale.ROOT)))
            }
            null
        }
        "screenshot" -> screenshot()
        "call" -> {
            activity.onCall(command.getString("state"), command.getString("app"), command.optString("caller").takeIf { command.has("caller") && !command.isNull("caller") }, command.optBoolean("video"))
            null
        }
        "agent" -> {
            activity.onAgent(command.getString("kind"), command.optString("askId").takeIf { command.has("askId") && !command.isNull("askId") }, command.optInt("waiting"))
            null
        }
        else -> error("Ordre inconnu : $type (mettre à jour l'appli ?)")
    }

    /** Capture de l'interface de l'appli (pas besoin d'adb, marche aussi en WiFi). */
    private suspend fun screenshot(): JSONObject {
        val root = activity.window.decorView
        // RGB_565 : moitié moins de mémoire, suffisant pour une capture JPEG.
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.RGB_565)
        root.draw(Canvas(bitmap))
        val jpeg = withContext(Dispatchers.Default) {
            try {
                ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                }
            } finally {
                bitmap.recycle()
            }
        }
        return JSONObject().put("jpeg", jpeg).put("width", root.width).put("height", root.height)
    }

    /** Ce que Claude voit avec l'outil tablette_etat. */
    private fun state(): JSONObject {
        val metrics = activity.resources.displayMetrics
        return JSONObject()
            .put("mode", activity.modeName)
            .put("document", activity.documentPath ?: JSONObject.NULL)
            .put("focus", focusState())
            .put("noise", NoisePlayer.kind?.name?.lowercase(Locale.ROOT) ?: JSONObject.NULL)
            .put("noiseVolume", Math.round(NoisePlayer.volume * 100))
            .put("voice", Speaker.ready)
            .put("screen", "${metrics.widthPixels}x${metrics.heightPixels}")
            .put("app", BuildConfig.VERSION_NAME)
    }

    private companion object {
        /** Recherche d'un ordinateur joignable au plus toutes les 15 s quand l'actif ne répond pas. */
        const val RESOLVE_EVERY_MS = 15_000L
        /** Injoignable depuis plus longtemps : un autre ordinateur qui répond prend le relais (rentré à la maison). */
        const val SWITCH_AFTER_MS = 120_000L
    }
}

/** Ordre `focus` : préréglage, puis start / pause / reset / skip du minuteur ; renvoie son état. */
internal fun focusCommand(command: JSONObject): JSONObject {
    if (command.has("focusMinutes") || command.has("breakMinutes")) {
        FocusTimer.setPreset(
            command.optInt("focusMinutes", FocusTimer.focusMinutes),
            command.optInt("breakMinutes", FocusTimer.breakMinutes),
        )
    }
    val running = FocusTimer.state.running
    when (command.getString("action")) {
        "start" -> if (!running) FocusTimer.toggle()
        "pause" -> if (running) FocusTimer.toggle()
        "reset" -> FocusTimer.reset()
        "skip" -> FocusTimer.skip()
    }
    return focusState()
}

internal fun focusState(): JSONObject {
    val s = FocusTimer.state
    return JSONObject()
        .put("phase", if (s.phase == FocusTimer.Phase.FOCUS) "focus" else "pause")
        .put("running", s.running)
        .put("remaining", FocusTimer.format(s.remainingMs))
        .put("focusMinutes", FocusTimer.focusMinutes)
        .put("breakMinutes", FocusTimer.breakMinutes)
        .put("completed", s.completed)
}

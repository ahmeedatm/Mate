package com.unowhy.companion.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.MainActivity
import com.unowhy.companion.Prefs
import com.unowhy.companion.R
import com.unowhy.companion.ui.themeColorList
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.IOException

/**
 * Mot de réveil « Mate » : écoute hors ligne par Vosk (grammaire restreinte, 16 kHz mono, fil de `SpeechService`),
 * seulement quand Companion est à l'écran ([resume] / [pause]), le réglage activé, le micro autorisé (jamais demandé
 * d'ici : « Parler » le fait), le modèle prêt ([WakeModel]) et le micro libre ([Mic]). À « Mate » : l'écoute s'arrête
 * (le micro est rendu), bip court, voyant à la couleur d'accent, puis `wake` (la commande vocale, comme « Parler ») ;
 * l'écoute reprend quand le micro est de nouveau libre, commande finie.
 * Voyant `indicator` (sous la cloche) : atténué = écoute le mot, accent = « Mate » entendu, absent = coupé ou en pause.
 */
class WakeWord(
    private val activity: MainActivity,
    private val indicator: ImageView,
    private val wake: () -> Unit,
) {
    private var resumed = false
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var loading = false
    private var loadFailed = false
    private var service: SpeechService? = null
    /** « Mate » entendu : voyant à l'accent jusqu'à la reprise de l'écoute. */
    private var heard = false
    private var released = false
    private var beeper: ToneGenerator? = null
    private val onMic: () -> Unit = { sync() }

    private val listener = object : RecognitionListener {
        override fun onResult(hypothesis: String) {
            if (service != null && WakeLogic.isWake(hypothesis)) onWake()
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "écoute interrompue", exception)
            stop()
            render(false)
        }

        override fun onPartialResult(hypothesis: String) = Unit
        override fun onFinalResult(hypothesis: String) = Unit
        override fun onTimeout() = Unit
    }

    init {
        Mic.onChange = onMic
        activity.lifecycleScope.launch { WakeModel.state.collect { sync() } }
    }

    /** Companion à l'écran : télécharge le modèle s'il manque (ou réessaie après un échec), puis écoute. */
    fun resume() {
        resumed = true
        loadFailed = false
        if (Prefs.wakeWord(activity)) WakeModel.ensure(activity)
        sync()
    }

    fun pause() {
        resumed = false
        sync()
    }

    /** Écoute ou non selon les conditions du moment ; rappelé à chaque changement (réglage, micro, modèle, écran). */
    fun sync() {
        if (released) return
        val enabled = Prefs.wakeWord(activity)
        val allowed = resumed && enabled && granted() && WakeModel.refresh(activity) == WakeModel.State.Ready
        if (allowed && Mic.free) start() else stop()
        if (!allowed) heard = false
        // Coupé : le modèle (plusieurs dizaines de Mo en mémoire native) est rendu.
        if (!enabled) unload()
        render(allowed)
    }

    private fun granted() =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun start() {
        if (service != null) return
        val recognizer = recognizer ?: return load()
        try {
            recognizer.reset()
            service = SpeechService(recognizer, SAMPLE_RATE).also { it.startListening(listener) }
            heard = false
        } catch (e: IOException) {
            // Micro pris par une autre appli : nouvel essai au prochain changement (écran, micro rendu).
            Log.w(TAG, "micro indisponible", e)
            service = null
        }
    }

    private fun stop() {
        val current = service ?: return
        service = null
        // cancel : arrête le fil d'écoute et l'attend, sans résultat final ; shutdown : rend le micro.
        current.cancel()
        current.shutdown()
    }

    /** Chargement du modèle (quelques secondes sur la tablette), hors du fil principal ; puis on réévalue. */
    // Fil à part plutôt qu'une coroutine de l'activité : annulé en route, le modèle déjà chargé ne serait jamais rendu.
    private fun load() {
        if (loading || loadFailed) return
        loading = true
        val dir = WakeModel.dir(activity).path
        Thread({
            val loaded = runCatching {
                val m = Model(dir)
                m to Recognizer(m, SAMPLE_RATE, WakeLogic.GRAMMAR).apply { setWords(true) }
            }
            activity.runOnUiThread {
                loading = false
                loaded.onFailure {
                    // Pas de nouvel essai avant le prochain passage à l'écran : un modèle abîmé ne boucle pas.
                    Log.w(TAG, "modèle illisible", it)
                    loadFailed = true
                }
                val (m, r) = loaded.getOrNull() ?: return@runOnUiThread
                if (released || !Prefs.wakeWord(activity)) {
                    r.close()
                    m.close()
                    return@runOnUiThread
                }
                model = m
                recognizer = r
                sync()
            }
        }, "wake-model").start()
    }

    private fun unload() {
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
    }

    /** « Mate » : micro rendu, bip, voyant allumé, puis la commande ; le micro reste pris jusqu'à son début. */
    private fun onWake() {
        Log.i(TAG, "« Mate » entendu")
        heard = true
        Mic.hold(Mic.WAKE)
        beep()
        // Après le bip : la reconnaissance d'Android ne l'entend pas comme le début de la demande.
        indicator.postDelayed({
            if (released) return@postDelayed
            // Companion a quitté l'écran pendant le bip : pas de commande.
            if (resumed) wake()
            Mic.release(Mic.WAKE)
        }, BEEP_MS + 100L)
    }

    private fun beep() {
        val tone = beeper ?: runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()?.also { beeper = it }
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
    }

    private fun render(allowed: Boolean) {
        val on = allowed && (service != null || heard)
        indicator.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        ImageViewCompat.setImageTintList(indicator, themeColorList(activity, if (heard) R.attr.c_accent else R.attr.c_text_faint))
        indicator.contentDescription = activity.getString(if (heard) R.string.wake_heard else R.string.wake_listening)
    }

    /** Activité détruite : micro, modèle et bip rendus ; plus rien n'est tenu sur le micro. */
    fun release() {
        released = true
        stop()
        unload()
        beeper?.release()
        beeper = null
        if (Mic.onChange === onMic) Mic.onChange = null
        Mic.clear()
    }

    private companion object {
        const val TAG = "WakeWord"
        const val SAMPLE_RATE = 16_000f
        const val BEEP_MS = 150
    }
}

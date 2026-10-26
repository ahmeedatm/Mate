package com.unowhy.companion.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Synthèse vocale intégrée à Android (hors ligne, gratuite, sans dépendance), partagée par
 * les messages envoyés depuis le Mac et la commande vocale. Un seul moteur pour toute l'appli.
 */
object Speaker {

    private var tts: TextToSpeech? = null

    /** Faux tant que le moteur démarre, ou s'il n'a pas de voix française. */
    @Volatile var ready = false
        private set

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS &&
                tts?.setLanguage(Locale.FRANCE)?.let { it >= TextToSpeech.LANG_AVAILABLE } == true
        }
    }

    /** Lit le texte (en coupant la lecture en cours) ; renvoie faux si la voix n'est pas disponible. */
    fun speak(text: String): Boolean {
        if (!ready) return false
        // Pas de lecture des symboles de mise en forme (réponses LLM en Markdown).
        val clean = text.replace(Regex("[*_#`>«»]"), "").replace(Regex("\\s+"), " ").trim()
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "companion")
        return true
    }

    fun stop() {
        tts?.stop()
    }
}

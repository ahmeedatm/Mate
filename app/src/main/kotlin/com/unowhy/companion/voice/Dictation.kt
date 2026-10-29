package com.unowhy.companion.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.unowhy.companion.MainActivity

/**
 * Dictée d'un texte libre (réponse « Autre » à une question de Claude) : la reconnaissance d'Android, comme la commande
 * vocale, mais le texte est rendu tel quel, sans chercher une commande dedans.
 */
class Dictation(private val activity: MainActivity) {

    private var recognizer: SpeechRecognizer? = null

    val available get() = SpeechRecognizer.isRecognitionAvailable(activity)

    /** `false` : pas d'autorisation du micro (hors kiosque, elle se donne par le bouton Parler) ; rien n'est lancé. */
    fun start(onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (Int) -> Unit): Boolean {
        if (!available) return false
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(activity).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle) {
                partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }

            override fun onResults(results: Bundle) {
                Mic.release(Mic.DICTATION)
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }?.let(onResult) ?: onError(SpeechRecognizer.ERROR_NO_MATCH)
            }

            override fun onError(error: Int) {
                Mic.release(Mic.DICTATION)
                onError(error)
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        // Micro pris avant l'écoute : le mot de réveil le rend tout de suite.
        Mic.hold(Mic.DICTATION)
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName),
        )
        return true
    }

    fun stop() {
        recognizer?.stopListening()
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
        Mic.release(Mic.DICTATION)
    }
}

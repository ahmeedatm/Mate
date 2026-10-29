package com.unowhy.companion.voice

import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Reconnaissance vocale sur la tablette d'abord : `EXTRA_PREFER_OFFLINE`, la voix ne part pas chez Google
 * (docs/confidentialite.md). Sans modèle français installé, Android refuse aussitôt : on repasse en ligne, et pour le
 * reste de la session (inutile de reperdre une seconde à chaque commande). Partagé par la commande vocale et la dictée.
 */
object Recognition {
    /** null : pas encore essayé ; true : la reconnaissance hors ligne a déjà répondu ; false : indisponible. */
    @Volatile var offline: Boolean? = null
        private set

    val tryOffline: Boolean get() = offline != false

    fun intent(packageName: String, maxResults: Int, offline: Boolean): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)

    /**
     * Erreur d'un essai hors ligne : true si c'est l'absence de modèle (on repasse en ligne). Silence ou rien compris
     * ne disent rien du modèle : on reste hors ligne.
     */
    fun offlineUnavailable(error: Int): Boolean {
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) return false
        val unavailable = error in UNAVAILABLE
        if (unavailable) {
            offline = false
            Log.i("Companion", "Reconnaissance vocale hors ligne indisponible (erreur $error) : en ligne pour cette session")
        }
        return unavailable
    }

    /** Une réponse est venue de l'essai hors ligne : le modèle est là. */
    fun offlineWorked() {
        offline = true
    }

    /** Pas de modèle : réseau, serveur ou client selon la version de l'appli Google ; langue indisponible (Android 12+). */
    private val UNAVAILABLE = setOf(
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_CLIENT,
        12, // ERROR_LANGUAGE_NOT_SUPPORTED (Android 12)
        13, // ERROR_LANGUAGE_UNAVAILABLE (Android 12)
    )
}

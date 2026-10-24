package com.unowhy.companion

import android.content.Context

/** Réglages locaux : ordinateur piloté (voir Computers) et lecture des réponses à voix haute. Aucune clé d'API ici. */
object Prefs {
    private const val FILE = "companion"
    private const val KEY_SPEAK = "speak_replies"
    private const val KEY_ALLOW_INSTALLS = "allow_installs"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Backend de l'ordinateur actif ; sans ordinateur, l'URL de build (USB : http://127.0.0.1:3000). */
    fun backendUrl(context: Context): String = Computers.active(context)?.url ?: BuildConfig.DEFAULT_BACKEND_URL

    fun token(context: Context): String = Computers.active(context)?.token.orEmpty()

    fun speakReplies(context: Context): Boolean = prefs(context).getBoolean(KEY_SPEAK, true)

    fun setSpeakReplies(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPEAK, value).apply()
    }

    /** Kiosque : installations permises (mise à jour de l'appli par adb). Bloquées par défaut. */
    fun allowInstalls(context: Context): Boolean = prefs(context).getBoolean(KEY_ALLOW_INSTALLS, false)

    fun setAllowInstalls(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_INSTALLS, value).apply()
    }
}

package com.unowhy.unomate.remote

import android.content.Context
import com.unowhy.unomate.Prefs
import com.unowhy.unomate.device.Screen
import com.unowhy.unomate.ui.AppTheme
import org.json.JSONObject

/**
 * Réglages de la tablette lus et changés depuis la page de l'ordinateur (carte Tablette de backend/src/local-page.ts,
 * ordre `settings`). Le volume, réglé par le système, est géré par RemoteChannel.
 */
internal object TabletSettings {

    /** Enregistre les réglages présents dans l'ordre ; vrai si le thème change (l'écran doit être recréé). */
    fun save(context: Context, command: JSONObject): Boolean {
        // Thème vérifié d'abord : un ordre refusé ne change rien.
        val theme = if (command.has("theme")) {
            val name = command.getString("theme")
            AppTheme.entries.firstOrNull { it.name == name } ?: error("Thème inconnu : $name (mettre à jour l'appli ?)")
        } else {
            null
        }
        if (command.has("brightness")) {
            Prefs.setBrightness(context, command.getDouble("brightness").toFloat().coerceIn(Screen.MIN_LEVEL, 1f))
        }
        if (command.has("nightDim")) Prefs.setNightDim(context, command.getBoolean("nightDim"))
        if (command.has("focusDnd")) Prefs.setFocusDnd(context, command.getBoolean("focusDnd"))
        if (theme == null || theme == AppTheme.current(context)) return false
        Prefs.setTheme(context, theme.name)
        return true
    }

    /** Réglages envoyés à l'ordinateur avec l'état ; `systemBrightness` : luminosité tant qu'aucune n'est choisie ici. */
    fun read(context: Context, systemBrightness: Float, volume: Float): JSONObject = JSONObject()
        .put("theme", AppTheme.current(context).name)
        .put("brightness", Prefs.brightness(context).takeIf { it >= 0 } ?: systemBrightness)
        .put("volume", volume)
        .put("nightDim", Prefs.nightDim(context))
        .put("focusDnd", Prefs.focusDnd(context))
}

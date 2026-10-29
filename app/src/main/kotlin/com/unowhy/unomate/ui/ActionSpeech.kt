package com.unowhy.unomate.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.unowhy.unomate.Prefs
import com.unowhy.unomate.R
import com.unowhy.unomate.audio.Speaker

/**
 * Retour parlé des actions (Réglages > Accessibilité) : une touche lancée est annoncée par la voix de la tablette, son
 * nom si elle a marché, « <nom> n'a pas marché » sinon (jamais le texte de l'erreur). Une annonce à la fois : la nouvelle
 * coupe l'ancienne ([Speaker.speak]). Rien quand TalkBack est actif, il annonce déjà.
 */
object ActionSpeech {
    /** Texte à dire, ou null pour se taire : réglage coupé, TalkBack actif, ou touche sans nom. [failedFormat] porte un `%1$s`. */
    fun text(label: String, ok: Boolean, failedFormat: String, enabled: Boolean, talkBack: Boolean): String? {
        val name = label.trim()
        if (!enabled || talkBack || name.isEmpty()) return null
        return if (ok) name else failedFormat.format(name)
    }

    fun announce(context: Context, label: String, ok: Boolean) {
        val talkBack = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        text(label, ok, context.getString(R.string.action_failed), Prefs.speakActions(context), talkBack)?.let(Speaker::speak)
    }
}

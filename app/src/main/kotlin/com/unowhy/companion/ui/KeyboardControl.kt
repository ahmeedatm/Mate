package com.unowhy.companion.ui

import android.app.Activity
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.unowhy.companion.R

/**
 * Clavier. Par défaut l'écran ne bouge pas quand il s'ouvre (adjustNothing) : les fenêtres de saisie s'affichent
 * au-dessus de lui (showForTyping) sans tasser l'Accueil derrière. Pour un champ de l'écran lui-même (recherche d'applis, éditeur de touches), l'écran se réduit au-dessus du clavier et la barre latérale se cache,
 * plutôt que d'être coupée.
 */
class KeyboardControl(private val activity: Activity) {

    private val window get() = activity.window

    fun setUp() {
        val rail = activity.findViewById<View>(R.id.rail)
        val content = activity.findViewById<View>(android.R.id.content)
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            window.setSoftInputMode(
                if (focused is EditText) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            )
        }
        content.viewTreeObserver.addOnGlobalLayoutListener {
            val keyboard = content.height in 1 until window.decorView.height - activity.dp(150)
            val visibility = if (keyboard) View.GONE else View.VISIBLE
            if (rail.visibility != visibility) rail.visibility = visibility
        }
    }

    /**
     * Boîte de saisie fermée clavier ouvert (code d'appairage…) : Android le laisse sur l'écran, sans champ, et
     * rien ne le ferme en kiosque. Après coup (post) : le clavier n'est rattaché à cette fenêtre qu'ensuite.
     */
    fun closeOrphan() {
        window.decorView.post {
            if (activity.currentFocus !is EditText) {
                activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(window.decorView.windowToken, 0)
            }
        }
    }

    /**
     * Toucher hors du champ en cours de saisie (et de sa ligne : bouton Envoyer, Ajouter…) ferme le clavier :
     * les barres système sont masquées, il n'y a pas de bouton Retour visible pour le faire.
     */
    fun onTouch(event: MotionEvent) {
        val focused = activity.currentFocus
        if (event.action == MotionEvent.ACTION_DOWN && focused is EditText) {
            val zone = Rect()
            ((focused.parent as? View) ?: focused).getGlobalVisibleRect(zone)
            if (!zone.contains(event.rawX.toInt(), event.rawY.toInt())) hide()
        }
    }

    /** Ferme le clavier et rend le focus au conteneur, pour qu'aucun champ ne le rouvre. */
    fun hide() {
        val focused = activity.currentFocus ?: return
        activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(focused.windowToken, 0)
        activity.findViewById<View>(R.id.mode_container).requestFocus()
    }
}

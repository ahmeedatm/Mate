package app.mate.ui

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.mate.R

/**
 * Clavier. Par défaut l'écran ne bouge pas quand il s'ouvre (adjustNothing) : les fenêtres de saisie s'affichent
 * au-dessus de lui (showForTyping) sans tasser l'Accueil derrière. Pour un champ de l'écran lui-même (recherche d'applis, éditeur de touches), l'écran se réduit au-dessus du clavier et la barre latérale se cache,
 * plutôt que d'être coupée.
 */
class KeyboardControl(private val activity: Activity) {

    private val window get() = activity.window

    fun setUp() {
        // Clavier ouvert : la barre fixe se cache plutôt que d'être coupée.
        val rail = activity.findViewById<View>(R.id.rail)
        val content = activity.findViewById<View>(android.R.id.content)
        // Fenêtre plein écran (setDecorFitsSystemWindows(false)) : adjustResize ne la réduit plus, le clavier arrive en
        // inset ; on le rend en marge basse pour que l'écran se réduise au-dessus de lui.
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            // Seulement pour un champ de l'écran : une boîte de saisie s'affiche au-dessus, sans tasser l'écran derrière.
            val resizing = window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            view.setPadding(0, 0, 0, if (resizing) keyboardHeight(insets) else 0)
            insets
        }
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            window.setSoftInputMode(
                if (focused is EditText) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            )
            ViewCompat.requestApplyInsets(content)
        }
        content.viewTreeObserver.addOnGlobalLayoutListener {
            val keyboard = content.height - content.paddingBottom in 1 until window.decorView.height - activity.dp(150)
            val visibility = if (keyboard) View.GONE else View.VISIBLE
            if (rail.visibility != visibility) rail.visibility = visibility
        }
    }

    /** Avant API 30 le clavier n'est qu'un inset de fenêtre, mêlé à la barre de navigation (inset stable) : on le lit au-delà d'elle. */
    @Suppress("DEPRECATION")
    private fun keyboardHeight(insets: WindowInsetsCompat): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        } else {
            insets.systemWindowInsetBottom.takeIf { it > insets.stableInsetBottom } ?: 0
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

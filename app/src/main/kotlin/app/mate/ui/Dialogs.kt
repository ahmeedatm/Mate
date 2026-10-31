package app.mate.ui

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.mate.R

/**
 * Affiche une boîte de dialogue de saisie en haut de l'écran : le clavier occupe la moitié basse de la tablette,
 * la boîte reste au-dessus en entier (son contenu défile si besoin, ses boutons restent visibles). L'écran derrière
 * ne bouge pas (MainActivity n'ajuste sa taille que pour ses propres champs). `keyboard` : clavier ouvert d'emblée,
 * curseur dans `input`.
 */
fun AlertDialog.showForTyping(input: EditText?, keyboard: Boolean = true) {
    window?.apply {
        setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        attributes = attributes.apply { y = context.dp(24) }
        val state = if (keyboard) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        setSoftInputMode(state or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    window?.setWindowAnimations(0)
    show()
    window?.decorView?.let(Look::popIn)
    // Saisie en cours tant que la boîte est là (un appel qui commence ne fait pas changer l'écran derrière).
    Typing.opened()
    window?.decorView?.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: android.view.View) = Unit
        override fun onViewDetachedFromWindow(v: android.view.View) {
            v.removeOnAttachStateChangeListener(this)
            Typing.closed()
        }
    })
    // Comme l'écran derrière : pas de barre d'état (sinon une bande noire vide en kiosque). Barres rendues par un
    // balayage seulement : par défaut (Android 10), le premier toucher les rappelle et n'atteint pas la boîte.
    window?.let {
        WindowCompat.getInsetsController(it, it.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    if (keyboard) input?.requestFocus()
}

/**
 * Affiche une boîte de dialogue sans barre d'état ni barre de navigation, comme l'écran derrière : sinon le kiosque
 * les ferait réapparaître le temps de la boîte (et la flèche retour d'Android avec). La fenêtre n'est pas focusable
 * pendant l'affichage, pour que les barres n'apparaissent même pas un instant.
 */
fun <T : Dialog> T.showImmersive(): T {
    val window = window
    window?.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    window?.setWindowAnimations(0)
    show()
    window?.decorView?.let(Look::popIn)
    window?.let {
        WindowCompat.getInsetsController(it, it.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        it.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }
    return this
}

fun AlertDialog.Builder.showImmersive(): AlertDialog = create().showImmersive()

/** Ferme le clavier et retire le focus du champ en cours. */
fun View.dismissKeyboard() {
    context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    requestFocus()
}

/** Affiche une erreur dans une ligne d'état (texte de [errorText]). */
fun TextView.showError(e: Throwable) {
    text = context.errorText(e)
    visibility = View.VISIBLE
}

/** Champ gonflé depuis item_input.xml (un EditText construit avec un style perd focus et curseur), ajouté à `parent`. */
fun Context.inputField(parent: LinearLayout, hint: Int, type: Int): EditText {
    val input = LayoutInflater.from(this).inflate(R.layout.item_input, parent, false) as EditText
    input.setHint(hint)
    input.inputType = type
    input.imeOptions = EditorInfo.IME_ACTION_DONE
    parent.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(16)
    })
    return input
}

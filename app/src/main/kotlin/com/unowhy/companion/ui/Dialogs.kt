package com.unowhy.companion.ui

import android.app.Dialog
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Affiche une boîte de dialogue de saisie en haut de l'écran : le clavier occupe la moitié basse de la tablette,
 * la boîte reste au-dessus en entier (son contenu défile si besoin, ses boutons restent visibles). L'écran derrière
 * ne bouge pas (MainActivity n'ajuste sa taille que pour ses propres champs). `keyboard` : clavier ouvert d'emblée,
 * curseur dans `input`.
 */
fun AlertDialog.showForTyping(input: EditText?, keyboard: Boolean = true) {
    window?.apply {
        setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        attributes = attributes.apply { y = (24 * context.resources.displayMetrics.density).toInt() }
        val state = if (keyboard) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        setSoftInputMode(state or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    show()
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
    show()
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

package com.unowhy.companion.ui

import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

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
    // Comme l'écran derrière : pas de barre d'état (sinon une bande noire vide en kiosque).
    window?.let { WindowCompat.getInsetsController(it, it.decorView).hide(WindowInsetsCompat.Type.statusBars()) }
    if (keyboard) input?.requestFocus()
}

package com.unowhy.unomate.ui

import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.unowhy.unomate.MainActivity
import com.unowhy.unomate.R
import com.unowhy.unomate.voice.Dictation

/**
 * Texte libre pour un agent Claude (réponse « Autre », message à une session) : écrit au clavier, ou dicté par « Dicter »
 * quand la reconnaissance d'Android est là. `onOk` reçoit le texte tel quel (éventuellement vide : à l'appelant d'en juger).
 */
fun MainActivity.askAgentText(title: String, hint: Int, initial: String, onOk: (String) -> Unit) {
    val dictation = Dictation(this)
    val input = layoutInflater.inflate(R.layout.item_input, null) as EditText
    input.setHint(hint)
    input.setText(initial)
    input.setSelection(input.text.length)
    input.imeOptions = EditorInfo.IME_ACTION_DONE
    val dialog = AlertDialog.Builder(this)
        .setTitle(title)
        .setView(input)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.agent_ask_ok, null)
        .apply { if (dictation.available) setNeutralButton(R.string.agent_ask_dictate, null) }
        .create()
    dialog.showForTyping(input)
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        dialog.dismiss()
        onOk(input.text.toString())
    }
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
        val show = { text: String ->
            input.setText(text)
            input.setSelection(input.text.length)
        }
        val started = dictation.start(
            onPartial = show,
            onResult = show,
            onError = { showBanner(getString(R.string.voice_nothing), 4) },
        )
        if (!started) showBanner(getString(R.string.voice_no_mic), 6)
    }
    dialog.setOnDismissListener { dictation.release() }
}

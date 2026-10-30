package com.unowhy.unomate.ui

import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.unowhy.unomate.MainActivity
import com.unowhy.unomate.R
import com.unowhy.unomate.agents.AgentsFeed
import com.unowhy.unomate.net.BackendClient
import com.unowhy.unomate.voice.Dictation
import com.unowhy.unomate.voice.PromptRecorder
import kotlinx.coroutines.launch

/**
 * Texte libre pour un agent Claude (réponse « Autre », message à une session) : écrit au clavier, ou dicté par « Dicter ».
 * Dictée transcrite sur l'ordinateur par whisper.cpp quand il l'offre (`voice` de `GET /agents`), sinon par la
 * reconnaissance d'Android si elle est là.
 * `onOk` reçoit le texte tel quel (éventuellement vide : à l'appelant d'en juger).
 */
fun MainActivity.askAgentText(title: String, hint: Int, initial: String, onOk: (String) -> Unit) {
    val recorder = if (AgentsFeed.last?.state?.voice == true) PromptRecorder(this) else null
    val dictation = if (recorder == null) Dictation(this) else null
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
        .apply { if (recorder != null || dictation?.available == true) setNeutralButton(R.string.agent_ask_dictate, null) }
        .create()
    dialog.showForTyping(input)
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        dialog.dismiss()
        onOk(input.text.toString())
    }
    val neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

    /** Écoute, puis transcription sur l'ordinateur ; le texte s'ajoute à ce qui est déjà écrit. Second toucher = fin. */
    fun listen(recorder: PromptRecorder) {
        if (recorder.recording) return recorder.stop()
        val started = recorder.start { wav ->
            neutral?.setText(R.string.agent_ask_dictate)
            if (wav == null) {
                input.setHint(hint)
                return@start showBanner(getString(R.string.voice_nothing), 4)
            }
            input.setHint(R.string.agent_voice_transcribing)
            neutral?.isEnabled = false
            lifecycleScope.launch {
                try {
                    val text = BackendClient(this@askAgentText).agents.transcribe(wav)
                    if (text.isBlank()) {
                        showBanner(getString(R.string.voice_nothing), 4)
                    } else if (dialog.isShowing) {
                        input.setText(listOf(input.text.toString().trim(), text).filter { it.isNotEmpty() }.joinToString(" "))
                        input.setSelection(input.text.length)
                    }
                } catch (e: Exception) {
                    showBanner(errorText(e), 6)
                } finally {
                    neutral?.isEnabled = true
                    input.setHint(hint)
                }
            }
        }
        if (!started) return showBanner(getString(R.string.voice_no_mic), 6)
        neutral?.setText(R.string.agent_voice_stop)
        input.setHint(R.string.agent_voice_listening)
    }

    neutral?.setOnClickListener {
        if (recorder != null) return@setOnClickListener listen(recorder)
        val show = { text: String ->
            input.setText(text)
            input.setSelection(input.text.length)
        }
        val started = dictation?.start(
            onPartial = show,
            onResult = show,
            onError = { showBanner(getString(R.string.voice_nothing), 4) },
        ) == true
        if (!started) showBanner(getString(R.string.voice_no_mic), 6)
    }
    dialog.setOnDismissListener {
        recorder?.cancel()
        dictation?.release()
    }
}

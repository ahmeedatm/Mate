package com.unowhy.companion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.Prefs
import com.unowhy.companion.R
import com.unowhy.companion.audio.Speaker
import com.unowhy.companion.audio.VoiceRecorder
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.ChatMessage
import kotlinx.coroutines.launch

/** Mode assistant (phase 1) : chat texte + bouton micro, via le backend proxy. */
class ChatFragment : Fragment() {

    private val history = mutableListOf<ChatMessage>()
    private lateinit var recorder: VoiceRecorder
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var empty: View
    private lateinit var send: ImageButton
    private lateinit var mic: ImageButton
    private lateinit var voice: ImageButton

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
        else Toast.makeText(requireContext(), R.string.mic_permission_denied, Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_chat, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        recorder = VoiceRecorder(requireContext())
        messages = view.findViewById(R.id.chat_messages)
        scroll = view.findViewById(R.id.chat_scroll)
        input = view.findViewById(R.id.chat_input)
        empty = view.findViewById(R.id.chat_empty)
        send = view.findViewById(R.id.chat_send)
        mic = view.findViewById(R.id.chat_mic)

        send.setOnClickListener { submit(input.text.toString()) }
        input.setOnEditorActionListener { _, actionId, event ->
            val hardwareEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || hardwareEnter) { submit(input.text.toString()); true } else false
        }
        mic.setOnClickListener { toggleRecording() }

        voice = view.findViewById(R.id.chat_voice)
        voice.setOnClickListener {
            val enabled = !Prefs.speakReplies(requireContext())
            Prefs.setSpeakReplies(requireContext(), enabled)
            if (!enabled) Speaker.stop()
            renderVoice()
        }
        renderVoice()
    }

    override fun onDestroyView() {
        recorder.release()
        super.onDestroyView()
    }

    private fun client() = BackendClient(requireContext())

    private fun submit(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        input.text.clear()
        history += ChatMessage("user", content)
        addBubble(content, fromUser = true)
        val pending = addBubble(getString(R.string.thinking), fromUser = false)
        setBusy(true)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val reply = client().chat(history.takeLast(MAX_HISTORY))
                history += ChatMessage("assistant", reply)
                pending.text = reply
                speak(reply)
            } catch (e: Exception) {
                history.removeAt(history.lastIndex)
                pending.text = requireContext().errorText(e)
            } finally {
                setBusy(false)
                scrollToBottom()
            }
        }
    }

    private fun toggleRecording() {
        when {
            recorder.isRecording -> finishRecording()
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> startRecording()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        try {
            recorder.start(onMaxDuration = ::finishRecording)
            renderMic(recording = true)
        } catch (e: Exception) {
            recorder.release()
            addBubble(requireContext().errorText(e), fromUser = false)
        }
    }

    private fun finishRecording() {
        if (!recorder.isRecording) return
        renderMic(recording = false)
        val file = recorder.stop() ?: return
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val text = client().transcribe(file, VoiceRecorder.MIME_TYPE)
                setBusy(false)
                if (text.isNotBlank()) submit(text)
            } catch (e: Exception) {
                setBusy(false)
                addBubble(requireContext().errorText(e), fromUser = false)
            }
        }
    }

    private fun renderVoice() {
        val on = Prefs.speakReplies(requireContext())
        voice.setImageResource(if (on) R.drawable.ic_volume else R.drawable.ic_volume_off)
        ImageViewCompat.setImageTintList(voice, ColorStateList.valueOf(ContextCompat.getColor(requireContext(), if (on) R.color.accent else R.color.text_faint)))
    }

    /** En enregistrement : bouton rouge plein, icône stop. */
    private fun renderMic(recording: Boolean) {
        val ctx = requireContext()
        mic.contentDescription = getString(if (recording) R.string.mic_stop else R.string.mic)
        ImageViewCompat.setImageTintList(mic, ColorStateList.valueOf(ContextCompat.getColor(ctx, if (recording) R.color.on_accent else R.color.text)))
        ViewCompat.setBackgroundTintList(mic, if (recording) ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.recording)) else null)
    }

    private fun speak(text: String) {
        if (Prefs.speakReplies(requireContext())) Speaker.speak(text)
    }

    private fun setBusy(busy: Boolean) {
        send.isEnabled = !busy
        mic.isEnabled = !busy
    }

    private fun addBubble(text: String, fromUser: Boolean): TextView {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        empty.visibility = View.GONE
        val bubble = TextView(ctx).apply {
            this.text = text
            textSize = 18f
            setLineSpacing(0f, 1.15f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text))
            setBackgroundResource(R.drawable.bubble)
            background.mutate().setTint(
                ContextCompat.getColor(ctx, if (fromUser) R.color.bubble_user else R.color.bubble_bot)
            )
            setPadding((18 * dp).toInt(), (12 * dp).toInt(), (18 * dp).toInt(), (12 * dp).toInt())
            // Bulles sur les trois quarts de la largeur au plus, comme une messagerie.
            if (scroll.width > 0) maxWidth = scroll.width * 3 / 4
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            topMargin = (10 * dp).toInt()
        }
        messages.addView(bubble, params)
        scrollToBottom()
        return bubble
    }

    private fun scrollToBottom() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        private const val MAX_HISTORY = 20
    }
}

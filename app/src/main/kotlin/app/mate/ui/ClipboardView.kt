package app.mate.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.mate.MainActivity
import app.mate.R
import app.mate.audio.Speaker
import app.mate.net.BackendClient
import app.mate.net.ClipContent
import app.mate.net.HttpException
import app.mate.voice.Mic
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Écran « Presse-papiers » de Depuis l'ordi (view_clipboard.xml), deux tuiles :
 * - « Sur l'ordi » : ce qui est copié sur l'ordinateur (texte, ou ce qu'il y a à la place), « Copier sur la tablette »
 *   pour un texte, « Ouvrir » pour un lien (dans le panneau latéral, comme « Page de l'ordi ») ;
 * - « Vers l'ordi » : un texte écrit (boîte de saisie) ou dicté (reconnaissance Android, proposé dans la même boîte),
 *   envoyé dans le presse-papiers de l'ordinateur.
 *   En dessous, « Récents » : l'historique des textes copiés sur l'ordinateur ([ClipboardHistoryList]).
 * Le presse-papiers de l'ordinateur est lu à l'affichage de l'écran et sur Actualiser seulement, jamais en boucle ; son
 * texte n'est écrit dans aucun journal.
 */
class ClipboardView(
    private val fragment: Fragment,
    root: View,
    /** Micro pas encore autorisé : le fragment demande la permission, puis rappelle [dictate]. */
    private val requestMic: () -> Unit,
) {
    private val context = root.context
    private val scroll: ScrollView = root.findViewById(R.id.clip_scroll)
    private val content: TextView = root.findViewById(R.id.clip_text)
    private val note: TextView = root.findViewById(R.id.clip_note)
    private val copyHere: Button = root.findViewById(R.id.clip_copy_here)
    private val open: Button = root.findViewById(R.id.clip_open)
    private val dictateButton: Button = root.findViewById(R.id.clip_dictate)
    private val sendStatus: TextView = root.findViewById(R.id.clip_send_status)

    /** Texte complet reçu de l'ordinateur (copié sur la tablette tel quel) ; null si ce n'est pas du texte. */
    private var received: String? = null
    private var readJob: Job? = null
    private val history = ClipboardHistoryList(fragment, root, ::clipError)
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private val scope get() = fragment.viewLifecycleOwner.lifecycleScope

    init {
        root.findViewById<View>(R.id.clip_refresh).setOnClickListener { read() }
        copyHere.setOnClickListener { copyToTablet() }
        open.setOnClickListener { received?.let(::linkOf)?.let { (fragment.activity as? MainActivity)?.openUrl(it) } }
        root.findViewById<View>(R.id.clip_write).setOnClickListener { compose() }
        dictateButton.setOnClickListener { dictate() }
    }

    /** Écran affiché (ouverture, retour dans le mode) ou Actualiser : lit le presse-papiers de l'ordinateur une fois. */
    fun read() {
        readJob?.cancel()
        showSendStatus(null)
        showState(context.getString(R.string.clip_reading))
        history.load()
        readJob = scope.launch {
            try {
                show(BackendClient(context).system.clipboard())
            } catch (e: Exception) {
                showState(clipError(e))
            }
        }
    }

    /** L'écran n'est plus affiché : la lecture en cours est oubliée, la dictée s'arrête. */
    fun stop() {
        readJob?.cancel()
        history.stop()
        stopDictation()
    }

    /** Coupe la dictée en cours (le texte entendu jusque-là est abandonné). */
    fun stopDictation() {
        if (listening) showSendStatus(null)
        recognizer?.cancel()
        setListening(false)
    }

    /** Vue détruite : libère la reconnaissance vocale. */
    fun release() {
        stop()
        recognizer?.destroy()
        recognizer = null
    }

    // --- Sur l'ordi

    private fun show(clip: ClipContent) {
        when (clip) {
            is ClipContent.Text -> if (clip.text.isEmpty()) showState(context.getString(R.string.clip_empty)) else showText(clip.text, clip.truncated)
            ClipContent.Image -> showState(context.getString(R.string.clip_image))
            ClipContent.Empty -> showState(context.getString(R.string.clip_empty))
            ClipContent.Protected -> showState(context.getString(R.string.clip_protected))
            ClipContent.Other -> showState(context.getString(R.string.clip_other))
        }
    }

    private fun showText(text: String, truncated: Boolean) {
        received = text
        val shown = displayText(text)
        content.text = shown
        content.setTextColor(themeColor(context, R.attr.c_text))
        scroll.scrollTo(0, 0)
        val hint = when {
            truncated -> R.string.clip_truncated
            shown != text -> R.string.clip_shortened
            else -> null
        }
        note.visibility = if (hint == null) View.GONE else View.VISIBLE
        hint?.let { note.setText(it) }
        copyHere.visibility = View.VISIBLE
        open.visibility = if (linkOf(text) != null) View.VISIBLE else View.GONE
    }

    /** Pas de texte à montrer : une phrase (image, vide, protégé, erreur…), sans bouton de copie. */
    private fun showState(message: String) {
        received = null
        content.text = message
        content.setTextColor(themeColor(context, R.attr.c_text_dim))
        scroll.scrollTo(0, 0)
        note.visibility = View.GONE
        copyHere.visibility = View.GONE
        open.visibility = View.GONE
    }

    private fun copyToTablet() {
        val text = received ?: return
        val message = try {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.clip_choose), text))
            context.getString(R.string.clip_copied_here)
        } catch (e: RuntimeException) {
            fragment.errorText(e)
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // --- Vers l'ordi

    /** Boîte de saisie du texte à copier ; `initial` : texte dicté, proposé avant l'envoi (clavier fermé). */
    private fun compose(initial: String = "") {
        val input = LayoutInflater.from(context).inflate(R.layout.item_input, null) as EditText
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.filters = arrayOf(InputFilter.LengthFilter(MAX_SEND))
        input.maxLines = 8
        input.setHint(R.string.clip_write_hint)
        input.setText(initial)
        input.setSelection(input.text.length)
        val box = LinearLayout(context).apply {
            setPadding(context.dp(24), context.dp(8), context.dp(24), 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.clip_write_title)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clip_send, null)
            .create()
        dialog.showForTyping(input, keyboard = initial.isEmpty())
        val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        // La boîte reste ouverte tant que l'envoi n'a pas réussi : le texte n'est pas perdu sur une erreur.
        send.setOnClickListener {
            // Seule la fin est nettoyée : l'indentation du début (code, liste) est voulue.
            val text = input.text.toString().trimEnd()
            if (text.isEmpty()) return@setOnClickListener
            // Écran recréé entre-temps (ordinateur changé) : plus rien à quoi rattacher l'envoi.
            if (fragment.view == null) return@setOnClickListener dialog.dismiss()
            send.isEnabled = false
            scope.launch {
                try {
                    BackendClient(context).system.copyToComputer(text)
                    dialog.dismiss()
                    onCopied(text)
                } catch (e: Exception) {
                    send.isEnabled = true
                    input.error = clipError(e)
                }
            }
        }
    }

    /** Texte envoyé : confirmation, et « Sur l'ordi » montre ce qu'on vient d'y mettre (sans relire l'ordinateur). */
    private fun onCopied(text: String) {
        readJob?.cancel()
        showText(text, truncated = false)
        showSendStatus(context.getString(R.string.clip_copied))
        Toast.makeText(context, R.string.clip_copied, Toast.LENGTH_SHORT).show()
    }

    /** Dicter : un toucher écoute, un second arrête ; le texte entendu s'ouvre dans la boîte de saisie avant l'envoi. */
    fun dictate() {
        if (listening) {
            recognizer?.stopListening()
            return
        }
        // Pas de reconnaissance (émulateur) : on écrit.
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return compose()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return requestMic()
        Speaker.stop()
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        setListening(true)
        showSendStatus(context.getString(R.string.voice_listening))
        r.startListening(intent)
    }

    /** Permission du micro refusée. */
    fun micDenied() = showSendStatus(context.getString(R.string.voice_no_mic))

    private fun setListening(on: Boolean) {
        // Pris avant startListening, rendu à la fin de la dictée : le mot de réveil se tait pendant ce temps.
        Mic.set(Mic.CLIPBOARD, on)
        listening = on
        dictateButton.setText(if (on) R.string.clip_dictate_stop else R.string.clip_dictate)
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partial: Bundle) {
            partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?.let { showSendStatus("« $it »") }
        }

        override fun onResults(results: Bundle) {
            setListening(false)
            val said = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }
                ?: return showSendStatus(context.getString(R.string.voice_nothing))
            showSendStatus(null)
            compose(said.trim())
        }

        override fun onError(error: Int) {
            setListening(false)
            showSendStatus(context.getString(when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_nothing
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> R.string.clip_dictate_offline
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_no_mic
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.voice_busy
                else -> R.string.clip_dictate_failed
            }))
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun showSendStatus(message: String?) {
        sendStatus.text = message
        sendStatus.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    /**
     * Erreur d'une lecture ou d'un envoi : 503 = pas de session de bureau sur l'ordinateur, 501 = système non pris en
     * charge ; le reste passe par [errorText] (qui relance l'annulation).
     */
    private fun clipError(e: Exception): String = when {
        e is HttpException && e.code == 503 -> context.getString(R.string.clip_unavailable)
        e is HttpException && e.code == 501 -> context.getString(R.string.clip_not_supported)
        else -> fragment.errorText(e)
    }

    private companion object {
        /** Limite du compagnon pour un texte envoyé (POST /system/clipboard). */
        const val MAX_SEND = 100_000
    }
}

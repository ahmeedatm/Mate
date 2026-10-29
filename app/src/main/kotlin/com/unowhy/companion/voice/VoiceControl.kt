package com.unowhy.companion.voice

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.unowhy.companion.Computers
import com.unowhy.companion.MainActivity
import com.unowhy.companion.R
import com.unowhy.companion.audio.Speaker
import com.unowhy.companion.focus.FocusTimer
import com.unowhy.companion.focus.NoisePlayer
import com.unowhy.companion.focus.Water
import com.unowhy.companion.net.BackendClient
import com.unowhy.companion.net.ComputerWindow
import com.unowhy.companion.net.DeckTile
import com.unowhy.companion.net.OpenApp
import com.unowhy.companion.net.OpenWindows
import com.unowhy.companion.ui.Look
import com.unowhy.companion.ui.errorText
import com.unowhy.companion.ui.showForTyping
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Commandes vocales : bouton « Parler » de la barre latérale. La reconnaissance est celle d'Android (appli Google,
 * présente dans l'image d'usine des tablettes : en ligne, sans clé ni compte) ; la phrase est comprise sur la
 * tablette par [VoiceCommands], sans IA. Appui long (ou pas de reconnaissance) : la commande s'écrit.
 */
class VoiceControl(
    private val activity: MainActivity,
    private val button: View,
    private val icon: View,
    /** Le bouton est touché (le menu Cadran se referme pour montrer le résultat). */
    private val onPressed: () -> Unit = {},
) {

    /** Réponse à afficher ; `speak` : lue à voix haute (questions, incompris), les actions restent silencieuses. */
    private class Answer(val text: String, val speak: Boolean = false)

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var pulse: ValueAnimator? = null
    /** Variantes entendues de la dernière phrase : les noms d'applis sont cherchés dans toutes. */
    private var heard: List<String> = emptyList()
    /** Touches du Deck, chargées pendant qu'on parle : reconnues par leur nom (« ouvre Discord »). */
    private var tiles: Deferred<List<DeckTile>>? = null
    private val time = SimpleDateFormat("H 'h' mm", Locale.FRENCH)
    private val dayName = SimpleDateFormat("EEEE", Locale.FRENCH)

    init {
        button.setOnClickListener {
            onPressed()
            if (listening) recognizer?.stopListening() else start()
        }
        button.setOnLongClickListener {
            onPressed()
            typeCommand()
            true
        }
    }

    private val available get() = SpeechRecognizer.isRecognitionAvailable(activity)

    fun start() {
        if (!available) return typeCommand()
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return activity.requestMicPermission()
        }
        loadTiles()
        Speaker.stop()
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(activity).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
        setListening(true)
        activity.showBanner(activity.getString(R.string.voice_listening), 10)
        r.startListening(intent)
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
        setListening(false)
    }

    private fun setListening(on: Boolean) {
        listening = on
        button.isSelected = on
        if (on) pulse = pulse ?: Look.pulse(icon) else { Look.stop(pulse, icon); pulse = null }
    }

    private fun loadTiles() {
        if (Computers.all(activity).isEmpty()) return
        tiles = activity.lifecycleScope.async {
            runCatching { BackendClient(activity).deck.pages().flatMap { it.tiles } }.getOrDefault(emptyList())
        }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partial: Bundle) {
            partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?.let { activity.showBanner("« $it »", 10) }
        }

        override fun onResults(results: Bundle) {
            setListening(false)
            handle(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
        }

        override fun onError(error: Int) {
            setListening(false)
            val text = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_nothing
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> R.string.voice_offline
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_no_mic
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.voice_busy
                else -> R.string.voice_failed
            }
            activity.showBanner(activity.getString(text), 6)
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** Commande écrite : sans reconnaissance vocale (émulateur), ou quand parler dérange. */
    private fun typeCommand() {
        loadTiles()
        val input = activity.layoutInflater.inflate(R.layout.item_input, null) as EditText
        input.setHint(R.string.voice_type_hint)
        input.isSingleLine = true
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.voice_type_title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.voice_type_run, null)
            .create()
        dialog.showForTyping(input)
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        ok.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                dialog.dismiss()
                handle(listOf(text))
            }
        }
        input.imeOptions = EditorInfo.IME_ACTION_GO
        input.setOnEditorActionListener { _, _, _ -> ok.performClick() }
    }

    /** `candidates` : ce que le reconnaisseur a entendu, du plus probable au moins probable. */
    private fun handle(candidates: List<String>) {
        val heard = candidates.firstOrNull { it.isNotBlank() } ?: return activity.showBanner(activity.getString(R.string.voice_nothing), 6)
        this.heard = candidates.filter { it.isNotBlank() }
        activity.lifecycleScope.launch {
            val deck = tiles?.await().orEmpty()
            val (said, command) = candidates.firstNotNullOfOrNull { c -> VoiceCommands.parse(c, deck)?.let { c to it } }
                ?: (heard to null)
            // Texte à copier sur l'ordinateur : jamais dans le journal (ni la phrase entendue, qui le contient).
            if (command is VoiceCommand.CopyToComputer) android.util.Log.i("Voice", "copie sur l'ordi (${command.text.length} caractères)")
            else android.util.Log.i("Voice", "heard $candidates → $command")
            val answer = if (command == null) {
                Answer(activity.getString(R.string.voice_not_understood), speak = true)
            } else {
                try {
                    run(command)
                } catch (e: Exception) {
                    Answer(activity.errorText(e))
                }
            }
            activity.showBanner(if (command is VoiceCommand.Help) answer.text else "« $said » · ${answer.text}", if (command is VoiceCommand.Help) 15 else 6)
            if (answer.speak) Speaker.speak(answer.text)
        }
    }

    private suspend fun run(command: VoiceCommand): Answer {
        val client = BackendClient(activity)
        return when (command) {
            is VoiceCommand.ShowMode -> { activity.showMode(command.mode); Answer(text(R.string.voice_done)) }
            is VoiceCommand.Volume -> {
                val state = client.system.setAudio(output = command.level, outputDelta = command.delta)
                Answer(state.output?.let { text(R.string.voice_volume, it) } ?: text(R.string.voice_done))
            }
            is VoiceCommand.Mute -> { client.system.setAudio(muted = command.muted); Answer(text(if (command.muted) R.string.voice_muted else R.string.voice_unmuted)) }
            is VoiceCommand.Mic -> { client.system.setAudio(micMuted = command.muted); Answer(text(if (command.muted) R.string.voice_mic_off else R.string.voice_mic_on)) }
            is VoiceCommand.Media -> {
                val session = client.system.media().let { all -> all.firstOrNull { it.current } ?: all.firstOrNull() }
                    ?: return Answer(text(R.string.voice_no_media), speak = true)
                when (command.action) {
                    "pause" -> if (session.playing) client.system.mediaControl("play-pause", session.app)
                    "play" -> if (!session.playing) client.system.mediaControl("play-pause", session.app)
                    else -> client.system.mediaControl(command.action, session.app)
                }
                Answer(session.title.ifBlank { session.name })
            }
            is VoiceCommand.Focus -> {
                when (command.action) {
                    "start" -> {
                        command.minutes?.let { FocusTimer.setPreset(it, FocusTimer.breakMinutes) }
                        if (!FocusTimer.state.running) FocusTimer.toggle()
                    }
                    "pause" -> if (FocusTimer.state.running) FocusTimer.toggle()
                    else -> FocusTimer.reset()
                }
                Answer(when (command.action) {
                    "start" -> text(R.string.voice_focus_started, FocusTimer.focusMinutes)
                    "pause" -> text(R.string.voice_focus_paused)
                    else -> text(R.string.voice_focus_stopped)
                })
            }
            is VoiceCommand.Noise -> {
                command.kind?.let { NoisePlayer.play(NoisePlayer.Kind.valueOf(it)) } ?: NoisePlayer.stop()
                Answer(text(if (command.kind == null) R.string.voice_noise_off else R.string.voice_noise_on))
            }
            is VoiceCommand.AddTask -> { client.tasks.add(command.label); Answer(text(R.string.voice_task_added, command.label)) }
            is VoiceCommand.Tile -> { client.deck.runTile(command.tile.id); Answer(command.tile.label) }
            VoiceCommand.Time -> Answer(text(R.string.voice_time, time.format(Date())), speak = true)
            VoiceCommand.NextEvent -> {
                val now = System.currentTimeMillis()
                val next = client.calendar.days(days = 7).events.firstOrNull { !it.allDay && it.endMs > now }
                    ?: return Answer(text(R.string.voice_no_event), speak = true)
                Answer(text(R.string.voice_next_event, next.title, whenText(now, next.startMs)), speak = true)
            }
            VoiceCommand.Join -> {
                val now = System.currentTimeMillis()
                val meeting = client.calendar.days(days = 1).events.firstOrNull { it.joinable && it.endMs > now && it.startMs - now <= 15 * 60_000 }
                    ?: return Answer(text(R.string.voice_no_meeting), speak = true)
                val kind = client.calendar.joinMeeting(meeting.id)
                activity.startCall(com.unowhy.companion.ui.CallSession.appOfKind(kind), meeting.endMs)
                Answer(text(R.string.join_opened, meeting.title))
            }
            VoiceCommand.Drink -> {
                Water.add(activity, 1)
                val state = Water.state(activity)
                Answer(text(R.string.voice_water, state.glasses, state.goal))
            }
            is VoiceCommand.Window -> window(client, command)
            VoiceCommand.Tasks -> {
                val todo = client.tasks.list().filter { !it.done }
                Answer(if (todo.isEmpty()) text(R.string.voice_no_tasks) else text(R.string.voice_tasks, todo.size, list(todo.map { it.label })), speak = true)
            }
            is VoiceCommand.DoneTask -> {
                val task = client.tasks.list().firstOrNull { !it.done && !it.auto && VoiceCommands.names(command.target, it.label) }
                    ?: return Answer(text(R.string.voice_task_unknown), speak = true)
                client.tasks.setDone(task.id, true)
                Answer(text(R.string.voice_task_done, task.label))
            }
            is VoiceCommand.Day -> day(client, command)
            is VoiceCommand.Remind -> { client.tasks.add(command.label, remindAt = command.at); Answer(text(R.string.voice_reminder, command.at.replace(":", " h "), command.label)) }
            is VoiceCommand.Search -> {
                client.docs.openUrlOnComputer("https://www.google.com/search?q=" + URLEncoder.encode(command.query, "UTF-8"))
                Answer(text(R.string.voice_search, command.query))
            }
            is VoiceCommand.Type -> { client.system.typeOnComputer(command.text); Answer(text(R.string.voice_typed)) }
            // Appuyer sur Parler vaut le toucher exigé : le texte part tel qu'il a été dit, sans restaurer l'ancien contenu.
            is VoiceCommand.CopyToComputer -> {
                if (command.text.isEmpty()) return Answer(text(R.string.voice_copy_what), speak = true)
                client.system.copyToComputer(command.text)
                Answer(text(R.string.voice_copied), speak = true)
            }
            VoiceCommand.Help -> Answer(text(R.string.voice_help))
            is VoiceCommand.Agent -> Answer(activity.agentVoice(command.action, command.choice))
            is VoiceCommand.Call -> {
                val overlay = activity.callOverlay
                // Pas d'appel, mais une demande d'agent à l'écran : « refuse » et « accepte » valent pour elle.
                if (overlay.app == null && activity.agentAskOpen) {
                    return Answer(activity.agentVoice(if (command.action == "decline") "deny" else "allow", null))
                }
                if (overlay.app == null) return Answer(text(R.string.voice_no_call), speak = true)
                if (command.action == "decline") overlay.decline() else overlay.respond(video = command.action == "video")
                Answer(text(R.string.voice_done))
            }
            VoiceCommand.HangUp -> Answer(text(R.string.voice_hung_up, client.system.hangUp()))
        }
    }

    /** Fenêtre désignée : l'appli ou le titre dit, sinon celle de devant ; agit comme l'onglet Ouvertes du Deck. */
    private suspend fun window(client: BackendClient, command: VoiceCommand.Window): Answer {
        val open = client.windows.openWindows()
        val pairs = open.apps.flatMap { app -> app.windows.map { app to it } }
        val target = if (VoiceCommands.words(command.target).isEmpty()) {
            pairs.firstOrNull { it.second.front } ?: return Answer(text(R.string.voice_win_no_front), speak = true)
        } else {
            fun windowOf(app: OpenApp) = app.windows.let { w -> w.firstOrNull { it.front } ?: w.firstOrNull { !it.minimized } ?: w.firstOrNull() }?.let { app to it }
            open.apps.filter { VoiceCommands.names(command.target, it.name) }.firstNotNullOfOrNull(::windowOf)
                ?: pairs.firstOrNull { VoiceCommands.words(command.target).all { w -> VoiceCommands.words(it.second.title).contains(w) } }
                ?: return if (command.launch) launch(client, command.target)
                else VoiceCommands.closest(targets(command.target), open.apps.map { it.name })
                    ?.let { name -> open.apps.first { it.name == name } }?.let(::windowOf)
                    ?.let { act(client, command, open, it) }
                    ?: Answer(text(R.string.voice_win_none, command.target), speak = true)
        }
        return act(client, command, open, target)
    }

    /** Variantes entendues de l'appli désignée (la phrase retenue d'abord). */
    private fun targets(said: String) = (listOf(said) + heard.mapNotNull { VoiceCommands.target(it) }).distinct()

    private suspend fun act(client: BackendClient, command: VoiceCommand.Window, open: OpenWindows, target: Pair<OpenApp, ComputerWindow>): Answer {
        val (app, window) = target
        val screens = open.displays.sortedBy { it.x }
        val here = window.display ?: screens.firstOrNull { it.main }?.id
        when (command.action) {
            "max" -> if (!window.maximized) client.windows.openWindowAction(app, window, "place", here, "max")
            "place" -> {
                val display = when (command.display) {
                    null -> here
                    0 -> screens.firstOrNull { it.id != here }?.id ?: return Answer(text(R.string.voice_win_one_screen), speak = true)
                    -1 -> screens.last().id
                    -2 -> screens[screens.size / 2].id
                    else -> screens.getOrNull(command.display - 1)?.id ?: return Answer(text(R.string.voice_win_no_screen, command.display), speak = true)
                }
                if (command.display != null && display == here) return Answer(text(R.string.voice_win_same_screen, app.name), speak = true)
                client.windows.openWindowAction(app, window, "place", display, command.layout ?: "keep")
            }
            else -> client.windows.openWindowAction(app, window, command.action)
        }
        return Answer(text(when (command.action) {
            "focus" -> R.string.voice_win_focus
            "minimize" -> R.string.voice_win_min
            "max" -> R.string.voice_win_max
            "close" -> R.string.voice_win_close
            else -> R.string.voice_win_place
        }, app.name))
    }

    /**
     * « ouvre X » : ni touche du Deck ni fenêtre ouverte, X est cherché parmi les applis installées ; sinon le nom
     * le plus proche à l'oreille parmi les applis installées et les touches du Deck.
     */
    private suspend fun launch(client: BackendClient, said: String): Answer {
        val installed = client.deck.catalog().apps
        val tiles = tiles?.await().orEmpty()
        val exact = targets(said).firstNotNullOfOrNull { VoiceCommands.bestApp(it, installed) }
        val name = exact ?: VoiceCommands.closest(targets(said), installed + tiles.map { it.label })
            ?: return Answer(text(R.string.voice_app_unknown, said), speak = true)
        tiles.firstOrNull { exact == null && it.label == name }?.let { client.deck.runTile(it.id); return Answer(it.label) }
        client.system.launchApp(name)
        return Answer(text(R.string.voice_app_opened, name))
    }

    /** Rendez-vous restants du jour (ou ceux de demain), et les tâches pour « qu'est-ce que j'ai aujourd'hui ». */
    private suspend fun day(client: BackendClient, command: VoiceCommand.Day): Answer {
        val now = System.currentTimeMillis()
        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, command.days)
        }.timeInMillis
        val dayEnd = dayStart + 24 * 3_600_000L
        val events = client.calendar.days(days = 2).events
            .filter { it.startMs < dayEnd && it.endMs > maxOf(dayStart, now) }
            .map { if (it.allDay) it.title else text(R.string.voice_event_at, it.title, time.format(Date(it.startMs))) }
        val whenDay = text(if (command.days == 0) R.string.voice_today else R.string.voice_tomorrow)
        val agenda = if (events.isEmpty()) text(R.string.voice_day_empty, whenDay) else text(R.string.voice_day, whenDay, list(events))
        if (!command.withTasks) return Answer(agenda, speak = true)
        val todo = runCatching { client.tasks.list().count { !it.done } }.getOrDefault(0)
        return Answer(if (todo == 0) agenda else agenda + " " + activity.resources.getQuantityString(R.plurals.voice_day_tasks, todo, todo), speak = true)
    }

    /** « a, b et c » ; au-delà de 5, « et 3 autres ». */
    private fun list(items: List<String>): String {
        val shown = items.take(5)
        val more = items.size - shown.size
        if (more > 0) return shown.joinToString(", ") + text(R.string.voice_list_more, more)
        return if (shown.size == 1) shown[0] else shown.dropLast(1).joinToString(", ") + text(R.string.voice_list_and, shown.last())
    }

    /** « à 14 h 30 », « demain à 9 h 00 », « jeudi à 10 h 00 ». */
    private fun whenText(now: Long, start: Long): String {
        if (start <= now) return text(R.string.voice_now)
        val day = { ms: Long -> Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) } }
        val at = time.format(Date(start))
        return when (day(start) - day(now)) {
            0 -> text(R.string.voice_at, at)
            1 -> text(R.string.voice_tomorrow_at, at)
            else -> text(R.string.voice_day_at, dayName.format(Date(start)), at)
        }
    }

    private fun text(id: Int, vararg args: Any) = activity.getString(id, *args)
}

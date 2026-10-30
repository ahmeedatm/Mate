package com.unowhy.unomate.agents

import com.unowhy.unomate.MainActivity
import com.unowhy.unomate.Prefs
import com.unowhy.unomate.R
import com.unowhy.unomate.net.AgentSession
import com.unowhy.unomate.net.BackendClient
import com.unowhy.unomate.ui.errorText
import com.unowhy.unomate.voice.PromptRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Prompt dicté à une session Claude, sans rien taper : le micro de la carte écoute (arrêt au silence ou au second
 * toucher), l'ordinateur transcrit (whisper.cpp), puis le texte part tout de suite à la session. Une dictée à la fois
 * pour toutes les vues (onglet, widget, widget agrandi) : elles lisent [phaseOf], et chaque changement relance leur rendu
 * ([AgentsFeed.replay]). Hors de l'activité : la transcription continue si l'écran change.
 */
object VoicePrompt {
    enum class Phase { LISTENING, SENDING }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: String? = null
    private var phase: Phase? = null
    private var recorder: PromptRecorder? = null

    fun phaseOf(sessionId: String): Phase? = if (session == sessionId) phase else null

    /** Micro d'une session touché : écoute, ou fin de l'écoute si c'est elle qui écoute. Rien pendant un envoi ou une autre dictée. */
    fun toggle(activity: MainActivity, s: AgentSession) {
        if (session == s.id && phase == Phase.LISTENING) return recorder?.stop() ?: Unit
        if (session != null) return
        val rec = PromptRecorder(activity)
        if (!rec.start { wav -> recorded(activity, s, wav) }) return activity.showBanner(activity.getString(R.string.voice_no_mic), 6)
        recorder = rec
        session = s.id
        change(Phase.LISTENING)
        activity.showBanner(activity.getString(R.string.agent_talk_listening, s.name), 4)
    }

    private fun recorded(activity: MainActivity, s: AgentSession, wav: ByteArray?) {
        recorder = null
        if (wav == null) {
            done()
            return banner(activity, activity.getString(R.string.voice_nothing), 4)
        }
        change(Phase.SENDING)
        scope.launch {
            try {
                val agents = BackendClient(activity).agents
                val text = agents.transcribe(wav).trim()
                if (text.isEmpty()) return@launch banner(activity, activity.getString(R.string.voice_nothing), 4)
                agents.send(s.id, text)
                // Mode privé : le texte n'est pas montré.
                val shown = if (Prefs.agentsPrivate(activity)) activity.getString(R.string.agent_sent, s.name) else activity.getString(R.string.agent_talk_sent, s.name, text.take(140))
                banner(activity, shown, 6)
                AgentsFeed.refresh(activity)
            } catch (e: Exception) {
                banner(activity, activity.errorText(e), 6)
            } finally {
                done()
            }
        }
    }

    private fun banner(activity: MainActivity, text: String, seconds: Int) {
        if (!activity.isDestroyed) activity.showBanner(text, seconds)
    }

    private fun change(p: Phase) {
        phase = p
        AgentsFeed.replay()
    }

    private fun done() {
        session = null
        phase = null
        AgentsFeed.replay()
    }
}

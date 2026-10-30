package app.mate.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import app.mate.Prefs
import app.mate.R
import java.util.Locale

/**
 * Synthèse vocale intégrée à Android (hors ligne, gratuite, sans dépendance), partagée par
 * les messages envoyés depuis le Mac et la commande vocale. Un seul moteur pour toute l'appli.
 *
 * Voix plus douce que celle du moteur : débit [VoiceChoice.DEFAULT_RATE] et hauteur [VoiceChoice.PITCH] par défaut,
 * voix et débit au choix dans Réglages > Accessibilité (Prefs.ttsVoice, Prefs.ttsRate), relus à chaque lecture.
 */
object Speaker {

    private var tts: TextToSpeech? = null
    private var appContext: Context? = null

    /** Voix françaises hors ligne du moteur, par nom (VoiceChoice.offlineFrench). */
    private var byName: Map<String, Voice> = emptyMap()

    /** Le moteur a fini de démarrer (prêt ou non) ; écouteurs en attente de ce moment, appelés sur le thread principal. */
    private var started = false
    private val waiting = mutableListOf<() -> Unit>()

    /** Faux tant que le moteur démarre, ou s'il n'a pas de voix française. */
    @Volatile var ready = false
        private set

    /** Noms des voix françaises utilisables hors ligne, triés ; vide tant que le moteur démarre. */
    @Volatile var voices: List<String> = emptyList()
        private set

    fun init(context: Context) {
        if (tts != null) return
        appContext = context.applicationContext
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS &&
                tts?.setLanguage(Locale.FRANCE)?.let { it >= TextToSpeech.LANG_AVAILABLE } == true
            if (ready) {
                // Certains moteurs renvoient null ou lèvent une exception sur la liste des voix.
                val all = runCatching { tts?.voices.orEmpty() }.getOrDefault(emptySet())
                val usable = VoiceChoice.offlineFrench(all.map(::info))
                byName = all.filter { it.name in usable }.associateBy { it.name }
                voices = usable
            }
            started = true
            waiting.toList().forEach { it() }
            waiting.clear()
        }
    }

    /** Appelle [listener] une fois le moteur démarré (tout de suite s'il l'est déjà). Thread principal. */
    fun whenStarted(listener: () -> Unit) {
        if (started) listener() else waiting += listener
    }

    /** Lit le texte (en coupant la lecture en cours) ; renvoie faux si la voix n'est pas disponible. */
    fun speak(text: String): Boolean {
        if (!ready) return false
        appContext?.let { use(Prefs.ttsVoice(it), Prefs.ttsRate(it)) }
        // Pas de lecture des symboles de mise en forme (réponses LLM en Markdown).
        val clean = text.replace(Regex("[*_#`>«»]"), "").replace(Regex("\\s+"), " ").trim()
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "companion")
        return true
    }

    /**
     * Fait écouter une voix (null : celle du moteur) à ce débit, sans rien enregistrer : la lecture suivante reprend
     * les réglages enregistrés.
     */
    fun preview(context: Context, voiceName: String?, rate: Float): Boolean {
        if (!ready) return false
        use(voiceName, rate)
        tts?.speak(context.getString(R.string.voice_preview_text), TextToSpeech.QUEUE_FLUSH, null, "preview")
        return true
    }

    fun stop() {
        tts?.stop()
    }

    /** Voix (enregistrée si elle existe encore, sinon celle du moteur pour le français), débit et hauteur. */
    private fun use(voiceName: String?, rate: Float) {
        val engine = tts ?: return
        val voice = VoiceChoice.voiceToApply(voiceName, voices)?.let(byName::get)
        // setLanguage remet la voix par défaut du français, pour « Automatique » ou une voix disparue.
        if (voice != null) engine.voice = voice else engine.setLanguage(Locale.FRANCE)
        engine.setSpeechRate(rate)
        engine.setPitch(VoiceChoice.PITCH)
    }

    private fun info(voice: Voice) = VoiceInfo(
        name = voice.name,
        language = voice.locale?.language.orEmpty(),
        networkRequired = voice.isNetworkConnectionRequired,
        notInstalled = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in voice.features.orEmpty(),
    )
}

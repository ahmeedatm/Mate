package app.mate.ui.home

import android.content.Context
import app.mate.R
import app.mate.net.AudioState
import app.mate.net.MixerChannel
import app.mate.net.MixerScene
import app.mate.net.MixerState

/** Exemple du widget Son pour le catalogue ([SoundSamples]). */
object AudioSample : WidgetSample

/** Exemple de la table de mixage pour le catalogue ([SoundSamples]). */
object MixerSample : WidgetSample

/** Données d'exemple de Son et de la table : sortie à 45 %, micro ouvert, deux applis qui jouent, trois scènes. */
object SoundSamples {
    private const val OUTPUT = 45
    private const val MIC = 70

    val audio = AudioState(output = OUTPUT, muted = false, input = MIC, micMuted = false)

    fun mixer(context: Context) = MixerState(
        master = MixerChannel("master", context.getString(R.string.mixer_master), OUTPUT, muted = false, level = 38),
        mic = MixerChannel("mic", context.getString(R.string.mixer_mic), MIC, muted = false, level = 12),
        apps = listOf(
            MixerChannel("spotify", context.getString(R.string.widget_sound_sample_music), 80, muted = false, level = 52),
            MixerChannel("discord", context.getString(R.string.widget_sound_sample_call), 60, muted = true, level = 0),
        ),
    )

    fun scenes(context: Context) = listOf(
        MixerScene("jeu", context.getString(R.string.widget_sound_sample_scene_game)),
        MixerScene("reunion", context.getString(R.string.widget_sound_sample_scene_meeting)),
        MixerScene("musique", context.getString(R.string.widget_sound_sample_scene_music)),
    )
}

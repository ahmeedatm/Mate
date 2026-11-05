package app.mate.ui.home

import android.content.Context
import android.graphics.Bitmap
import app.mate.R
import app.mate.net.MediaSession
import app.mate.net.YtDesktop
import app.mate.net.YtMusicState
import app.mate.net.YtQueueItem
import app.mate.net.YtTrack

/** Exemple du widget Lecture pour le catalogue ([PlayingSamples]). */
object MediaSample : WidgetSample

/** Exemple du widget YouTube Music pour le catalogue ([PlayingSamples]). */
object YtMusicSample : WidgetSample

/** Données d'exemple de Lecture et de YouTube Music : un morceau en lecture au quart, deux titres à suivre. */
object PlayingSamples {
    private const val POSITION_S = 61
    private const val DURATION_S = 244
    private const val MS = 1_000L

    fun playing(context: Context, art: Bitmap) = Playing(
        context.getString(R.string.widget_media_sample_title),
        context.getString(R.string.widget_media_sample_artist),
        playing = true,
        progress = POSITION_S.toFloat() / DURATION_S,
        cover = art,
    )

    fun media(context: Context) = MediaSession(
        app = "Spotify.exe", name = context.getString(R.string.widget_media_sample_app), current = true,
        title = context.getString(R.string.widget_media_sample_title), artist = context.getString(R.string.widget_media_sample_artist),
        playing = true, canPlayPause = true, canNext = true, canPrevious = true, canSeek = true,
        positionMs = POSITION_S * MS, durationMs = DURATION_S * MS, thumb = "exemple",
    )

    fun ytMusic(context: Context): YtMusicState {
        val artist = context.getString(R.string.widget_media_sample_artist)
        val title = context.getString(R.string.widget_media_sample_title)
        return YtMusicState(
            open = true, source = "browser", playing = true,
            track = YtTrack(title, artist, context.getString(R.string.widget_media_sample_album), null, POSITION_S.toDouble(), DURATION_S.toDouble(), "like"),
            repeat = "none",
            queue = listOf(
                YtQueueItem(title, artist, null, current = true),
                YtQueueItem(context.getString(R.string.widget_media_sample_next), artist, null, current = false),
                YtQueueItem(context.getString(R.string.widget_media_sample_after), artist, null, current = false),
            ),
            desktop = YtDesktop("off", null, null),
        )
    }
}

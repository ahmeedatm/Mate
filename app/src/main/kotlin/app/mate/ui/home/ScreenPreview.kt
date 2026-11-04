package app.mate.ui.home

import kotlin.math.min

/** Logique pure du widget Écran ([ScreenWidget]) : cadence des images et réduction au décodage. */
object ScreenPreview {
    /** Une image toutes les 1,5 s : assez pour suivre un export, sans user la tablette ni le WiFi. */
    const val REFRESH_MS = 1_500L

    /** Plafond de l'attente après des échecs : l'ordinateur verrouillé ou sans autorisation n'est pas harcelé. */
    const val MAX_BACKOFF_MS = 10_000L

    /** Attente avant la prochaine image : [REFRESH_MS], doublée à chaque échec de suite, jusqu'à [MAX_BACKOFF_MS]. */
    fun nextDelayMs(failures: Int): Long {
        var delay = REFRESH_MS
        repeat(failures.coerceIn(0, 10)) { delay = min(delay * 2, MAX_BACKOFF_MS) }
        return delay
    }

    /**
     * `inSampleSize` du décodage : la plus grande puissance de deux qui laisse l'image au moins aussi grande que celle
     * montrée (calée dans la vue, sans l'agrandir). Vue pas encore mesurée : 1. Moins de pixels en mémoire (tas de 128 Mo).
     */
    fun sampleSize(imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int): Int {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return 1
        val scale = min(1f, min(viewWidth / imageWidth.toFloat(), viewHeight / imageHeight.toFloat()))
        val targetWidth = imageWidth * scale
        val targetHeight = imageHeight * scale
        var sample = 1
        while (imageWidth / (sample * 2) >= targetWidth && imageHeight / (sample * 2) >= targetHeight) sample *= 2
        return sample
    }
}

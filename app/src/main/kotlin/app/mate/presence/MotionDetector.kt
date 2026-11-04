package app.mate.presence

import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Détecteur de mouvement sur des images de luminance très réduites (voir [reduce]) : compare chaque image à la
 * précédente. Sans Android, sans allocation d'une image à l'autre : rien n'est gardé que la dernière image réduite
 * (quelques centaines d'octets).
 *
 * La lumière qui change sur toute la scène (nuage, lampe, exposition automatique) déplace la moyenne : elle est retirée
 * avant de compter les pixels qui changent franchement, seuls restent les vrais mouvements.
 */
class MotionDetector(
    private val pixelDelta: Int = PIXEL_DELTA,
    private val minChangedRatio: Float = MIN_CHANGED_RATIO,
) {
    private var previous: ByteArray? = null

    /** Vrai si [luma] diffère assez de l'image précédente ; la première image (ou une taille différente) ne dit rien. */
    fun moved(luma: ByteArray): Boolean {
        val before = previous
        if (before == null || before.size != luma.size) {
            previous = luma.copyOf()
            return false
        }
        var sum = 0L
        for (i in luma.indices) sum += (luma[i].toInt() and 0xFF) - (before[i].toInt() and 0xFF)
        val shift = sum.toFloat() / luma.size
        var changed = 0
        for (i in luma.indices) {
            if (abs((luma[i].toInt() and 0xFF) - (before[i].toInt() and 0xFF) - shift) > pixelDelta) changed++
        }
        System.arraycopy(luma, 0, before, 0, luma.size)
        return changed >= maxOf(1f, luma.size * minChangedRatio)
    }

    /** Oublie l'image précédente (caméra relancée : la prochaine ne se compare à rien). */
    fun reset() {
        previous = null
    }

    companion object {
        /** Écart de luminance (0 à 255), moyenne de la scène retirée, à partir duquel un pixel a changé. */
        const val PIXEL_DELTA = 24

        /** Part des pixels qui doit changer : un visage ou une main à quelques dizaines de centimètres en couvre bien plus. */
        const val MIN_CHANGED_RATIO = 0.02f

        /**
         * Plan Y d'une image YUV_420_888 ([width]×[height], foulées propres au fabricant) réduit dans [out]
         * ([outW]×[outH]) : chaque case prend la moyenne de 4 pixels de son intérieur, sans lire le reste. Lecture
         * absolue : la position du tampon ne bouge pas.
         */
        fun reduce(plane: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int, out: ByteArray, outW: Int, outH: Int) {
            for (oy in 0 until outH) {
                val y0 = oy * height / outH
                val bh = (oy + 1) * height / outH - y0
                for (ox in 0 until outW) {
                    val x0 = ox * width / outW
                    val bw = (ox + 1) * width / outW - x0
                    var sum = 0
                    for (sy in 0..1) {
                        val y = y0 + (1 + 2 * sy) * bh / 4
                        for (sx in 0..1) {
                            val x = x0 + (1 + 2 * sx) * bw / 4
                            sum += plane.get(y * rowStride + x * pixelStride).toInt() and 0xFF
                        }
                    }
                    out[oy * outW + ox] = (sum / 4).toByte()
                }
            }
        }
    }
}
